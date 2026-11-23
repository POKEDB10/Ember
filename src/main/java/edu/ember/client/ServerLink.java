package edu.ember.client;

import edu.ember.common.security.TlsSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLSocket;

/**
 * A two-way byte connection to the Ember server. The client controller only sees this interface,
 * so the same Swing app runs over a direct TLS socket or over the website's WebSocket.
 * @concept interfaces, polymorphism, Factory Method pattern, nested classes
 */
public interface ServerLink extends Closeable {
    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    boolean isClosed();

    /** Shown in the status bar, e.g. "TLS 1.3". */
    String describe();

    static ServerLink tls(String host, int port, Path trustStore, char[] password) throws Exception {
        return new TlsLink(host, port, trustStore, password);
    }

    /** Accepts https://, http://, wss:// or ws:// addresses of an Ember website. */
    static ServerLink webSocket(String address) throws IOException {
        String url = address.strip().replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
        if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
            url = "wss://" + url;
        }
        URI uri = URI.create(url);
        return new WebSocketLink(uri.getPath() == null || uri.getPath().isEmpty() ? URI.create(url + "/") : uri);
    }

    /** Direct TLS 1.3 socket to the server's main port. */
    final class TlsLink implements ServerLink {
        private final SSLSocket socket;

        private TlsLink(String host, int port, Path trustStore, char[] password) throws Exception {
            socket = TlsSocketFactory.createClientSocket(trustStore, password, host, port);
            socket.setSoTimeout(45_000);
            socket.startHandshake();
        }

        @Override public InputStream input() throws IOException {
            return new java.io.BufferedInputStream(socket.getInputStream());
        }

        @Override public OutputStream output() throws IOException {
            return new java.io.BufferedOutputStream(socket.getOutputStream());
        }

        @Override public boolean isClosed() {
            return socket.isClosed();
        }

        @Override public String describe() {
            return "TLS 1.3";
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * WebSocket through the website (HTTPS proxy friendly, e.g. Render). Each Ember frame travels
     * as one binary message, exactly as the browser client sends it.
     */
    final class WebSocketLink implements ServerLink, WebSocket.Listener {
        private static final byte[] END = new byte[0];
        private final LinkedBlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private final URI uri;
        private final WebSocket socket;
        private volatile boolean closed;

        private WebSocketLink(URI uri) throws IOException {
            this.uri = uri;
            try {
                socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
                        .newWebSocketBuilder().buildAsync(uri, this).get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException exception) {
                throw new IOException("Could not reach " + uri.getHost(), exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Connection interrupted", exception);
            }
        }

        @Override public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk); // the buffer is reused after we return, so copy now
            partial.writeBytes(chunk);
            if (last) {
                received.add(partial.toByteArray());
                partial.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            finish();
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            finish();
        }

        private void finish() {
            closed = true;
            received.add(END);
        }

        @Override public InputStream input() {
            return new InputStream() {
                private byte[] current = new byte[0];
                private int position;

                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                }

                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (length == 0) {
                        return 0; // readNBytes asks for 0 bytes after a frame; waiting here held each reply back
                    }
                    while (position == current.length) {
                        if (current == END) {
                            return -1;
                        }
                        try {
                            current = received.take();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new java.io.InterruptedIOException("Read interrupted");
                        }
                        position = 0;
                        if (current == END) {
                            received.add(END); // keep later reads at end-of-stream too
                            return -1;
                        }
                    }
                    int count = Math.min(length, current.length - position);
                    System.arraycopy(current, position, buffer, offset, count);
                    position += count;
                    return count;
                }
            };
        }

        @Override public OutputStream output() {
            return new OutputStream() {
                private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

                @Override public void write(int value) {
                    frame.write(value);
                }

                @Override public void write(byte[] buffer, int offset, int length) {
                    frame.write(buffer, offset, length);
                }

                /** FrameCodec flushes once per frame, so each flush is one WebSocket message. */
                @Override public void flush() throws IOException {
                    if (frame.size() == 0) {
                        return;
                    }
                    try {
                        socket.sendBinary(ByteBuffer.wrap(frame.toByteArray()), true).join();
                    } catch (java.util.concurrent.CompletionException exception) {
                        throw new IOException("WebSocket send failed", exception.getCause());
                    } finally {
                        frame.reset();
                    }
                }
            };
        }

        @Override public boolean isClosed() {
            return closed;
        }

        @Override public String describe() {
            return "WebSocket · " + uri.getHost();
        }

        @Override public void close() {
            if (!closed) {
                finish();
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                socket.abort();
            }
        }
    }
}
