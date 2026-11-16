package edu.ember.server;

import edu.ember.common.model.GroupChat;
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
    private final SocialFeedService socialFeedService;
    private final java.util.Set<String> adminUsernames;
    private final SecurityAuditLogger auditLogger;
    private final AuthRateLimiter authRateLimiter = new AuthRateLimiter();
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> messageBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> reportBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, GroupReceiptTracker> receiptTrackers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, Long> typingLastSent =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AutoSaveThread autoSaveThread;
    private final ExpiryReaperThread expiryReaperThread;
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
                new SecurityAuditLogger(Path.of("logs", "security-audit.json")),
                Path.of("server_data", "media"));
    }

    public EmberServer(Path keyStore, char[] password, int port, StorageEngine storage,
            SecurityAuditLogger auditLogger) throws Exception {
        this(keyStore, password, port, storage, auditLogger, Path.of("server_data", "media"));
    }

    public EmberServer(Path keyStore, char[] password, int port, StorageEngine storage,
            SecurityAuditLogger auditLogger, Path mediaDirectory) throws Exception {
        this(keyStore, password, port, storage, auditLogger, mediaDirectory, 86_400);
    }

    public EmberServer(Path keyStore, char[] password, int port, StorageEngine storage,
            SecurityAuditLogger auditLogger, Path mediaDirectory, int storyTtlSec) throws Exception {
        this(keyStore, password, port, storage, auditLogger, mediaDirectory, storyTtlSec,
                java.util.Set.of());
    }

    public EmberServer(Path keyStore, char[] password, int port, StorageEngine storage,
            SecurityAuditLogger auditLogger, Path mediaDirectory, int storyTtlSec,
            java.util.Set<String> adminUsernames) throws Exception {
        serverSocket = TlsSocketFactory.createServerSocket(keyStore, password, port);
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.adminUsernames = adminUsernames.stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        storage.init();
        PasswordHasher hasher = new Pbkdf2PasswordHasher();
        authService = new AuthService(storage, hasher, new UserLockManager(), auditLogger);
        groupService = new GroupService(storage);
        socialFeedService = new SocialFeedService(storage, mediaDirectory, storyTtlSec);
        autoSaveThread = new AutoSaveThread(storage, 30_000);
        expiryReaperThread = new ExpiryReaperThread(storage, this::broadcastExpiredMessage);
    }

    public static void main(String[] args) throws Exception {
        int port = 7443;
        Path dataDirectory = Path.of("server_data");
        boolean strictDurability = true;
        boolean demoSeed = false;
        int storyTtlSec = 86_400;
        java.util.Set<String> adminUsernames = new java.util.HashSet<>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--port")) {
                port = Integer.parseInt(args[++index]);
            } else if (args[index].equals("--storage")) {
                dataDirectory = Path.of(args[++index]);
            } else if (args[index].equals("--durability")) {
                strictDurability = args[++index].equals("strict");
            } else if (args[index].equals("--story-ttl-sec")) {
                storyTtlSec = Integer.parseInt(args[++index]);
            } else if (args[index].equals("--admin-user")) {
                adminUsernames.add(args[++index]);
            } else if (args[index].equals("--demo-seed")) {
                demoSeed = true;
            }
        }
        Path config = Path.of("config");
        if (demoSeed) {
            if (edu.ember.tools.DemoSeeder.seed(dataDirectory)) {
                System.out.println("Demo accounts and sample content seeded.");
            } else {
                System.out.println("Demo seed skipped: Alice already exists.");
            }
            adminUsernames.add("alice");
        }
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, port,
                new WalStorageEngine(dataDirectory, strictDurability),
                new SecurityAuditLogger(Path.of("logs", "security-audit.json")),
                dataDirectory.resolve("media"), storyTtlSec, adminUsernames);
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

    private boolean isAdminUser(String username) {
        return adminUsernames.contains(username.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean isAdminUserId(String requestedUserId) throws IOException {
        for (String username : adminUsernames) {
            var user = storage.findUser(username);
            if (user.isPresent() && user.get().userId().equals(requestedUserId)) { return true; }
        }
        return false;
    }

    public void start() {
        autoSaveThread.start();
        expiryReaperThread.start();
        Thread acceptThread = new Thread(this::acceptLoop, "ember-tls-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        autoSaveThread.interrupt();
        expiryReaperThread.interrupt();
        serverSocket.close();
        clients.shutdownNow();
        try {
            expiryReaperThread.join(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
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
                                userId == null ? 4096 : 64 * 1024,
                                userId == null ? -1
                                        : edu.ember.common.protocol.WireOpcodes.CMD_MEDIA_UPLOAD,
                                7 * 1024 * 1024);
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
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_REACT -> reactMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_EDIT -> editMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_DELETE -> deleteMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_PIN -> pinMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MSG_STAR -> starMessage(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_PING -> ping(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_TYPING -> typing(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_HISTORY_PULL -> history(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_GROUP_CREATE -> createGroup(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_GROUP_MEMBER -> changeGroupMember(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_GROUP_INFO -> groupInfo(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_PROFILE_UPDATE -> updateProfile(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_FOLLOW_ACTION -> follow(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_USER_BLOCK -> blockUser(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MOD_REPORT -> reportObject(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MEDIA_UPLOAD -> uploadMedia(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_POST_CREATE -> createPost(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_POST_INTERACT -> interactPost(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_STORY_CREATE -> createStory(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_STORY_VIEW -> viewStories(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_POLL_CREATE -> createPoll(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_POLL_VOTE -> votePoll(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_ADMIN_ACTION -> adminAction(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_FEED_PULL -> pullFeed(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_PROFILE_GET -> getProfile(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_MEDIA_GET -> getMedia(frame);
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
                        ? "401" : exception instanceof edu.ember.common.exceptions.ResourceNotFoundException
                        ? "404" : "400";
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
            response.put("role", isAdminUser(user.username()) ? "ADMIN" : "STUDENT");
            response.put("admin", Boolean.toString(isAdminUser(user.username())));
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
            response.put("role", isAdminUserId(userId) ? "ADMIN" : "STUDENT");
            response.put("admin", Boolean.toString(isAdminUserId(userId)));
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
                requireDirectConversationAccess(conversationId, userId);
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
                String recipient = directRecipient(conversationId, userId);
                if (socialFeedService.isBlocked(userId, recipient)) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Conversation is unavailable");
                }
                recipients = java.util.List.of(recipient);
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

        private void groupInfo(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            String groupId = new MapFields(frame.payload()).required("groupId");
            GroupChat group = groupService.requireMember(userId, groupId);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_GROUP_UPDATE,
                    frame.correlationId(), groupFields(group)));
        }

        private void updateProfile(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String requestedOwner = fields.required("userId");
            if (!requestedOwner.equals(userId)) {
                throw new edu.ember.common.exceptions.AuthorizationException("Profile update is not allowed");
            }
            var profile = socialFeedService.updateProfile(userId,
                    fields.required("displayName"), fields.required("bio"),
                    Boolean.parseBoolean(fields.values.getOrDefault("privateAccount", "false")));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_PROFILE_DATA,
                    frame.correlationId(), profileFields(profile)));
        }

        private void follow(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String target = fields.required("targetUserId");
            String action = fields.values.getOrDefault("action", "FOLLOW");
            boolean following = Boolean.parseBoolean(fields.values.getOrDefault("follow", "true"));
            if (action.equals("APPROVE")) {
                socialFeedService.approveFollow(userId, target,
                        Boolean.parseBoolean(fields.values.getOrDefault("approve", "false")));
            } else {
                socialFeedService.follow(userId, target, following);
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_PROFILE_DATA, frame.correlationId(),
                    java.util.Map.of("followed", target,
                            "following", Boolean.toString(storage.followStatus(userId, target)
                                    .orElse(null) == edu.ember.common.model.FollowStatus.ACCEPTED),
                            "followStatus", storage.followStatus(userId, target)
                                    .map(Enum::name).orElse("NONE"),
                            "followerCount", Integer.toString(storage.followersOf(target).size()),
                            "followingCount", Integer.toString(storage.followingOf(target).size()))));
        }

        private void blockUser(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String target = fields.required("targetUserId");
            boolean blocked = Boolean.parseBoolean(fields.values.getOrDefault("blocked", "true"));
            socialFeedService.block(userId, target, blocked);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_PROFILE_DATA,
                    frame.correlationId(), java.util.Map.of("blockedUserId", target,
                            "blocked", Boolean.toString(blocked))));
        }

        private void reportObject(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            TokenBucket bucket = reportBuckets.computeIfAbsent(userId,
                    ignored -> new TokenBucket(3, 0.05));
            if (!bucket.tryConsume()) {
                auditLogger.log("rate_limited", userId, "reports");
                sendError(frame.correlationId(), "429", "Too many reports");
                return;
            }
            MapFields fields = new MapFields(frame.payload());
            var report = socialFeedService.report(userId, fields.required("targetType"),
                    fields.required("targetId"), fields.required("reason"));
            auditLogger.log("report_created", userId, report.targetType() + ":" + report.targetId());
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_NOTIFY, frame.correlationId(),
                    java.util.Map.of("kind", "REPORT_RECEIVED", "reportId", report.reportId())));
        }

        private void uploadMedia(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String encoded = fields.required("data");
            if (encoded.length() > 7 * 1024 * 1024) {
                throw new edu.ember.common.exceptions.MediaValidationException("Image upload exceeds the 5 MB limit");
            }
            byte[] bytes;
            try {
                bytes = java.util.Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new edu.ember.common.exceptions.MediaValidationException("Image data is invalid");
            }
            edu.ember.common.model.MediaAsset asset;
            try {
                asset = socialFeedService.upload(userId, bytes, fields.required("fileName"));
            } catch (edu.ember.common.exceptions.MediaValidationException exception) {
                auditLogger.log("upload_rejected", userId, "image");
                throw exception;
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MEDIA_OK, frame.correlationId(),
                    java.util.Map.of("mediaId", asset.mediaId(), "width", Integer.toString(asset.width()),
                            "height", Integer.toString(asset.height()))));
        }

        private void createPost(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            var post = socialFeedService.createPost(userId, fields.required("mediaId"),
                    fields.required("caption"));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE, frame.correlationId(),
                    java.util.Map.of("post.n", "1", "post.0", post.postId(),
                            "owner.0", post.ownerUserId(), "media.0", post.mediaId(),
                            "caption.0", post.caption(), "created.0", Long.toString(post.createdAtMs()),
                            "likes.0", "0", "comments.0", "0", "liked.0", "false")));
        }

        private void createStory(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            var story = socialFeedService.createStory(userId, fields.required("mediaId"),
                    fields.values.getOrDefault("caption", ""));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_STORY_UPDATE,
                    frame.correlationId(), java.util.Map.of("story.n", "1",
                            "story.0", story.storyId(), "owner.0", story.ownerUserId(),
                            "media.0", story.mediaId(), "caption.0", story.caption(),
                            "created.0", Long.toString(story.createdAtMs()),
                            "expires.0", Long.toString(story.expiresAtMs()))));
        }

        private void viewStories(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            java.util.List<edu.ember.common.model.StoryItem> stories =
                    socialFeedService.stories(userId);
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("story.n", Integer.toString(stories.size()));
            for (int index = 0; index < stories.size(); index++) {
                var story = stories.get(index);
                response.put("story." + index, story.storyId());
                response.put("owner." + index, story.ownerUserId());
                response.put("media." + index, story.mediaId());
                response.put("caption." + index, story.caption());
                response.put("created." + index, Long.toString(story.createdAtMs()));
                response.put("expires." + index, Long.toString(story.expiresAtMs()));
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_STORY_UPDATE,
                    frame.correlationId(), response));
        }

        private void createPoll(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String groupId = fields.required("groupId");
            var group = groupService.requireMember(userId, groupId);
            String question = fields.required("question").strip();
            int count = Integer.parseInt(fields.required("option.n"));
            if (question.isBlank() || question.length() > 200 || count < 2 || count > 6) {
                throw new edu.ember.common.exceptions.ValidationException("Poll requires a question and 2–6 options");
            }
            java.util.List<String> options = new java.util.ArrayList<>();
            for (int index = 0; index < count; index++) {
                String option = fields.required("option." + index).strip();
                if (option.isBlank() || option.length() > 80) {
                    throw new edu.ember.common.exceptions.ValidationException("Poll options must be 1–80 characters");
                }
                options.add(option);
            }
            var poll = new edu.ember.common.model.PollData(
                    java.util.UUID.randomUUID().toString(), groupId, question, options,
                    java.util.List.copyOf(group.members().keySet()));
            storage.savePoll(poll);
            sendPollUpdate(poll);
        }

        private void votePoll(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String pollId = fields.required("pollId");
            var poll = storage.findPoll(pollId).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Poll not found"));
            groupService.requireMember(userId, poll.groupId());
            int option;
            try { option = Integer.parseInt(fields.required("option")); }
            catch (NumberFormatException exception) {
                throw new edu.ember.common.exceptions.ValidationException("Invalid poll option");
            }
            storage.votePoll(pollId, userId, option);
            sendPollUpdate(poll);
        }

        private void adminAction(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            if (!isAdminUserId(userId)) {
                throw new edu.ember.common.exceptions.AuthorizationException("Administrator role required");
            }
            MapFields fields = new MapFields(frame.payload());
            String action = fields.required("action");
            if (action.equals("UNLOCK")) {
                String username = fields.required("username");
                boolean unlocked = authService.unlock(username);
                auditLogger.log("admin_unlock", userId, username);
                send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_NOTIFY, frame.correlationId(),
                        java.util.Map.of("kind", "UNLOCK_RESULT",
                                "unlocked", Boolean.toString(unlocked))));
            } else if (!action.equals("REPORTS")) {
                throw new edu.ember.common.exceptions.ValidationException("Unknown admin action");
            }
            sendAdminData(frame.correlationId());
        }

        private void sendAdminData(long correlationId) throws IOException {
            java.util.List<edu.ember.common.model.ModerationReport> reports = storage.listReports();
            java.util.List<String> locked = authService.lockedUsers();
            java.util.Map<String, String> fields = new java.util.HashMap<>();
            fields.put("report.n", Integer.toString(reports.size()));
            fields.put("locked.n", Integer.toString(locked.size()));
            for (int index = 0; index < reports.size(); index++) {
                var report = reports.get(index);
                fields.put("report.id." + index, report.reportId());
                fields.put("reporter." + index, report.reporterUserId());
                fields.put("targetType." + index, report.targetType());
                fields.put("targetId." + index, report.targetId());
                fields.put("reason." + index, report.reason());
                fields.put("created." + index, Long.toString(report.createdAtMs()));
            }
            for (int index = 0; index < locked.size(); index++) {
                fields.put("locked." + index, locked.get(index));
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_ADMIN_DATA, correlationId, fields));
        }

        private void sendPollUpdate(edu.ember.common.model.PollData poll) throws IOException,
                edu.ember.common.exceptions.AuthorizationException {
            java.util.Map<String, String> fields = new java.util.HashMap<>();
            fields.put("pollId", poll.pollId()); fields.put("groupId", poll.groupId());
            fields.put("question", poll.question());
            fields.put("option.n", Integer.toString(poll.options().size()));
            fields.put("member.n", Integer.toString(poll.memberIds().size()));
            for (int index = 0; index < poll.options().size(); index++) {
                fields.put("option." + index, poll.options().get(index));
                fields.put("total." + index, Integer.toString(poll.totals().get(index)));
            }
            for (int index = 0; index < poll.memberIds().size(); index++) {
                fields.put("member." + index, poll.memberIds().get(index));
                int[] row = poll.voteMatrix()[index];
                for (int option = 0; option < row.length; option++) {
                    if (row[option] == 1) { fields.put("vote." + index, Integer.toString(option)); }
                }
            }
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_POLL_UPDATE, 0, fields);
            for (String member : groupService.memberIds(poll.groupId())) {
                ConnectionHandler connection = online.get(member);
                if (connection != null) { connection.send(event); }
            }
        }

        private void interactPost(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            if (fields.values.getOrDefault("action", "").equals("comments")) {
                var comments = socialFeedService.comments(userId, fields.required("postId"));
                java.util.Map<String, String> response = new java.util.HashMap<>();
                response.put("postId", fields.required("postId"));
                response.put("comment.n", Integer.toString(comments.size()));
                for (int index = 0; index < comments.size(); index++) {
                    var comment = comments.get(index);
                    response.put("comment.id." + index, comment.commentId());
                    response.put("comment.author." + index, comment.authorUserId());
                    response.put("comment.text." + index, comment.text());
                    response.put("comment.created." + index, Long.toString(comment.createdAtMs()));
                }
                send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE,
                        frame.correlationId(), response));
                return;
            }
            var post = socialFeedService.interact(userId, fields.required("postId"),
                    fields.required("action"), fields.values.getOrDefault("text", ""));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE, frame.correlationId(),
                    java.util.Map.of("post.n", "1", "post.0", post.postId(),
                            "owner.0", post.ownerUserId(), "media.0", post.mediaId(),
                            "caption.0", post.caption(), "created.0", Long.toString(post.createdAtMs()),
                            "likes.0", Integer.toString(post.likeCount()), "comments.0",
                            Integer.toString(post.commentCount()), "liked.0",
                            Boolean.toString(post.likedByViewer()))));
        }

        private void pullFeed(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String owner = fields.values.getOrDefault("ownerUserId", "");
            if (!owner.isEmpty()) { socialFeedService.profile(owner); }
            java.util.List<edu.ember.common.model.SocialPost> posts = socialFeedService.feed(
                    owner, userId, Integer.parseInt(fields.values.getOrDefault("limit", "30")));
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("post.n", Integer.toString(posts.size()));
            for (int index = 0; index < posts.size(); index++) {
                var post = posts.get(index);
                response.put("post." + index, post.postId());
                response.put("owner." + index, post.ownerUserId());
                response.put("media." + index, post.mediaId());
                response.put("caption." + index, post.caption());
                response.put("created." + index, Long.toString(post.createdAtMs()));
                response.put("likes." + index, Integer.toString(post.likeCount()));
                response.put("comments." + index, Integer.toString(post.commentCount()));
                response.put("liked." + index, Boolean.toString(post.likedByViewer()));
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE,
                    frame.correlationId(), response));
        }

        private void getProfile(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            String target = new MapFields(frame.payload()).required("userId");
            var profile = socialFeedService.visibleProfile(userId, target);
            java.util.Map<String, String> response = profileFields(profile);
            response.put("followerCount", Integer.toString(storage.followersOf(target).size()));
            response.put("followingCount", Integer.toString(storage.followingOf(target).size()));
            response.put("following", Boolean.toString(storage.followingOf(userId).contains(target)));
            response.put("privateAccount", Boolean.toString(profile.privateAccount()));
            response.put("pendingCount", Integer.toString(storage.pendingFollowersOf(target).size()));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_PROFILE_DATA,
                    frame.correlationId(), response));
        }

        private void getMedia(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            byte[] image = socialFeedService.media(userId, fields.required("mediaId"),
                    Boolean.parseBoolean(fields.values.getOrDefault("thumbnail", "true")));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MEDIA_DATA, frame.correlationId(),
                    java.util.Map.of("mediaId", fields.required("mediaId"),
                            "data", java.util.Base64.getEncoder().encodeToString(image))));
        }

        private java.util.Map<String, String> profileFields(edu.ember.common.model.UserProfile profile) {
            return new java.util.HashMap<>(java.util.Map.of("userId", profile.userId(),
                    "displayName", profile.displayName(), "bio", profile.bio(),
                    "avatarMediaId", profile.avatarMediaId(),
                    "privateAccount", Boolean.toString(profile.privateAccount())));
        }

        private void sendGroupUpdate(long correlationId, String groupId) throws IOException,
                edu.ember.common.exceptions.AuthorizationException {
            GroupChat group = storage.findGroup(groupId).orElseThrow(
                    () -> new edu.ember.common.exceptions.AuthorizationException("Group not found"));
            java.util.Map<String, String> fields = groupFields(group);
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_GROUP_UPDATE, correlationId, fields);
            for (String memberId : groupService.memberIds(groupId)) {
                ConnectionHandler member = online.get(memberId);
                if (member != null) {
                    member.send(event);
                }
            }
        }

        private java.util.Map<String, String> groupFields(GroupChat group) {
            java.util.Map<String, String> fields = new java.util.HashMap<>();
            fields.put("groupId", group.groupId());
            fields.put("groupName", group.name());
            fields.put("description", group.description());
            fields.put("member.n", Integer.toString(group.members().size()));
            int index = 0;
            for (var member : group.members().entrySet()) {
                fields.put("member." + index, member.getKey());
                fields.put("role." + index, member.getValue().name());
                index++;
            }
            return fields;
        }

        private void sendMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String conversationId = fields.required("convId");
            String clientMsgId = fields.required("clientMsgId");
            String body = edu.ember.common.security.InputValidator.validateMessageBody(
                    fields.required("body"));
            String replyToId = fields.values.getOrDefault("replyToId", "");
            String replyPreview = "";
            if (!replyToId.isEmpty()) {
                var parent = storage.findByClientMsgId(replyToId).orElseThrow(
                        () -> new edu.ember.common.exceptions.ResourceNotFoundException("Reply target not found"));
                if (!parent.conversationId().equals(conversationId)
                        || (parent.expiresAtMs() > 0 && parent.expiresAtMs() <= System.currentTimeMillis())) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Reply target is not available");
                }
                replyPreview = parent.body().substring(0, Math.min(100, parent.body().length()));
            }
            int ttlSec;
            try {
                ttlSec = Integer.parseInt(fields.values.getOrDefault("ttlSec", "0"));
            } catch (NumberFormatException exception) {
                throw new edu.ember.common.exceptions.ValidationException("Invalid message expiry");
            }
            if (ttlSec != 0 && ttlSec != 15) {
                throw new edu.ember.common.exceptions.ValidationException(
                        "Message expiry must be off or 15 seconds");
            }
            TokenBucket bucket = messageBuckets.computeIfAbsent(userId,
                    ignored -> new TokenBucket(20, 5.0));
            if (!bucket.tryConsume()) {
                auditLogger.log("rate_limited", userId, "messages");
                sendError(frame.correlationId(), "429", "Too many requests");
                return;
            }
            java.util.List<String> recipients;
            if (conversationId.startsWith("dm:")) {
                String recipient = directRecipient(conversationId, userId);
                if (socialFeedService.isBlocked(userId, recipient)) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Conversation is unavailable");
                }
                recipients = java.util.List.of(recipient);
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
                if (!saved.conversationId().equals(conversationId) || !saved.body().equals(body)
                        || saved.ttlSec() != ttlSec || !saved.replyToId().equals(replyToId)) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Message ID is already used");
                }
                sendMessageAck(frame.correlationId(), saved);
                return;
            }
            var message = new edu.ember.common.model.Message(clientMsgId, userId, conversationId,
                    body, System.currentTimeMillis(), ttlSec, replyToId, replyPreview);
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

        private void reactMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String clientMsgId = fields.required("clientMsgId");
            String emoji = fields.required("emoji");
            java.util.Set<String> quickReactions = java.util.Set.of("👍", "❤️", "😂", "😮", "😢", "🔥");
            if (!quickReactions.contains(emoji)) {
                throw new edu.ember.common.exceptions.ValidationException("Unsupported quick reaction");
            }
            var message = storage.findByClientMsgId(clientMsgId).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            if (message.expiresAtMs() > 0 && message.expiresAtMs() <= System.currentTimeMillis()) {
                throw new edu.ember.common.exceptions.ResourceNotFoundException("Message not found");
            }
            if (message.conversationId().startsWith("dm:")) {
                requireDirectConversationAccess(message.conversationId(), userId);
            } else if (message.conversationId().startsWith("grp:")) {
                groupService.requireMember(userId, message.conversationId().substring(4));
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
            boolean selected = !Boolean.parseBoolean(fields.values.getOrDefault("remove", "false"));
            storage.setMessageReaction(clientMsgId, userId, emoji, selected);
            var updated = storage.findByClientMsgId(clientMsgId).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            int count = updated.reactionCounts().getOrDefault(emoji, 0);
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE, frame.correlationId(),
                    java.util.Map.of("state", "REACTION", "clientMsgId", clientMsgId,
                            "convId", message.conversationId(), "userId", userId,
                            "emoji", emoji, "selected", Boolean.toString(selected),
                            "count", Integer.toString(count)));
            broadcastToConversation(message.conversationId(), event);
        }

        private void editMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String id = fields.required("clientMsgId");
            var message = storage.findByClientMsgId(id).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            requireMessageConversationAccess(message);
            if (!message.senderUserId().equals(userId)) {
                throw new edu.ember.common.exceptions.AuthorizationException("Only the sender can edit this message");
            }
            String body = edu.ember.common.security.InputValidator.validateMessageBody(
                    fields.required("newBody"));
            storage.editMessage(id, body);
            var updated = storage.findByClientMsgId(id).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            java.util.Map<String, String> eventFields = messageFields(updated);
            eventFields.put("state", "EDITED");
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE,
                    frame.correlationId(), eventFields);
            broadcastToConversation(message.conversationId(), event);
        }

        private void deleteMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            String id = new MapFields(frame.payload()).required("clientMsgId");
            var message = storage.findByClientMsgId(id).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            requireMessageConversationAccess(message);
            boolean allowed = message.senderUserId().equals(userId);
            if (!allowed && message.conversationId().startsWith("grp:")) {
                allowed = groupService.roles(message.conversationId().substring(4))
                        .get(userId) == edu.ember.common.model.UserRole.MODERATOR;
            }
            if (!allowed) {
                throw new edu.ember.common.exceptions.AuthorizationException("Message delete is not allowed");
            }
            storage.deleteMessage(id);
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE,
                    frame.correlationId(), java.util.Map.of("state", "DELETED",
                            "clientMsgId", id, "convId", message.conversationId()));
            broadcastToConversation(message.conversationId(), event);
        }

        private void pinMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String id = fields.required("clientMsgId");
            var message = storage.findByClientMsgId(id).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            requireMessageConversationAccess(message);
            boolean pinned = Boolean.parseBoolean(fields.required("pinned"));
            storage.setMessagePinned(id, pinned);
            broadcastMessageState(message, frame.correlationId(), "PINNED", "pinned",
                    Boolean.toString(pinned));
        }

        private void starMessage(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String id = fields.required("clientMsgId");
            var message = storage.findByClientMsgId(id).orElseThrow(
                    () -> new edu.ember.common.exceptions.ResourceNotFoundException("Message not found"));
            requireMessageConversationAccess(message);
            boolean starred = Boolean.parseBoolean(fields.required("starred"));
            storage.setMessageStarred(id, userId, starred);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE, frame.correlationId(),
                    java.util.Map.of("state", "STARRED", "clientMsgId", id,
                            "userId", userId, "starred", Boolean.toString(starred))));
        }

        private void broadcastMessageState(edu.ember.common.model.Message message,
                long correlationId, String state, String key, String value) throws IOException,
                edu.ember.common.exceptions.AuthorizationException {
            var event = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE, correlationId,
                    java.util.Map.of("state", state, "clientMsgId", message.clientMsgId(),
                            "convId", message.conversationId(), key, value));
            broadcastToConversation(message.conversationId(), event);
        }

        private void broadcastToConversation(String conversationId,
                edu.ember.common.protocol.WireFrame event) throws IOException,
                edu.ember.common.exceptions.AuthorizationException {
            java.util.List<String> recipients;
            if (conversationId.startsWith("dm:")) {
                String[] participants = conversationId.substring(3).split(":", -1);
                if (participants.length != 2) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
                }
                recipients = java.util.List.of(participants);
            } else {
                recipients = groupService.memberIds(conversationId.substring(4));
            }
            for (String recipient : recipients) {
                ConnectionHandler target = online.get(recipient);
                if (target != null) { target.send(event); }
            }
        }

        private void sendMessageAck(long correlationId, edu.ember.common.model.Message message)
                throws IOException {
            java.util.Map<String, String> ack = new java.util.HashMap<>();
            ack.put("clientMsgId", message.clientMsgId());
            ack.put("convSeq", Long.toString(message.conversationSequence()));
            ack.put("globalSeq", Long.toString(message.globalSequence()));
            ack.put("serverTs", Long.toString(message.createdAtMs()));
            ack.put("ttlSec", Integer.toString(message.ttlSec()));
            ack.put("expiresAtMs", Long.toString(message.expiresAtMs()));
            ack.put("replyToId", message.replyToId());
            ack.put("replyPreview", message.replyPreview());
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

        private String requireDirectConversationAccess(String conversationId, String participant)
                throws IOException, edu.ember.common.exceptions.AuthorizationException {
            String recipient = directRecipient(conversationId, participant);
            if (socialFeedService.isBlocked(participant, recipient)) {
                throw new edu.ember.common.exceptions.AuthorizationException("Conversation is unavailable");
            }
            return recipient;
        }

        private void requireMessageConversationAccess(edu.ember.common.model.Message message)
                throws IOException, edu.ember.common.exceptions.EmberException {
            String conversationId = message.conversationId();
            if (conversationId.startsWith("dm:")) {
                requireDirectConversationAccess(conversationId, userId);
            } else if (conversationId.startsWith("grp:")) {
                groupService.requireMember(userId, conversationId.substring(4));
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
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
                if (!directRecipient(conversationId, message.get().senderUserId()).equals(userId)
                        || socialFeedService.isBlocked(userId, message.get().senderUserId())) {
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

    private void broadcastExpiredMessage(edu.ember.common.model.Message message) {
        var event = edu.ember.common.protocol.FrameFactory.event(
                edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE, 0,
                java.util.Map.of("clientMsgId", message.clientMsgId(),
                        "convId", message.conversationId(), "state", "EXPIRED"));
        for (ConnectionHandler handler : online.values()) {
            try {
                handler.send(event);
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.FINE, "Expiry notification failed", exception);
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
        fields.put("ttlSec", Integer.toString(message.ttlSec()));
        fields.put("expiresAtMs", Long.toString(message.expiresAtMs()));
        fields.put("replyToId", message.replyToId());
        fields.put("replyPreview", message.replyPreview());
        java.util.Map<String, Integer> reactions = message.reactionCounts();
        fields.put("reaction.n", Integer.toString(reactions.size()));
        int reactionIndex = 0;
        for (var reaction : reactions.entrySet()) {
            fields.put("reaction.emoji." + reactionIndex, reaction.getKey());
            fields.put("reaction.count." + reactionIndex, Integer.toString(reaction.getValue()));
            reactionIndex++;
        }
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
