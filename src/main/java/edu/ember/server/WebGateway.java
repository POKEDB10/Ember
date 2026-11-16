package edu.ember.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets browsers use the same TLS port as the desktop app: serves the web app's static files and
 * upgrades to a WebSocket that carries the unchanged binary Ember frames, so every server rule
 * applies to web users too. @concept Adapter pattern, streams
 */
final class WebGateway {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_LINE = 8192;
    /** Allowlist of served files; anything else is 404, so no path traversal is possible. */
    private static final Map<String, String> FILES = Map.of(
            "/", "index.html", "/index.html", "index.html", "/app.js", "app.js",
            "/app.css", "app.css", "/manifest.json", "manifest.json", "/sw.js", "sw.js",
            "/icon.svg", "icon.svg");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/manifest+json",
            "svg", "image/svg+xml");
    private static final Map<String, Asset> CACHE = new ConcurrentHashMap<>();

    record Upgrade(InputStream input, OutputStream output) { }

    private record Asset(byte[] gzip, String etag, String type) { }

    private WebGateway() {
    }

    static boolean isHttp(InputStream input) throws IOException {
        input.mark(4);
        byte[] head = input.readNBytes(4);
        input.reset();
        return Arrays.equals(head, "GET ".getBytes(StandardCharsets.US_ASCII));
    }

    /** Answers one HTTP request; returns WebSocket streams on upgrade, or null after a file. */
    static Upgrade serve(InputStream in, OutputStream out) throws IOException {
        String[] request = readLine(in).split(" ");
        Map<String, String> headers = new HashMap<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            if (headers.size() > 64) {
                throw new IOException("Too many headers");
            }
        }
        String path = request.length > 1 ? request[1].split("\\?", 2)[0] : "";
        String key = headers.get("sec-websocket-key");
        if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && key != null) {
            write(out, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n");
            out.flush();
            return new Upgrade(new WsInput(in, out), new WsOutput(out));
        }
        Asset asset = FILES.containsKey(path) ? asset(FILES.get(path)) : null;
        if (asset == null) {
            write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        } else if (asset.etag().equals(headers.get("if-none-match"))) {
            write(out, "HTTP/1.1 304 Not Modified\r\nETag: " + asset.etag()
                    + "\r\nConnection: close\r\n\r\n");
        } else {
            // Every browser since 2010 accepts gzip; serving only the compressed copy keeps it simple.
            write(out, "HTTP/1.1 200 OK\r\nContent-Type: " + asset.type()
                    + "\r\nContent-Encoding: gzip\r\nContent-Length: " + asset.gzip().length
                    + "\r\nETag: " + asset.etag() + "\r\nCache-Control: no-cache"
                    + "\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
                    + "\r\nContent-Security-Policy: default-src 'self'; img-src 'self' blob: data:;"
                    + " connect-src 'self'; frame-ancestors 'none'"
                    + "\r\nConnection: close\r\n\r\n");
            out.write(asset.gzip());
        }
        out.flush();
        return null;
    }

    private static Asset asset(String name) throws IOException {
        Asset cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream resource = WebGateway.class.getResourceAsStream("/web/" + name)) {
            if (resource == null) {
                return null;
            }
            byte[] raw = resource.readAllBytes();
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(packed)) {
                gzip.write(raw);
            }
            String etag = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    Arrays.copyOf(sha("SHA-256", raw), 12)) + "\"";
            Asset asset = new Asset(packed.toByteArray(), etag,
                    TYPES.get(name.substring(name.lastIndexOf('.') + 1)));
            CACHE.put(name, asset);
            return asset;
        }
    }

    static String acceptKey(String key) {
        return Base64.getEncoder().encodeToString(
                sha("SHA-1", (key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int value = in.read(); value != '\n'; value = in.read()) {
            if (value < 0) {
                throw new EOFException("HTTP request ended early");
            }
            if (line.length() >= MAX_LINE) {
                throw new IOException("HTTP line too long");
            }
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return line.toString();
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Reads WebSocket frames from the browser and exposes their payload bytes as one stream. */
    static final class WsInput extends InputStream {
        private final InputStream in;
        private final OutputStream raw;
        private final byte[] mask = new byte[4];
        private long remaining;
        private int maskIndex;

        WsInput(InputStream in, OutputStream raw) {
            this.in = in;
            this.raw = raw;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (remaining == 0) {
                if (!nextFrame()) {
                    return -1;
                }
            }
            int count = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (count < 0) {
                return -1;
            }
            for (int index = offset; index < offset + count; index++) {
                buffer[index] ^= mask[maskIndex++ & 3];
            }
            remaining -= count;
            return count;
        }

        /** Reads one frame header; answers pings itself; false means the browser closed. */
        private boolean nextFrame() throws IOException {
            int first = in.read();
            int second = in.read();
            if (first < 0 || second < 0) {
                return false;
            }
            int opcode = first & 0x0F;
            long length = second & 0x7F;
            if (length == 126) {
                length = (readByte() << 8) | readByte();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | readByte();
                }
            }
            if ((second & 0x80) == 0 || length < 0) {
                throw new IOException("Browser frames must be masked");
            }
            for (int index = 0; index < 4; index++) {
                mask[index] = (byte) readByte();
            }
            maskIndex = 0;
            if (opcode == 0x8) {
                return false;
            }
            if (opcode >= 0x8) {
                if (length > 125) {
                    throw new IOException("Control frame too long");
                }
                byte[] payload = in.readNBytes((int) length);
                for (int index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index & 3];
                }
                if (opcode == 0x9) {
                    synchronized (raw) {
                        raw.write(0x8A);
                        raw.write(payload.length);
                        raw.write(payload);
                        raw.flush();
                    }
                }
                return true;
            }
            remaining = length;
            return true;
        }

        private int readByte() throws IOException {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("WebSocket frame ended early");
            }
            return value;
        }
    }

    /** Collects one Ember frame and sends it as one binary WebSocket message on flush(). */
    static final class WsOutput extends OutputStream {
        private final OutputStream raw;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        WsOutput(OutputStream raw) {
            this.raw = raw;
        }

        @Override
        public void write(int value) {
            pending.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            pending.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            if (pending.size() == 0) {
                return;
            }
            int size = pending.size();
            synchronized (raw) {
                raw.write(0x82);
                if (size < 126) {
                    raw.write(size);
                } else if (size < 65_536) {
                    raw.write(126);
                    raw.write(size >>> 8);
                    raw.write(size);
                } else {
                    raw.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        raw.write((int) ((long) size >>> shift));
                    }
                }
                pending.writeTo(raw);
                raw.flush();
            }
            pending.reset();
        }
    }
}
