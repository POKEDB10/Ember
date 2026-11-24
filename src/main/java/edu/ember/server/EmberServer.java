package edu.ember.server;

import edu.ember.common.model.GroupChat;
import edu.ember.common.security.PasswordHasher;
import edu.ember.common.security.Pbkdf2PasswordHasher;
import edu.ember.common.security.TlsSocketFactory;
import edu.ember.server.storage.BlobStoreReplica;
import edu.ember.server.storage.InMemoryStorageEngine;
import edu.ember.server.storage.RootzBlobStore;
import edu.ember.server.storage.StorageReplica;
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
    private volatile java.net.ServerSocket httpSocket;
    private volatile int maxFrameBytes = 25 * 1024 * 1024;
    /** Off by default: only a server that sits behind a known proxy may believe X-Forwarded-For. */
    private volatile boolean trustForwardedFor;
    /** Hand-off tickets live this long (tests shorten it). */
    private volatile long handoffTtlMs = 30_000;
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> handoffBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Sends sign-up and recovery codes; the console until SMTP is configured. */
    private volatile Mailer mailer = Mailer.console();
    private final ExecutorService mailSender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ember-mail");
        thread.setDaemon(true);
        return thread;
    });
    private EmailCodes emailCodes;
    /** Per client address: 5 mails, then one every 12 minutes; and 300 an hour in total. */
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> mailBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final TokenBucket mailTotal = new TokenBucket(300, 300.0 / 3600);
    /** Calls v2 feature flag (server half): --calls-v2 or EMBER_CALLS_V2=1. Off = legacy calls only. */
    private volatile boolean callsV2 = "1".equals(System.getenv("EMBER_CALLS_V2"));
    /** Chosen status per user: away, dnd or invisible (online when absent). Kept while the
     * server runs; devices send it again after they reconnect. */
    private final java.util.concurrent.ConcurrentMap<String, String> statuses =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> STATUSES =
            java.util.Set.of("online", "idle", "away", "dnd", "invisible");
    /** Null when push keys could not be set up; everything else still works. */
    private final WebPush webPush;
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
    /** Voice and video calls: the server rings and connects, the media itself is end-to-end encrypted. */
    private final CallRegistry<ConnectionHandler> callRegistry = new CallRegistry<>();
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> callBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** STUN/TURN servers handed to browsers for direct (lowest-delay) calls; JSON, set by EMBER_ICE_SERVERS. */
    private static final String ICE_SERVERS = java.util.Optional.ofNullable(System.getenv("EMBER_ICE_SERVERS"))
            .filter(value -> !value.isBlank())
            .orElse("[{\"urls\":[\"stun:stun.cloudflare.com:3478\",\"stun:stun.l.google.com:19302\"]}]");
    /** Every live connection per user, so several devices can be signed in at once. */
    private final java.util.concurrent.ConcurrentMap<String, java.util.Set<ConnectionHandler>> online =
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
        this(keyStore, password, port, storage, auditLogger, mediaDirectory, storyTtlSec,
                adminUsernames, "127.0.0.1");
    }

    public EmberServer(Path keyStore, char[] password, int port, StorageEngine storage,
            SecurityAuditLogger auditLogger, Path mediaDirectory, int storyTtlSec,
            java.util.Set<String> adminUsernames, String bindAddress) throws Exception {
        // No keystore means web-only mode: a hosting proxy (Render, Caddy) terminates HTTPS.
        serverSocket = keyStore == null ? null
                : TlsSocketFactory.createServerSocket(keyStore, password, port, bindAddress);
        this.storage = storage;
        this.auditLogger = auditLogger;
        this.adminUsernames = adminUsernames.stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        storage.init();
        PasswordHasher hasher = new Pbkdf2PasswordHasher();
        authService = new AuthService(storage, hasher, new UserLockManager(), auditLogger);
        String pepper = storage.setting("email.pepper").orElse(null);
        if (pepper == null) {
            pepper = EmailCodes.newPepper();
            storage.saveSetting("email.pepper", pepper);
        }
        emailCodes = new EmailCodes(java.util.Base64.getUrlDecoder().decode(pepper));
        groupService = new GroupService(storage);
        socialFeedService = new SocialFeedService(storage, mediaDirectory, storyTtlSec);
        WebPush push = null;
        try {
            push = new WebPush(storage);
        } catch (java.security.GeneralSecurityException exception) {
            java.util.logging.Logger.getLogger(EmberServer.class.getName())
                    .log(Level.WARNING, "Web Push disabled", exception);
        }
        webPush = push;
        autoSaveThread = new AutoSaveThread(storage, 30_000);
        expiryReaperThread = new ExpiryReaperThread(storage, this::broadcastExpiredMessage);
    }

    public static void main(String[] args) throws Exception {
        int port = 7443;
        Path dataDirectory = Path.of("server_data");
        boolean strictDurability = true;
        boolean demoSeed = false;
        boolean tls = true;
        boolean rootz = false;
        boolean trustProxy = false;
        boolean callsV2Flag = false;
        int storyTtlSec = 86_400;
        java.util.Set<String> adminUsernames = new java.util.HashSet<>();
        String bindAddress = "0.0.0.0";
        String httpBind = "127.0.0.1";
        int httpPort = -1;
        int maxFrameMb = 25;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--port" -> port = Integer.parseInt(args[++index]);
                case "--storage" -> dataDirectory = Path.of(args[++index]);
                case "--durability" -> strictDurability = args[++index].equals("strict");
                case "--story-ttl-sec" -> storyTtlSec = Integer.parseInt(args[++index]);
                case "--admin-user" -> adminUsernames.add(args[++index]);
                case "--http-port" -> httpPort = Integer.parseInt(args[++index]);
                case "--http-bind" -> httpBind = args[++index];
                case "--bind" -> bindAddress = args[++index];
                case "--no-tls" -> tls = false;
                case "--trust-proxy" -> trustProxy = true;
                case "--calls-v2" -> callsV2Flag = true;
                case "--rootz" -> rootz = true;
                case "--max-frame-mb" -> maxFrameMb = Integer.parseInt(args[++index]);
                case "--demo-seed" -> demoSeed = true;
                case "--new-storage-key" -> {
                    byte[] key = new byte[32];
                    new java.security.SecureRandom().nextBytes(key);
                    System.out.println("EMBER_STORAGE_KEY="
                            + java.util.Base64.getEncoder().encodeToString(key));
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        Path secrets = Path.of("config", "secrets.properties");
        if (demoSeed) {
            if (edu.ember.tools.DemoSeeder.seed(dataDirectory)) {
                System.out.println("Demo accounts and sample content seeded.");
            } else {
                System.out.println("Demo seed skipped: Alice already exists.");
            }
            adminUsernames.add("alice");
        }
        StorageReplica replica = null;
        if (rootz) {
            byte[] key = java.util.Base64.getDecoder().decode(readSecret(secrets, "EMBER_STORAGE_KEY"));
            if (key.length != 32) {
                throw new IllegalArgumentException("EMBER_STORAGE_KEY must be 32 bytes in Base64");
            }
            String apiKey = readSecret(secrets, "ROOTZ_API_KEY");
            String folder = readOptionalSecret(secrets, "ROOTZ_FOLDER_ID");
            // Inside the Ember folder, "data" holds the encrypted log and snapshots, "media" the
            // images. Missing subfolders fall back to the Ember folder itself.
            String dataFolder = folder == null ? null : RootzBlobStore.findSubfolder(apiKey, folder, "data");
            String mediaFolder = folder == null ? null : RootzBlobStore.findSubfolder(apiKey, folder, "media");
            System.out.println("rootz storage: data in " + (dataFolder != null ? "ember/data"
                    : folder != null ? "the Ember folder" : "the account root") + ", images in "
                    + (mediaFolder != null ? "ember/media" : folder != null ? "the Ember folder" : "the account root"));
            replica = new BlobStoreReplica(
                    new RootzBlobStore(apiKey, dataFolder != null ? dataFolder : folder),
                    new RootzBlobStore(apiKey, mediaFolder != null ? mediaFolder : folder),
                    new javax.crypto.spec.SecretKeySpec(key, "AES"), 2_000, "ember-");
        }
        EmberServer server = new EmberServer(
                tls ? Path.of("config", "ember-server.p12") : null,
                tls ? readSecret(secrets, "EMBER_KEYSTORE_PASS").toCharArray() : null, port,
                new WalStorageEngine(dataDirectory, strictDurability, replica),
                new SecurityAuditLogger(Path.of("logs", "security-audit.json")),
                dataDirectory.resolve("media"), storyTtlSec, adminUsernames, bindAddress);
        server.setMaxFrameBytes(maxFrameMb * 1024 * 1024);
        server.setTrustForwardedFor(trustProxy);
        if (callsV2Flag) {
            server.setCallsV2(true);
        }
        server.setPushContact(readOptionalSecret(secrets, "EMBER_PUBLIC_URL"));
        String smtpHost = readOptionalSecret(secrets, "EMBER_SMTP_HOST");
        if (smtpHost != null) {
            int smtpPort = Integer.parseInt(java.util.Objects.requireNonNullElse(
                    readOptionalSecret(secrets, "EMBER_SMTP_PORT"), "587"));
            String smtpUser = readSecret(secrets, "EMBER_SMTP_USER");
            server.setMailer(new SmtpMailer(smtpHost, smtpPort,
                    smtpPort == 465 ? SmtpMailer.Security.TLS : SmtpMailer.Security.STARTTLS, smtpUser,
                    readSecret(secrets, "EMBER_SMTP_PASS"),
                    java.util.Objects.requireNonNullElse(readOptionalSecret(secrets, "EMBER_SMTP_FROM"), smtpUser)));
            System.out.println("Email codes go out through " + smtpHost + ":" + smtpPort);
        } else {
            System.out.println("Email codes are printed here (no EMBER_SMTP_HOST in config/secrets.properties)");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.WARNING, "Server shutdown failed", exception);
            }
        }, "ember-server-shutdown"));
        server.start();
        if (tls) {
            System.out.println("Ember server listening on " + bindAddress + ":" + server.port()
                    + " (desktop app, and web app at https://<this-host>:" + server.port() + "/)");
        }
        if (httpPort >= 0) {
            System.out.println("Web app (plain HTTP, for a TLS proxy) on http://" + httpBind + ":"
                    + server.startHttp(httpPort, httpBind) + "/");
        }
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /** Environment variable first (hosting dashboards), then config/secrets.properties. */
    private static String readSecret(Path secrets, String name) throws IOException {
        String value = readOptionalSecret(secrets, name);
        if (value == null) {
            throw new IOException(name + " is not set (environment or config/secrets.properties)");
        }
        return value;
    }

    private static String readOptionalSecret(Path secrets, String name) throws IOException {
        String fromEnvironment = System.getenv(name);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        if (java.nio.file.Files.exists(secrets)) {
            for (String line : java.nio.file.Files.readAllLines(secrets,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.startsWith(name + "=") && !line.substring(name.length() + 1).isBlank()) {
                    return line.substring(name.length() + 1).strip();
                }
            }
        }
        return null;
    }

    /** Largest frame a signed-in client may send; keeps one huge upload from exhausting memory. */
    public void setMaxFrameBytes(int bytes) {
        maxFrameBytes = bytes;
    }

    /** Tests deliver push messages to a local endpoint instead of a real push service. */
    public void setPushEndpointFilter(java.util.function.Predicate<java.net.URI> filter) {
        if (webPush != null) {
            webPush.setAllowedEndpoint(filter);
        }
    }

    /** The site address push services may contact (VAPID "sub"); configuration only, never a header. */
    public void setPushContact(String httpsUrl) {
        if (webPush != null && httpsUrl != null && httpsUrl.startsWith("https://")) {
            webPush.setContact(httpsUrl);
        }
    }

    /** Call only when the plain-HTTP listener is reachable solely through your own proxy. */
    public void setCallsV2(boolean enabled) {
        callsV2 = enabled;
    }

    public void setMailer(Mailer mailer) {
        this.mailer = mailer;
    }

    /** For tests: how long a hand-off ticket stays valid. */
    public void setHandoffTtlMs(long ttlMs) {
        handoffTtlMs = ttlMs;
    }

    public void setTrustForwardedFor(boolean trust) {
        trustForwardedFor = trust;
    }

    public int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
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
        if (serverSocket != null) {
            Thread acceptThread = new Thread(() -> acceptLoop(serverSocket), "ember-tls-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    public int startHttp(int port) throws IOException {
        return startHttp(port, "127.0.0.1");
    }

    /**
     * Plain-HTTP web listener for a TLS proxy (Render, Caddy, cloudflared) that holds a
     * browser-trusted certificate. Serves web pages and WebSockets, never raw desktop frames.
     * Bind 127.0.0.1 when the proxy runs on this machine; 0.0.0.0 only inside a hosting platform.
     */
    public int startHttp(int port, String bindAddress) throws IOException {
        httpSocket = new java.net.ServerSocket(port, 50, java.net.InetAddress.getByName(bindAddress));
        Thread acceptThread = new Thread(() -> acceptLoop(httpSocket), "ember-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return httpSocket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        autoSaveThread.interrupt();
        expiryReaperThread.interrupt();
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (httpSocket != null) {
            httpSocket.close();
        }
        clients.shutdownNow();
        try {
            expiryReaperThread.join(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        storage.close();
    }

    private void acceptLoop(java.net.ServerSocket listener) {
        while (running.get()) {
            try {
                ConnectionHandler handler = new ConnectionHandler(listener.accept());
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
        var call = callRegistry.callOf(handler);
        if (call != null && handler.userId != null && call.deviceOf(handler.userId) == handler) {
            leaveCall(call, handler.userId, "ended");
        }
        if (handler.userId != null && removeOnline(handler.userId, handler)) {
            broadcastPresence(handler.userId, false);
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
        private final java.net.Socket socket;
        private final Object outputLock = new Object();
        private java.io.OutputStream output;
        private String userId;
        private String token;
        /** Only browsers that said they can take calls are rung (the Swing app cannot). */
        private volatile boolean supportsCalls;
        /** Signed in with the password on this connection (not a resumed token): may set the key backup. */
        private boolean passwordAuthed;
        /** Who the login rate limiter sees: the socket peer, or the proxy-reported client. */
        private java.net.InetAddress clientAddress;

        private ConnectionHandler(java.net.Socket socket) {
            this.socket = socket;
            this.clientAddress = socket.getInetAddress();
        }

        /**
         * Behind the plain-HTTP listener every peer is the proxy, so use the last
         * X-Forwarded-For entry (the one the proxy itself appended, which a client cannot forge).
         */
        private void useForwardedClient(String forwardedFor) {
            if (!trustForwardedFor || forwardedFor == null
                    || socket instanceof javax.net.ssl.SSLSocket) {
                return;
            }
            String last = forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).strip();
            if (last.matches("[0-9a-fA-F:.]{2,45}")) { // IP literal only, so no DNS lookup happens
                try {
                    java.net.InetAddress forwarded = java.net.InetAddress.getByName(last);
                    // Never accept loopback from a header: loopback is exempt from the login limiter.
                    if (!forwarded.isLoopbackAddress() && !forwarded.isAnyLocalAddress()) {
                        clientAddress = forwarded;
                    }
                } catch (java.net.UnknownHostException ignored) {
                    // Keep the socket peer.
                }
            }
        }

        @Override
        public void run() {
            WebGateway.Upgrade upgrade = null;
            try (socket) {
                socket.setSoTimeout(45_000);
                socket.setTcpNoDelay(true); // frames are flushed whole; never hold one back (call audio)
                if (socket instanceof javax.net.ssl.SSLSocket tls) {
                    tls.setEnabledProtocols(new String[] {"TLSv1.3"});
                    tls.startHandshake();
                }
                // Buffered so each frame leaves as one TLS record instead of one per header field.
                output = new java.io.BufferedOutputStream(socket.getOutputStream());
                java.io.InputStream input = new java.io.BufferedInputStream(socket.getInputStream());
                boolean http = WebGateway.isHttp(input);
                if (!http && !(socket instanceof javax.net.ssl.SSLSocket)) {
                    return; // the plain listener is web-only; desktop frames need TLS
                }
                if (http) {
                    upgrade = WebGateway.serve(input, output, mediaSource,
                            () -> edu.ember.common.protocol.FrameCodec.HEADER_BYTES + (userId == null ? 4096 : maxFrameBytes));
                    if (upgrade == null) {
                        return;
                    }
                    input = upgrade.input();
                    output = upgrade.output();
                    useForwardedClient(upgrade.forwardedFor());
                }
                while (running.get() && !socket.isClosed()) {
                    try {
                        // Before login only small frames; after login up to --max-frame-mb.
                        var frame = edu.ember.common.protocol.FrameCodec.readFrame(input,
                                userId == null ? 4096 : maxFrameBytes);
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
                if (upgrade != null) {
                    synchronized (outputLock) {
                        upgrade.release();
                    }
                }
            }
        }

        private void route(edu.ember.common.protocol.WireFrame frame) throws IOException {
            try {
                if ((frame.opcode() == edu.ember.common.protocol.WireOpcodes.CMD_AUTH_LOGIN
                        || frame.opcode() == edu.ember.common.protocol.WireOpcodes.CMD_AUTH_REGISTER
                        || frame.opcode() == edu.ember.common.protocol.WireOpcodes.CMD_PASSWORD_RESET
                        || frame.opcode() == edu.ember.common.protocol.WireOpcodes.CMD_EMAIL_CODE)
                        && !authRateLimiter.allow(clientAddress)) {
                    auditLogger.log("rate_limited", "unknown", clientAddress.getHostAddress());
                    sendError(frame.correlationId(), "429", "Too many requests");
                    return;
                }
                switch (frame.opcode()) {
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_REGISTER -> authenticate(frame, true);
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_LOGIN -> authenticate(frame, false);
                    case edu.ember.common.protocol.WireOpcodes.CMD_AUTH_RESUME -> resume(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_EMAIL_CODE -> emailCode(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_PASSWORD_RESET -> passwordReset(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_EMAIL_LINK -> emailLink(frame);
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
                    case edu.ember.common.protocol.WireOpcodes.CMD_STATUS_SET -> setStatus(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_PUSH_SUBSCRIBE -> subscribePush(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_CALL -> call(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_CALL_MEDIA -> callMedia(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_DEVICE_KEY -> deviceKey(frame);
                    case edu.ember.common.protocol.WireOpcodes.CMD_KEYS_GET -> keysGet(frame);
                    default -> sendError(frame.correlationId(), "400", "Unsupported command");
                }
            } catch (IllegalArgumentException malformed) {
                // A missing or malformed field is the client's mistake: answer 400, keep the connection.
                sendError(frame.correlationId(), "400", "Missing or invalid field");
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
            String email = register ? fields.values.get("email") : null;
            String emailHash = null;
            if (email != null && !email.isBlank()) {
                // Sign-up with an email: the address must be free and proven with the code sent to it.
                email = EmailCodes.normalize(email);
                emailHash = emailCodes.hash(email);
                edu.ember.common.security.InputValidator.validateUsername(username);
                if (storage.findUser(username).isPresent()) {
                    throw new edu.ember.common.exceptions.AuthenticationException("Invalid username or password");
                }
                if (emailOwner(emailHash) != null
                        || !emailCodes.verify("register", email, fields.values.get("code"), System.currentTimeMillis())) {
                    throw new edu.ember.common.exceptions.ValidationException("Wrong or expired email code");
                }
            }
            if (!register && username.contains("@")) {
                String owner = emailOwner(emailCodes.hash(EmailCodes.normalize(username)));
                if (owner == null) {
                    authService.failLogin(password);
                }
                username = owner;
            }
            var user = register
                    ? authService.register(username, password, fields.required("displayName"))
                    : authService.login(username, password);
            if (emailHash != null) {
                linkEmail(user.userId(), emailHash);
            }
            passwordAuthed = true;
            signIn(frame, user.userId(), fields);
        }

        /** Finishes any password sign-in: session, AUTH_OK, sync, presence. */
        private void signIn(edu.ember.common.protocol.WireFrame frame, String signedInUser, MapFields fields)
                throws IOException {
            var user = storage.findUser(signedInUser).orElseThrow();
            goOnline(user.userId());
            token = sessions.create(userId);
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("token", token);
            response.put("userId", userId);
            response.put("role", isAdminUser(user.username()) ? "ADMIN" : "STUDENT");
            response.put("admin", Boolean.toString(isAdminUser(user.username())));
            response.put("globalSeq", "0");
            response.put("ice", ICE_SERVERS);
            response.put("callsV2", Boolean.toString(callsV2));
            response.put("email", Boolean.toString(storage.setting("useremail:" + userId).filter(v -> !v.isEmpty()).isPresent()));
            storage.setting("keybackup:" + userId).ifPresent(backup -> response.put("keyBackup", backup));
            supportsCalls = "1".equals(fields.values.get("calls"));
            String sinceRevision = syncStart(fields, response);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_AUTH_OK,
                    frame.correlationId(), response));
            sendSync(0, sinceRevision);
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
            goOnline(resumedUser);
            token = suppliedToken;
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("token", token);
            response.put("userId", userId);
            response.put("role", isAdminUserId(userId) ? "ADMIN" : "STUDENT");
            response.put("admin", Boolean.toString(isAdminUserId(userId)));
            response.put("globalSeq", "0");
            response.put("ice", ICE_SERVERS);
            response.put("callsV2", Boolean.toString(callsV2));
            response.put("email", Boolean.toString(storage.setting("useremail:" + userId).filter(v -> !v.isEmpty()).isPresent()));
            storage.setting("keybackup:" + userId).ifPresent(backup -> response.put("keyBackup", backup));
            supportsCalls = "1".equals(fields.values.get("calls"));
            String sinceRevision = syncStart(fields, response);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_AUTH_OK,
                    frame.correlationId(), response));
            sendSync(Long.parseLong(fields.required("lastGlobalSeq")), sinceRevision);
            sendPresenceSnapshot(this);
            broadcastPresence(userId, true);
        }

        private void goOnline(String newUserId) {
            if (userId != null && !userId.equals(newUserId)) {
                removeOnline(userId, this);
            }
            userId = newUserId;
            // compute(), not computeIfAbsent().add(), so a concurrent removeOnline cannot drop us.
            online.compute(newUserId, (ignored, devices) -> {
                java.util.Set<ConnectionHandler> set = devices != null ? devices
                        : java.util.concurrent.ConcurrentHashMap.newKeySet();
                set.add(this);
                return set;
            });
        }

        /**
         * Adds push key, status and cache instructions to AUTH_OK. A device with a local cache
         * sends sinceRevision; a revision from a different or reset server means "start over".
         * Returns the revision to sync from, or null for the classic full/globalSeq sync.
         */
        private String syncStart(MapFields fields, java.util.Map<String, String> response)
                throws IOException {
            if (webPush != null) {
                response.put("vapidKey", webPush.publicKey());
            }
            response.put("status", statuses.getOrDefault(userId, "online"));
            String since = fields.values.get("sinceRevision");
            if (since == null) {
                return null;
            }
            long requested;
            try {
                requested = Long.parseLong(since);
            } catch (NumberFormatException exception) {
                requested = -1;
            }
            boolean reset = requested < 0 || requested > storage.currentRevision();
            response.put("syncReset", Boolean.toString(reset));
            return reset ? "0" : Long.toString(requested);
        }

        private void sendSync(long sinceGlobalSeq, String sinceRevision) throws IOException {
            long upToRevision = storage.currentRevision();
            long upToGlobalSequence = sinceGlobalSeq;
            var items = sinceRevision != null
                    ? storage.getChangesSince(userId, Long.parseLong(sinceRevision))
                    : storage.getDeltaFramesSince(userId, sinceGlobalSeq);
            for (var item : items) {
                send(item);
                upToGlobalSequence = Math.max(upToGlobalSequence, item.correlationId());
            }
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_SYNC_DONE, 0,
                    java.util.Map.of("upToGlobalSeq", Long.toString(upToGlobalSequence),
                            "upToRevision", Long.toString(upToRevision))));
        }

        private void setStatus(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            String status = new MapFields(frame.payload()).required("status");
            if (!STATUSES.contains(status)) {
                throw new edu.ember.common.exceptions.ValidationException("Unknown status");
            }
            if (status.equals("online")) {
                statuses.remove(userId);
            } else {
                statuses.put(userId, status);
            }
            broadcastPresence(userId, true);
        }

        /** Who owns an email hash, or null. An unlinked email is stored as an empty value. */
        private String emailOwner(String emailHash) throws IOException {
            return storage.setting("email:" + emailHash).filter(owner -> !owner.isEmpty()).orElse(null);
        }

        private void linkEmail(String owner, String emailHash) throws IOException {
            String previous = storage.setting("useremail:" + owner).orElse("");
            if (!previous.isEmpty() && !previous.equals(emailHash)) {
                storage.saveSetting("email:" + previous, "");
            }
            storage.saveSetting("email:" + emailHash, owner);
            storage.saveSetting("useremail:" + owner, emailHash);
        }

        /**
         * Emails a one-time code. The answer is always "sent", so nobody can learn which addresses
         * have accounts: an address already in use gets a notice instead of a sign-up code, and a
         * reset for an unknown address sends nothing.
         */
        private void emailCode(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            MapFields fields = new MapFields(frame.payload());
            String purpose = fields.required("purpose");
            String email = EmailCodes.normalize(fields.required("email"));
            if (purpose.equals("link")) {
                requireAuthenticated(frame.correlationId());
            } else if (!purpose.equals("register") && !purpose.equals("reset")) {
                throw new edu.ember.common.exceptions.ValidationException("Unknown code purpose");
            }
            boolean perAddress = clientAddress.isLoopbackAddress() // like the login limiter: loopback demos share one address
                    || mailBuckets.computeIfAbsent(clientAddress.getHostAddress(), ignored -> new TokenBucket(5, 1.0 / 720)).tryConsume();
            if (!perAddress || !mailTotal.tryConsume()) {
                throw new edu.ember.common.exceptions.RateLimitedException("Too many emails: try again later");
            }
            String owner = emailOwner(emailCodes.hash(email));
            String code = emailCodes.issue(purpose, email, System.currentTimeMillis());
            String subject;
            String body;
            if (purpose.equals("reset") && owner == null) {
                subject = null;
                body = null;
            } else if (owner != null && !purpose.equals("reset") && !owner.equals(userId)) {
                subject = "Your Ember account";
                body = "Someone (maybe you) tried to use this email for a new Ember account, but it already "
                        + "belongs to @" + owner + ".\n\nForgot the password? Choose \"Forgot password\" on the "
                        + "sign-in screen. If this was not you, you can ignore this email.";
            } else {
                subject = "Your Ember code: " + code;
                body = "Your Ember " + (purpose.equals("reset") ? "password reset" : purpose.equals("link")
                        ? "email confirmation" : "sign-up") + " code is\n\n    " + code + "\n\nIt works once, "
                        + "for 10 minutes. Nobody from Ember will ever ask you for it.";
            }
            if (subject != null) {
                String to = email;
                Mailer current = mailer;
                mailSender.execute(() -> {
                    try {
                        current.send(to, subject, body);
                    } catch (IOException | RuntimeException failure) {
                        java.util.logging.Logger.getLogger(EmberServer.class.getName())
                                .log(Level.WARNING, "An email could not be sent: " + failure.getMessage());
                    }
                });
            }
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_EMAIL,
                    frame.correlationId(), java.util.Map.of("sent", "true")));
        }

        /** Email + code + new password: sets it, signs out every other device, and signs in here. */
        private void passwordReset(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            MapFields fields = new MapFields(frame.payload());
            String email = EmailCodes.normalize(fields.required("email"));
            String owner = emailOwner(emailCodes.hash(email));
            char[] password = fields.required("pass").toCharArray();
            edu.ember.common.security.InputValidator.validatePassword(password);
            if (owner == null || !emailCodes.verify("reset", email, fields.required("code"), System.currentTimeMillis())) {
                throw new edu.ember.common.exceptions.ValidationException("Wrong or expired email code");
            }
            authService.resetPassword(owner, password);
            sessions.removeUser(owner);
            for (ConnectionHandler device : online.getOrDefault(owner, java.util.Set.of())) {
                if (device != this) {
                    device.closeQuietly(); // signed in with the old password: they must sign in again
                }
            }
            passwordAuthed = true;
            signIn(frame, owner, fields);
        }

        /** Links (or changes) the signed-in account's email after the code sent to it is entered. */
        private void emailLink(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String email = EmailCodes.normalize(fields.required("email"));
            String emailHash = emailCodes.hash(email);
            String owner = emailOwner(emailHash);
            if ((owner != null && !owner.equals(userId))
                    || !emailCodes.verify("link", email, fields.required("code"), System.currentTimeMillis())) {
                throw new edu.ember.common.exceptions.ValidationException("Wrong or expired email code");
            }
            linkEmail(userId, emailHash);
            auditLogger.log("email_linked", userId, "email_code");
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_EMAIL,
                    frame.correlationId(), java.util.Map.of("linked", "true")));
        }

        /** Registers (or retires, on logout) this device's public key for encrypted chats. */
        private void deviceKey(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String deviceId = fields.required("deviceId");
            if (!DeviceKeys.validDeviceId(deviceId)) {
                throw new edu.ember.common.exceptions.ValidationException("Invalid device id");
            }
            if ("backup".equals(fields.values.get("action"))) {
                saveKeyBackup(frame, fields);
                return;
            }
            boolean retire = "retire".equals(fields.values.get("action"));
            synchronized (DeviceKeys.class) {
                var keys = DeviceKeys.parse(storage.setting("devkeys:" + userId).orElse(""));
                java.util.List<DeviceKeys.Key> next;
                if (retire) {
                    next = DeviceKeys.retire(keys, deviceId);
                } else {
                    String publicKey = fields.required("pub");
                    if (!DeviceKeys.validPublicKey(publicKey)) {
                        throw new edu.ember.common.exceptions.ValidationException("Invalid public key");
                    }
                    next = DeviceKeys.add(keys, deviceId, publicKey, System.currentTimeMillis());
                    if (next == null) {
                        throw new edu.ember.common.exceptions.AuthorizationException(
                                "This device already has a different key");
                    }
                }
                if (!next.equals(keys)) {
                    storage.saveSetting("devkeys:" + userId, DeviceKeys.format(next));
                    auditLogger.log(retire ? "device_key_retired" : "device_key_added", userId, deviceId);
                    var changed = edu.ember.common.protocol.FrameFactory.event(
                            edu.ember.common.protocol.WireOpcodes.EVT_KEYS_CHANGED, 0,
                            java.util.Map.of("userId", userId));
                    sendToUser(userId, changed, this);
                    for (String contact : contactsOf(userId)) {
                        sendToUser(contact, changed, null);
                    }
                }
            }
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_KEYS,
                    frame.correlationId(), java.util.Map.of("n", "0")));
        }

        /**
         * The account key, sealed on the device with a key derived from the password (the server
         * cannot open it without the password). Every device that signs in with the password opens
         * it, so all of them read the same messages. Only a password sign-in may set it, and an
         * existing backup is replaced only on request (after a password reset).
         */
        private void saveKeyBackup(edu.ember.common.protocol.WireFrame frame, MapFields fields)
                throws IOException, edu.ember.common.exceptions.EmberException {
            String backup = fields.required("backup");
            if (!backup.matches("kb1\\.[A-Za-z0-9_-]{16,43}\\.[A-Za-z0-9_-]{87}\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{100,400}")) {
                throw new edu.ember.common.exceptions.ValidationException("Invalid key backup");
            }
            if (!passwordAuthed) {
                throw new edu.ember.common.exceptions.AuthorizationException("Sign in with your password to change the key backup");
            }
            synchronized (DeviceKeys.class) {
                if (storage.setting("keybackup:" + userId).isPresent() && !"1".equals(fields.values.get("replace"))) {
                    sendError(frame.correlationId(), "409", "A key backup already exists");
                    return;
                }
                storage.saveSetting("keybackup:" + userId, backup);
            }
            auditLogger.log("key_backup_saved", userId, "device");
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_KEYS,
                    frame.correlationId(), java.util.Map.of("n", "0")));
        }

        /** The device keys of everyone in a conversation the caller belongs to (their own included). */
        private void keysGet(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String conversationId = fields.required("convId");
            java.util.Set<String> people;
            if (conversationId.startsWith("dm:")) {
                people = java.util.Set.of(userId, requireDirectConversationAccess(conversationId, userId));
            } else if (conversationId.startsWith("grp:")) {
                groupService.requireMember(userId, conversationId.substring(4));
                people = participantsOf(conversationId);
            } else {
                throw new edu.ember.common.exceptions.AuthorizationException("Invalid conversation");
            }
            java.util.Map<String, String> reply = new java.util.HashMap<>();
            int index = 0;
            for (String person : people) {
                for (var key : DeviceKeys.parse(storage.setting("devkeys:" + person).orElse(""))) {
                    reply.put("user." + index, person);
                    reply.put("dev." + index, key.deviceId());
                    reply.put("pub." + index, key.publicKey());
                    reply.put("active." + index, Boolean.toString(key.active()));
                    index++;
                }
            }
            reply.put("n", Integer.toString(index));
            reply.put("convId", conversationId);
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_KEYS,
                    frame.correlationId(), reply));
        }

        private void subscribePush(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String endpoint = fields.required("endpoint");
            String p256dh = fields.required("p256dh");
            String auth = fields.required("auth");
            if (webPush == null || !webPush.accepts(endpoint, p256dh, auth)) {
                throw new edu.ember.common.exceptions.ValidationException(
                        "This browser's push service is not supported");
            }
            storage.savePushSubscription(new edu.ember.common.model.PushSubscription(
                    userId, endpoint, p256dh, auth),
                    Boolean.parseBoolean(fields.values.getOrDefault("active", "true")));
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_NOTIFY, frame.correlationId(),
                    java.util.Map.of("kind", "PUSH_OK")));
        }

        private void logout() throws IOException {
            if (token != null) {
                sessions.remove(token);
            }
            socket.close();
        }

        /**
         * Call signalling for one-to-one and group calls. Ringing follows the same rules as a
         * message (no calls across a block, group calls only for members).
         */
        private void call(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            String kind = fields.required("kind");
            if (kind.equals("ring")) {
                ringCall(frame.correlationId(), fields);
                return;
            }
            var call = callRegistry.find(fields.required("callId"));
            if (call == null || (!call.caller.equals(userId) && !call.invited.contains(userId))) {
                if (kind.equals("end") || kind.equals("decline")) {
                    return; // hanging up a call that is over looks like any other hang-up
                }
                throw new edu.ember.common.exceptions.ResourceNotFoundException("The call has ended");
            }
            switch (kind) {
                case "accept" -> joinCall(frame.correlationId(), call);
                case "decline" -> {
                    if (call.group) {
                        // Only this person stops ringing; the call goes on for the others.
                        sendToUser(userId, callEvent(call, "end", java.util.Map.of("reason", "declined")), this);
                    } else if (call.deviceOf(userId) == null) {
                        leaveCall(call, call.caller, "declined");
                    }
                }
                case "end" -> {
                    if (call.deviceOf(userId) == this) {
                        leaveCall(call, userId, "ended");
                    } else if (!call.group && call.deviceOf(userId) == null) {
                        leaveCall(call, call.caller, "declined");
                    }
                }
                case "handoff-prepare" -> prepareHandoff(frame.correlationId(), call);
                case "handoff-commit" -> commitHandoff(frame.correlationId(), call, fields.required("token"));
                case "sdp", "ice", "key", "relay" -> {
                    // Signals go to one named participant; they are end-to-end encrypted except "key".
                    var peer = call.deviceOf(fields.required("to"));
                    String data = fields.values.getOrDefault("data", "");
                    if (peer == null || call.deviceOf(userId) != this || peer == this || data.length() > 16_384
                            || !callRegistry.allowSignal(call, this)) {
                        throw new edu.ember.common.exceptions.ValidationException("Call signal rejected");
                    }
                    sendToDevice(peer, callEvent(call, kind, java.util.Map.of("data", data, "from", userId)));
                }
                default -> throw new edu.ember.common.exceptions.ValidationException("Unknown call action");
            }
        }

        /**
         * Hand-off phase 1 (calls v2): this device belongs to someone already in the answered call
         * on another device. Returns a single-use ticket; nothing changes for the call yet.
         */
        private void prepareHandoff(long correlationId, CallRegistry.Call<ConnectionHandler> call) throws IOException,
                edu.ember.common.exceptions.EmberException {
            if (!callsV2) {
                throw new edu.ember.common.exceptions.ValidationException("Hand-off is not enabled");
            }
            if (!handoffBuckets.computeIfAbsent(userId, ignored -> new TokenBucket(5, 1 / 10.0)).tryConsume()) {
                auditLogger.log("rate_limited", userId, "handoff");
                throw new edu.ember.common.exceptions.RateLimitedException("Too many hand-offs, wait a moment");
            }
            String token = callRegistry.prepareHandoff(call.id, userId, this, System.currentTimeMillis(), handoffTtlMs);
            if (token == null) {
                throw new edu.ember.common.exceptions.ResourceNotFoundException("The call is not connected on another device");
            }
            auditLogger.log("call_handoff_prepared", userId, "call");
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_CALL, correlationId,
                    java.util.Map.of("kind", "handoff-ready", "callId", call.id, "token", token,
                            "convId", call.conversationId, "video", Boolean.toString(call.video))));
        }

        /** Hand-off phase 2: swaps this device in; the old device stops, the others re-key with this one. */
        private void commitHandoff(long correlationId, CallRegistry.Call<ConnectionHandler> call, String token)
                throws IOException, edu.ember.common.exceptions.EmberException {
            var handoff = callRegistry.commitHandoff(token, call.id, this, System.currentTimeMillis());
            if (handoff == null) {
                throw new edu.ember.common.exceptions.ResourceNotFoundException("The hand-off expired or happened elsewhere");
            }
            auditLogger.log("call_handoff_committed", userId, "call");
            java.util.Map<String, String> roster = new java.util.HashMap<>();
            int index = 0;
            for (String person : call.participants().keySet()) {
                if (!person.equals(userId)) {
                    roster.put("user." + index++, person);
                }
            }
            roster.put("n", Integer.toString(index));
            roster.put("kind", "roster");
            roster.put("callId", call.id);
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_CALL,
                    correlationId, roster));
            sendToDevice(handoff.oldDevice(), callEvent(call, "end", java.util.Map.of("reason", "elsewhere")));
            var moved = callEvent(call, "moved", java.util.Map.of("userId", userId));
            for (var entry : call.participants().entrySet()) {
                if (!entry.getKey().equals(userId)) {
                    sendToDevice(entry.getValue(), moved);
                }
            }
        }

        private void joinCall(long correlationId, CallRegistry.Call<ConnectionHandler> call) throws IOException,
                edu.ember.common.exceptions.EmberException {
            if (call.group) {
                groupService.requireMember(userId, call.conversationId.substring(4)); // still a member now
            }
            if (callRegistry.join(call.id, userId, this) == null) {
                throw new edu.ember.common.exceptions.ResourceNotFoundException("The call has ended");
            }
            java.util.Map<String, String> roster = new java.util.HashMap<>();
            int index = 0;
            for (String person : call.participants().keySet()) {
                if (!person.equals(userId)) {
                    roster.put("user." + index++, person);
                }
            }
            roster.put("n", Integer.toString(index));
            roster.put("kind", "roster");
            roster.put("callId", call.id);
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_CALL,
                    correlationId, roster));
            var joined = callEvent(call, "joined", java.util.Map.of("userId", userId));
            for (var entry : call.participants().entrySet()) {
                if (!entry.getKey().equals(userId)) {
                    sendToDevice(entry.getValue(), joined);
                }
            }
            // Stop the ringing on this person's other devices.
            sendToUser(userId, callEvent(call, "end", java.util.Map.of("reason", "elsewhere")), this);
        }

        private void ringCall(long correlationId, MapFields fields) throws IOException,
                edu.ember.common.exceptions.EmberException {
            String conversationId = fields.required("convId");
            java.util.Set<String> invited;
            if (conversationId.startsWith("dm:")) {
                invited = java.util.Set.of(requireDirectConversationAccess(conversationId, userId));
            } else if (conversationId.startsWith("grp:")) {
                groupService.requireMember(userId, conversationId.substring(4));
                invited = new java.util.HashSet<>(participantsOf(conversationId));
                invited.remove(userId);
                if (invited.isEmpty()) {
                    throw new edu.ember.common.exceptions.ValidationException("Nobody else is in this group");
                }
            } else {
                throw new edu.ember.common.exceptions.ValidationException("Invalid conversation");
            }
            if (!callBuckets.computeIfAbsent(userId, ignored -> new TokenBucket(5, 1 / 20.0)).tryConsume()) {
                auditLogger.log("rate_limited", userId, "calls");
                throw new edu.ember.common.exceptions.RateLimitedException("Too many calls, wait a moment");
            }
            boolean video = "true".equals(fields.values.get("video"));
            String first = invited.iterator().next();
            boolean reachable = callDevices(first).size() > 0 && !"dnd".equals(statuses.get(first));
            var call = callRegistry.ring(userId, this, conversationId, invited, video, reachable);
            if (call == null) {
                throw new edu.ember.common.exceptions.ValidationException("You are already in a call");
            }
            // Offline, hidden, do not disturb or busy callees get a phantom call: the caller hears the
            // same ringing and the same "no answer", so a call never reveals hidden presence.
            send(edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_CALL,
                    correlationId, java.util.Map.of("kind", "ringing", "callId", call.id)));
            String title = displayName(userId);
            if (call.phantom) {
                pushIfAway(first, title, video ? "Missed video call" : "Missed call", conversationId, "call");
            } else {
                var ring = callEvent(call, "ring", java.util.Map.of("from", userId, "convId", conversationId,
                        "video", Boolean.toString(video), "group", Boolean.toString(call.group)));
                for (String person : invited) {
                    if (call.group && "dnd".equals(statuses.get(person))) {
                        continue;
                    }
                    for (ConnectionHandler device : callDevices(person)) {
                        sendToDevice(device, ring);
                    }
                    if (call.group) {
                        pushIfAway(person, title, video ? "📞 group video call" : "📞 group call",
                                conversationId, "call");
                    }
                }
            }
            java.util.concurrent.CompletableFuture.delayedExecutor(CallRegistry.RING_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
                        var expired = callRegistry.expire(call.id);
                        if (expired != null) {
                            sendToUser(expired.caller, callEvent(expired, "end",
                                    java.util.Map.of("reason", "noanswer")), null);
                            if (!expired.phantom) {
                                for (String person : expired.invited) {
                                    sendCallEnd(expired, person, "missed");
                                }
                            }
                        }
                    });
        }

        /** One encrypted media packet: forwarded unread to every other device in the call. */
        private void callMedia(edu.ember.common.protocol.WireFrame frame) {
            if (userId == null) {
                return;
            }
            var call = callRegistry.callOf(this);
            if (call == null) {
                return;
            }
            byte[] packet = frame.payload();
            var others = callRegistry.others(call, this);
            // Dropped silently: answering every bad packet would itself be a flood.
            // 256 KB fits a 720p key frame.
            if (others.isEmpty() || packet.length > 262_144 || !callRegistry.allowMedia(call, this, packet.length)) {
                return;
            }
            // Stamped with the sender's account, so nobody in the call can pass audio off as someone else's.
            byte[] sender = userId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] stamped = new byte[1 + sender.length + packet.length];
            stamped[0] = (byte) sender.length;
            System.arraycopy(sender, 0, stamped, 1, sender.length);
            System.arraycopy(packet, 0, stamped, 1 + sender.length, packet.length);
            var event = new edu.ember.common.protocol.WireFrame.Builder(
                    edu.ember.common.protocol.WireOpcodes.EVT_CALL_MEDIA).payload(stamped).build();
            for (ConnectionHandler other : others) {
                sendToDevice(other, event);
            }
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
                sendToUser(recipient, event, null);
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
            String groupId = fields.required("groupId");
            String target = fields.required("targetUser");
            groupService.changeMember(userId, groupId, target, fields.required("action"));
            var call = callRegistry.callOfUser(target);
            if (call != null && call.conversationId.equals("grp:" + groupId)
                    && !participantsOf(call.conversationId).contains(target)) {
                leaveCall(call, target, "ended"); // removed from the group: out of its call too
            }
            sendGroupUpdate(frame.correlationId(), groupId);
        }

        private void groupInfo(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            String groupId = new MapFields(frame.payload()).required("groupId");
            GroupChat group = groupService.requireMember(userId, groupId);
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_GROUP_UPDATE,
                    frame.correlationId(), groupFields(group)));
            for (var poll : storage.pollsForGroup(groupId)) {
                send(pollEvent(poll)); // so polls survive a page reload or a new device
            }
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
                boolean approve = Boolean.parseBoolean(fields.values.getOrDefault("approve", "false"));
                socialFeedService.approveFollow(userId, target, approve);
                notifyUser(target, approve ? "FOLLOW_APPROVED" : "FOLLOW_DECLINED", userId);
                if (approve) {
                    introduce(userId, target);
                }
            } else {
                socialFeedService.follow(userId, target, following);
                if (following && storage.followStatus(userId, target).orElse(null)
                        == edu.ember.common.model.FollowStatus.PENDING) {
                    notifyUser(target, "FOLLOW_REQUEST", userId);
                    pushIfAway(target, displayName(userId), "wants to follow you", "", "follow");
                } else if (following) {
                    introduce(userId, target);
                }
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
            boolean video = "video".equals(fields.values.get("kind"));
            byte[] bytes;
            byte[] poster = null;
            try {
                bytes = java.util.Base64.getDecoder().decode(encoded);
                if (video) {
                    poster = java.util.Base64.getDecoder().decode(fields.required("poster"));
                }
            } catch (IllegalArgumentException exception) {
                throw new edu.ember.common.exceptions.MediaValidationException("Media data is invalid");
            }
            edu.ember.common.model.MediaAsset asset;
            try {
                asset = video ? socialFeedService.uploadVideo(userId, bytes, fields.required("fileName"), poster)
                        : socialFeedService.upload(userId, bytes, fields.required("fileName"));
            } catch (edu.ember.common.exceptions.MediaValidationException exception) {
                auditLogger.log("upload_rejected", userId, video ? "video" : "image");
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
            java.util.Map<String, String> response = new java.util.HashMap<>(java.util.Map.of("post.n", "1",
                    "post.0", post.postId(), "owner.0", post.ownerUserId(), "media.0", post.mediaId(),
                    "caption.0", post.caption(), "created.0", Long.toString(post.createdAtMs()),
                    "likes.0", "0", "comments.0", "0", "liked.0", "false"));
            putMediaLinks(response, ".0", post.mediaId());
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE, frame.correlationId(), response));
        }

        private void createStory(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields fields = new MapFields(frame.payload());
            var story = socialFeedService.createStory(userId, fields.required("mediaId"),
                    fields.values.getOrDefault("caption", ""));
            java.util.Map<String, String> response = new java.util.HashMap<>(java.util.Map.of("story.n", "1",
                    "story.0", story.storyId(), "owner.0", story.ownerUserId(),
                    "media.0", story.mediaId(), "caption.0", story.caption(),
                    "created.0", Long.toString(story.createdAtMs()),
                    "expires.0", Long.toString(story.expiresAtMs())));
            putMediaLinks(response, ".0", story.mediaId());
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_STORY_UPDATE, frame.correlationId(), response));
        }

        private void viewStories(edu.ember.common.protocol.WireFrame frame) throws IOException,
                edu.ember.common.exceptions.EmberException {
            requireAuthenticated(frame.correlationId());
            MapFields request = new MapFields(frame.payload());
            if ("delete".equals(request.values.get("action"))) {
                socialFeedService.deleteStory(userId, request.required("storyId"));
            }
            java.util.List<edu.ember.common.model.StoryItem> stories =
                    socialFeedService.stories(userId);
            java.util.Map<String, String> response = new java.util.HashMap<>();
            response.put("story.n", Integer.toString(stories.size()));
            for (int index = 0; index < stories.size(); index++) {
                var story = stories.get(index);
                response.put("story." + index, story.storyId());
                response.put("owner." + index, story.ownerUserId());
                response.put("media." + index, story.mediaId());
                putMediaLinks(response, "." + index, story.mediaId());
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
            var event = pollEvent(poll);
            for (String member : groupService.memberIds(poll.groupId())) {
                sendToUser(member, event, null);
            }
        }

        private edu.ember.common.protocol.WireFrame pollEvent(edu.ember.common.model.PollData poll) {
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
            return edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_POLL_UPDATE, 0, fields);
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
            if (fields.values.getOrDefault("action", "").equals("delete")) {
                socialFeedService.deletePost(userId, fields.required("postId"));
                send(edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE, frame.correlationId(),
                        java.util.Map.of("post.n", "0", "deleted", fields.required("postId"))));
                return;
            }
            var post = socialFeedService.interact(userId, fields.required("postId"),
                    fields.required("action"), fields.values.getOrDefault("text", ""));
            java.util.Map<String, String> response = new java.util.HashMap<>(java.util.Map.of("post.n", "1",
                    "post.0", post.postId(), "owner.0", post.ownerUserId(), "media.0", post.mediaId(),
                    "caption.0", post.caption(), "created.0", Long.toString(post.createdAtMs()),
                    "likes.0", Integer.toString(post.likeCount()), "comments.0",
                    Integer.toString(post.commentCount()), "liked.0", Boolean.toString(post.likedByViewer())));
            putMediaLinks(response, ".0", post.mediaId());
            send(edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_FEED_PAGE, frame.correlationId(), response));
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
                putMediaLinks(response, "." + index, post.mediaId());
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
            if (target.equals(userId)) { // only the owner sees who is waiting
                java.util.List<String> pending = storage.pendingFollowersOf(userId);
                response.put("pending.n", Integer.toString(pending.size()));
                for (int index = 0; index < pending.size(); index++) {
                    response.put("pending." + index, pending.get(index));
                }
            }
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
                sendToUser(memberId, event, null);
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
                // An encrypted parent has no readable preview; devices show it from their own copy.
                replyPreview = isEncrypted(parent.body()) ? ""
                        : parent.body().substring(0, Math.min(100, parent.body().length()));
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
                // A resent encrypted message has fresh ciphertext for the same text, so only its chat must match.
                boolean sameBody = saved.body().equals(body) || (isEncrypted(saved.body()) && isEncrypted(body));
                if (!saved.conversationId().equals(conversationId) || !sameBody
                        || saved.ttlSec() != ttlSec || !saved.replyToId().equals(replyToId)) {
                    throw new edu.ember.common.exceptions.AuthorizationException("Message ID is already used");
                }
                sendMessageAck(frame.correlationId(), saved);
                return;
            }
            var message = new edu.ember.common.model.Message(clientMsgId, userId, conversationId,
                    body, System.currentTimeMillis(), ttlSec, replyToId, replyPreview);
            storage.appendMessage(message);
            if (conversationId.startsWith("dm:") && message.conversationSequence() == 1) {
                introduce(userId, recipients.get(0));
            }
            receiptTrackers.put(receiptKey(message), new GroupReceiptTracker(recipients.size()));
            sendMessageAck(frame.correlationId(), message);
            var deliver = edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_DELIVER, 0, messageFields(message));
            for (String recipient : recipients) {
                sendToUser(recipient, deliver, null);
            }
            sendToUser(userId, deliver, this); // the sender's other signed-in devices
            String title = displayName(userId);
            if (conversationId.startsWith("grp:")) {
                title += " · " + storage.findGroup(conversationId.substring(4))
                        .map(edu.ember.common.model.GroupChat::name).orElse("group");
            }
            String preview = ttlSec > 0 ? "⏱ disappearing message" : isEncrypted(body) ? "🔒 New message"
                    : body.length() > 140 ? body.substring(0, 140) + "…" : body;
            for (String recipient : recipients) {
                pushIfAway(recipient, title, preview, conversationId, "message");
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
            sendToUser(userId, edu.ember.common.protocol.FrameFactory.event(
                    edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE, frame.correlationId(),
                    java.util.Map.of("state", "STARRED", "clientMsgId", id,
                            "userId", userId, "starred", Boolean.toString(starred))), null);
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
                sendToUser(recipient, event, null);
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
            {
                java.util.Map<String, String> receipt = new java.util.HashMap<>();
                receipt.put("clientMsgId", messageId);
                receipt.put("status", requestedStatus);
                receipt.put("delivered", Integer.toString(summary.delivered()));
                receipt.put("read", Integer.toString(summary.read()));
                receipt.put("total", Integer.toString(summary.total()));
                receipt.put("readBy", String.join(", ", summary.readBy()));
                sendToUser(message.get().senderUserId(), edu.ember.common.protocol.FrameFactory.event(
                        edu.ember.common.protocol.WireOpcodes.EVT_RECEIPT_UPDATE, 0, receipt), null);
            }
        }

        private String receiptKey(edu.ember.common.model.Message message) {
            return message.senderUserId() + "\n" + message.clientMsgId();
        }

        private void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
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

    private final MediaLinks mediaLinks = new MediaLinks();

    /** Serves /media/ID to the browser: a valid signed link, or a session token of someone allowed to see it. */
    private final WebGateway.MediaSource mediaSource = new WebGateway.MediaSource() {
        @Override
        public java.nio.file.Path open(String sessionToken, String mediaId, boolean thumbnail) throws IOException {
            String viewer = sessions.findUserId(sessionToken);
            if (viewer == null) {
                return null;
            }
            try {
                return socialFeedService.mediaPath(viewer, mediaId, thumbnail);
            } catch (edu.ember.common.exceptions.EmberException | java.nio.file.NoSuchFileException exception) {
                return null;
            }
        }

        @Override
        public java.nio.file.Path openLink(String mediaId, String variant, String expiry, String signature)
                throws IOException {
            if (!mediaLinks.valid(mediaId, variant, expiry, signature, System.currentTimeMillis())) {
                return null;
            }
            try {
                return socialFeedService.mediaPathForLink(mediaId, "t".equals(variant));
            } catch (edu.ember.common.exceptions.EmberException | java.nio.file.NoSuchFileException exception) {
                return null;
            }
        }
    };

    /** Adds kind, src and thumb for one listed post or story, so the website loads it without another request. */
    private void putMediaLinks(java.util.Map<String, String> response, String suffix, String mediaId) throws IOException {
        long now = System.currentTimeMillis();
        response.put("kind" + suffix, socialFeedService.isVideo(mediaId) ? "video" : "image");
        response.put("src" + suffix, mediaLinks.link(mediaId, true, now));
        response.put("thumb" + suffix, mediaLinks.link(mediaId, false, now));
    }

    /** Sends to every device the user is signed in on; one dead device never fails the caller. */
    private void sendToUser(String targetUserId, edu.ember.common.protocol.WireFrame event,
            ConnectionHandler skip) {
        for (ConnectionHandler handler : online.getOrDefault(targetUserId, java.util.Set.of())) {
            if (handler == skip) {
                continue;
            }
            try {
                handler.send(event);
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.FINE, "Event delivery failed", exception);
            }
        }
    }

    /** End-to-end encrypted message bodies start with this tag; the server cannot read them. */
    static boolean isEncrypted(String body) {
        return body.startsWith("e2e1.");
    }

    private static edu.ember.common.protocol.WireFrame callEvent(CallRegistry.Call<?> call, String kind,
            java.util.Map<String, String> extra) {
        java.util.Map<String, String> fields = new java.util.HashMap<>(extra);
        fields.put("kind", kind);
        fields.put("callId", call.id);
        return edu.ember.common.protocol.FrameFactory.event(edu.ember.common.protocol.WireOpcodes.EVT_CALL, 0, fields);
    }

    private void sendCallEnd(CallRegistry.Call<?> call, String target, String reason) {
        sendToUser(target, callEvent(call, "end", java.util.Map.of("reason", reason)), null);
        if (reason.equals("missed") && !call.group) {
            try {
                pushIfAway(target, displayName(call.caller), call.video ? "Missed video call" : "Missed call",
                        call.conversationId, "call");
            } catch (IOException exception) {
                java.util.logging.Logger.getLogger(EmberServer.class.getName())
                        .log(Level.FINE, "Missed-call push failed", exception);
            }
        }
    }

    /** The signed-in devices of a user that can take calls (the website, not the Swing app). */
    private java.util.List<ConnectionHandler> callDevices(String userId) {
        return online.getOrDefault(userId, java.util.Set.of()).stream()
                .filter(device -> device.supportsCalls).toList();
    }

    /**
     * Someone leaves a call. The others hear "left", or "end" when that finished the call;
     * anyone still ringing stops; the person's own other devices stop too.
     */
    private void leaveCall(CallRegistry.Call<ConnectionHandler> call, String who, String reason) {
        var before = call.participants();
        boolean over = callRegistry.leave(call, who);
        for (var entry : before.entrySet()) {
            if (!entry.getKey().equals(who)) {
                sendToDevice(entry.getValue(), over ? callEvent(call, "end", java.util.Map.of("reason", reason))
                        : callEvent(call, "left", java.util.Map.of("userId", who)));
            }
        }
        if (over && !call.phantom) {
            for (String person : call.invited) {
                if (!before.containsKey(person)) {
                    sendToUser(person, callEvent(call, "end", java.util.Map.of("reason",
                            call.group ? "ended" : reason.equals("declined") ? "declined" : "missed")), null);
                }
            }
        }
        sendToUser(who, callEvent(call, "end", java.util.Map.of("reason", reason)), before.get(who));
    }

    private static void sendToDevice(ConnectionHandler device, edu.ember.common.protocol.WireFrame event) {
        if (device == null) {
            return;
        }
        try {
            device.send(event);
        } catch (IOException exception) {
            java.util.logging.Logger.getLogger(EmberServer.class.getName())
                    .log(Level.FINE, "Call event delivery failed", exception);
        }
    }

    /** Removes one device; returns true when it was the user's last live device. */
    private boolean removeOnline(String targetUserId, ConnectionHandler handler) {
        boolean[] wentOffline = new boolean[1];
        online.computeIfPresent(targetUserId, (ignored, devices) -> {
            wentOffline[0] = devices.remove(handler) && devices.isEmpty();
            return devices.isEmpty() ? null : devices;
        });
        return wentOffline[0];
    }

    private String displayName(String userId) throws IOException {
        return storage.findProfile(userId).map(edu.ember.common.model.UserProfile::displayName)
                .filter(name -> !name.isBlank()).orElse(userId);
    }

    private void notifyUser(String target, String kind, String from) {
        sendToUser(target, edu.ember.common.protocol.FrameFactory.event(
                edu.ember.common.protocol.WireOpcodes.EVT_NOTIFY, 0,
                java.util.Map.of("kind", kind, "from", from)), null);
    }

    /**
     * Web Push only when the user has no device connected (an open tab shows its own
     * notification) and is not on Do Not Disturb.
     */
    private void pushIfAway(String target, String title, String body, String conversationId,
            String kind) throws IOException {
        java.util.Set<ConnectionHandler> devices = online.get(target);
        if (webPush == null || (devices != null && !devices.isEmpty())
                || "dnd".equals(statuses.get(target))) {
            return;
        }
        String payload = "{\"title\":" + edu.ember.common.util.MiniJson.quote(title)
                + ",\"body\":" + edu.ember.common.util.MiniJson.quote(body)
                + ",\"conv\":" + edu.ember.common.util.MiniJson.quote(conversationId)
                + ",\"kind\":" + edu.ember.common.util.MiniJson.quote(kind) + "}";
        for (var subscription : storage.pushSubscriptions(target)) {
            webPush.send(subscription, payload);
        }
    }

    /** Others never see "invisible" (it looks offline); the user's own devices see the truth. */
    private edu.ember.common.protocol.WireFrame presenceEvent(String userId, boolean isOnline,
            boolean forSelf) {
        String status = statuses.getOrDefault(userId, "online");
        boolean visible = isOnline && (forSelf || !status.equals("invisible"));
        return edu.ember.common.protocol.FrameFactory.event(
                edu.ember.common.protocol.WireOpcodes.EVT_PRESENCE_UPDATE, 0,
                java.util.Map.of("userId", userId, "online", Boolean.toString(visible),
                        "status", visible ? status : "offline"));
    }

    /** Presence goes only to contacts (chat partners, group mates, follows), never to strangers. */
    private void broadcastPresence(String userId, boolean isOnline) {
        sendToUser(userId, presenceEvent(userId, isOnline, true), null);
        var event = presenceEvent(userId, isOnline, false);
        for (String contact : contactsOf(userId)) {
            if (online.containsKey(contact)) {
                sendToUser(contact, event, null);
            }
        }
    }

    /** Contacts minus anyone blocked in either direction. */
    private java.util.Set<String> contactsOf(String userId) {
        try {
            java.util.Set<String> contacts = new java.util.HashSet<>();
            for (String contact : storage.contactsOf(userId)) {
                if (!storage.isBlockedEitherDirection(userId, contact)) {
                    contacts.add(contact);
                }
            }
            return contacts;
        } catch (IOException exception) {
            return java.util.Set.of();
        }
    }

    /** Two people who just started a direct chat learn each other's presence. */
    private void introduce(String first, String second) {
        if (online.containsKey(second)) {
            sendToUser(first, presenceEvent(second, true, false), null);
        }
        if (online.containsKey(first)) {
            sendToUser(second, presenceEvent(first, true, false), null);
        }
    }

    /** Tells only the people in that chat; others never learn the conversation exists. */
    private void broadcastExpiredMessage(edu.ember.common.model.Message message) {
        var event = edu.ember.common.protocol.FrameFactory.event(
                edu.ember.common.protocol.WireOpcodes.EVT_MSG_UPDATE, 0,
                java.util.Map.of("clientMsgId", message.clientMsgId(),
                        "convId", message.conversationId(), "state", "EXPIRED"));
        for (String participant : participantsOf(message.conversationId())) {
            sendToUser(participant, event, null);
        }
    }

    /** The users in a conversation: both people of a direct chat, or a group's members. */
    private java.util.Set<String> participantsOf(String conversationId) {
        String[] parts = conversationId.split(":");
        if (parts.length == 3 && parts[0].equals("dm")) {
            return java.util.Set.of(parts[1], parts[2]);
        }
        if (parts.length == 2 && parts[0].equals("grp")) {
            try {
                return storage.findGroup(parts[1]).map(group -> group.members().keySet())
                        .orElse(java.util.Set.of());
            } catch (IOException exception) {
                return java.util.Set.of();
            }
        }
        return java.util.Set.of();
    }

    private void sendPresenceSnapshot(ConnectionHandler handler) throws IOException {
        handler.send(presenceEvent(handler.userId, true, true));
        for (String contact : contactsOf(handler.userId)) {
            if (online.containsKey(contact) && !"invisible".equals(statuses.get(contact))) {
                handler.send(presenceEvent(contact, true, false));
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
