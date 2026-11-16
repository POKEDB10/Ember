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
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.PostComment;
import edu.ember.client.ClientController;
import edu.ember.client.ClientStateModel;
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
        runTest("bad frame magic rejected", this::testBadMagic);
        runTest("oversized frames rejected before payload read", this::testFrameLimits);
        runTest("password hashing and dummy verification", this::testPasswordHasher);
        runTest("input validation", this::testInputValidation);
        runTest("TLS 1.3 loopback frame round trip", this::testTlsRoundTrip);
        runTest("headless registration, message, and delivery", this::testWalkingSkeleton);
        runTest("theme palettes, wrapped message bodies, and EDT notifications",
                this::testUiBasics);
        runTest("WAL restart recovers Arabic and emoji records and trims torn tail",
                this::testWalTornTailRecovery);
        runTest("WAL CRC failure truncates invalid tail", this::testWalCrcRecovery);
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
            CountDownLatch bobMemberDenied = new CountDownLatch(1);
            CountDownLatch eveSendDenied = new CountDownLatch(4);
            CountDownLatch eveReceiptDenied = new CountDownLatch(1);
            CountDownLatch senderReceiptDenied = new CountDownLatch(1);
            AtomicReference<Boolean> expectEveReceiptError = new AtomicReference<>(false);
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
            clients.get("alice").changeGroupMember(group, "bob", "ADD");
            clients.get("alice").changeGroupMember(group, "charlie", "ADD");
            clients.get("alice").changeGroupMember(group, "dana", "ADD");
            assertTrue(groupReady.await(10, TimeUnit.SECONDS), "Group did not reach four members");
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

    private void testUiBasics() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            assertEquals(new java.awt.Color(0x0F172A), ThemeManager.current().background(),
                    "Dark theme background token changed");
            assertEquals(new java.awt.Color(0x0F172A), ThemeManager.Theme.LIGHT.text(),
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
        first.saveProfile(new UserProfile("alice", "Alice", "Campus life", ""));
        first.setFollow("bob", "alice", true);
        first.saveMedia(media);
        first.savePost(post);
        first.setPostLike(post.postId(), "bob", true);
        first.addComment(new PostComment("comment-1", post.postId(), "bob", "مرحبا 🌋", 102));
        first.close();
        WalStorageEngine recovered = new WalStorageEngine(directory, true);
        recovered.init();
        assertEquals("Campus life", recovered.findProfile("alice").orElseThrow().bio(),
                "Profile was not restored from WAL");
        assertEquals(List.of("bob"), recovered.followersOf("alice"),
                "Follow relationship was not restored from WAL");
        SocialPost restored = recovered.findPost(post.postId(), "bob").orElseThrow();
        assertTrue(restored.likedByViewer() && restored.likeCount() == 1,
                "Post like was not restored from WAL");
        assertEquals("مرحبا 🌋", recovered.commentsForPost(post.postId(), 10).get(0).text(),
                "Comment text did not survive WAL UTF-8 replay");
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
