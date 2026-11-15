package edu.ember.server;

import edu.ember.common.security.PasswordHasher;
import edu.ember.common.security.Pbkdf2PasswordHasher;
import edu.ember.common.security.TlsSocketFactory;
import edu.ember.server.storage.InMemoryStorageEngine;
import edu.ember.server.storage.WalStorageEngine;
import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.net.ssl.SSLServerSocket;

/** Accepts TLS connections and routes each connection on a named platform thread. @concept threads, inner classes */
public final class EmberServer implements AutoCloseable {
    private final SSLServerSocket serverSocket;
    private final StorageEngine storage;
    private final AuthService authService;
    private final SecurityAuditLogger auditLogger;
    private final AuthRateLimiter authRateLimiter = new AuthRateLimiter();
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> messageBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AutoSaveThread autoSaveThread;
    private final SessionStore sessions = new SessionStore();
    private final java.util.concurrent.ConcurrentMap<String, ConnectionHandler> online =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ember-connection-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);

    public EmberServer(Path keyStore, char[] password, int port) throws Exception {
        this(keyStore, password, port, new InMemoryStorageEngine(),
                new SecurityAuditLogger(Path.of("logs", "security-audit.json")));
    }

    public EmberServer(Path keyStore, char[] password, int port, StorageEngine storage,
            SecurityAuditLogger auditLogger) throws Exception {
        serverSocket = TlsSocketFactory.createServerSocket(keyStore, password, port);
        this.storage = storage;
        this.auditLogger = auditLogger;
        storage.init();
        PasswordHasher hasher = new Pbkdf2PasswordHasher();
        authService = new AuthService(storage, hasher, new UserLockManager(), auditLogger);
        autoSaveThread = new AutoSaveThread(storage, 30_000);
    }

    public static void main(String[] args) throws Exception {
        int port = 7443;
        Path dataDirectory = Path.of("server_data");
        boolean strictDurability = true;
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals("--port")) {
                port = Integer.parseInt(args[++index]);
            } else if (args[index].equals("--storage")) {
                dataDirectory = Path.of(args[++index]);
            } else if (args[index].equals("--durability")) {
                strictDurability = args[++index].equals("strict");
            }
        }
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, port,
                new WalStorageEngine(dataDirectory, strictDurability),
                new SecurityAuditLogger(Path.of("logs", "security-audit.json")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.WARNING, "Server shutdown failed", exception);
            }
        }, "ember-server-shutdown"));
        server.start();
        System.out.println("Ember server listening on 127.0.0.1:" + server.port());
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private static char[] readStorePassword(Path path) throws IOException {
        for (String line : java.nio.file.Files.readAllLines(path,
                java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("EMBER_KEYSTORE_PASS=")) {
                return line.substring("EMBER_KEYSTORE_PASS=".length()).toCharArray();
            }
        }
        throw new IOException("Keystore password missing from config/secrets.properties");
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        autoSaveThread.start();
        Thread acceptThread = new Thread(this::acceptLoop, "ember-tls-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        autoSaveThread.interrupt();
        serverSocket.close();
        clients.shutdownNow();
        storage.close();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                ConnectionHandler handler = new ConnectionHandler(
                        (javax.net.ssl.SSLSocket) serverSocket.accept());
                clients.execute(handler);
            } catch (SocketException exception) {
                if (running.get()) {
                    java.util.logging.Logger.getLogger(EmberServer.class.getName())
                            .log(Level.WARNING, "TLS accept failed", exception);
                }
                return;
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.WARNING, "TLS accept failed", exception);
            }
        }
    }

    private void disconnect(ConnectionHandler handler) {
        if (handler.userId != null) {
            online.remove(handler.userId, handler);
        }
        if (handler.token != null) {
            sessions.remove(handler.token);
        }
        try {
            handler.socket.close();
        } catch (IOException exception) {
            java.util.logging.Logger.getLogger(EmberServer.class.getName())
                    .log(Level.FINE, "Connection close failed", exception);
        }
    }

    /** Owns one connection's authenticated identity and frame routing. @concept member inner class, threads */
    private final class ConnectionHandler implements Runnable {
        private final javax.net.ssl.SSLSocket socket;
        private final Object outputLock = new Object();
        private java.io.OutputStream output;
        private String userId;
        private String token;

        private ConnectionHandler(javax.net.ssl.SSLSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (socket) {
                socket.setEnabledProtocols(new String[] {"TLSv1.3"});
                socket.setSoTimeout(45_000);
                socket.startHandshake();
                output = socket.getOutputStream();
                java.io.InputStream input = socket.getInputStream();
                while (running.get() && !socket.isClosed()) {
                    try {
                        var frame = edu.ember.common.protocol.FrameCodec.readFrame(input,
                                userId == null ? 4096 : 64 * 1024);
                        route(frame);
                    } catch (IOException | edu.ember.common.exceptions.EmberException exception) {
                        break;
                    }
                }
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.FINE, "Client connection ended", exception);
            } finally {
                disconnect(this);
            }
        }

        private void route(edu.ember.common.protocol.WireFrame frame) throws IOException {
            try {
                if ((frame.opcode() == edu.ember.common.protocol.WireOpcodes.CMD_AUTH_LOGIN
                        || frame.opcode() == edu.ember.common.protocol.WireOpcodes.CMD_AUTH_REGISTER)
                        && !authRateLimiter.allow(socket.getInetAddress())) {
                    auditLogger.log("rate_limited", "unknown", socket.getInetAddress().getHostAddress());
                    sendError(frame.correlationId(), "429", "Too many requests");
                    return;
                }
                switch (frame.opcode()) {
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_REGISTER -> authenticate(frame, true);
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_LOGIN -> authenticate(frame, false);
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_LOGOUT -> socket.close();
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_SEND -> sendMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_RECEIPT -> receiveReceipt(frame);
                    default -> sendError(frame.correlationId(), "400", "Unsupported command");
                }
            } catch (edu.ember.common.exceptions.EmberException exception) {
                String code = exception instanceof edu.ember.common.exceptions.AuthenticationLockedException
                        ? "423" : exception instanceof edu.ember.common.exceptions.AuthorizationException
                        ? "403" : exception instanceof edu.ember.common.exceptions.RateLimitedException
                        ? "429" : exception instanceof edu.ember.common.exceptions.AuthenticationException
                        ? "401" : "400";
                sendError(frame.correlationId(), code, exception.getMessage());
            }
        }

        private void authenticate(edu.ember.common.protocol.WireFrame frame, boolean register)
                throws IOException, edu.ember.common.exceptions.EmberException {
            MapFields fields = new MapFields(frame.payload());
            String username = fields.required("user");
            char[] password = fields.required("pass").toCharArray();
            var user = register
                    ? authService.register(username, password, fields.required("displayName"))
                    : authService.login(username, password);
            userId = user.userId();
            token = sessions.create(userId);
            online.put(userId, this);
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("token", token);
            response.put("userId", userId);
            response.put("role", "STUDENT");
            response.put("globalSeq", "0");
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_AUTH_OK,
                    frame.correlationId(), response));
        }

        private void sendMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String conversationId = fields.required("convId");
            String clientMsgId = fields.required("clientMsgId");
            String body = edu.ember.common.security.InputValidator.validateMessageBody(
                    fields.required("body"));
            TokenBucket bucket = messageBuckets.computeIfAbsent(userId,
                    ignored -> new TokenBucket(20, 5.0));
            if (!bucket.tryConsume()) {
                auditLogger.log("rate_limited", userId, "messages");
                sendError(frame.correlationId(), "429", "Too many requests");
                return;
            }
            String recipient = directRecipient(conversationId, userId);
            var message = new edu.ember.common.model.Message(clientMsgId, userId, conversationId,
                    body, System.currentTimeMillis());
            long conversationSequence = storage.appendMessage(message);
            java.util.Map<String, String> ack = new java.util.HashMap<>();
            ack.put("clientMsgId", clientMsgId);
            ack.put("convSeq", Long.toString(conversationSequence));
            ack.put("globalSeq", Long.toString(message.globalSequence()));
            ack.put("serverTs", Long.toString(message.createdAtMs()));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_ACK, frame.correlationId(), ack));
            ConnectionHandler target = online.get(recipient);
            if (target != null) {
                target.send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_MSG_DELIVER, 0,
                        messageFields(message)));
            }
        }

        private String directRecipient(String conversationId, String sender)
                throws edu.ember.common.exceptions.AuthorizationException {
            if (!conversationId.startsWith("dm:")) {
                throw new edu.ember.common.exceptions.AuthorizationException("Conversation is not a DM");
            }
            String[] participants = conversationId.substring(3).split(":", -1);
            if (participants.length != 2) {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
            if (participants[0].equals(sender)) {
                return participants[1];
            }
            if (participants[1].equals(sender)) {
                return participants[0];
            }
            throw new edu.ember.common.exceptions.AuthorizationException("Not a conversation participant");
        }

        private void receiveReceipt(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String messageId = fields.required("clientMsgId");
            var message = storage.findByClientMsgId(messageId);
            if (message.isEmpty() || message.get().senderUserId().equals(userId)
                    || !directRecipient(message.get().conversationId(),
                            message.get().senderUserId()).equals(userId)) {
                return;
            }
            String status = fields.required("status");
            if (!status.equals("READ")) {
                status = "DELIVERED";
                message.get().advanceStatus(edu.ember.common.model.MessageStatus.DELIVERED);
            } else {
                message.get().advanceStatus(edu.ember.common.model.MessageStatus.READ);
            }
            ConnectionHandler sender = online.get(message.get().senderUserId());
            if (sender != null) {
                java.util.Map<String, String> receipt = new java.util.HashMap<>();
                receipt.put("clientMsgId", messageId);
                receipt.put("status", status);
                receipt.put("delivered", "1");
                receipt.put("read", "0");
                receipt.put("total", "1");
                sender.send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_RECEIPT_UPDATE, 0, receipt));
            }
        }

        private void requireAuthenticated(long correlationId) throws IOException {
            if (userId == null) {
                sendError(correlationId, "401", "Authentication required");
                throw new IOException("Authentication required");
            }
        }

        private void sendError(long correlationId, String code, String message) throws IOException {
            java.util.Map<String, String> fields = new java.util.HashMap<>();
            fields.put("code", code);
            fields.put("message", message);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_ERROR, correlationId, fields));
        }

        private void send(edu.ember.common.protocol.WireFrame frame) throws IOException {
            synchronized (outputLock) {
                if (!socket.isClosed()) {
                    edu.ember.common.protocol.FrameCodec.writeFrame(output, frame);
                }
            }
        }

    }

    private static java.util.Map<String, String> messageFields(edu.ember.common.model.Message message) {
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("clientMsgId", message.clientMsgId());
        fields.put("senderUserId", message.senderUserId());
        fields.put("convId", message.conversationId());
        fields.put("body", message.body());
        fields.put("convSeq", Long.toString(message.conversationSequence()));
        fields.put("globalSeq", Long.toString(message.globalSequence()));
        fields.put("serverTs", Long.toString(message.createdAtMs()));
        return fields;
    }

    /** Provides required-field access over a decoded frame payload. @concept encapsulation */
    private static final class MapFields {
        private final java.util.Map<String, String> values;

        private MapFields(byte[] payload) {
            values = edu.ember.common.protocol.KvPayloadCodec.decode(payload);
        }

        private String required(String key) {
            return edu.ember.common.protocol.KvPayloadCodec.required(values, key);
        }
    }
}
