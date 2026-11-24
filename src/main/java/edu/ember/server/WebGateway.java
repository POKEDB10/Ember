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
import java.util.function.IntSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Lets browsers use the same server as the desktop app: serves the web app's static files and
 * images, and upgrades to a WebSocket that carries the unchanged binary Ember frames, so every
 * server rule applies to web users too. WebSocket traffic is compressed (permessage-deflate)
 * when the browser offers it. @concept Adapter pattern, streams, compression
 */
public final class WebGateway {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_LINE = 8192;
    /** Deflate's sync-flush marker: stripped when sending, appended before inflating (RFC 7692). */
    private static final byte[] DEFLATE_TAIL = {0, 0, (byte) 0xFF, (byte) 0xFF};
    /** Messages smaller than this are not worth compressing. */
    private static final int COMPRESS_MIN_BYTES = 96;
    /** Allowlist of served files; anything else is 404, so no path traversal is possible. */
    private static final Map<String, String> FILES = Map.ofEntries(
            Map.entry("/", "index.html"), Map.entry("/index.html", "index.html"), Map.entry("/app.js", "app.js"),
            Map.entry("/app.css", "app.css"), Map.entry("/manifest.json", "manifest.json"), Map.entry("/sw.js", "sw.js"),
            Map.entry("/icon.svg", "icon.svg"), Map.entry("/call-worklet.js", "call-worklet.js"),
            Map.entry("/call.js", "call.js"), Map.entry("/e2e.js", "e2e.js"), Map.entry("/quality.js", "quality.js"));
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/manifest+json",
            "svg", "image/svg+xml");
    /** Locks the page down: only its own scripts, no framing, camera/microphone only for this site. */
    private static final String SECURITY_HEADERS = "\r\nX-Content-Type-Options: nosniff"
            + "\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self';"
            + " img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self';"
            + " object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
            + "\r\nStrict-Transport-Security: max-age=31536000"
            + "\r\nPermissions-Policy: camera=(self), microphone=(self), geolocation=(), payment=(), usb=()"
            + "\r\nCross-Origin-Opener-Policy: same-origin\r\nCross-Origin-Resource-Policy: same-origin";
    private static final Map<String, Asset> CACHE = new ConcurrentHashMap<>();

    /** Loads an image for a signed-in viewer, or returns null when it is missing or not visible. */
    @FunctionalInterface
    interface MediaSource {
        byte[] load(String sessionToken, String mediaId, boolean thumbnail) throws IOException;
    }

    /** forwardedFor is the X-Forwarded-For header, or null. */
    record Upgrade(WsInput input, WsOutput output, String forwardedFor) {
        /** Frees the native compression buffers; call once the connection has ended. */
        void release() {
            input.end();
            output.end();
        }
    }

    private record Asset(byte[] gzip, String etag, String type) { }

    private WebGateway() {
    }

    static boolean isHttp(InputStream input) throws IOException {
        input.mark(4);
        byte[] head = input.readNBytes(4);
        input.reset();
        return Arrays.equals(head, "GET ".getBytes(StandardCharsets.US_ASCII))
                || Arrays.equals(head, "HEAD".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Answers one HTTP request. Returns WebSocket streams on upgrade, or null after a response.
     * maxMessage caps one incoming WebSocket message, checked while it arrives.
     */
    static Upgrade serve(InputStream in, OutputStream out, MediaSource media, IntSupplier maxMessage)
            throws IOException {
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
        String[] target = request.length > 1 ? request[1].split("\\?", 2) : new String[] {""};
        String path = target[0];
        String query = target.length > 1 ? target[1] : "";
        String key = headers.get("sec-websocket-key");
        if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && key != null) {
            // A browser always sends Origin; another website's page must not open a socket here.
            if (!sameOrigin(headers.get("origin"), headers.get("host"))) {
                write(out, "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                out.flush();
                return null;
            }
            boolean deflate = headers.getOrDefault("sec-websocket-extensions", "")
                    .contains("permessage-deflate");
            write(out, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + acceptKey(key)
                    + (deflate ? "\r\nSec-WebSocket-Extensions: permessage-deflate" : "") + "\r\n\r\n");
            out.flush();
            return new Upgrade(new WsInput(in, out, maxMessage, deflate), new WsOutput(out, deflate),
                    headers.get("x-forwarded-for"));
        }
        boolean head = request[0].equals("HEAD");
        if (path.equals("/health")) {
            // Uptime monitors ping this to keep a sleeping free host awake; it touches no data.
            write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2"
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n" + (head ? "" : "ok"));
        } else if (path.startsWith("/media/")) {
            serveMedia(out, media, headers.getOrDefault("authorization", ""),
                    path.substring("/media/".length()), !query.contains("full=1"), head);
        } else {
            serveFile(out, FILES.containsKey(path) ? asset(FILES.get(path)) : null,
                    headers.get("if-none-match"), head);
        }
        out.flush();
        return null;
    }

    /** True with no Origin (desktop app, tools) or when the Origin names this same host. */
    public static boolean sameOrigin(String origin, String host) {
        if (origin == null) {
            return true;
        }
        try {
            String authority = java.net.URI.create(origin).getRawAuthority();
            return authority != null && host != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Images never change once uploaded, so the browser may cache them for a year. */
    private static void serveMedia(OutputStream out, MediaSource media, String authorization,
            String mediaId, boolean thumbnail, boolean head) throws IOException {
        byte[] image = null;
        // The ID is only a lookup key (never a file path); still keep it to safe characters.
        if (authorization.startsWith("Bearer ") && mediaId.matches("[A-Za-z0-9_-]{1,64}")) {
            image = media.load(authorization.substring(7).strip(), mediaId, thumbnail);
        }
        if (image == null) {
            write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            return;
        }
        write(out, "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + image.length
                + "\r\nCache-Control: private, max-age=31536000, immutable" + SECURITY_HEADERS
                + "\r\nConnection: close\r\n\r\n");
        if (!head) {
            out.write(image);
        }
    }

    private static void serveFile(OutputStream out, Asset asset, String ifNoneMatch, boolean head)
            throws IOException {
        if (asset == null) {
            write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        } else if (asset.etag().equals(ifNoneMatch)) {
            write(out, "HTTP/1.1 304 Not Modified\r\nETag: " + asset.etag()
                    + "\r\nConnection: close\r\n\r\n");
        } else {
            // Every browser since 2010 accepts gzip; serving only the compressed copy keeps it simple.
            write(out, "HTTP/1.1 200 OK\r\nContent-Type: " + asset.type()
                    + "\r\nContent-Encoding: gzip\r\nContent-Length: " + asset.gzip().length
                    + "\r\nETag: " + asset.etag() + "\r\nCache-Control: no-cache" + SECURITY_HEADERS
                    + "\r\nConnection: close\r\n\r\n");
            if (!head) {
                out.write(asset.gzip());
            }
        }
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

    /**
     * Reads whole WebSocket messages from the browser (inflating compressed ones) and exposes
     * their bytes as one stream. Size is checked while a message arrives, so an oversized or
     * compression-bomb message is refused before it fills memory.
     */
    static final class WsInput extends InputStream {
        private final InputStream in;
        private final OutputStream raw;
        private final IntSupplier maxMessage;
        private final Inflater inflater;
        private byte[] current = new byte[0];
        private int position;

        WsInput(InputStream in, OutputStream raw, IntSupplier maxMessage, boolean deflate) {
            this.in = in;
            this.raw = raw;
            this.maxMessage = maxMessage;
            this.inflater = deflate ? new Inflater(true) : null;
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
            while (position == current.length) {
                if (!nextMessage()) {
                    return -1;
                }
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, count);
            position += count;
            return count;
        }

        void end() {
            if (inflater != null) {
                inflater.end();
            }
        }

        /** Reads frames until one message is complete; answers pings; false when closed. */
        private boolean nextMessage() throws IOException {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            boolean compressed = false;
            boolean first = true;
            int limit = maxMessage.getAsInt() + 64;
            while (true) {
                int head = in.read();
                int second = in.read();
                if (head < 0 || second < 0) {
                    return false;
                }
                int opcode = head & 0x0F;
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
                byte[] mask = in.readNBytes(4);
                if (opcode >= 0x8) {
                    if (length > 125) {
                        throw new IOException("Control frame too long");
                    }
                    byte[] payload = unmask(in.readNBytes((int) length), mask);
                    if (opcode == 0x8) {
                        return false;
                    }
                    if (opcode == 0x9) {
                        synchronized (raw) {
                            raw.write(0x8A);
                            raw.write(payload.length);
                            raw.write(payload);
                            raw.flush();
                        }
                    }
                    continue;
                }
                if (first) {
                    compressed = (head & 0x40) != 0;
                    first = false;
                }
                if (message.size() + length > limit) {
                    throw new IOException("WebSocket message too large");
                }
                byte[] payload = in.readNBytes((int) length);
                if (payload.length != length) {
                    throw new EOFException("WebSocket frame ended early");
                }
                message.writeBytes(unmask(payload, mask));
                if ((head & 0x80) != 0) {
                    break;
                }
            }
            current = compressed ? inflate(message, limit) : message.toByteArray();
            position = 0;
            return true;
        }

        private byte[] inflate(ByteArrayOutputStream compressed, int limit) throws IOException {
            if (inflater == null) {
                throw new IOException("Compressed message without negotiated compression");
            }
            compressed.writeBytes(DEFLATE_TAIL);
            inflater.setInput(compressed.toByteArray());
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            byte[] chunk = new byte[16_384];
            try {
                while (!inflater.needsInput()) {
                    if (inflater.finished()) {
                        inflater.reset(); // the client ended its stream: next message starts fresh
                        break;
                    }
                    int count = inflater.inflate(chunk);
                    plain.write(chunk, 0, count);
                    if (plain.size() > limit) {
                        throw new IOException("WebSocket message too large");
                    }
                    if (count == 0 && inflater.needsDictionary()) {
                        throw new IOException("Unexpected deflate dictionary");
                    }
                }
            } catch (DataFormatException exception) {
                throw new IOException("Corrupt compressed message", exception);
            }
            return plain.toByteArray();
        }

        private static byte[] unmask(byte[] payload, byte[] mask) {
            for (int index = 0; index < payload.length; index++) {
                payload[index] ^= mask[index & 3];
            }
            return payload;
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
        /** Keeps its window between messages, so repeated field names compress to almost nothing. */
        private final Deflater deflater;

        WsOutput(OutputStream raw, boolean deflate) {
            this.raw = raw;
            this.deflater = deflate ? new Deflater(Deflater.DEFAULT_COMPRESSION, true) : null;
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
            byte[] payload = pending.toByteArray();
            pending.reset();
            // Call packets are encrypted, so compressing them would only burn CPU (byte 5 is the opcode).
            boolean compress = deflater != null && payload.length >= COMPRESS_MIN_BYTES
                    && (payload[5] & 0xFF) != edu.ember.common.protocol.WireOpcodes.EVT_CALL_MEDIA;
            if (compress) {
                payload = deflate(payload);
            }
            synchronized (raw) {
                raw.write(compress ? 0xC2 : 0x82); // FIN + binary, RSV1 marks compression
                int size = payload.length;
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
                raw.write(payload);
                raw.flush();
            }
        }

        void end() {
            if (deflater != null) {
                deflater.end();
            }
        }

        private byte[] deflate(byte[] plain) {
            deflater.setInput(plain);
            ByteArrayOutputStream packed = new ByteArrayOutputStream(plain.length / 2 + 16);
            byte[] chunk = new byte[16_384];
            int count;
            do {
                count = deflater.deflate(chunk, 0, chunk.length, Deflater.SYNC_FLUSH);
                packed.write(chunk, 0, count);
            } while (count == chunk.length);
            byte[] result = packed.toByteArray();
            return Arrays.copyOf(result, result.length - DEFLATE_TAIL.length);
        }
    }
}
