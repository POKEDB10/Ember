package edu.ember.test;

import edu.ember.common.exceptions.EmberException;
import edu.ember.common.protocol.FrameCodec;
import edu.ember.common.protocol.FrameFactory;
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.ProtocolFrameException;
import edu.ember.common.protocol.WireFrame;
import edu.ember.common.protocol.WireOpcodes;
import edu.ember.common.security.InputValidator;
import edu.ember.common.security.Pbkdf2PasswordHasher;
import edu.ember.common.security.PasswordHasher;
import edu.ember.common.security.TlsSocketFactory;
import edu.ember.common.security.ImageValidator;
import edu.ember.common.exceptions.MediaValidationException;
import edu.ember.common.model.UserProfile;
import edu.ember.common.model.FollowStatus;
import edu.ember.common.model.ModerationReport;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.PostComment;
import edu.ember.client.ClientController;
import edu.ember.client.ClientStateModel;
import edu.ember.client.SearchEngine;
import edu.ember.client.LocalOutbox;
import edu.ember.client.ReconnectManager;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.Message;
import edu.ember.common.model.GroupChat;
import edu.ember.common.model.UserRole;
import edu.ember.common.model.UserRecord;
import edu.ember.client.ui.ChatBubblePanel;
import edu.ember.client.ui.FontResolver;
import edu.ember.client.ui.ThemeManager;
import edu.ember.server.EmberServer;
import edu.ember.server.AuthRateLimiter;
import edu.ember.server.AuthService;
import edu.ember.server.SecurityAuditLogger;
import edu.ember.server.TokenBucket;
import edu.ember.server.UserLockManager;
import edu.ember.server.storage.InMemoryStorageEngine;
import edu.ember.server.storage.WalStorageEngine;
import edu.ember.tools.DemoSeeder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Zero-dependency test runner; phase tests are registered as features are implemented. @concept command-line args, methods */
public final class EmberTestSuite {
    private int passed;
    private int failed;

    private EmberTestSuite() {
    }

    public static void main(String[] args) {
        EmberTestSuite suite = new EmberTestSuite();
        suite.runAll();
    }

    private void runAll() {
        runTest("KV codec round trip", this::testKvRoundTrip);
        runTest("in-chat search matches every token including Arabic", this::testSearchEngine);
        runTest("bad frame magic rejected", this::testBadMagic);
        runTest("oversized frames rejected before payload read", this::testFrameLimits);
        runTest("password hashing and dummy verification", this::testPasswordHasher);
        runTest("input validation", this::testInputValidation);
        runTest("TLS 1.3 loopback frame round trip", this::testTlsRoundTrip);
        runTest("headless registration, message, and delivery", this::testWalkingSkeleton);
        runTest("two devices on one account, web socket client, and long messages",
                this::testMultiDeviceAndWeb);
        runTest("theme palettes, wrapped message bodies, and EDT notifications",
                this::testUiBasics);
        runTest("WAL restart recovers Arabic and emoji records and trims torn tail",
                this::testWalTornTailRecovery);
        runTest("WAL CRC failure truncates invalid tail", this::testWalCrcRecovery);
        runTest("compressed WAL snapshot compacts and restores later records", this::testWalSnapshotCompaction);
        runTest("WAL restart restores group members and roles", this::testWalGroupRecovery);
        runTest("server restart restores accounts and messages from WAL",
                this::testWalServerRestart);
        runTest("five bad logins lock account and audit stays one JSON line",
                this::testLockoutAndAudit);
        runTest("concurrent password hashing is capped at four", this::testHashConcurrencyLimit);
        runTest("message flood receives 429", this::testMessageFlood);
        runTest("loopback IP limiter remains disabled", this::testLoopbackRateLimit);
        runTest("local outbox persists framed commands and reconnect delay is bounded",
                this::testLocalOutbox);
        runTest("resume syncs offline messages once and history, typing, presence, and ping work",
                this::testReconnectHistoryAndPresence);
        runTest("group fan-out aggregates receipts and denies unauthorized object access",
                this::testGroupMessagingAndAuthorization);
        runTest("image signatures and oversized dimensions are rejected before decode",
                this::testImageAttacks);
        runTest("Campus Pulse WAL persists profiles, follows, posts, likes, and comments",
                this::testSocialWalRecovery);
        runTest("Campus Pulse TLS upload, feed, and object authorization",
                this::testCampusPulseNetwork);
        runTest("private profile requests, block enforcement, reports, and WAL recovery",
                this::testPrivateBlockAndReport);
        runTest("story upload, viewer access, WAL recovery, and TTL expiry",
                this::testStories);
        runTest("administrator report queue and role authorization over TLS",
                this::testAdminConsoleProtocol);
        runTest("15-second messages expire on both live clients",
                this::testExpiringMessages);
        runTest("replies, six quick reactions, and reaction authorization",
                this::testRepliesAndReactions);
        runTest("demo seed persists sample accounts and content idempotently",
                this::testDemoSeeder);
        System.out.println("Ember tests: " + passed + " passed, " + failed + " failed.");
        if (failed != 0) {
            throw new AssertionError("Test suite failed");
        }
    }

    private void testKvRoundTrip() {
        String arabicEmoji = "مرحبا 🌋";
        String escaped = "first=line\nsecond\\part\rend";
        Map<String, String> original = new LinkedHashMap<>();
        original.put("displayName", arabicEmoji);
        original.put("body", escaped);
        Map<String, String> decoded = KvPayloadCodec.decode(KvPayloadCodec.encode(original));
        assertEquals(original, decoded, "KV payload changed during UTF-8 round trip");
    }

    private void testSearchEngine() {
        Message first = new Message("search-1", "alice", "dm:alice:bob",
                "Campus study مرحبا", 1);
        Message second = new Message("search-2", "bob", "dm:alice:bob",
                "Campus event", 2);
        List<Message> matches = new SearchEngine().search(List.of(first, second), "CAMPUS مرحبا");
        assertEquals(List.of(first), matches, "Search should match every term case-insensitively");
    }

    private void testBadMagic() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x01020304);
        output.writeByte(FrameCodec.VERSION);
        output.writeByte(1);
        output.writeLong(0);
        output.writeInt(0);
        assertThrows(ProtocolFrameException.class,
                () -> FrameCodec.readFrame(new ByteArrayInputStream(bytes.toByteArray()), 4096));
    }

    private void testFrameLimits() throws IOException {
        byte[] oversized = headerOnly(4097);
        assertThrows(ProtocolFrameException.class,
                () -> FrameCodec.readFrame(new ByteArrayInputStream(oversized), 4096));
        byte[] postAuthLimit = headerOnly(65 * 1024);
        assertThrows(ProtocolFrameException.class,
                () -> FrameCodec.readFrame(new ByteArrayInputStream(postAuthLimit), 64 * 1024));
    }

    private void testPasswordHasher() {
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
        char[] password = "Ember-pass-42".toCharArray();
        String encoded = hasher.hash(password);
        assertTrue(hasher.verify(password, encoded), "Correct password was rejected");
        assertFalse(hasher.verify("different-pass".toCharArray(), encoded),
                "Wrong password was accepted");
        String dummyHash = hasher.dummyHash();
        assertTrue(dummyHash.startsWith("$pbkdf2-sha256$600000$"),
                "Dummy hash does not use the required algorithm and work factor");
        assertFalse(hasher.verify(password, dummyHash), "Dummy password unexpectedly matched");
    }

    private void testInputValidation() throws Exception {
        assertEquals("alice_7", InputValidator.validateUsername("alice_7"),
                "Valid username changed");
        assertThrows(EmberException.class, () -> InputValidator.validateUsername("bad name"));
        assertEquals("helloworld", InputValidator.validateMessageBody("hello\u0000world"),
                "Control character was not stripped");
        assertEquals("line one\nline two", InputValidator.validateMessageBody("line one\nline two"),
                "Newline was not kept");
    }

    private void testTlsRoundTrip() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        try (SSLServerSocket server = TlsSocketFactory.createServerSocket(
                config.resolve("ember-server.p12"), password, 0)) {
            server.setSoTimeout(30_000);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            AtomicReference<String> negotiatedProtocol = new AtomicReference<>();
            Thread serverThread = tlsEchoThread(server, serverFailure, negotiatedProtocol);
            serverThread.start();
            verifyDefaultTruststoreRejected(server.getLocalPort());
            verifyLargeFrameEcho(config, password, server.getLocalPort());
            serverThread.join(30_000);
            assertFalse(serverThread.isAlive(), "TLS server test thread did not stop");
            if (serverFailure.get() != null) {
                throw new AssertionError("TLS server test failed", serverFailure.get());
            }
            assertEquals("TLSv1.3", negotiatedProtocol.get(), "Unexpected negotiated TLS version");
            System.out.println("TLS loopback negotiated: " + negotiatedProtocol.get());
        }
    }

    private void testWalTornTailRecovery() throws Exception {
        Path root = Path.of("build", "test-data", "wal-torn");
        deleteTree(root);
        Path walFile = root.resolve("wal").resolve("events.wal");
        long goodLength;
        try (WalStorageEngine storage = new WalStorageEngine(root, true)) {
            storage.init();
            storage.saveUser(new UserRecord("alice", "alice", "علي 🌋", "hash-value"));
            Message message = new Message("msg-1", "alice", "dm:alice:bob", "مرحبا 🌋", 1234);
            storage.appendMessage(message);
            storage.updateMessageStatus("msg-1", MessageStatus.DELIVERED);
            goodLength = Files.size(walFile);
        }
        Files.write(walFile, new byte[] {0, 0, 0, 20, 0, 0, 0},
                java.nio.file.StandardOpenOption.APPEND);
        try (WalStorageEngine recovered = new WalStorageEngine(root, true)) {
            recovered.init();
            assertEquals("علي 🌋", recovered.findUser("alice").orElseThrow().displayName(),
                    "Arabic/emoji user did not survive WAL replay");
            Message restored = recovered.getHistory("dm:alice:bob", Long.MAX_VALUE, 10)
                    .get(0);
            assertEquals("مرحبا 🌋", restored.body(), "Arabic/emoji message did not survive WAL replay");
            assertEquals(MessageStatus.DELIVERED, restored.status(), "Message status did not replay");
        }
        assertEquals(goodLength, Files.size(walFile), "Torn tail was not truncated");
        deleteTree(root);
    }

    private void testWalCrcRecovery() throws Exception {
        Path root = Path.of("build", "test-data", "wal-crc");
        deleteTree(root);
        Path walFile = root.resolve("wal").resolve("events.wal");
        try (WalStorageEngine storage = new WalStorageEngine(root, true)) {
            storage.init();
            storage.saveUser(new UserRecord("alice", "alice", "Alice", "hash-value"));
        }
        try (FileChannel file = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            ByteBuffer corruptByte = ByteBuffer.wrap(new byte[] {0x7F});
            file.write(corruptByte, file.size() - 1);
        }
        try (WalStorageEngine recovered = new WalStorageEngine(root, true)) {
            recovered.init();
            assertTrue(recovered.findUser("alice").isEmpty(),
                    "Record with invalid CRC was replayed");
        }
        assertEquals(0L, Files.size(walFile), "Invalid CRC tail was not truncated");
        deleteTree(root);
    }

    private void testWalSnapshotCompaction() throws Exception {
        Path directory = Files.createTempDirectory("ember-wal-snapshot");
        Path walPath = directory.resolve("wal").resolve("events.wal");
        WalStorageEngine first = new WalStorageEngine(directory, true);
        first.init();
        first.saveUser(new UserRecord("snapshot-alice", "snapshot-alice", "Alice", "hash"));
        first.saveProfile(new UserProfile("snapshot-alice", "Alice", "Before snapshot", ""));
        Message before = new Message("snapshot-before", "snapshot-alice", "dm:snapshot-alice:bob",
                "Before snapshot 🌋", System.currentTimeMillis());
        first.appendMessage(before);
        byte[] coveredWal = Files.readAllBytes(walPath);
        first.compact();
        assertTrue(Files.exists(directory.resolve("snapshots").resolve("state.snap")),
                "Snapshot file was not written");
        assertEquals(0L, Files.size(walPath), "WAL was not compacted after snapshot commit");
        first.close();
        Files.write(walPath, coveredWal);
        WalStorageEngine recovered = new WalStorageEngine(directory, true);
        recovered.init();
        assertEquals("Alice", recovered.findUser("snapshot-alice").orElseThrow().displayName(),
                "Crash between snapshot replacement and WAL truncation replayed records twice");
        Message after = new Message("snapshot-after", "snapshot-alice", "dm:snapshot-alice:bob",
                "After snapshot 🌟", System.currentTimeMillis());
        recovered.appendMessage(after);
        recovered.saveProfile(new UserProfile("snapshot-alice", "Alice", "After snapshot", ""));
        assertEquals("After snapshot", recovered.findProfile("snapshot-alice").orElseThrow().bio(),
                "Post-snapshot records did not replay");
        assertEquals("Before snapshot 🌋", recovered.findByClientMsgId("snapshot-before")
                .orElseThrow().body(), "Snapshot data did not replay");
        assertEquals("After snapshot 🌟", recovered.findByClientMsgId("snapshot-after")
                .orElseThrow().body(), "WAL tail after snapshot did not replay");
        assertTrue(coveredWal.length > 0 && Files.size(walPath) > 0,
                "Snapshot test did not preserve its compacted prefix and new WAL tail");
        recovered.compact();
        recovered.close();
        WalStorageEngine finalRecovery = new WalStorageEngine(directory, true);
        finalRecovery.init();
        assertEquals("After snapshot", finalRecovery.findProfile("snapshot-alice").orElseThrow().bio(),
                "Repeated compaction lost the combined snapshot state");
        finalRecovery.close();
    }

    private void testLockoutAndAudit() throws Exception {
        Path auditPath = Path.of("build", "test-data", "security-audit.json");
        Files.createDirectories(auditPath.getParent());
        Files.deleteIfExists(auditPath);
        SecurityAuditLogger audit = new SecurityAuditLogger(auditPath);
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        storage.init();
        UserLockManager locks = new UserLockManager();
        AuthService auth = new AuthService(storage, new Pbkdf2PasswordHasher(), locks, audit);
        auth.register("alice", "correct-pass-42".toCharArray(), "Alice");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(EmberException.class,
                    () -> auth.login("alice", "wrong-pass-42".toCharArray()));
        }
        assertTrue(locks.isLocked("alice"), "Five failed logins did not lock the user");
        assertThrows(edu.ember.common.exceptions.AuthenticationLockedException.class,
                () -> auth.login("alice", "correct-pass-42".toCharArray()));
        audit.log("input_validation_fail", "alice\r\n{\"event\":\"admin_grant\"}", "test");
        List<String> lines = Files.readAllLines(auditPath, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(8, lines.size(), "Audit events were split into extra lines");
        assertTrue(lines.get(7).contains("alice\\u000d\\u000a{\\\"event\\\":\\\"admin_grant\\\"}"),
                "Audit control characters were not escaped");
        for (String line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"), "Audit line is not a JSON object");
        }
        Files.deleteIfExists(auditPath);
    }

    private void testHashConcurrencyLimit() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstFourEntered = new CountDownLatch(4);
        CountDownLatch releaseHashes = new CountDownLatch(1);
        PasswordHasher measuredHasher = new PasswordHasher() {
            @Override
            public String hash(char[] password) {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                firstFourEntered.countDown();
                try {
                    if (!releaseHashes.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Hash concurrency test timed out");
                    }
                    return "test-hash";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Hash concurrency test interrupted", exception);
                } finally {
                    active.decrementAndGet();
                }
            }

            @Override
            public boolean verify(char[] password, String encodedHash) {
                return false;
            }

            @Override
            public String dummyHash() {
                return "test-dummy";
            }
        };
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        storage.init();
        AuthService auth = new AuthService(storage, measuredHasher);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        List<Future<?>> attempts = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                int userNumber = index;
                attempts.add(workers.submit(() -> {
                    try {
                        auth.register("user" + userNumber, "valid-pass-42".toCharArray(), "User");
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }
            assertTrue(firstFourEntered.await(10, TimeUnit.SECONDS),
                    "Four hash operations did not start concurrently");
            assertEquals(4, active.get(), "Hash semaphore admitted more than four operations");
            releaseHashes.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(10, TimeUnit.SECONDS);
            }
            assertEquals(4, maximum.get(), "Hash operation cap was not exactly four");
        } finally {
            releaseHashes.countDown();
            workers.shutdownNow();
        }
    }

    private void testWalServerRestart() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path root = Path.of("build", "test-data", "server-restart");
        deleteTree(root);
        Path auditPath = Path.of("build", "test-data", "server-restart-audit.json");
        Files.deleteIfExists(auditPath);
        EmberServer firstServer = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                new WalStorageEngine(root, true), new SecurityAuditLogger(auditPath));
        ClientController alice = null;
        ClientController bob = null;
        EmberServer restartedServer = null;
        ClientController restartedAlice = null;
        ClientController restartedBob = null;
        try {
            firstServer.start();
            alice = new ClientController("127.0.0.1", firstServer.port(),
                    config.resolve("ember-client-trust.p12"), password);
            bob = new ClientController("127.0.0.1", firstServer.port(),
                    config.resolve("ember-client-trust.p12"), password);
            CountDownLatch firstAuth = new CountDownLatch(2);
            CountDownLatch sent = new CountDownLatch(1);
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) {
                    firstAuth.countDown();
                }
                if (event.equals("MESSAGE_STATUS") && message != null
                        && message.status() == MessageStatus.SENT) {
                    sent.countDown();
                }
            });
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) {
                    firstAuth.countDown();
                }
            });
            alice.register("alice", "alice-pass-42".toCharArray(), "Alice");
            bob.register("bob", "bob-pass-42".toCharArray(), "Bob");
            assertTrue(firstAuth.await(30, TimeUnit.SECONDS), "Initial users did not authenticate");
            bob.close();
            bob = null;
            alice.sendMessage("dm:alice:bob", "persist me 🌋");
            assertTrue(sent.await(30, TimeUnit.SECONDS), "Server did not ACK the WAL-backed message");
            alice.close();
            alice = null;
            firstServer.close();
            restartedServer = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                    new WalStorageEngine(root, true), new SecurityAuditLogger(auditPath));
            restartedServer.start();
            restartedAlice = new ClientController("127.0.0.1", restartedServer.port(),
                    config.resolve("ember-client-trust.p12"), password);
            CountDownLatch login = new CountDownLatch(1);
            restartedAlice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) {
                    login.countDown();
                }
            });
            restartedAlice.login("alice", "alice-pass-42".toCharArray());
            assertTrue(login.await(30, TimeUnit.SECONDS), "Persisted account could not log in after restart");
            restartedBob = new ClientController("127.0.0.1", restartedServer.port(),
                    config.resolve("ember-client-trust.p12"), password);
            CountDownLatch bobLogin = new CountDownLatch(1);
            CountDownLatch recoveredDelivery = new CountDownLatch(1);
            AtomicInteger recoveredCopies = new AtomicInteger();
            restartedBob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) bobLogin.countDown();
                if (event.equals("MESSAGE") && message != null
                        && message.body().equals("persist me 🌋")) {
                    recoveredCopies.incrementAndGet();
                    recoveredDelivery.countDown();
                }
            });
            restartedBob.login("bob", "bob-pass-42".toCharArray());
            assertTrue(bobLogin.await(30, TimeUnit.SECONDS), "Bob could not log in after restart");
            assertTrue(recoveredDelivery.await(20, TimeUnit.SECONDS),
                    "Bob did not recover the undelivered WAL message");
            Thread.sleep(300);
            assertEquals(1, recoveredCopies.get(), "WAL recovery delivered the message more than once");
            try (WalStorageEngine reopened = new WalStorageEngine(root, true)) {
                reopened.init();
                assertTrue(reopened.findUser("bob").isPresent(), "Bob's account was lost on restart");
                List<Message> history = reopened.getHistory("dm:alice:bob", Long.MAX_VALUE, 10);
                assertEquals("persist me 🌋", history.get(0).body(),
                        "WAL message was lost on server restart");
            }
        } finally {
            if (alice != null) { alice.close(); }
            if (bob != null) { bob.close(); }
            if (restartedAlice != null) { restartedAlice.close(); }
            if (restartedBob != null) { restartedBob.close(); }
            firstServer.close();
            if (restartedServer != null) { restartedServer.close(); }
            Files.deleteIfExists(auditPath);
            deleteTree(root);
        }
    }

    private void testWalGroupRecovery() throws Exception {
        Path root = Path.of("build", "test-data", "group-wal");
        deleteTree(root);
        GroupChat original = new GroupChat("group-wal-id", "Study", "Review group",
                Map.of("alice", UserRole.MODERATOR, "bob", UserRole.STUDENT));
        try (WalStorageEngine storage = new WalStorageEngine(root, true)) {
            storage.init();
            storage.saveGroup(original);
        }
        try (WalStorageEngine recovered = new WalStorageEngine(root, true)) {
            recovered.init();
            GroupChat group = recovered.findGroup("group-wal-id").orElseThrow();
            assertEquals(original.members(), group.members(), "Group roles changed after WAL replay");
        } finally {
            deleteTree(root);
        }
    }

    private void testMessageFlood() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path auditPath = Path.of("build", "test-data", "flood-audit.json");
        Files.createDirectories(auditPath.getParent());
        Files.deleteIfExists(auditPath);
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                new InMemoryStorageEngine(), new SecurityAuditLogger(auditPath));
        ClientController alice = null;
        try {
            server.start();
            alice = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password);
            CountDownLatch authenticated = new CountDownLatch(1);
            CountDownLatch limited = new CountDownLatch(1);
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) {
                    authenticated.countDown();
                }
                if (event.equals("ERROR") && "Too many requests".equals(model.errorMessage())) {
                    limited.countDown();
                }
            });
            alice.register("flooder", "flooder-pass-42".toCharArray(), "Flooder");
            assertTrue(authenticated.await(20, TimeUnit.SECONDS), "Flood test user did not authenticate");
            for (int index = 0; index < 30; index++) {
                alice.sendMessage("dm:flooder:bob", "flood-" + index);
            }
            assertTrue(limited.await(20, TimeUnit.SECONDS), "Message flood did not receive 429");
            assertTrue(Files.readString(auditPath, java.nio.charset.StandardCharsets.UTF_8)
                    .contains("\"event\":\"rate_limited\""), "Flood was not audited");
        } finally {
            if (alice != null) {
                alice.close();
            }
            server.close();
            Files.deleteIfExists(auditPath);
        }
    }

    private void testLoopbackRateLimit() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter();
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        for (int request = 0; request < 100; request++) {
            assertTrue(limiter.allow(loopback), "Loopback IP limit affected demo clients");
        }
        TokenBucket bucket = new TokenBucket(2, 0);
        assertTrue(bucket.tryConsume(), "First token was unavailable");
        assertTrue(bucket.tryConsume(), "Second token was unavailable");
        assertFalse(bucket.tryConsume(), "Empty bucket allowed a flood request");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void testLocalOutbox() throws Exception {
        Path root = Path.of("build", "test-data", "outbox");
        deleteTree(root);
        Path file = root.resolve("outbox.dat");
        WireFrame first = FrameFactory.command(WireOpcodes.CMD_MSG_SEND, 1,
                Map.of("clientMsgId", "first", "body", "مرحبا 🌋"));
        WireFrame second = FrameFactory.command(WireOpcodes.CMD_MSG_SEND, 2,
                Map.of("clientMsgId", "second", "body", "second"));
        LocalOutbox outbox = new LocalOutbox(file);
        outbox.enqueue(first);
        outbox.enqueue(second);
        LocalOutbox recovered = new LocalOutbox(file);
        assertEquals(2, recovered.size(), "Outbox entries were lost after reopen");
        assertEquals(1L, recovered.peek().correlationId(), "Outbox FIFO order changed");
        recovered.removeFirst();
        LocalOutbox remaining = new LocalOutbox(file);
        assertEquals(1, remaining.size(), "Acknowledged outbox entry was not removed");
        assertEquals("second", KvPayloadCodec.decode(remaining.peek().payload()).get("clientMsgId"),
                "Remaining outbox frame changed");
        for (int attempt = 0; attempt < 12; attempt++) {
            long delay = ReconnectManager.nextDelayMillis(attempt);
            assertTrue(delay >= 500 && delay <= 15_250, "Reconnect delay exceeded its bounds");
        }
        deleteTree(root);
    }

    private void testGroupMessagingAndAuthorization() throws Exception {
        Path config = Path.of("config");
        char[] storePassword = readStorePassword(config.resolve("secrets.properties"));
        Path dataRoot = Path.of("build", "test-data", "group-clients");
        Path auditPath = Path.of("build", "test-data", "group-audit.json");
        deleteTree(dataRoot);
        Files.deleteIfExists(auditPath);
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), storePassword, 0,
                new InMemoryStorageEngine(), new SecurityAuditLogger(auditPath));
        Map<String, ClientController> clients = new java.util.LinkedHashMap<>();
        try {
            server.start();
            String[] users = {"alice", "bob", "charlie", "dana", "eve"};
            CountDownLatch authenticated = new CountDownLatch(users.length);
            CountDownLatch groupCreated = new CountDownLatch(1);
            CountDownLatch groupReady = new CountDownLatch(1);
            CountDownLatch groupMessages = new CountDownLatch(3);
            CountDownLatch groupRead = new CountDownLatch(1);
            CountDownLatch pollCreated = new CountDownLatch(2);
            CountDownLatch pollVoted = new CountDownLatch(2);
            CountDownLatch bobMemberDenied = new CountDownLatch(1);
            CountDownLatch eveSendDenied = new CountDownLatch(4);
            CountDownLatch eveReceiptDenied = new CountDownLatch(1);
            CountDownLatch senderReceiptDenied = new CountDownLatch(1);
            CountDownLatch outsiderPollDenied = new CountDownLatch(1);
            AtomicReference<Boolean> expectEveReceiptError = new AtomicReference<>(false);
            AtomicReference<Boolean> expectEvePollError = new AtomicReference<>(false);
            AtomicReference<String> groupId = new AtomicReference<>();
            for (String user : users) {
                ClientController client = new ClientController("127.0.0.1", server.port(),
                        config.resolve("ember-client-trust.p12"), storePassword, dataRoot);
                clients.put(user, client);
                client.addListener((model, event, message) -> {
                    if (event.equals("AUTH_OK")) {
                        authenticated.countDown();
                    }
                    if (event.equals("GROUP_UPDATE") && user.equals("alice")) {
                        String foundGroup = model.groupIds().stream().findFirst().orElse(null);
                        if (foundGroup != null) {
                            groupId.set(foundGroup);
                            if (model.groupMemberCount(foundGroup) == 1) {
                                groupCreated.countDown();
                            } else if (model.groupMemberCount(foundGroup) == 4) {
                                groupReady.countDown();
                            }
                        }
                    }
                    if (event.equals("MESSAGE") && message != null
                            && message.conversationId().equals("grp:" + groupId.get())) {
                        groupMessages.countDown();
                    }
                    if (event.equals("POLL_UPDATE") && model.polls(groupId.get()).size() == 1) {
                        pollCreated.countDown();
                        if (model.polls(groupId.get()).get(0).totals().get(0) == 1) {
                            pollVoted.countDown();
                        }
                    }
                    if (event.equals("RECEIPT") && message != null
                            && message.conversationId().equals("grp:" + groupId.get())) {
                        ClientStateModel.ReceiptSummary receipt = model.receiptSummary(message.clientMsgId());
                        if (receipt != null && receipt.delivered() == 3 && receipt.total() == 3) {
                            groupReady.countDown();
                        }
                        if (receipt != null && receipt.read() == 3 && receipt.total() == 3) {
                            groupRead.countDown();
                        }
                    }
                    if (event.equals("ERROR") && "403".equals(model.errorCode())) {
                        if (user.equals("bob")) {
                            bobMemberDenied.countDown();
                        } else if (user.equals("eve")) {
                            eveSendDenied.countDown();
                            if (expectEveReceiptError.get()) {
                                eveReceiptDenied.countDown();
                            }
                            if (expectEvePollError.get()) { outsiderPollDenied.countDown(); }
                        } else if (user.equals("alice")) {
                            senderReceiptDenied.countDown();
                        }
                    }
                });
            }
            for (String user : users) {
                clients.get(user).register(user, (user + "-pass-42").toCharArray(), user);
            }
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Group test users did not authenticate");
            clients.get("alice").createGroup("Study", "Study group");
            assertTrue(groupCreated.await(10, TimeUnit.SECONDS), "Group creation was not returned");
            String group = groupId.get();
            clients.get("alice").requestGroupInfo(group);
            waitFor(() -> clients.get("alice").model().groupDetails(group) != null,
                    "Authorized group info was not returned");
            assertEquals(UserRole.MODERATOR,
                    clients.get("alice").model().groupDetails(group).roleOf("alice"),
                    "Group info omitted the moderator role");
            clients.get("alice").changeGroupMember(group, "bob", "ADD");
            clients.get("alice").changeGroupMember(group, "charlie", "ADD");
            clients.get("alice").changeGroupMember(group, "dana", "ADD");
            assertTrue(groupReady.await(10, TimeUnit.SECONDS), "Group did not reach four members");
            clients.get("alice").createPoll(group, "Study session?", List.of("Today", "Tomorrow"));
            assertTrue(pollCreated.await(15, TimeUnit.SECONDS), "Group poll was not broadcast");
            String pollId = clients.get("alice").model().polls(group).get(0).pollId();
            clients.get("bob").votePoll(pollId, 0);
            assertTrue(pollVoted.await(15, TimeUnit.SECONDS), "Poll vote totals did not update");
            expectEvePollError.set(true);
            clients.get("eve").votePoll(pollId, 0);
            assertTrue(outsiderPollDenied.await(10, TimeUnit.SECONDS), "Outsider poll vote was allowed");
            clients.get("bob").changeGroupMember(group, "eve", "ADD");
            assertTrue(bobMemberDenied.await(10, TimeUnit.SECONDS), "Non-moderator member change was allowed");
            clients.get("eve").sendMessage("grp:" + group, "forbidden group send");
            clients.get("eve").sendMessage("dm:alice:bob", "forbidden direct send");
            clients.get("eve").requestHistory("grp:" + group, Long.MAX_VALUE, 10);
            clients.get("eve").setTyping("grp:" + group, true);
            assertTrue(eveSendDenied.await(10, TimeUnit.SECONDS), "Non-participant message send was allowed");
            String messageId = clients.get("alice").sendMessage("grp:" + group, "Group hello 🌋");
            assertTrue(groupMessages.await(20, TimeUnit.SECONDS), "Group members did not receive fan-out");
            assertTrue(awaitReceipt(clients.get("alice").model(), messageId, 3, 20_000),
                    "Group delivered receipt did not aggregate to 3/3");
            clients.get("bob").markRead("grp:" + group, messageId);
            clients.get("charlie").markRead("grp:" + group, messageId);
            clients.get("dana").markRead("grp:" + group, messageId);
            assertTrue(groupRead.await(15, TimeUnit.SECONDS), "Group read receipts did not aggregate to 3/3");
            expectEveReceiptError.set(true);
            clients.get("alice").markRead("grp:" + group, messageId);
            clients.get("eve").markRead("grp:" + group, messageId);
            assertTrue(eveReceiptDenied.await(10, TimeUnit.SECONDS), "Outsider receipt was accepted");
            assertTrue(senderReceiptDenied.await(10, TimeUnit.SECONDS), "Sender receipt was accepted");
            ClientStateModel.ReceiptSummary summary = clients.get("alice").model()
                    .receiptSummary(messageId);
            assertEquals(3, summary.delivered(), "Group delivered count was incorrect");
            assertEquals(3, summary.total(), "Group receipt total was incorrect");
            assertEquals(3, summary.read(), "Group read count was incorrect");
            assertEquals(MessageStatus.READ, clients.get("alice").model().messages("grp:" + group)
                    .stream().filter(message -> message.clientMsgId().equals(messageId))
                    .findFirst().orElseThrow().status(), "Group message did not reach READ");
        } finally {
            for (ClientController client : clients.values()) {
                client.close();
            }
            server.close();
            Files.deleteIfExists(auditPath);
            deleteTree(dataRoot);
        }
    }

    private void testReconnectHistoryAndPresence() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path dataRoot = Path.of("build", "test-data", "resume-clients");
        deleteTree(dataRoot);
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0);
        ClientController alice = null;
        ClientController bob = null;
        ClientController resumedBob = null;
        try {
            server.start();
            alice = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password, dataRoot);
            bob = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password, dataRoot);
            CountDownLatch authenticated = new CountDownLatch(2);
            CountDownLatch pong = new CountDownLatch(1);
            CountDownLatch typing = new CountDownLatch(1);
            AtomicInteger offlineCopies = new AtomicInteger();
            AtomicInteger historyCopies = new AtomicInteger();
            CountDownLatch offlineMessages = new CountDownLatch(2);
            CountDownLatch historyMessages = new CountDownLatch(2);
            AtomicInteger presenceEvents = new AtomicInteger();
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("PONG")) pong.countDown();
                if (event.equals("PRESENCE")) presenceEvents.incrementAndGet();
                if (event.equals("HISTORY_MESSAGE")) historyMessages.countDown();
            });
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("TYPING") && "alice".equals(model.typingUser("dm:alice:bob"))) {
                    typing.countDown();
                }
            });
            alice.register("alice", "alice-pass-42".toCharArray(), "Alice");
            bob.register("bob", "bob-pass-42".toCharArray(), "Bob");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Resume users did not authenticate");
            assertTrue(awaitPresence(alice.model(), "bob", true, 10_000),
                    "Online presence was not broadcast (events=" + presenceEvents.get()
                            + ", lastError=" + alice.model().errorMessage() + ")");
            alice.ping();
            assertTrue(pong.await(10, TimeUnit.SECONDS), "Heartbeat ping did not receive a pong");
            alice.setTyping("dm:alice:bob", true);
            assertTrue(typing.await(10, TimeUnit.SECONDS), "Typing event was not delivered");
            String duplicateId = "duplicate-resend-1";
            alice.sendMessageWithId("dm:alice:bob", "Idempotent 🌋", duplicateId);
            alice.sendMessageWithId("dm:alice:bob", "Idempotent 🌋", duplicateId);
            Thread.sleep(500);
            assertEquals(1, bob.model().messages("dm:alice:bob").size(),
                    "Duplicate resend created more than one message");
            String savedToken = bob.sessionToken();
            bob.close();
            bob = null;
            String offlineId = alice.sendMessage("dm:alice:bob", "While you were away مرحبا");
            Thread.sleep(500);
            assertEquals(null, alice.model().receiptSummary(offlineId),
                    "Offline recipient unexpectedly acknowledged delivery");
            resumedBob = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password, dataRoot);
            CountDownLatch resumed = new CountDownLatch(1);
            resumedBob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) resumed.countDown();
                if (event.equals("MESSAGE") && message != null) {
                    if (message.body().equals("While you were away مرحبا")) {
                        offlineCopies.incrementAndGet();
                    }
                    offlineMessages.countDown();
                }
            });
            assertEquals(savedToken, Files.readString(dataRoot.resolve("bob/session.token")),
                    "Saved session token did not persist");
            resumedBob.resumeStoredSession("bob");
            assertTrue(resumed.await(15, TimeUnit.SECONDS), "Session token did not resume");
            assertTrue(offlineMessages.await(15, TimeUnit.SECONDS), "Sync did not restore both messages");
            Thread.sleep(500);
            assertEquals(1, offlineCopies.get(), "Offline message arrived more than once");
            alice.requestHistory("dm:alice:bob", Long.MAX_VALUE, 10);
            assertTrue(historyMessages.await(15, TimeUnit.SECONDS), "History pull returned no messages");
            historyCopies.set(alice.model().messages("dm:alice:bob").size());
            assertEquals(2, historyCopies.get(), "History/model did not retain exactly two messages");
        } finally {
            if (resumedBob != null) resumedBob.close();
            if (bob != null) bob.close();
            if (alice != null) alice.close();
            server.close();
            deleteTree(dataRoot);
        }
    }

    private boolean awaitReceipt(ClientStateModel model, String messageId, int total, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            ClientStateModel.ReceiptSummary receipt = model.receiptSummary(messageId);
            if (receipt != null && receipt.delivered() == total && receipt.total() == total) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private boolean awaitPresence(ClientStateModel model, String userId, boolean expected,
            long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (model.isOnline(userId) == expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void testWalkingSkeleton() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0);
        ClientController alice = null;
        ClientController bob = null;
        try {
            server.start();
            alice = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password);
            bob = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password);
            CountDownLatch authLatch = new CountDownLatch(2);
            CountDownLatch deliveryLatch = new CountDownLatch(1);
            CountDownLatch arabicDeliveryLatch = new CountDownLatch(1);
            CountDownLatch sentLatch = new CountDownLatch(1);
            CountDownLatch deliveredReceiptLatch = new CountDownLatch(1);
            AtomicBoolean callbacksOnEdt = new AtomicBoolean(true);
            ClientStateModel aliceModel = alice.model();
            ClientStateModel bobModel = bob.model();
            alice.addListener((model, event, message) -> {
                if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
                    callbacksOnEdt.set(false);
                }
                if (event.equals("AUTH_OK")) {
                    authLatch.countDown();
                }
                if (event.equals("MESSAGE_STATUS") && message != null
                        && message.status() == MessageStatus.SENT) {
                    sentLatch.countDown();
                }
                if (event.equals("MESSAGE_STATUS") && message != null
                        && message.status() == MessageStatus.DELIVERED) {
                    deliveredReceiptLatch.countDown();
                }
            });
            bob.addListener((model, event, message) -> {
                if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
                    callbacksOnEdt.set(false);
                }
                if (event.equals("AUTH_OK")) {
                    authLatch.countDown();
                }
                if (event.equals("MESSAGE") && message != null && message.body().equals("Hello")) {
                    deliveryLatch.countDown();
                }
                if (event.equals("MESSAGE") && message != null
                        && message.body().equals("مرحبا 🌋")) {
                    arabicDeliveryLatch.countDown();
                }
            });
            alice.register("alice", "alice-pass-42".toCharArray(), "Alice");
            bob.register("bob", "bob-pass-42".toCharArray(), "Bob");
            assertTrue(authLatch.await(30, TimeUnit.SECONDS), "Both users did not authenticate");
            alice.openConversation("dm:alice:bob");
            bob.openConversation("dm:alice:bob");
            String clientMsgId = alice.sendMessage("dm:alice:bob", "Hello");
            assertTrue(sentLatch.await(20, TimeUnit.SECONDS),
                    "Alice did not observe SENT acknowledgement");
            assertTrue(deliveryLatch.await(30, TimeUnit.SECONDS), "Bob did not receive the message");
            assertTrue(deliveredReceiptLatch.await(30, TimeUnit.SECONDS),
                    "Alice did not receive the DELIVERED receipt");
            assertEquals("Hello", bobModel.messages("dm:alice:bob").get(0).body(),
                    "Bob's message body changed");
            MessageStatus aliceStatus = aliceModel.messages("dm:alice:bob").stream()
                    .filter(message -> message.clientMsgId().equals(clientMsgId))
                    .findFirst().orElseThrow().status();
            assertEquals(MessageStatus.DELIVERED, aliceStatus,
                    "Sender message status did not reach DELIVERED");
            alice.sendMessage("dm:alice:bob", "مرحبا 🌋");
            assertTrue(arabicDeliveryLatch.await(20, TimeUnit.SECONDS),
                    "Arabic/emoji message did not reach Bob's client model");
            assertEquals("مرحبا 🌋", bobModel.messages("dm:alice:bob").get(1).body(),
                    "Client model changed Arabic/emoji text");
            assertTrue(callbacksOnEdt.get(), "Client model listeners ran outside the Swing EDT");
        } finally {
            if (alice != null) {
                alice.close();
            }
            if (bob != null) {
                bob.close();
            }
            server.close();
        }
    }

    private void testMultiDeviceAndWeb() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path trust = config.resolve("ember-client-trust.p12");
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0);
        ClientController laptop = null;
        ClientController phone = null;
        try {
            server.start();
            try (SSLSocket web = TlsSocketFactory.createClientSocket(trust, password, "127.0.0.1",
                    server.port())) {
                web.setSoTimeout(20_000);
                web.getOutputStream().write("GET /app.js HTTP/1.1\r\nHost: x\r\n\r\n"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                String head = new String(web.getInputStream().readNBytes(15),
                        java.nio.charset.StandardCharsets.US_ASCII);
                assertEquals("HTTP/1.1 200 OK", head, "Web app file was not served on the TLS port");
            }
            laptop = new ClientController("127.0.0.1", server.port(), trust, password);
            phone = new ClientController("127.0.0.1", server.port(), trust, password);
            String longBody = "x".repeat(5_000);
            CountDownLatch laptopAuth = new CountDownLatch(1);
            CountDownLatch phoneAuth = new CountDownLatch(1);
            CountDownLatch laptopGotLong = new CountDownLatch(1);
            CountDownLatch phoneGotLong = new CountDownLatch(1);
            CountDownLatch phoneGotOwn = new CountDownLatch(1);
            laptop.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) { laptopAuth.countDown(); }
                if (event.equals("MESSAGE") && message != null && message.body().equals(longBody)) {
                    laptopGotLong.countDown();
                }
            });
            phone.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) { phoneAuth.countDown(); }
                if (event.equals("MESSAGE") && message != null && message.body().equals(longBody)) {
                    phoneGotLong.countDown();
                }
                if (event.equals("MESSAGE") && message != null && message.body().equals("from laptop")) {
                    phoneGotOwn.countDown();
                }
            });
            laptop.register("alice", "alice-pass-42".toCharArray(), "Alice");
            assertTrue(laptopAuth.await(30, TimeUnit.SECONDS), "Laptop did not authenticate");
            phone.login("alice", "alice-pass-42".toCharArray());
            assertTrue(phoneAuth.await(30, TimeUnit.SECONDS), "Second device did not authenticate");
            try (SSLSocket bob = TlsSocketFactory.createClientSocket(trust, password, "127.0.0.1",
                    server.port())) {
                bob.setSoTimeout(20_000);
                var in = new java.io.BufferedInputStream(bob.getInputStream());
                var out = bob.getOutputStream();
                out.write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                StringBuilder response = new StringBuilder();
                while (!response.toString().endsWith("\r\n\r\n")) {
                    response.append((char) in.read());
                }
                assertTrue(response.toString().contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),
                        "WebSocket accept key is wrong: " + response);
                wsSend(out, FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "bob", "pass", "bob-pass-42", "displayName", "Bob")));
                assertEquals(WireOpcodes.EVT_AUTH_OK, wsRead(in, WireOpcodes.EVT_AUTH_OK).opcode(),
                        "Web user did not authenticate");
                wsSend(out, FrameFactory.command(WireOpcodes.CMD_MSG_SEND, 2, Map.of(
                        "clientMsgId", "web-long-1", "convId", "dm:alice:bob", "body", longBody)));
                assertEquals(WireOpcodes.EVT_MSG_ACK, wsRead(in, WireOpcodes.EVT_MSG_ACK).opcode(),
                        "5,000-character web message was not accepted");
                assertTrue(laptopGotLong.await(20, TimeUnit.SECONDS), "First device missed the message");
                assertTrue(phoneGotLong.await(20, TimeUnit.SECONDS), "Second device missed the message");
                laptop.sendMessage("dm:alice:bob", "from laptop");
                assertTrue(phoneGotOwn.await(20, TimeUnit.SECONDS),
                        "Sender's other device did not receive the sent message");
                WireFrame delivered = wsRead(in, WireOpcodes.EVT_MSG_DELIVER);
                assertEquals("from laptop", KvPayloadCodec.decode(delivered.payload()).get("body"),
                        "Web user did not receive the desktop message");
            }
        } finally {
            if (laptop != null) { laptop.close(); }
            if (phone != null) { phone.close(); }
            server.close();
        }
    }

    /** Sends one masked client WebSocket frame, as a browser would. */
    private static void wsSend(java.io.OutputStream out, WireFrame frame) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        FrameCodec.writeFrame(body, frame);
        byte[] payload = body.toByteArray();
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        ByteArrayOutputStream ws = new ByteArrayOutputStream();
        ws.write(0x82);
        if (payload.length < 126) {
            ws.write(0x80 | payload.length);
        } else {
            ws.write(0x80 | 126);
            ws.write(payload.length >>> 8);
            ws.write(payload.length);
        }
        ws.write(mask);
        for (int index = 0; index < payload.length; index++) {
            ws.write(payload[index] ^ mask[index & 3]);
        }
        out.write(ws.toByteArray());
        out.flush();
    }

    /** Reads server WebSocket messages until one carries the wanted Ember opcode. */
    private static WireFrame wsRead(java.io.InputStream in, int opcode) throws Exception {
        while (true) {
            int first = in.read();
            long length = in.read() & 0x7F;
            if (first < 0) {
                throw new IOException("WebSocket closed");
            }
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | in.read();
                }
            }
            WireFrame frame = FrameCodec.readFrame(
                    new ByteArrayInputStream(in.readNBytes((int) length)), Integer.MAX_VALUE);
            if (frame.opcode() == opcode) {
                return frame;
            }
        }
    }

    private void testUiBasics() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            assertEquals(new java.awt.Color(0x120B06), ThemeManager.current().background(),
                    "Dark theme background token changed");
            assertEquals(new java.awt.Color(0x2B1D10), ThemeManager.Theme.LIGHT.text(),
                    "Light theme text token changed");
            assertTrue(FontResolver.resolve() != null, "Font resolver returned null");
            Message message = new Message("ui-test", "alice", "dm:alice:bob",
                    "مرحبا 🌋 ".repeat(20), System.currentTimeMillis());
            ChatBubblePanel bubble = new ChatBubblePanel(message, false, "Delivered 1/1");
            bubble.setSize(460, 220);
            layoutComponentTree(bubble);
            java.util.List<javax.swing.JTextArea> bodies = new java.util.ArrayList<>();
            collectTextAreas(bubble, bodies);
            assertEquals(1, bodies.size(), "Chat bubble should use a single wrapping text area");
            assertTrue(bodies.get(0).getLineWrap() && bodies.get(0).getWrapStyleWord(),
                    "Chat bubble text does not wrap at word boundaries");
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread(),
                    "UI fixture was not constructed on the EDT");
            ThemeManager.apply(ThemeManager.Theme.LIGHT);
            assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.current(),
                    "Theme switch did not apply");
        });
    }

    private void testImageAttacks() throws Exception {
        ImageValidator validator = new ImageValidator();
        boolean scriptRejected = false;
        try {
            validator.validate("#!/bin/sh\necho unsafe".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), "avatar.jpg");
        } catch (MediaValidationException expected) { scriptRejected = true; }
        assertTrue(scriptRejected, "Renamed script was accepted as an image");
        boolean dimensionsRejected = false;
        try {
            validator.validate(pngHeader(12_000, 12_000), "large.png");
        } catch (MediaValidationException expected) { dimensionsRejected = true; }
        assertTrue(dimensionsRejected, "12,000 × 12,000 PNG was not rejected by its header");
        System.out.println("Verified: image signature and 12,000 x 12,000 PNG header rejected before decode");
    }

    private void testSocialWalRecovery() throws Exception {
        Path directory = Files.createTempDirectory("ember-social-wal");
        UserRecord user = new UserRecord("alice", "alice", "Alice", "hash");
        MediaAsset media = new MediaAsset("media-1", "alice", "media-1.jpg",
                "media-1_thumb.jpg", 64, 48, 100);
        SocialPost post = new SocialPost("post-1", "alice", media.mediaId(), "مرحبا 🌋",
                101, 0, 0, false);
        WalStorageEngine first = new WalStorageEngine(directory, true);
        first.init();
        first.saveUser(user);
        first.saveProfile(new UserProfile("alice", "Alice", "Campus life", "", true));
        Message walMessage = new Message("wal-social-message", "alice", "dm:alice:bob",
                "Saved message", 99);
        first.appendMessage(walMessage);
        first.setMessageReaction(walMessage.clientMsgId(), "bob", "🔥", true);
        first.setMessagePinned(walMessage.clientMsgId(), true);
        first.setMessageStarred(walMessage.clientMsgId(), "bob", true);
        first.editMessage(walMessage.clientMsgId(), "Updated WAL text");
        Message deletedMessage = new Message("wal-deleted-message", "alice", "dm:alice:bob",
                "Delete me", 100);
        first.appendMessage(deletedMessage);
        first.deleteMessage(deletedMessage.clientMsgId());
        first.setFollow("bob", "alice", true);
        first.setFollowStatus("charlie", "alice", FollowStatus.PENDING);
        first.setBlocked("alice", "charlie", true);
        first.saveReport(new ModerationReport("report-1", "bob", "profile", "alice",
                "Abusive profile 🌋", 103));
        first.saveMedia(media);
        first.savePost(post);
        first.saveStory(new edu.ember.common.model.StoryItem("story-1", "alice", "media-1",
                "WAL story 🌋", System.currentTimeMillis(), System.currentTimeMillis() + 60_000));
        edu.ember.common.model.PollData poll = new edu.ember.common.model.PollData("poll-1",
                "group-1", "When?", List.of("Today", "Tomorrow"), List.of("alice", "bob"));
        first.savePoll(poll);
        first.votePoll("poll-1", "bob", 1);
        first.setPostLike(post.postId(), "bob", true);
        first.addComment(new PostComment("comment-1", post.postId(), "bob", "مرحبا 🌋", 102));
        first.close();
        WalStorageEngine recovered = new WalStorageEngine(directory, true);
        recovered.init();
        assertEquals("Campus life", recovered.findProfile("alice").orElseThrow().bio(),
                "Profile was not restored from WAL");
        assertTrue(recovered.findProfile("alice").orElseThrow().privateAccount(),
                "Private-account setting was not restored from WAL");
        assertTrue(recovered.isBlockedEitherDirection("alice", "charlie"),
                "Block relationship was not restored from WAL");
        assertEquals("Abusive profile 🌋", recovered.listReports().get(0).reason(),
                "Moderation report was not restored from WAL");
        assertEquals("WAL story 🌋", recovered.findStory("story-1").orElseThrow().caption(),
                "Story was not restored from WAL");
        assertEquals(List.of(0, 1), recovered.findPoll("poll-1").orElseThrow().totals(),
                "Poll and vote were not restored from WAL");
        assertTrue(recovered.followStatus("charlie", "alice").isEmpty(),
                "Blocking did not clear a pending follow in recovered state");
        assertEquals(List.of("bob"), recovered.followersOf("alice"),
                "Follow relationship was not restored from WAL");
        SocialPost restored = recovered.findPost(post.postId(), "bob").orElseThrow();
        assertTrue(restored.likedByViewer() && restored.likeCount() == 1,
                "Post like was not restored from WAL");
        assertEquals("مرحبا 🌋", recovered.commentsForPost(post.postId(), 10).get(0).text(),
                "Comment text did not survive WAL UTF-8 replay");
        assertEquals(1, recovered.findByClientMsgId(walMessage.clientMsgId()).orElseThrow()
                .reactionCounts().get("🔥"), "Message reaction was not restored from WAL");
        Message restoredMessage = recovered.findByClientMsgId(walMessage.clientMsgId()).orElseThrow();
        assertTrue(restoredMessage.pinned() && restoredMessage.starredBy("bob"),
                "Pinned or starred message state was not restored from WAL");
        assertEquals("Updated WAL text", recovered.findByClientMsgId(walMessage.clientMsgId())
                .orElseThrow().body(), "Message edit was not restored from WAL");
        assertTrue(recovered.findByClientMsgId(deletedMessage.clientMsgId()).isEmpty(),
                "Message deletion was not restored from WAL");
        recovered.close();
    }

    private void testCampusPulseNetwork() throws Exception {
        Path config = Path.of("config");
        Path temporary = Files.createTempDirectory("ember-social-network");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                new InMemoryStorageEngine(), new SecurityAuditLogger(
                        temporary.resolve("audit.json")), temporary.resolve("media"));
        ClientController alice = null;
        ClientController bob = null;
        SSLSocket attacker = null;
        try {
            server.start();
            alice = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password,
                    temporary.resolve("client-a"));
            bob = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password,
                    temporary.resolve("client-b"));
            CountDownLatch authenticated = new CountDownLatch(2);
            CountDownLatch uploaded = new CountDownLatch(1);
            CountDownLatch aliceFeed = new CountDownLatch(1);
            CountDownLatch bobFeed = new CountDownLatch(1);
            CountDownLatch commentsReady = new CountDownLatch(1);
            CountDownLatch rejectedRequests = new CountDownLatch(9);
            CountDownLatch prePostRejections = new CountDownLatch(2);
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MEDIA_UPLOADED")) uploaded.countDown();
                if (event.equals("SOCIAL_FEED") && !model.socialPosts().isEmpty()) aliceFeed.countDown();
            });
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("SOCIAL_FEED") && !model.socialPosts().isEmpty()) bobFeed.countDown();
                if (event.equals("COMMENTS") && !model.comments(
                        model.socialPosts().isEmpty() ? "" : model.socialPosts().get(0).postId()).isEmpty()) {
                    commentsReady.countDown();
                }
                if (event.equals("ERROR") && ("400".equals(model.errorCode())
                        || "403".equals(model.errorCode()) || "404".equals(model.errorCode()))) {
                    rejectedRequests.countDown();
                    prePostRejections.countDown();
                }
            });
            alice.register("pulsealice", "alice-pass-42".toCharArray(), "Pulse Alice");
            bob.register("pulsebob", "bob-pass-42".toCharArray(), "Pulse Bob");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Campus Pulse accounts did not authenticate");

            bob.uploadMedia("#!/bin/sh".getBytes(java.nio.charset.StandardCharsets.UTF_8), "avatar.jpg");

            byte[] photo = paddedJpeg(3 * 1024 * 1024);
            alice.uploadMedia(photo, "campus-photo.jpg");
            CountDownLatch edtResponsive = new CountDownLatch(1);
            javax.swing.SwingUtilities.invokeLater(edtResponsive::countDown);
            assertTrue(edtResponsive.await(1, TimeUnit.SECONDS),
                    "3 MB upload preparation blocked the Swing EDT");
            assertTrue(uploaded.await(30, TimeUnit.SECONDS), "3 MB photo upload did not complete");
            String mediaId = alice.model().lastUploadedMediaId();
            bob.requestMedia(mediaId, true);
            assertTrue(prePostRejections.await(20, TimeUnit.SECONDS),
                    "Invalid upload or media without a published post was not rejected");
            bob.createPost(mediaId, "unauthorized reuse");
            bob.follow("missing-user", true);
            bob.requestProfile("missing-user");
            bob.requestFeed("missing-user");
            alice.createPost(mediaId, "مرحبا 🌋 on campus");
            assertTrue(aliceFeed.await(20, TimeUnit.SECONDS), "Post was not returned to its author");
            bob.requestFeed(null);
            assertTrue(bobFeed.await(20, TimeUnit.SECONDS), "Public feed did not return the post to Bob");
            String postId = bob.model().socialPosts().get(0).postId();
            bob.interactPost(postId, "comment", "Campus Pulse comment 🌋");
            bob.interactPost(postId, "comments", "");
            assertTrue(commentsReady.await(20, TimeUnit.SECONDS), "Post comment was not returned");
            assertEquals("Campus Pulse comment 🌋", bob.model().comments(postId).get(0).text(),
                    "Comment text changed over the TLS social protocol");
            bob.requestMedia(mediaId, true);
            CountDownLatch mediaReady = new CountDownLatch(1);
            bob.addListener((model, event, message) -> {
                if (event.equals("MEDIA") && model.mediaBytes(mediaId) != null) mediaReady.countDown();
            });
            bob.requestMedia(mediaId, true);
            assertTrue(mediaReady.await(20, TimeUnit.SECONDS), "Public post media was not returned");
            assertTrue(bob.model().mediaBytes(mediaId).length > 0, "Downloaded media was empty");

            bob.requestMedia("missing-media", true);
            bob.interactPost("missing-post", "like", "");
            bob.interactPost("missing-post", "comment", "unauthorized");

            attacker = TlsSocketFactory.createClientSocket(
                    config.resolve("ember-client-trust.p12"), password, "127.0.0.1", server.port());
            attacker.setSoTimeout(10_000);
            attacker.startHandshake();
            FrameCodec.writeFrame(attacker.getOutputStream(), FrameFactory.command(
                    WireOpcodes.CMD_AUTH_REGISTER, 1, Map.of("user", "pulseattacker",
                            "pass", "attacker-pass-42", "displayName", "Attacker")));
            WireFrame auth = FrameCodec.readFrame(attacker.getInputStream(), 4096);
            assertEquals(WireOpcodes.EVT_AUTH_OK, auth.opcode(), "Raw attacker did not authenticate");
            FrameCodec.writeFrame(attacker.getOutputStream(), FrameFactory.command(
                    WireOpcodes.CMD_PROFILE_UPDATE, 2, Map.of("userId", "pulsealice",
                            "displayName", "Forged", "bio", "forged")));
            WireFrame response;
            do {
                response = FrameCodec.readFrame(attacker.getInputStream(), 64 * 1024);
            } while (response.opcode() != WireOpcodes.EVT_ERROR);
            Map<String, String> error = KvPayloadCodec.decode(response.payload());
            assertEquals("403", error.get("code"), "Cross-user profile update was not forbidden");
            assertTrue(rejectedRequests.await(20, TimeUnit.SECONDS),
                    "An invalid upload or unauthorized/nonexistent social object was accepted");
        } finally {
            if (attacker != null) attacker.close();
            if (alice != null) alice.close();
            if (bob != null) bob.close();
            server.close();
        }
    }

    private void testPrivateBlockAndReport() throws Exception {
        Path config = Path.of("config");
        Path temporary = Files.createTempDirectory("ember-private-profile");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                storage, new SecurityAuditLogger(temporary.resolve("audit.json")),
                temporary.resolve("media"));
        ClientController alice = null;
        ClientController bob = null;
        try {
            server.start();
            Path trust = config.resolve("ember-client-trust.p12");
            alice = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-a"));
            bob = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-b"));
            CountDownLatch authenticated = new CountDownLatch(2);
            CountDownLatch bobError = new CountDownLatch(2);
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("ERROR") && "404".equals(model.errorCode())) bobError.countDown();
            });
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
            });
            alice.register("privatealice", "alice-pass-42".toCharArray(), "Private Alice");
            bob.register("privatebob", "bob-pass-42".toCharArray(), "Private Bob");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Private-account clients did not authenticate");
            String aliceId = alice.model().userId();
            String bobId = bob.model().userId();
            ClientController bobClient = bob;
            alice.updateProfile("Private Alice", "Members only", true);
            waitFor(() -> storage.findProfile(aliceId).orElseThrow().privateAccount(),
                    "Private setting was not saved");
            bob.requestProfile(aliceId);
            bob.requestFeed(aliceId);
            assertTrue(bobError.await(20, TimeUnit.SECONDS),
                    "A non-follower could access a private profile or feed");
            bob.follow(aliceId, true);
            waitFor(() -> storage.followStatus(bobId, aliceId).orElse(null) == FollowStatus.PENDING,
                    "Private follow request was not recorded as pending");
            alice.approveFollow(bobId, true);
            waitFor(() -> storage.followStatus(bobId, aliceId).orElse(null) == FollowStatus.ACCEPTED,
                    "Owner approval did not accept the follow request");
            bob.requestProfile(aliceId);
            waitFor(() -> bobClient.model().profile(aliceId) != null,
                    "Approved follower could not access private profile");
            bob.blockUser(aliceId, true);
            waitFor(() -> storage.isBlockedEitherDirection(bobId, aliceId),
                    "Block request was not persisted");
            assertTrue(storage.followStatus(bobId, aliceId).isEmpty(),
                    "Blocking did not remove the follow relationship");
            bob.sendMessage("dm:" + aliceId + ":" + bobId, "blocked direct message");
            waitFor(() -> "403".equals(bobClient.model().errorCode()),
                    "Blocked user was able to send a direct message");
            alice.reportObject("profile", bobId, "Harassment 🌋");
            waitFor(() -> storage.listReports().size() == 1,
                    "A report was not stored after the TLS command");
            assertEquals("Harassment 🌋", storage.listReports().get(0).reason(),
                    "Report reason changed during protocol handling");
        } finally {
            if (alice != null) alice.close();
            if (bob != null) bob.close();
            server.close();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void testStories() throws Exception {
        Path config = Path.of("config");
        Path temporary = Files.createTempDirectory("ember-stories");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                storage, new SecurityAuditLogger(temporary.resolve("audit.json")),
                temporary.resolve("media"), 2);
        ClientController alice = null;
        ClientController bob = null;
        try {
            server.start();
            Path trust = config.resolve("ember-client-trust.p12");
            alice = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-a"));
            bob = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-b"));
            CountDownLatch authenticated = new CountDownLatch(2);
            CountDownLatch uploaded = new CountDownLatch(1);
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MEDIA_UPLOADED")) uploaded.countDown();
            });
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MEDIA_UPLOADED")) uploaded.countDown();
            });
            alice.register("storyalice", "alice-pass-42".toCharArray(), "Story Alice");
            bob.register("storybob", "bob-pass-42".toCharArray(), "Story Bob");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Story clients did not authenticate");
            ClientController bobClient = bob;
            alice.uploadMedia(paddedJpeg(2048), "story.jpg");
            assertTrue(uploaded.await(20, TimeUnit.SECONDS), "Story media upload did not finish");
            String mediaId = alice.model().lastUploadedMediaId();
            alice.createStory(mediaId, "Temporary campus moment 🌋");
            waitFor(() -> storage.listStories().size() == 1,
                    "Story record was not persisted");
            bob.requestStories();
            waitFor(() -> bobClientStories(bobClient).size() == 1,
                    "Public story was not delivered to another TLS client");
            String storyMedia = storage.findStory(storage.listStories().get(0).storyId())
                    .orElseThrow().mediaId();
            CountDownLatch storyImage = new CountDownLatch(1);
            bob.addListener((model, event, message) -> {
                if (event.equals("MEDIA") && model.mediaBytes(storyMedia) != null) storyImage.countDown();
            });
            bob.requestMedia(storyMedia, false);
            assertTrue(storyImage.await(20, TimeUnit.SECONDS), "Story image was not retrievable");
            Thread.sleep(2_100);
            bob.requestStories();
            waitFor(() -> bobClientStories(bobClient).isEmpty(),
                    "Story remained visible after configured TTL");
        } finally {
            if (alice != null) alice.close();
            if (bob != null) bob.close();
            server.close();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private List<edu.ember.common.model.StoryItem> bobClientStories(ClientController client) {
        return client.model().stories();
    }

    private boolean clientHasMessage(ClientController client, String id, String body) {
        return client.model().conversationIds().stream().flatMap(conversation ->
                client.model().messages(conversation).stream()).anyMatch(message ->
                        message.clientMsgId().equals(id) && message.body().equals(body));
    }

    private void testAdminConsoleProtocol() throws Exception {
        Path config = Path.of("config");
        Path temporary = Files.createTempDirectory("ember-admin-console");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                storage, new SecurityAuditLogger(temporary.resolve("audit.json")),
                temporary.resolve("media"), 86_400, java.util.Set.of("paneladmin"));
        ClientController admin = null;
        ClientController member = null;
        try {
            server.start();
            Path trust = config.resolve("ember-client-trust.p12");
            admin = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-admin"));
            member = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-member"));
            CountDownLatch authenticated = new CountDownLatch(2);
            CountDownLatch forbidden = new CountDownLatch(1);
            member.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("ERROR") && "403".equals(model.errorCode())) forbidden.countDown();
            });
            admin.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
            });
            admin.register("paneladmin", "admin-pass-42".toCharArray(), "Admin");
            member.register("panelmember", "member-pass-42".toCharArray(), "Member");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Admin clients did not authenticate");
            ClientController adminClient = admin;
            assertTrue(admin.model().administrator(), "Configured administrator role was not assigned");
            assertTrue(!member.model().administrator(), "Regular member received administrator role");
            member.requestAdminData();
            assertTrue(forbidden.await(10, TimeUnit.SECONDS), "Regular member accessed admin queue");
            admin.reportObject("profile", member.model().userId(), "Needs review");
            waitFor(() -> storage.listReports().size() == 1, "Moderation report was not recorded");
            admin.requestAdminData();
            waitFor(() -> adminClient.model().moderationReports().size() == 1,
                    "Admin queue did not return the moderation report");
            admin.unlockUser("panelmember");
            Thread.sleep(100);
            assertTrue(admin.model().errorCode() == null,
                    "Administrator unlock action was rejected");
        } finally {
            if (admin != null) admin.close();
            if (member != null) member.close();
            server.close();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void waitFor(java.util.function.BooleanSupplier condition, String failure)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError(failure);
    }

    private byte[] pngHeader(int width, int height) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(33);
        header.put(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        header.putInt(13).put(new byte[] {0x49, 0x48, 0x44, 0x52});
        header.putInt(width).putInt(height).put(new byte[] {8, 2, 0, 0, 0});
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(header.array(), 12, 17);
        header.putInt((int) crc.getValue());
        return header.array();
    }

    private byte[] paddedJpeg(int targetBytes) throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 48,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.ORANGE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", jpeg);
        byte[] result = new byte[targetBytes];
        byte[] encoded = jpeg.toByteArray();
        System.arraycopy(encoded, 0, result, 0, encoded.length);
        return result;
    }

    private void testExpiringMessages() throws Exception {
        Path config = Path.of("config");
        Path temporary = Files.createTempDirectory("ember-expiring-message");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                new InMemoryStorageEngine(), new SecurityAuditLogger(
                        temporary.resolve("audit.json")), temporary.resolve("media"));
        ClientController alice = null;
        ClientController bob = null;
        try {
            server.start();
            alice = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password,
                    temporary.resolve("client-a"));
            bob = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password,
                    temporary.resolve("client-b"));
            CountDownLatch authenticated = new CountDownLatch(2);
            CountDownLatch expired = new CountDownLatch(2);
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MESSAGE_EXPIRED")) expired.countDown();
            });
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MESSAGE_EXPIRED")) expired.countDown();
            });
            alice.register("fadealice", "alice-pass-42".toCharArray(), "Fade Alice");
            bob.register("fadebob", "bob-pass-42".toCharArray(), "Fade Bob");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Expiry clients did not authenticate");
            String id = alice.sendMessage("dm:fadealice:fadebob", "This disappears 🌋", 15);
            assertTrue(expired.await(25, TimeUnit.SECONDS), "15-second message did not expire on both clients");
            assertTrue(alice.model().messages("dm:fadealice:fadebob").stream()
                    .noneMatch(message -> message.clientMsgId().equals(id)),
                    "Expired message remained in the sender model");
            assertTrue(bob.model().messages("dm:fadealice:fadebob").stream()
                    .noneMatch(message -> message.clientMsgId().equals(id)),
                    "Expired message remained in the recipient model");
        } finally {
            if (alice != null) alice.close();
            if (bob != null) bob.close();
            server.close();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void testRepliesAndReactions() throws Exception {
        Path config = Path.of("config");
        Path temporary = Files.createTempDirectory("ember-replies-reactions");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                new InMemoryStorageEngine(), new SecurityAuditLogger(
                        temporary.resolve("audit.json")), temporary.resolve("media"));
        ClientController alice = null;
        ClientController bob = null;
        ClientController charlie = null;
        try {
            server.start();
            Path trust = config.resolve("ember-client-trust.p12");
            alice = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-a"));
            bob = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-b"));
            charlie = new ClientController("127.0.0.1", server.port(), trust, password,
                    temporary.resolve("client-c"));
            CountDownLatch authenticated = new CountDownLatch(3);
            CountDownLatch originalReceived = new CountDownLatch(1);
            CountDownLatch replyReceived = new CountDownLatch(1);
            CountDownLatch reactionReceived = new CountDownLatch(1);
            CountDownLatch editReceived = new CountDownLatch(1);
            CountDownLatch deleteReceived = new CountDownLatch(2);
            CountDownLatch pinnedReceived = new CountDownLatch(2);
            CountDownLatch starredReceived = new CountDownLatch(1);
            CountDownLatch forwardedReceived = new CountDownLatch(1);
            CountDownLatch forbiddenActions = new CountDownLatch(5);
            AtomicReference<String> parentId = new AtomicReference<>();
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MESSAGE") && message != null
                        && message.replyToId().equals(parentId.get())
                        && message.body().equals("Reply 🌋")) replyReceived.countDown();
                if (event.equals("MESSAGE_REACTION") && message != null
                        && message.clientMsgId().equals(parentId.get())
                        && message.reactionCounts().getOrDefault("🔥", 0) == 1) {
                    reactionReceived.countDown();
                }
                if (event.equals("MESSAGE_EDITED") && message != null
                        && message.clientMsgId().equals(parentId.get())
                        && message.body().equals("Edited original 🌋")) editReceived.countDown();
                if (event.equals("MESSAGE_DELETED") && message != null
                        && message.clientMsgId().equals(parentId.get())) deleteReceived.countDown();
                if (event.equals("MESSAGE_PINNED") && message != null && message.pinned()) {
                    pinnedReceived.countDown();
                }
            });
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MESSAGE") && message != null
                        && message.body().equals("Original 🌟")) originalReceived.countDown();
                if (event.equals("MESSAGE_EDITED") && message != null
                        && message.clientMsgId().equals(parentId.get())
                        && message.body().equals("Edited original 🌋")) editReceived.countDown();
                if (event.equals("MESSAGE_DELETED") && message != null
                        && message.clientMsgId().equals(parentId.get())) deleteReceived.countDown();
                if (event.equals("MESSAGE_PINNED") && message != null && message.pinned()) {
                    pinnedReceived.countDown();
                }
                if (event.equals("MESSAGE_STARRED") && message != null
                        && message.starredBy(model.userId())) starredReceived.countDown();
                if (event.equals("ERROR") && "403".equals(model.errorCode())) {
                    forbiddenActions.countDown();
                }
            });
            charlie.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) authenticated.countDown();
                if (event.equals("MESSAGE") && message != null
                        && message.conversationId().equals("dm:replyalice:replycharlie")
                        && message.body().equals("Original 🌟")) forwardedReceived.countDown();
                if (event.equals("ERROR") && "403".equals(model.errorCode())) {
                    forbiddenActions.countDown();
                }
            });
            alice.register("replyalice", "alice-pass-42".toCharArray(), "Reply Alice");
            bob.register("replybob", "bob-pass-42".toCharArray(), "Reply Bob");
            charlie.register("replycharlie", "charlie-pass-42".toCharArray(), "Reply Charlie");
            assertTrue(authenticated.await(30, TimeUnit.SECONDS), "Reply clients did not authenticate");
            ClientController aliceClient = alice;
            String originalId = alice.sendMessage("dm:replyalice:replybob", "Original 🌟");
            parentId.set(originalId);
            assertTrue(originalReceived.await(20, TimeUnit.SECONDS), "Original message was not delivered");
            bob.sendReply("dm:replyalice:replybob", "Reply 🌋", 0, originalId);
            assertTrue(replyReceived.await(20, TimeUnit.SECONDS), "Reply did not reach Alice");
            Message reply = alice.model().messages("dm:replyalice:replybob").stream()
                    .filter(message -> message.body().equals("Reply 🌋")).findFirst().orElseThrow();
            assertEquals(originalId, reply.replyToId(), "Reply target ID changed");
            assertEquals("Original 🌟", reply.replyPreview(), "Reply quote changed");
            String forwardedId = alice.forwardMessage(originalId, "dm:replyalice:replycharlie");
            assertTrue(!forwardedId.equals(originalId), "Forward reused the original message ID");
            assertTrue(forwardedReceived.await(20, TimeUnit.SECONDS),
                    "Forwarded message did not reach the target chat");
            alice.editMessage(forwardedId, "Edited forwarded copy 🌋");
            waitFor(() -> clientHasMessage(aliceClient, forwardedId, "Edited forwarded copy 🌋"),
                    "Forwarded copy could not be edited independently");
            assertEquals("Original 🌟", alice.model().messages("dm:replyalice:replybob").stream()
                    .filter(message -> message.clientMsgId().equals(originalId)).findFirst()
                    .orElseThrow().body(), "Editing the forwarded copy changed the source");
            bob.reactMessage(originalId, "🔥", false);
            assertTrue(reactionReceived.await(20, TimeUnit.SECONDS),
                    "Reaction was not broadcast to the other conversation participant");
            alice.pinMessage(originalId, true);
            assertTrue(pinnedReceived.await(20, TimeUnit.SECONDS), "Pinned state was not broadcast");
            bob.starMessage(originalId, true);
            assertTrue(starredReceived.await(20, TimeUnit.SECONDS), "Starred state was not returned");
            bob.editMessage(originalId, "forged edit");
            bob.deleteMessage(originalId);
            charlie.reactMessage(originalId, "👍", false);
            charlie.pinMessage(originalId, true);
            charlie.starMessage(originalId, true);
            assertTrue(forbiddenActions.await(20, TimeUnit.SECONDS),
                    "Nonparticipant reaction or cross-user edit/delete was allowed");
            alice.editMessage(originalId, "Edited original 🌋");
            assertTrue(editReceived.await(20, TimeUnit.SECONDS), "Edited message was not broadcast");
            alice.deleteMessage(originalId);
            assertTrue(deleteReceived.await(20, TimeUnit.SECONDS), "Deleted message was not removed on both clients");
        } finally {
            if (alice != null) alice.close();
            if (bob != null) bob.close();
            if (charlie != null) charlie.close();
            server.close();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void testDemoSeeder() throws Exception {
        Path root = Path.of("build", "test-data", "demo-seed-" + System.nanoTime());
        assertTrue(DemoSeeder.seed(root), "First demo seed did not populate the store");
        assertFalse(DemoSeeder.seed(root), "Second demo seed was not idempotent");
        try (WalStorageEngine storage = new WalStorageEngine(root, true)) {
            storage.init();
            assertTrue(storage.findUser("alice").isPresent(), "Seeded Alice account is missing");
            assertTrue(storage.findUser("bob").isPresent(), "Seeded Bob account is missing");
            assertTrue(storage.findUser("charlie").isPresent(), "Seeded Charlie account is missing");
            assertEquals(3, storage.findGroup("study-group").orElseThrow().members().size(),
                    "Seeded study group does not contain three members");
            assertTrue(storage.findPost("demo-post", "bob").isPresent(),
                    "Seeded Campus Pulse post is missing");
            assertTrue(storage.findMedia("demo-campus").isPresent(), "Seeded image is missing");
            assertEquals(1, storage.pollsForGroup("study-group").size(),
                    "Seeded study group poll is missing");
            assertEquals(1, storage.listStories().size(), "Seeded story is missing");
            assertTrue(Files.exists(root.resolve("media").resolve("demo-campus.jpg")),
                    "Seeded image file is missing");
        }
    }

    private void collectTextAreas(java.awt.Container container,
            java.util.List<javax.swing.JTextArea> result) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JTextArea area) result.add(area);
            if (child instanceof java.awt.Container nested) collectTextAreas(nested, result);
        }
    }

    private void layoutComponentTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) layoutComponentTree(nested);
        }
    }

    private Thread tlsEchoThread(SSLServerSocket server, AtomicReference<Throwable> failure,
            AtomicReference<String> protocol) {
        return new Thread(() -> {
            try {
                rejectDefaultTrustClient(server);
                echoTrustedClient(server, protocol);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "ember-test-tls-server");
    }

    private void rejectDefaultTrustClient(SSLServerSocket server) throws IOException {
        try (SSLSocket untrusted = (SSLSocket) server.accept()) {
            untrusted.setSoTimeout(30_000);
            untrusted.startHandshake();
            throw new AssertionError("Default JVM trust store accepted Ember's certificate");
        } catch (IOException expected) {
            System.out.println("Verified: default JVM trust store rejected Ember certificate");
        }
    }

    private void echoTrustedClient(SSLServerSocket server, AtomicReference<String> protocol)
            throws Exception {
        try (SSLSocket client = (SSLSocket) server.accept()) {
            client.setSoTimeout(30_000);
            client.startHandshake();
            protocol.set(client.getSession().getProtocol());
            WireFrame frame = FrameCodec.readFrame(client.getInputStream(), 3 * 1024 * 1024);
            FrameCodec.writeFrame(client.getOutputStream(), frame);
        }
    }

    private void verifyDefaultTruststoreRejected(int port) throws Exception {
        boolean rejected = false;
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket("127.0.0.1", port)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(30_000);
            socket.startHandshake();
        } catch (SSLHandshakeException expected) {
            rejected = true;
        }
        assertTrue(rejected, "Default JVM trust store accepted Ember's self-signed certificate");
    }

    private void verifyLargeFrameEcho(Path config, char[] password, int port) throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31);
        }
        WireFrame sent = new WireFrame.Builder(0x10).correlationId(991).payload(payload).build();
        try (SSLSocket client = TlsSocketFactory.createClientSocket(
                config.resolve("ember-client-trust.p12"), password, "127.0.0.1", port)) {
            client.setSoTimeout(30_000);
            client.startHandshake();
            FrameCodec.writeFrame(client.getOutputStream(), sent);
            WireFrame received = FrameCodec.readFrame(client.getInputStream(), 3 * 1024 * 1024);
            assertEquals(sent.opcode(), received.opcode(), "Frame opcode changed in TLS echo");
            assertEquals(sent.correlationId(), received.correlationId(),
                    "Frame correlation ID changed in TLS echo");
            assertTrue(java.util.Arrays.equals(payload, received.payload()),
                    "2 MiB frame payload changed in TLS echo");
        }
    }

    private static char[] readStorePassword(Path path) throws IOException {
        for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("EMBER_KEYSTORE_PASS=")) {
                return line.substring("EMBER_KEYSTORE_PASS=".length()).toCharArray();
            }
        }
        throw new IOException("Keystore password missing from local secrets file");
    }

    private static byte[] headerOnly(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(FrameCodec.MAGIC);
        output.writeByte(FrameCodec.VERSION);
        output.writeByte(0x10);
        output.writeLong(0);
        output.writeInt(length);
        return bytes.toByteArray();
    }

    private void runTest(String name, CheckedTest test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Throwable throwable) {
            failed++;
            System.out.println("FAIL: " + name + " — " + throwable.getMessage());
            throwable.printStackTrace(System.out);
        }
    }

    private void assertThrows(Class<? extends Throwable> type, CheckedTest test) {
        try {
            test.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError("Unexpected exception type: " + throwable, throwable);
        }
        throw new AssertionError("Expected exception was not thrown: " + type.getSimpleName());
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    @FunctionalInterface
    /** Allows test cases to report checked failures to the runner. @concept interfaces, exceptions */
    private interface CheckedTest {
        void run() throws Exception;
    }
}
