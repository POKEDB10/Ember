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
    private final GroupService groupService;
    private final SecurityAuditLogger auditLogger;
    private final AuthRateLimiter authRateLimiter = new AuthRateLimiter();
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> messageBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, GroupReceiptTracker> receiptTrackers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, Long> typingLastSent =
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
        groupService = new GroupService(storage);
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
            boolean wasOnline = online.remove(handler.userId, handler);
            if (wasOnline) {
                broadcastPresence(handler.userId, false);
            }
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
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_RESUME -> resume(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_LOGOUT -> logout();
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_SEND -> sendMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_RECEIPT -> receiveReceipt(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_PING -> ping(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_TYPING -> typing(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_HISTORY_PULL -> history(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_GROUP_CREATE -> createGroup(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_GROUP_MEMBER -> changeGroupMember(frame);
                    default -> sendError(frame.correlationId(), "400", "Unsupported command");
                }
            } catch (edu.ember.common.exceptions.EmberException exception) {
                if (exception instanceof edu.ember.common.exceptions.AuthorizationException) {
                    auditLogger.log("authz_fail", userId == null ? "unknown" : userId,
                            "opcode:" + frame.opcode());
                }
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
            sendSync(0);
            sendPresenceSnapshot(this);
            broadcastPresence(userId, true);
        }

        private void resume(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.AuthenticationException {
            MapFields fields = new MapFields(frame.payload());
            String suppliedToken = fields.required("token");
            String resumedUser = sessions.findUserId(suppliedToken);
            if (resumedUser == null) {
                sendError(frame.correlationId(), "401", "Authentication required");
                return;
            }
            userId = resumedUser;
            token = suppliedToken;
            online.put(userId, this);
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("token", token);
            response.put("userId", userId);
            response.put("role", "STUDENT");
            response.put("globalSeq", "0");
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_AUTH_OK,
                    frame.correlationId(), response));
            sendSync(Long.parseLong(fields.required("lastGlobalSeq")));
            sendPresenceSnapshot(this);
            broadcastPresence(userId, true);
        }

        private void sendSync(long sinceGlobalSeq) throws IOException {
            long upToGlobalSequence = sinceGlobalSeq;
            for (var item : storage.getDeltaFramesSince(userId, sinceGlobalSeq)) {
                send(item);
                upToGlobalSequence = Math.max(upToGlobalSequence, item.correlationId());
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_SYNC_DONE, 0,
                    java.util.Map.of("upToGlobalSeq", Long.toString(upToGlobalSequence))));
        }

        private void logout() throws IOException {
            if (token != null) {
                sessions.remove(token);
            }
            socket.close();
        }

        private void ping(edu.ember.common.protocol.WireFrame frame) throws IOException {
            requireAuthenticated(frame.correlationId());
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_PONG, frame.correlationId(),
                    java.util.Map.of("serverTs", Long.toString(System.currentTimeMillis()))));
        }

        private void history(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String conversationId = fields.required("convId");
            if (conversationId.startsWith("dm:")) {
                directRecipient(conversationId, userId);
            } else if (conversationId.startsWith("grp:")) {
                groupService.requireMember(userId, conversationId.substring(4));
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
            long before = Long.parseLong(fields.required("beforeSeq"));
            int limit = Integer.parseInt(fields.required("limit"));
            if (before < 1 || limit < 1 || limit > 100) {
                throw new edu.ember.common.exceptions.ValidationException("Invalid history range");
            }
            for (var message : storage.getHistory(conversationId, before, limit)) {
                send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_HISTORY_PAGE,
                        frame.correlationId(), messageFields(message)));
            }
        }

        private void typing(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String conversationId = fields.required("convId");
            boolean isTyping = Boolean.parseBoolean(fields.required("typing"));
            java.util.List<String> recipients;
            if (conversationId.startsWith("dm:")) {
                recipients = java.util.List.of(directRecipient(conversationId, userId));
            } else if (conversationId.startsWith("grp:")) {
                String groupId = conversationId.substring(4);
                groupService.requireMember(userId, groupId);
                recipients = groupService.memberIds(groupId).stream()
                        .filter(member -> !member.equals(userId)).toList();
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
            String key = conversationId + "\n" + userId;
            long now = System.currentTimeMillis();
            if (isTyping) {
                Long previous = typingLastSent.putIfAbsent(key, now);
                if (previous != null && now - previous < 3_000) {
                    return;
                }
                typingLastSent.put(key, now);
            } else {
                typingLastSent.remove(key);
            }
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_TYPING, frame.correlationId(),
                    java.util.Map.of("convId", conversationId, "userId", userId,
                            "typing", Boolean.toString(isTyping)));
            for (String recipient : recipients) {
                ConnectionHandler target = online.get(recipient);
                if (target != null) {
                    target.send(event);
                }
            }
        }

        private void createGroup(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            var group = groupService.create(userId, fields.required("name"), fields.required("desc"));
            int count = Integer.parseInt(fields.required("member.n"));
            for (int index = 0; index < count; index++) {
                groupService.changeMember(userId, group.groupId(), fields.required("member." + index), "ADD");
            }
            sendGroupUpdate(frame.correlationId(), group.groupId());
        }

        private void changeGroupMember(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            groupService.changeMember(userId, fields.required("groupId"),
                    fields.required("targetUser"), fields.required("action"));
            sendGroupUpdate(frame.correlationId(), fields.required("groupId"));
        }

        private void sendGroupUpdate(long correlationId, String groupId) throws IOException,
                edu.ember.common.exceptions.AuthorizationException {
            java.util.Map<String, String> fields = new java.util.HashMap<>();
            fields.put("groupId", groupId);
            fields.put("member.n", Integer.toString(groupService.memberIds(groupId).size()));
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_GROUP_UPDATE, correlationId, fields);
            for (String memberId : groupService.memberIds(groupId)) {
                ConnectionHandler member = online.get(memberId);
                if (member != null) {
                    member.send(event);
                }
            }
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
            java.util.List<String> recipients;
            if (conversationId.startsWith("dm:")) {
                recipients = java.util.List.of(directRecipient(conversationId, userId));
            } else if (conversationId.startsWith("grp:")) {
                String groupId = conversationId.substring(4);
                groupService.requireMember(userId, groupId);
                recipients = groupService.memberIds(groupId).stream()
                        .filter(member -> !member.equals(userId)).toList();
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
            var original = storage.findBySenderAndClientMsgId(userId, clientMsgId);
            if (original.isPresent()) {
                var saved = original.get();
                if (!saved.conversationId().equals(conversationId) || !saved.body().equals(body)) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Message ID is already used");
                }
                sendMessageAck(frame.correlationId(), saved);
                return;
            }
            var message = new edu.ember.common.model.Message(clientMsgId, userId, conversationId,
                    body, System.currentTimeMillis());
            storage.appendMessage(message);
            receiptTrackers.put(receiptKey(message), new GroupReceiptTracker(recipients.size()));
            sendMessageAck(frame.correlationId(), message);
            for (String recipient : recipients) {
                ConnectionHandler target = online.get(recipient);
                if (target != null) {
                    target.send(edu.ember.common.protocol.FrameFactory.event(
                            edu.ember.common.protocol.WireOpcodes.EVT_MSG_DELIVER, 0,
                            messageFields(message)));
                }
            }
        }

        private void sendMessageAck(long correlationId, edu.ember.common.model.Message message)
                throws IOException {
            java.util.Map<String, String> ack = new java.util.HashMap<>();
            ack.put("clientMsgId", message.clientMsgId());
            ack.put("convSeq", Long.toString(message.conversationSequence()));
            ack.put("globalSeq", Long.toString(message.globalSequence()));
            ack.put("serverTs", Long.toString(message.createdAtMs()));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_ACK, correlationId, ack));
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
            String conversationId = fields.required("convId");
            if (message.isEmpty() || message.get().senderUserId().equals(userId)
                    || !message.get().conversationId().equals(conversationId)) {
                throw new edu.ember.common.exceptions.AuthorizationException("Receipt is not allowed");
            }
            if (conversationId.startsWith("dm:")) {
                if (!directRecipient(conversationId, message.get().senderUserId()).equals(userId)) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Receipt is not allowed");
                }
            } else if (conversationId.startsWith("grp:")) {
                groupService.requireMember(userId, conversationId.substring(4));
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Receipt is not allowed");
            }
            String requestedStatus = fields.required("status");
            edu.ember.common.model.MessageStatus status = requestedStatus.equals("READ")
                    ? edu.ember.common.model.MessageStatus.READ
                    : edu.ember.common.model.MessageStatus.DELIVERED;
            if (!requestedStatus.equals("READ") && !requestedStatus.equals("DELIVERED")) {
                throw new edu.ember.common.exceptions.ValidationException("Invalid receipt status");
            }
            String key = receiptKey(message.get());
            GroupReceiptTracker tracker = receiptTrackers.get(key);
            if (tracker == null) {
                int totalRecipients = conversationId.startsWith("grp:")
                        ? Math.max(1, groupService.memberIds(conversationId.substring(4)).size() - 1)
                        : 1;
                GroupReceiptTracker fresh = new GroupReceiptTracker(totalRecipients);
                GroupReceiptTracker previous = receiptTrackers.putIfAbsent(key, fresh);
                tracker = previous == null ? fresh : previous;
            }
            GroupReceiptTracker.ReceiptSummary summary = tracker.record(userId, status);
            if (summary.read() == summary.total()) {
                storage.updateMessageStatus(messageId, edu.ember.common.model.MessageStatus.READ);
            } else if (summary.delivered() == summary.total()) {
                storage.updateMessageStatus(messageId, edu.ember.common.model.MessageStatus.DELIVERED);
            }
            ConnectionHandler sender = online.get(message.get().senderUserId());
            if (sender != null) {
                java.util.Map<String, String> receipt = new java.util.HashMap<>();
                receipt.put("clientMsgId", messageId);
                receipt.put("status", requestedStatus);
                receipt.put("delivered", Integer.toString(summary.delivered()));
                receipt.put("read", Integer.toString(summary.read()));
                receipt.put("total", Integer.toString(summary.total()));
                receipt.put("readBy", String.join(", ", summary.readBy()));
                sender.send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_RECEIPT_UPDATE, 0, receipt));
            }
        }

        private String receiptKey(edu.ember.common.model.Message message) {
            return message.senderUserId() + "\n" + message.clientMsgId();
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

    private void broadcastPresence(String userId, boolean isOnline) {
        var event = edu.ember.common.protocol.FrameFactory.event(
                edu.ember.common.protocol.WireOpcodes.EVT_PRESENCE_UPDATE, 0,
                java.util.Map.of("userId", userId, "online", Boolean.toString(isOnline)));
        for (ConnectionHandler handler : online.values()) {
            try {
                handler.send(event);
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.FINE, "Presence notification failed", exception);
            }
        }
    }

    private void sendPresenceSnapshot(ConnectionHandler handler) throws IOException {
        for (String onlineUser : online.keySet()) {
            handler.send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_PRESENCE_UPDATE, 0,
                    java.util.Map.of("userId", onlineUser, "online", "true")));
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
