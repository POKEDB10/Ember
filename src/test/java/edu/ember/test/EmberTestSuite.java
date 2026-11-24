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
        try {
            deleteTree(Path.of("client_data")); // device keys and known contacts from earlier runs
        } catch (IOException exception) {
            throw new IllegalStateException("Could not clear old client data", exception);
        }
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
        runTest("web-only server mode, health endpoint, and Swing client over WebSocket",
                this::testWebOnlyModeAndDesktopOverWebSocket);
        runTest("compressed WebSocket both ways and cached image download over HTTP",
                this::testCompressedWebSocketAndMediaHttp);
        runTest("revision sync sends only edits, pins, stars, and deletions since the cursor",
                this::testRevisionSync);
        runTest("Web Push: SSRF guard, invisible status, encrypted payload, VAPID signature",
                this::testWebPush);
        runTest("calls: ringing, answer, encrypted relay, busy, block, and origin check", this::testCalls);
        runTest("wideband desktop voice: 64 kbit/s frames decode cleanly", this::testWidebandVoice);
        runTest("calls v2: codec negotiation, shared ADPCM vectors, legacy compatibility", this::testCallsV2Codecs);
        runTest("calls v2: anti-aliasing resampler passband, stopband and length", this::testResampler);
        runTest("calls v2: jitter buffer order, concealment, steady delay, stall recovery", this::testJitterBuffer);
        runTest("calls v2: settings persist, microphone unplug falls back, devices released", this::testCallSettingsAndDevices);
        runTest("calls v2: feature flag reported in AUTH_OK, off by default", this::testCallsV2Flag);
        runTest("calls v2: hand-off is single-use, short-lived, same person only, first commit wins", this::testCallHandoff);
        runTest("calls v2: Speaker mode ducks the mic only while the other person is heard", this::testSpeakerMode);
        runTest("end-to-end chats: tamper, impostor and outsider refused; server sees only ciphertext", this::testEndToEndChat);
        runTest("theme palettes, wrapped message bodies, and EDT notifications",
                this::testUiBasics);
        runTest("WAL restart recovers Arabic and emoji records and trims torn tail",
                this::testWalTornTailRecovery);
        runTest("WAL CRC failure truncates invalid tail", this::testWalCrcRecovery);
        runTest("compressed WAL snapshot compacts and restores later records", this::testWalSnapshotCompaction);
        runTest("WAL restart restores group members and roles", this::testWalGroupRecovery);
        runTest("server restart restores accounts and messages from WAL",
                this::testWalServerRestart);
        runTest("encrypted off-server replica restores a wiped server and rejects a wrong key",
                this::testBlobStoreReplica);
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

    /** In-memory stand-in for rootz.so, so the replica logic is tested without the network. */
    private static final class FakeBlobStore implements edu.ember.server.storage.BlobStore {
        private final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public Map<String, String> list(String prefix) {
            Map<String, String> result = new java.util.HashMap<>();
            names.forEach((id, name) -> { if (name.startsWith(prefix)) { result.put(name, id); } });
            return result;
        }

        @Override public String put(String name, byte[] data) {
            String id = java.util.UUID.randomUUID().toString();
            files.put(id, data.clone());
            names.put(id, name);
            return id;
        }

        @Override public byte[] get(String id) throws IOException {
            byte[] data = files.get(id);
            if (data == null) { throw new IOException("missing " + id); }
            return data.clone();
        }

        @Override public void delete(String id) {
            files.remove(id);
            names.remove(id);
        }
    }

    private void testBlobStoreReplica() throws Exception {
        FakeBlobStore store = new FakeBlobStore();
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
        Path first = Files.createTempDirectory("ember-replica-a");
        WalStorageEngine writer = new WalStorageEngine(first, false,
                new edu.ember.server.storage.BlobStoreReplica(store, key, 50));
        writer.init();
        writer.saveUser(new UserRecord("rep-alice", "rep-alice", "Alice", "hash"));
        writer.appendMessage(new Message("rep-1", "rep-alice", "dm:bob:rep-alice",
                "secret-body before snapshot", System.currentTimeMillis()));
        // Over 1 MB of WAL triggers a snapshot upload when the replica is on.
        writer.appendMessage(new Message("rep-big", "rep-alice", "dm:bob:rep-alice",
                "x".repeat(1_100_000), System.currentTimeMillis()));
        writer.compact();
        writer.appendMessage(new Message("rep-2", "rep-alice", "dm:bob:rep-alice",
                "secret-body after snapshot 🌋", System.currentTimeMillis()));
        writer.mirrorMedia("photo.jpg", new byte[] {1, 2, 3, 4});
        writer.close();
        for (String id : store.names.keySet()) {
            String content = new String(store.get(id), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(content.contains("secret-body"), "Plaintext reached the remote store");
        }
        assertTrue(store.names.values().stream().anyMatch(name -> name.startsWith("ember-snap-1")),
                "Snapshot was not uploaded");
        assertFalse(store.names.values().stream().anyMatch(name -> name.startsWith("ember-log-0-")),
                "Logs covered by the snapshot were not deleted");

        Path fresh = Files.createTempDirectory("ember-replica-b");
        WalStorageEngine reader = new WalStorageEngine(fresh, false,
                new edu.ember.server.storage.BlobStoreReplica(store, key, 50));
        reader.init();
        assertEquals("Alice", reader.findUser("rep-alice").orElseThrow().displayName(),
                "Account was not restored from the replica");
        assertEquals("secret-body before snapshot", reader.findByClientMsgId("rep-1").orElseThrow()
                .body(), "Snapshot message was not restored");
        assertEquals("secret-body after snapshot 🌋", reader.findByClientMsgId("rep-2").orElseThrow()
                .body(), "Post-snapshot message was not restored");
        assertTrue(java.util.Arrays.equals(new byte[] {1, 2, 3, 4},
                reader.fetchMirroredMedia("photo.jpg")), "Media was not restored");
        reader.appendMessage(new Message("rep-3", "rep-alice", "dm:bob:rep-alice",
                "third", System.currentTimeMillis()));
        reader.close();

        WalStorageEngine again = new WalStorageEngine(Files.createTempDirectory("ember-replica-c"),
                false, new edu.ember.server.storage.BlobStoreReplica(store, key, 50));
        again.init();
        assertEquals(4, again.getHistory("dm:bob:rep-alice", Long.MAX_VALUE, 100).size(),
                "Second restart lost messages written after the first restore");
        again.close();

        byte[] otherKey = keyBytes.clone();
        otherKey[0] ^= 1;
        WalStorageEngine wrongKey = new WalStorageEngine(Files.createTempDirectory("ember-replica-d"),
                false, new edu.ember.server.storage.BlobStoreReplica(store,
                        new javax.crypto.spec.SecretKeySpec(otherKey, "AES"), 50));
        assertThrows(IOException.class, wrongKey::init);
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
                String stored = history.get(0).body();
                // Stored end-to-end encrypted when both devices had keys; never readable otherwise.
                assertTrue(stored.equals("persist me 🌋") || stored.startsWith("e2e1."),
                        "WAL message was lost on server restart: " + stored);
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
            // The latch counts any two members; Alice's own copy may still be in flight.
            waitFor(() -> !clients.get("alice").model().polls(group).isEmpty(),
                    "Alice did not receive her poll");
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
            alice.follow("bob", true); // presence is shared only between contacts
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
            Path devices = Files.createTempDirectory("ember-two-devices"); // no keys left by other tests
            laptop = new ClientController("127.0.0.1", server.port(), trust, password, devices);
            phone = new ClientController("127.0.0.1", server.port(), trust, password, devices);
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
                        "Sender's other device did not receive the sent message: laptop="
                                + laptop.model().errorMessage() + " phone=" + phone.model().errorMessage());
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

    /** Render-style deployment: no TLS listener, plain HTTP behind the host's HTTPS proxy. */
    private void testWebOnlyModeAndDesktopOverWebSocket() throws Exception {
        Path temporary = Files.createTempDirectory("ember-web-only");
        EmberServer server = new EmberServer(null, null, 0, new InMemoryStorageEngine(),
                new SecurityAuditLogger(temporary.resolve("audit.json")), temporary.resolve("media"),
                86_400, java.util.Set.of(), "127.0.0.1");
        ClientController desktop = null;
        try {
            server.start();
            int httpPort = server.startHttp(0);
            assertEquals(-1, server.port(), "Web-only mode still opened a TLS port");
            try (java.net.Socket probe = new java.net.Socket("127.0.0.1", httpPort)) {
                probe.setSoTimeout(10_000);
                probe.getOutputStream().write("GET /health HTTP/1.1\r\nHost: x\r\n\r\n"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                String reply = new String(probe.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.US_ASCII);
                assertTrue(reply.startsWith("HTTP/1.1 200") && reply.endsWith("ok"),
                        "Health endpoint did not answer: " + reply);
            }
            desktop = new ClientController(
                    edu.ember.client.ServerLink.webSocket("http://127.0.0.1:" + httpPort),
                    temporary.resolve("client"));
            CountDownLatch auth = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1);
            desktop.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) { auth.countDown(); }
                if (event.equals("MESSAGE_STATUS") && message != null
                        && message.status() == MessageStatus.SENT) { sent.countDown(); }
            });
            desktop.register("carol", "carol-pass-42".toCharArray(), "Carol");
            assertTrue(auth.await(30, TimeUnit.SECONDS), "Swing client could not log in over WebSocket");
            desktop.sendMessage("dm:carol:dave", "hello over websocket");
            assertTrue(sent.await(20, TimeUnit.SECONDS), "Swing client message was not acknowledged: " + desktop.model().errorMessage());
            assertTrue(desktop.transport().startsWith("WebSocket"), "Transport label is wrong");
        } finally {
            if (desktop != null) { desktop.close(); }
            server.close();
        }
    }

    /** A caching device asks only for changes; edits, pins, stars and deletions all arrive. */
    private void testRevisionSync() throws Exception {
        Path directory = Files.createTempDirectory("ember-revisions");
        WalStorageEngine first = new WalStorageEngine(directory, false);
        first.init();
        first.appendMessage(new Message("rv-1", "alice", "dm:alice:bob", "one", System.currentTimeMillis()));
        first.appendMessage(new Message("rv-2", "alice", "dm:alice:bob", "two", System.currentTimeMillis()));
        first.appendMessage(new Message("rv-3", "carol", "dm:carol:dave", "not bob's", System.currentTimeMillis()));
        long cursor = first.currentRevision();
        assertTrue(first.getChangesSince("bob", cursor).isEmpty(), "Nothing changed, yet changes came back");
        first.editMessage("rv-1", "one, edited");
        first.setMessagePinned("rv-1", true);
        first.setMessageStarred("rv-1", "bob", true);
        first.deleteMessage("rv-2");
        Map<String, Map<String, String>> changes = new java.util.HashMap<>();
        for (WireFrame frame : first.getChangesSince("bob", cursor)) {
            Map<String, String> fields = KvPayloadCodec.decode(frame.payload());
            changes.put(fields.get("clientMsgId"), fields);
        }
        assertEquals(java.util.Set.of("rv-1", "rv-2"), changes.keySet(), "Wrong change set (or another chat leaked)");
        assertEquals("one, edited", changes.get("rv-1").get("body"), "Edit missing from changes");
        assertEquals("true", changes.get("rv-1").get("pinned"), "Pin missing from changes");
        assertEquals("true", changes.get("rv-1").get("starred"), "Bob's star missing from changes");
        assertEquals("true", changes.get("rv-2").get("deleted"), "Deletion marker missing");
        long revision = first.currentRevision();
        first.close();
        WalStorageEngine replayed = new WalStorageEngine(directory, false);
        replayed.init();
        assertEquals(revision, replayed.currentRevision(), "Revisions changed after a restart");
        assertTrue(replayed.getChangesSince("bob", revision).isEmpty(), "Restart re-sent old changes");
        replayed.close();
    }

    /** The test plays a browser push service: it decrypts what the server sends (RFC 8291). */
    private void testWebPush() throws Exception {
        InMemoryStorageEngine subscriptions = new InMemoryStorageEngine();
        var shared = new edu.ember.common.model.PushSubscription("alice", "https://fcm.googleapis.com/x", "k", "a");
        subscriptions.savePushSubscription(shared, true);
        subscriptions.savePushSubscription(new edu.ember.common.model.PushSubscription(
                "bob", shared.endpoint(), "k", "a"), true);
        assertTrue(subscriptions.pushSubscriptions("alice").isEmpty(),
                "A browser endpoint stayed bound to the previous user");
        for (int index = 0; index < 12; index++) {
            subscriptions.savePushSubscription(new edu.ember.common.model.PushSubscription(
                    "carol", "https://fcm.googleapis.com/c" + index, "k", "a"), true);
        }
        assertEquals(10, subscriptions.pushSubscriptions("carol").size(), "Subscriptions were not capped");
        Path temporary = Files.createTempDirectory("ember-push");
        EmberServer server = new EmberServer(null, null, 0, new InMemoryStorageEngine(),
                new SecurityAuditLogger(temporary.resolve("audit.json")), temporary.resolve("media"),
                86_400, java.util.Set.of(), "127.0.0.1");
        java.util.concurrent.LinkedBlockingQueue<Object[]> pushes = new java.util.concurrent.LinkedBlockingQueue<>();
        com.sun.net.httpserver.HttpServer pushService = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        pushService.createContext("/push", exchange -> {
            pushes.add(new Object[] {exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Encoding"),
                    exchange.getRequestBody().readAllBytes()});
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        pushService.start();
        try {
            server.start();
            int port = server.startHttp(0);
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair browser = generator.generateKeyPair();
            byte[] authSecret = new byte[16];
            new java.security.SecureRandom().nextBytes(authSecret);
            var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
            Map<String, String> subscription = Map.of("endpoint",
                    "http://127.0.0.1:" + pushService.getAddress().getPort() + "/push/pam",
                    "p256dh", b64.encodeToString(rawPoint((java.security.interfaces.ECPublicKey) browser.getPublic())),
                    "auth", b64.encodeToString(authSecret));
            String vapidKey;
            java.net.Socket pam = webSocket(port); // closed mid-test to take pam offline
            try (java.net.Socket quin = webSocket(port)) {
                var pamIn = new java.io.BufferedInputStream(pam.getInputStream());
                var quinIn = new java.io.BufferedInputStream(quin.getInputStream());
                wsSend(pam.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "pam", "pass", "pam-pass-42", "displayName", "Pam")));
                vapidKey = KvPayloadCodec.decode(wsRead(pamIn, WireOpcodes.EVT_AUTH_OK).payload()).get("vapidKey");
                wsSend(pam.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_PUSH_SUBSCRIBE, 2, subscription));
                wsRead(pamIn, WireOpcodes.EVT_ERROR); // plain http to a local address: refused (SSRF guard)
                server.setPushEndpointFilter(uri -> true);
                wsSend(pam.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_PUSH_SUBSCRIBE, 3, subscription));
                wsRead(pamIn, WireOpcodes.EVT_NOTIFY);
                wsSend(quin.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "quin", "pass", "quin-pass-42", "displayName", "Quin")));
                wsRead(quinIn, WireOpcodes.EVT_AUTH_OK);
                wsSend(quin.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_MSG_SEND, 2, Map.of(
                        "clientMsgId", "push-0", "convId", "dm:pam:quin", "body", "pam is online, no push")));
                wsRead(quinIn, WireOpcodes.EVT_MSG_ACK);
                assertTrue(pushes.poll(1, TimeUnit.SECONDS) == null, "Pushed although a device was connected");
                wsSend(pam.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_STATUS_SET, 4,
                        Map.of("status", "invisible")));
                Map<String, String> seen;
                do {
                    seen = KvPayloadCodec.decode(wsRead(quinIn, WireOpcodes.EVT_PRESENCE_UPDATE).payload());
                } while (!"pam".equals(seen.get("userId")) || !"offline".equals(seen.get("status")));
                assertEquals("false", seen.get("online"), "Invisible user still looked online");
                pam.close();
                Thread.sleep(300); // let the server notice pam's device left
                wsSend(quin.getOutputStream(), FrameFactory.command(WireOpcodes.CMD_MSG_SEND, 3, Map.of(
                        "clientMsgId", "push-1", "convId", "dm:pam:quin", "body", "push hello 🌋")));
                wsRead(quinIn, WireOpcodes.EVT_MSG_ACK);
                Object[] push = pushes.poll(10, TimeUnit.SECONDS);
                assertTrue(push != null, "No push was delivered to the offline user");
                assertEquals("aes128gcm", push[1], "Wrong push content encoding");
                String json = new String(decryptPush((byte[]) push[2], browser, authSecret),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(json.contains("push hello 🌋") && json.contains("\"title\":\"Quin\"")
                        && json.contains("dm:pam:quin"), "Push payload is wrong: " + json);
                verifyVapid((String) push[0], vapidKey);
            } finally {
                pam.close();
            }
        } finally {
            pushService.stop(0);
            server.close();
        }
    }

    private static java.net.Socket webSocket(int port) throws IOException {
        java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
        socket.setSoTimeout(20_000);
        socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var in = socket.getInputStream();
        int matched = 0;
        while (matched < 4) { // skip the 101 response headers byte by byte (no read-ahead)
            int value = in.read();
            if (value < 0) { throw new IOException("Upgrade failed"); }
            matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : (value == '\r' ? 1 : 0);
        }
        return socket;
    }

    private static byte[] rawPoint(java.security.interfaces.ECPublicKey key) {
        byte[] result = new byte[65];
        result[0] = 4;
        copyFixed(key.getW().getAffineX(), result, 1);
        copyFixed(key.getW().getAffineY(), result, 33);
        return result;
    }

    private static void copyFixed(java.math.BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int copy = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - copy, target, offset + 32 - copy, copy);
    }

    /** What the browser does with a push: ECDH + HKDF (RFC 8291), then AES-128-GCM. */
    private static byte[] decryptPush(byte[] body, java.security.KeyPair browser, byte[] authSecret)
            throws Exception {
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        header.get(salt);
        header.getInt(); // record size
        byte[] serverKey = new byte[header.get()];
        header.get(serverKey);
        var curve = ((java.security.interfaces.ECPublicKey) browser.getPublic()).getParams();
        var point = new java.security.spec.ECPoint(new java.math.BigInteger(1, java.util.Arrays.copyOfRange(serverKey, 1, 33)),
                new java.math.BigInteger(1, java.util.Arrays.copyOfRange(serverKey, 33, 65)));
        var agreement = javax.crypto.KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(java.security.KeyFactory.getInstance("EC").generatePublic(
                new java.security.spec.ECPublicKeySpec(point, curve)), true);
        byte[] browserKey = rawPoint((java.security.interfaces.ECPublicKey) browser.getPublic());
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write("WebPush: info\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        info.write(browserKey);
        info.write(serverKey);
        info.write(1);
        byte[] ikm = java.util.Arrays.copyOf(hmac(hmac(authSecret, agreement.generateSecret()), info.toByteArray()), 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = java.util.Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII)), 16);
        byte[] nonce = java.util.Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII)), 12);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(cek, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(body, header.position(), body.length - header.position());
        assertEquals(2, (int) plain[plain.length - 1], "Missing last-record delimiter");
        return java.util.Arrays.copyOf(plain, plain.length - 1);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** The push service checks this: an ES256 JWT signed by the server's VAPID key. */
    private static void verifyVapid(String authorization, String vapidKey) throws Exception {
        assertTrue(authorization.startsWith("vapid t=") && authorization.endsWith(", k=" + vapidKey),
                "Bad VAPID header: " + authorization);
        String[] jwt = authorization.substring(8, authorization.indexOf(',')).split("\\.");
        byte[] key = java.util.Base64.getUrlDecoder().decode(vapidKey);
        var curve = java.security.AlgorithmParameters.getInstance("EC");
        curve.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var point = new java.security.spec.ECPoint(new java.math.BigInteger(1, java.util.Arrays.copyOfRange(key, 1, 33)),
                new java.math.BigInteger(1, java.util.Arrays.copyOfRange(key, 33, 65)));
        var verifier = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.ECPublicKeySpec(
                point, curve.getParameterSpec(java.security.spec.ECParameterSpec.class))));
        verifier.update((jwt[0] + "." + jwt[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(java.util.Base64.getUrlDecoder().decode(jwt[2])), "VAPID signature invalid");
        String claims = new String(java.util.Base64.getUrlDecoder().decode(jwt[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"http://127.0.0.1:"), "VAPID audience wrong: " + claims);
    }

    /** Browser-like client: compressed WebSocket both ways, then an image fetched over HTTP. */
    private void testCompressedWebSocketAndMediaHttp() throws Exception {
        Path temporary = Files.createTempDirectory("ember-deflate-media");
        EmberServer server = new EmberServer(null, null, 0, new InMemoryStorageEngine(),
                new SecurityAuditLogger(temporary.resolve("audit.json")), temporary.resolve("media"),
                86_400, java.util.Set.of(), "127.0.0.1");
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(6, true);
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try {
            server.start();
            int port = server.startHttp(0);
            String token;
            String mediaId;
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                socket.setSoTimeout(20_000);
                var in = new java.io.BufferedInputStream(socket.getInputStream());
                var out = socket.getOutputStream();
                out.write(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                StringBuilder response = new StringBuilder();
                while (!response.toString().endsWith("\r\n\r\n")) {
                    response.append((char) in.read());
                }
                assertTrue(response.toString().contains("permessage-deflate"),
                        "Server did not accept WebSocket compression");
                AtomicBoolean sawCompressed = new AtomicBoolean();
                wsSendCompressed(out, FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "erin", "pass", "erin-pass-42", "displayName", "Erin")), deflater);
                token = KvPayloadCodec.decode(wsReadInflating(in, WireOpcodes.EVT_AUTH_OK, inflater,
                        sawCompressed).payload()).get("token");
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(40, 30,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "jpeg", jpeg);
                wsSendCompressed(out, FrameFactory.command(WireOpcodes.CMD_MEDIA_UPLOAD, 2,
                        Map.of("fileName", "photo.jpg",
                                "data", java.util.Base64.getEncoder().encodeToString(jpeg.toByteArray()))),
                        deflater);
                mediaId = KvPayloadCodec.decode(wsReadInflating(in, WireOpcodes.EVT_MEDIA_OK, inflater,
                        sawCompressed).payload()).get("mediaId");
                wsSendCompressed(out, FrameFactory.command(WireOpcodes.CMD_POST_CREATE, 3,
                        Map.of("mediaId", mediaId, "caption", "compressed hello")), deflater);
                wsReadInflating(in, WireOpcodes.EVT_FEED_PAGE, inflater, sawCompressed);
                assertTrue(sawCompressed.get(), "No server message arrived compressed");
            }
            String withToken = httpGet(port, "/media/" + mediaId, "Bearer " + token);
            assertTrue(withToken.startsWith("HTTP/1.1 200") && withToken.contains("image/jpeg")
                    && withToken.contains("immutable"), "Image was not served with caching headers");
            assertTrue(httpGet(port, "/media/" + mediaId, "").startsWith("HTTP/1.1 404"),
                    "Image was served without a session token");
        } finally {
            deflater.end();
            inflater.end();
            server.close();
        }
    }

    private static String httpGet(int port, String path, String authorization) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\n"
                    + (authorization.isEmpty() ? "" : "Authorization: " + authorization + "\r\n")
                    + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    /** Sends a compressed, masked frame the way a browser does with permessage-deflate. */
    private static void wsSendCompressed(java.io.OutputStream out, WireFrame frame,
            java.util.zip.Deflater deflater) throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        FrameCodec.writeFrame(plain, frame);
        deflater.setInput(plain.toByteArray());
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        byte[] chunk = new byte[65_536];
        int count;
        do {
            count = deflater.deflate(chunk, 0, chunk.length, java.util.zip.Deflater.SYNC_FLUSH);
            packed.write(chunk, 0, count);
        } while (count == chunk.length);
        byte[] payload = java.util.Arrays.copyOf(packed.toByteArray(), packed.size() - 4);
        byte[] mask = {0x21, 0x32, 0x43, 0x54};
        ByteArrayOutputStream ws = new ByteArrayOutputStream();
        ws.write(0xC2);
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

    private static WireFrame wsReadInflating(java.io.InputStream in, int opcode,
            java.util.zip.Inflater inflater, AtomicBoolean sawCompressed) throws Exception {
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
            byte[] payload = in.readNBytes((int) length);
            if ((first & 0x40) != 0) {
                sawCompressed.set(true);
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                joined.write(payload);
                joined.write(new byte[] {0, 0, (byte) 0xFF, (byte) 0xFF});
                inflater.setInput(joined.toByteArray());
                ByteArrayOutputStream plain = new ByteArrayOutputStream();
                byte[] chunk = new byte[65_536];
                while (!inflater.needsInput()) {
                    plain.write(chunk, 0, inflater.inflate(chunk));
                }
                payload = plain.toByteArray();
            }
            WireFrame frame = FrameCodec.readFrame(new ByteArrayInputStream(payload), Integer.MAX_VALUE);
            if (frame.opcode() == WireOpcodes.EVT_ERROR) {
                throw new AssertionError("Server error: " + KvPayloadCodec.decode(frame.payload()));
            }
            if (frame.opcode() == opcode) {
                return frame;
            }
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

    /** A browser-like WebSocket client for the call tests. */
    private record WsClient(SSLSocket socket, java.io.InputStream in, java.io.OutputStream out) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static WsClient wsOpen(Path trust, char[] password, int port, String origin) throws Exception {
        SSLSocket socket = TlsSocketFactory.createClientSocket(trust, password, "127.0.0.1", port);
        socket.setSoTimeout(20_000);
        var in = new java.io.BufferedInputStream(socket.getInputStream());
        var out = socket.getOutputStream();
        out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + (origin == null ? "" : "Origin: " + origin + "\r\n")
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        StringBuilder response = new StringBuilder();
        while (!response.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            response.append((char) next);
        }
        if (!response.toString().startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IOException(response.toString().split("\r\n")[0]);
        }
        return new WsClient(socket, in, out);
    }

    private static Map<String, String> wsFields(WsClient client, int opcode) throws Exception {
        return KvPayloadCodec.decode(wsRead(client.in(), opcode).payload());
    }

    /** Reads call events until one of this kind (other devices of the same person also hear rings and ends). */
    private static Map<String, String> wsCallKind(WsClient client, String kind) throws Exception {
        while (true) {
            Map<String, String> fields = wsFields(client, WireOpcodes.EVT_CALL);
            if (kind.equals(fields.get("kind"))) {
                return fields;
            }
        }
    }

    /** What the server relays: [sender length][sender][the packet unchanged]. */
    private static byte[] stamp(String sender, byte[] packet) {
        byte[] name = sender.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[1 + name.length + packet.length];
        out[0] = (byte) name.length;
        System.arraycopy(name, 0, out, 1, name.length);
        System.arraycopy(packet, 0, out, 1 + name.length, packet.length);
        return out;
    }

    private static void callCommand(WsClient client, long corr, Map<String, String> fields) throws IOException {
        wsSend(client.out(), FrameFactory.command(WireOpcodes.CMD_CALL, corr, fields));
    }

    /** Ringing, answering, encrypted-packet relay, busy, block, capability and origin rules for calls. */
    private void testCalls() throws Exception {
        assertTrue(edu.ember.server.WebGateway.sameOrigin(null, "x"), "No Origin (desktop app) must be allowed");
        assertTrue(edu.ember.server.WebGateway.sameOrigin("https://ember.pokedb.site", "ember.pokedb.site"),
                "Same-site Origin was refused");
        assertTrue(!edu.ember.server.WebGateway.sameOrigin("https://evil.example", "ember.pokedb.site"),
                "Another site's Origin was accepted");
        TokenBucket bytes = new TokenBucket(100, 0);
        assertTrue(bytes.tryConsume(60) && !bytes.tryConsume(60), "Byte bucket did not limit");

        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path trust = config.resolve("ember-client-trust.p12");
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0);
        try {
            server.start();
            int port = server.port();
            boolean refused = false;
            try (WsClient evil = wsOpen(trust, password, port, "https://evil.example")) {
                evil.socket();
            } catch (IOException expected) {
                refused = expected.getMessage().contains("403");
            }
            assertTrue(refused, "A WebSocket from another website's page was accepted");
            try (WsClient alice = wsOpen(trust, password, port, "https://127.0.0.1:" + port);
                    WsClient bob = wsOpen(trust, password, port, null);
                    WsClient carol = wsOpen(trust, password, port, null)) {
                wsSend(alice.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "alice", "pass", "alice-pass-42", "displayName", "Alice", "calls", "1")));
                assertTrue(wsFields(alice, WireOpcodes.EVT_AUTH_OK).get("ice").contains("stun:"),
                        "Browsers were not given STUN servers");
                wsSend(bob.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "bob", "pass", "bob-pass-42", "displayName", "Bob", "calls", "1")));
                wsRead(bob.in(), WireOpcodes.EVT_AUTH_OK);
                // Carol's client says nothing about calls, like the Swing app.
                wsSend(carol.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "carol", "pass", "carol-pass-42", "displayName", "Carol")));
                wsRead(carol.in(), WireOpcodes.EVT_AUTH_OK);

                callCommand(alice, 3, Map.of("kind", "ring", "convId", "dm:alice:carol", "video", "false"));
                // Unreachable looks exactly like ringing, so calls never reveal hidden presence.
                Map<String, String> phantom = wsFields(alice, WireOpcodes.EVT_CALL);
                assertEquals("ringing", phantom.get("kind"), "An unreachable callee was revealed to the caller");
                // A phantom call answers every later action exactly like an unanswered real call.
                callCommand(alice, 29, Map.of("kind", "sdp", "callId", phantom.get("callId"), "to", "carol", "data", "x"));
                assertEquals("400", wsFields(alice, WireOpcodes.EVT_ERROR).get("code"),
                        "Signalling a phantom call differed from an unanswered call");
                callCommand(alice, 28, Map.of("kind", "ring", "convId", "dm:alice:bob"));
                assertEquals("400", wsFields(alice, WireOpcodes.EVT_ERROR).get("code"),
                        "A caller with a phantom call in progress was not treated as in a call");
                callCommand(alice, 30, Map.of("kind", "end", "callId", phantom.get("callId")));

                callCommand(alice, 4, Map.of("kind", "ring", "convId", "dm:alice:bob", "video", "true"));
                Map<String, String> ringing = wsFields(alice, WireOpcodes.EVT_CALL);
                assertEquals("ringing", ringing.get("kind"), "Caller did not hear ringing");
                String callId = ringing.get("callId");
                Map<String, String> ring = wsFields(bob, WireOpcodes.EVT_CALL);
                assertEquals("ring", ring.get("kind"), "Callee was not rung");
                assertEquals(callId, ring.get("callId"), "Ring carried another call id");
                assertEquals("true", ring.get("video"), "Video flag was lost");

                callCommand(carol, 2, Map.of("kind", "sdp", "callId", callId, "to", "alice", "data", "x"));
                assertEquals("404", wsFields(carol, WireOpcodes.EVT_ERROR).get("code"),
                        "An outsider could signal into someone else's call");
                callCommand(carol, 3, Map.of("kind", "ring", "convId", "dm:bob:carol"));
                assertEquals("ringing", wsFields(carol, WireOpcodes.EVT_CALL).get("kind"),
                        "A busy callee was revealed to the caller");
                callCommand(alice, 31, Map.of("kind", "ring", "convId", "dm:alice:carol"));
                assertEquals("400", wsFields(alice, WireOpcodes.EVT_ERROR).get("code"),
                        "A caller already in a call could start a second one");

                callCommand(bob, 2, Map.of("kind", "accept", "callId", callId));
                Map<String, String> roster = wsFields(bob, WireOpcodes.EVT_CALL);
                assertEquals("roster", roster.get("kind"), "The person answering did not get the list of people");
                assertEquals("alice", roster.get("user.0"), "The roster did not name the caller");
                Map<String, String> joined = wsFields(alice, WireOpcodes.EVT_CALL);
                assertEquals("joined", joined.get("kind"), "Caller missed the answer");
                assertEquals("bob", joined.get("userId"), "Caller was told the wrong person joined");
                callCommand(alice, 5, Map.of("kind", "key", "callId", callId, "to", "bob", "data", "PUBLICKEY"));
                Map<String, String> key = wsFields(bob, WireOpcodes.EVT_CALL);
                assertEquals("PUBLICKEY", key.get("data"), "Key exchange was not relayed unchanged");
                assertEquals("alice", key.get("from"), "A signal did not say who sent it");

                byte[] packet = new byte[1200];
                new java.security.SecureRandom().nextBytes(packet);
                wsSend(alice.out(), new WireFrame.Builder(WireOpcodes.CMD_CALL_MEDIA).payload(packet).build());
                assertTrue(java.util.Arrays.equals(stamp("alice", packet), wsRead(bob.in(), WireOpcodes.EVT_CALL_MEDIA).payload()),
                        "Encrypted call packet was not forwarded byte for byte with its sender");

                callCommand(bob, 3, Map.of("kind", "end", "callId", callId));
                assertEquals("ended", wsFields(alice, WireOpcodes.EVT_CALL).get("reason"), "Hang-up did not reach caller");
                callCommand(alice, 6, Map.of("kind", "key", "callId", callId, "to", "bob", "data", "late"));
                assertEquals("404", wsFields(alice, WireOpcodes.EVT_ERROR).get("code"), "A finished call accepted signals");

                // Group call: everyone who joins hears everyone else; leaving keeps the call going.
                try (WsClient dave = wsOpen(trust, password, port, null)) {
                    wsSend(dave.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                            Map.of("user", "dave", "pass", "dave-pass-42", "displayName", "Dave", "calls", "1")));
                    wsRead(dave.in(), WireOpcodes.EVT_AUTH_OK);
                    wsSend(alice.out(), FrameFactory.command(WireOpcodes.CMD_GROUP_CREATE, 40, Map.of("name", "lab",
                            "desc", "", "member.n", "3", "member.0", "bob", "member.1", "carol", "member.2", "dave")));
                    String groupId = wsFields(alice, WireOpcodes.EVT_GROUP_UPDATE).get("groupId");
                    callCommand(alice, 41, Map.of("kind", "ring", "convId", "grp:" + groupId));
                    String groupCall = wsFields(alice, WireOpcodes.EVT_CALL).get("callId");
                    Map<String, String> groupRing = wsFields(bob, WireOpcodes.EVT_CALL);
                    assertEquals("true", groupRing.get("group"), "A group call was not marked as one");
                    assertEquals("ring", wsFields(dave, WireOpcodes.EVT_CALL).get("kind"), "A member was not rung");
                    callCommand(bob, 42, Map.of("kind", "accept", "callId", groupCall));
                    wsFields(bob, WireOpcodes.EVT_CALL); // roster
                    assertEquals("bob", wsFields(alice, WireOpcodes.EVT_CALL).get("userId"), "Caller missed Bob joining");
                    callCommand(dave, 43, Map.of("kind", "accept", "callId", groupCall));
                    Map<String, String> daveRoster = wsFields(dave, WireOpcodes.EVT_CALL);
                    assertEquals("2", daveRoster.get("n"), "A late joiner was not told about everyone");
                    assertEquals("dave", wsFields(alice, WireOpcodes.EVT_CALL).get("userId"), "Alice missed Dave joining");
                    assertEquals("dave", wsFields(bob, WireOpcodes.EVT_CALL).get("userId"), "Bob missed Dave joining");
                    byte[] shout = new byte[300];
                    new java.security.SecureRandom().nextBytes(shout);
                    wsSend(dave.out(), new WireFrame.Builder(WireOpcodes.CMD_CALL_MEDIA).payload(shout).build());
                    assertTrue(java.util.Arrays.equals(stamp("dave", shout), wsRead(alice.in(), WireOpcodes.EVT_CALL_MEDIA).payload())
                            && java.util.Arrays.equals(stamp("dave", shout), wsRead(bob.in(), WireOpcodes.EVT_CALL_MEDIA).payload()),
                            "A group packet did not reach every other participant");
                    callCommand(bob, 44, Map.of("kind", "end", "callId", groupCall));
                    Map<String, String> left = wsFields(alice, WireOpcodes.EVT_CALL);
                    assertEquals("left", left.get("kind"), "The call ended when one of three left");
                    assertEquals("bob", left.get("userId"), "The wrong person was reported as leaving");
                    callCommand(dave, 45, Map.of("kind", "end", "callId", groupCall));
                    Map<String, String> over;
                    do {
                        over = wsFields(alice, WireOpcodes.EVT_CALL);
                    } while (!"end".equals(over.get("kind")));
                    assertEquals(groupCall, over.get("callId"), "The last person was not told the call ended");
                }

                wsSend(bob.out(), FrameFactory.command(WireOpcodes.CMD_USER_BLOCK, 4,
                        Map.of("targetUserId", "alice", "blocked", "true")));
                Thread.sleep(300);
                callCommand(alice, 7, Map.of("kind", "ring", "convId", "dm:alice:bob"));
                assertEquals("403", wsFields(alice, WireOpcodes.EVT_ERROR).get("code"), "A blocked user could call");
            }
        } finally {
            server.close();
        }
    }

    /** End-to-end encryption: crypto rules, then two desktop apps whose server only sees ciphertext. */
    private void testEndToEndChat() throws Exception {
        var alice = edu.ember.common.security.E2eCrypto.newIdentity();
        var bob = edu.ember.common.security.E2eCrypto.newIdentity();
        var mallory = edu.ember.common.security.E2eCrypto.newIdentity();
        var keys = List.of(
                new edu.ember.common.security.E2eCrypto.DeviceKey("alice", alice.deviceId(), alice.publicKey(), true),
                new edu.ember.common.security.E2eCrypto.DeviceKey("bob", bob.deviceId(), bob.publicKey(), true));
        String sealed = edu.ember.common.security.E2eCrypto.seal(alice, "dm:alice:bob", "alice", "m1",
                "top secret 🔐", keys);
        assertTrue(sealed.startsWith("e2e1.") && !sealed.contains("secret"), "Message was not encrypted");
        assertEquals("top secret 🔐", edu.ember.common.security.E2eCrypto.open(bob, "dm:alice:bob", "alice", "m1",
                sealed, keys), "Recipient could not decrypt");
        assertEquals("top secret 🔐", edu.ember.common.security.E2eCrypto.open(alice, "dm:alice:bob", "alice", "m1",
                sealed, keys), "Sender's own device could not read its message");
        for (String[] wrong : new String[][] {{"dm:bob:carol", "alice", "m1"}, {"dm:alice:bob", "alice", "m2"},
                {"dm:alice:bob", "bob", "m1"}}) {
            boolean refused = false;
            try {
                edu.ember.common.security.E2eCrypto.open(bob, wrong[0], wrong[1], wrong[2], sealed, keys);
            } catch (java.security.GeneralSecurityException expected) {
                refused = true;
            }
            assertTrue(refused, "A message moved to another chat, id or sender was accepted");
        }
        boolean outsider = false;
        try {
            edu.ember.common.security.E2eCrypto.open(mallory, "dm:alice:bob", "alice", "m1", sealed, keys);
        } catch (java.security.GeneralSecurityException expected) {
            outsider = true;
        }
        assertTrue(outsider, "A device the message was not for could read it");
        // An impostor device posing as Alice is refused: its key is not one of Alice's.
        String forged = edu.ember.common.security.E2eCrypto.seal(mallory, "dm:alice:bob", "alice", "m3", "hi", keys);
        boolean impostor = false;
        try {
            edu.ember.common.security.E2eCrypto.open(bob, "dm:alice:bob", "alice", "m3", forged, keys);
        } catch (java.security.GeneralSecurityException expected) {
            impostor = true;
        }
        assertTrue(impostor, "A message from an unknown device was accepted as Alice's");

        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path trust = config.resolve("ember-client-trust.p12");
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        Path temporary = Files.createTempDirectory("ember-e2e");
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0, storage,
                new SecurityAuditLogger(temporary.resolve("audit.json")));
        ClientController aliceApp = null;
        ClientController bobApp = null;
        try {
            server.start();
            aliceApp = new ClientController("127.0.0.1", server.port(), trust, password, temporary.resolve("a"));
            bobApp = new ClientController("127.0.0.1", server.port(), trust, password, temporary.resolve("b"));
            CountDownLatch received = new CountDownLatch(1);
            bobApp.addListener((model, event, message) -> {
                if (event.equals("MESSAGE") && message != null && message.body().equals("only bob can read this 🔐")) {
                    received.countDown();
                }
            });
            aliceApp.register("alice", "alice-pass-42".toCharArray(), "Alice");
            bobApp.register("bob", "bob-pass-42".toCharArray(), "Bob");
            waitFor(() -> storage.setting("devkeys:alice").isPresent() && storage.setting("devkeys:bob").isPresent(),
                    "Devices did not register their keys");
            aliceApp.sendMessage("dm:alice:bob", "only bob can read this 🔐");
            assertTrue(received.await(20, TimeUnit.SECONDS), "Bob did not receive the decrypted message");
            String onServer = storage.getHistory("dm:alice:bob", Long.MAX_VALUE, 5).get(0).body();
            assertTrue(onServer.startsWith("e2e1.") && !onServer.contains("bob can read"),
                    "The server stored readable text: " + onServer);
        } finally {
            if (aliceApp != null) { aliceApp.close(); }
            if (bobApp != null) { bobApp.close(); }
            server.close();
        }
    }

    /** Wideband desktop voice: 20 ms frames are 163 bytes (64 kbit/s) and decode back close to the input. */
    private void testWidebandVoice() {
        int[] state = {0};
        double signal = 0;
        double noise = 0;
        for (int frame = 0; frame < 5; frame++) {
            short[] pcm = new short[320];
            for (int index = 0; index < pcm.length; index++) {
                double time = (frame * 320 + index) / 16000.0;
                pcm[index] = (short) Math.round(0.6 * Math.sin(2 * Math.PI * 440 * time) * 32767);
            }
            byte[] encoded = edu.ember.client.CallSession.adpcmEncode(pcm, state);
            assertEquals(163, encoded.length, "A 20 ms wideband frame changed size");
            short[] decoded = edu.ember.client.CallSession.adpcmDecode(encoded);
            assertEquals(320, decoded.length, "A frame did not decode to 320 samples");
            for (int index = 0; index < pcm.length; index++) {
                signal += (double) pcm[index] * pcm[index];
                noise += Math.pow(decoded[index] - pcm[index], 2);
            }
        }
        assertTrue(10 * Math.log10(signal / noise) > 18, "Wideband voice lost too much quality");
    }

    // ---- calls v2 (P1: voice) ----

    /** Codec choice per call, shared ADPCM vectors (16 and 32 kHz), and compatibility with the legacy encoder. */
    private void testCallsV2Codecs() throws Exception {
        var auto = edu.ember.client.call.QualityMode.AUTO.policy();
        var saver = edu.ember.client.call.QualityMode.DATA_SAVER.policy();
        assertEquals(4, edu.ember.client.call.CodecFactory.negotiate(auto.desktopCodecPreference(), List.of(1, 4, 3, 2)).id(),
                "Auto should send 32 kHz ADPCM to a v2 website");
        assertEquals(3, edu.ember.client.call.CodecFactory.negotiate(saver.desktopCodecPreference(), List.of(1, 4, 3, 2)).id(),
                "Data saver should send 16 kHz ADPCM");
        assertEquals(3, edu.ember.client.call.CodecFactory.negotiate(auto.desktopCodecPreference(), List.of(3, 2)).id(),
                "An older build that only plays 16 kHz must get 16 kHz");
        assertEquals(2, edu.ember.client.call.CodecFactory.negotiate(auto.desktopCodecPreference(), List.of(2)).id(),
                "G.711 is the last resort");
        boolean refused = false;
        try {
            edu.ember.client.call.CodecFactory.negotiate(auto.desktopCodecPreference(), List.of(1));
        } catch (edu.ember.client.call.CodecNegotiationException expected) {
            refused = true;
        }
        assertTrue(refused, "Negotiation without a shared codec must fail loudly");
        for (var mode : edu.ember.client.call.QualityMode.values()) {
            assertEquals(mode, edu.ember.client.call.QualityMode.fromKey(mode.key()), "Mode key does not round-trip");
        }
        assertEquals(edu.ember.client.call.QualityMode.AUTO, edu.ember.client.call.QualityMode.fromKey("bogus"),
                "Unknown modes must fall back to Auto");

        List<String> vectors = Files.readAllLines(Path.of("src", "test", "resources", "adpcm-vectors.txt"));
        for (int rate : new int[] {16_000, 32_000}) {
            var codec = new edu.ember.client.call.AdpcmCodec(rate);
            int[] legacyState = {0};
            int samples = rate / 50;
            double signal = 0;
            double noise = 0;
            for (int frame = 0; frame < 3; frame++) {
                short[] pcm = new short[samples];
                for (int index = 0; index < samples; index++) {
                    double time = (frame * samples + index) / (double) rate;
                    pcm[index] = (short) Math.round((0.5 * Math.sin(2 * Math.PI * 440 * time)
                            + 0.2 * Math.sin(2 * Math.PI * 3100 * time)) * 32767);
                }
                byte[] encoded = codec.encode(pcm);
                assertEquals(3 + samples / 2, encoded.length, "ADPCM frame size changed");
                String expected = null;
                for (String line : vectors) {
                    if (line.startsWith(rate + " " + frame + " ")) {
                        expected = line.split(" ")[2];
                    }
                }
                assertEquals(expected, hex(encoded), "ADPCM " + rate + " frame " + frame + " differs from the shared vector");
                if (rate == 16_000) {
                    assertEquals(hex(edu.ember.client.CallSession.adpcmEncode(pcm, legacyState)), hex(encoded),
                            "v2 16 kHz ADPCM must stay byte-identical with the legacy encoder");
                }
                short[] decoded = codec.decode(encoded);
                for (int index = 0; index < samples; index++) {
                    signal += (double) pcm[index] * pcm[index];
                    noise += Math.pow(decoded[index] - pcm[index], 2);
                }
            }
            double snr = 10 * Math.log10(signal / noise);
            assertTrue(snr > 18, "ADPCM " + rate + " round trip too lossy: " + snr + " dB");
            assertEquals(0, codec.decode(new byte[5]).length, "A wrong-size frame must count as lost");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 0xFF));
        }
        return out.toString();
    }

    /** Anti-aliasing resampler: flat passband, deep stopband, correct output length across chunks. */
    private void testResampler() {
        // Passband sweep 200 Hz - 12 kHz: within 1 dB; stopband 17 - 23 kHz: at least 50 dB down (48 -> 32 kHz).
        for (int frequency = 200; frequency <= 23_000; frequency += 400) {
            if (frequency > 12_000 && frequency < 17_000) {
                continue; // transition band
            }
            double gainDb = toneGainDb(48_000, 32_000, frequency);
            if (frequency <= 12_000) {
                assertTrue(Math.abs(gainDb) < 1.0, frequency + " Hz passband gain " + gainDb + " dB");
            } else {
                assertTrue(gainDb < -50, frequency + " Hz stopband only " + gainDb + " dB");
            }
        }
        assertTrue(Math.abs(toneGainDb(44_100, 32_000, 1_000)) < 1.0, "44.1 kHz microphones must resample cleanly");
        assertTrue(Math.abs(toneGainDb(32_000, 48_000, 3_000)) < 1.0, "Playback up-sampling must keep the voice band");
        var resampler = new edu.ember.client.call.Resampler(48_000, 32_000);
        long produced = 0;
        for (int chunk = 0; chunk < 500; chunk++) {
            produced += resampler.process(new float[chunk % 7 == 0 ? 961 : 960]).length;
        }
        long expected = (500L * 960 + 500 / 7 + 1) * 2 / 3;
        assertTrue(Math.abs(produced - expected) <= 2, "Resampler drifted: " + produced + " vs " + expected);
    }

    private static double toneGainDb(int inRate, int outRate, double frequency) {
        var resampler = new edu.ember.client.call.Resampler(inRate, outRate);
        float[] input = new float[inRate / 2];
        for (int index = 0; index < input.length; index++) {
            input[index] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * index / inRate));
        }
        float[] output = resampler.process(input);
        double inPower = 0;
        double outPower = 0;
        for (int index = input.length / 4; index < input.length; index++) {
            inPower += input[index] * input[index];
        }
        inPower /= input.length - input.length / 4;
        int skip = output.length / 4;
        for (int index = skip; index < output.length; index++) {
            outPower += output[index] * output[index];
        }
        outPower /= output.length - skip;
        return 10 * Math.log10(Math.max(outPower, 1e-20) / inPower);
    }

    /** A fake clock the test moves by hand. */
    private static final class FakeClock implements edu.ember.client.call.Clock {
        long now;

        @Override
        public long nowMs() {
            return now;
        }
    }

    /** Jitter buffer: order, duplicates, concealment, steady delay, and recovery after a 500 ms stall. */
    private void testJitterBuffer() {
        FakeClock clock = new FakeClock();
        var buffer = new edu.ember.client.call.JitterBuffer(clock, 4);
        buffer.put(3, new short[] {3, 3, 3, 3});
        buffer.put(1, new short[] {1, 1, 1, 1});
        buffer.put(2, new short[] {2, 2, 2, 2});
        buffer.put(2, new short[] {9, 9, 9, 9});
        assertEquals(1, (int) buffer.poll()[0], "Frames must play in sequence order");
        assertEquals(2, (int) buffer.poll()[0], "Second frame out of order");
        assertEquals(3, (int) buffer.poll()[0], "Duplicate replaced the original");
        buffer.put(1, new short[] {7, 7, 7, 7});
        assertEquals(2, (int) buffer.stats().lateDropped(), "Late and duplicate frames must be counted as dropped");
        short[] concealed = buffer.poll();
        assertEquals(2, (int) concealed[0], "Concealment must repeat the last frame, faded (3 * 0.7 = 2)");
        buffer.poll();
        buffer.poll();
        assertEquals(0, (int) buffer.poll()[0], "After three concealed frames it must go quiet");

        // Simulated transport on a fake clock: 20 ms frames, 2 ms network, then a 500 ms stall and a TCP burst.
        FakeClock time = new FakeClock();
        var jb = new edu.ember.client.call.JitterBuffer(time, 160);
        java.util.TreeMap<Long, List<Long>> deliveries = new java.util.TreeMap<>();
        for (long sequence = 1; sequence <= 400; sequence++) {
            long sent = sequence * 20;
            long arrive = sent + 2;
            if (sent >= 3000 && sent < 3500) {
                arrive = 3500 + 2; // stalled: everything arrives together when the connection recovers
            }
            deliveries.computeIfAbsent(arrive, ignored -> new java.util.ArrayList<>()).add(sequence);
        }
        int steadyMax = 0;
        int afterStall = 0;
        long recoveredAt = -1;
        for (time.now = 0; time.now <= 8000; time.now++) {
            for (long sequence : deliveries.getOrDefault(time.now, List.of())) {
                jb.put(sequence, new short[160]);
            }
            if (time.now % 20 == 10) {
                jb.poll();
                int depth = jb.depthMs();
                if (time.now > 1000 && time.now < 3000) {
                    steadyMax = Math.max(steadyMax, depth);
                }
                if (time.now > 3500 && time.now < 3600) {
                    afterStall = Math.max(afterStall, depth);
                }
                if (time.now > 3600 && recoveredAt < 0 && depth <= 80) {
                    recoveredAt = time.now;
                }
            }
        }
        assertTrue(steadyMax <= 80, "Steady-state delay on a good network is above 80 ms: " + steadyMax);
        assertTrue(afterStall > 100, "The stall burst should have filled the buffer (sanity check): " + afterStall);
        assertTrue(recoveredAt > 0 && recoveredAt - 3500 <= 3000,
                "Delay did not return to <= 80 ms within 3 s of the stall (recovered at " + recoveredAt + ")");

        // Loss and reordering: never plays a frame twice or goes backwards.
        java.util.Random random = new java.util.Random(7);
        FakeClock lossy = new FakeClock();
        var jl = new edu.ember.client.call.JitterBuffer(lossy, 2);
        java.util.TreeMap<Long, List<Long>> arrivals = new java.util.TreeMap<>();
        for (long sequence = 1; sequence <= 500; sequence++) {
            if (random.nextInt(100) < 3) {
                continue; // 3% lost
            }
            long arrive = sequence * 20 + 20 + random.nextInt(60);
            arrivals.computeIfAbsent(arrive, ignored -> new java.util.ArrayList<>()).add(sequence);
        }
        long lastPlayed = 0;
        for (lossy.now = 0; lossy.now <= 11_000; lossy.now++) {
            for (long sequence : arrivals.getOrDefault(lossy.now, List.of())) {
                jl.put(sequence, new short[] {(short) sequence, 30_000}); // 30000 marks a real (not concealed) frame
            }
            if (lossy.now % 20 == 0) {
                short[] played = jl.poll();
                short value = played[0];
                if (played[1] == 30_000) {
                    assertTrue(value > lastPlayed, "Played out of order: " + value + " after " + lastPlayed);
                    lastPlayed = value;
                }
            }
        }
        assertTrue(jl.stats().targetMs() > 40, "Jitter of up to 60 ms must raise the target delay");
        var flooded = new edu.ember.client.call.JitterBuffer(new FakeClock(), 2);
        for (long sequence = 1; sequence <= 10_000; sequence++) {
            flooded.put(sequence, new short[2]);
        }
        assertTrue(flooded.depthMs() <= 25 * 20, "A flood of frames grew the buffer past its cap: " + flooded.depthMs());
    }

    /** Settings persist; a damaged file falls back to defaults; a microphone that unplugs is replaced. */
    private void testCallSettingsAndDevices() throws Exception {
        Path directory = Files.createTempDirectory("ember-call-settings");
        Path file = directory.resolve("call-settings.properties");
        var settings = edu.ember.client.call.CallSettings.load(file);
        assertEquals(edu.ember.client.call.QualityMode.AUTO, settings.mode(), "Default mode must be Auto");
        assertTrue(!settings.speakerMode(), "Speaker mode must be off by default");
        assertTrue(settings.callsV2(), "The device half of the v2 flag defaults on");
        settings.setMode(edu.ember.client.call.QualityMode.MAX);
        settings.setMicrophone("USB Mic");
        var reloaded = edu.ember.client.call.CallSettings.load(file);
        assertEquals(edu.ember.client.call.QualityMode.MAX, reloaded.mode(), "Mode was not saved");
        assertEquals("USB Mic", reloaded.microphone(), "Microphone choice was not saved");
        Files.writeString(file, "mode=\\u12");
        assertEquals(edu.ember.client.call.QualityMode.AUTO, edu.ember.client.call.CallSettings.load(file).mode(),
                "A damaged settings file must fall back to defaults");

        AtomicInteger microphonesOpened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var devices = new edu.ember.client.call.AudioDevices.Provider() {
            @Override
            public edu.ember.client.call.AudioDevices.Microphone openMicrophone(String preferred) {
                int which = microphonesOpened.incrementAndGet();
                return new edu.ember.client.call.AudioDevices.Microphone() {
                    int reads;

                    @Override public int sampleRate() { return which == 1 ? 48_000 : 44_100; }
                    @Override public String name() { return "mic" + which; }

                    @Override
                    public void read(short[] samples) throws edu.ember.client.call.AudioDeviceException {
                        sleepQuietly(20);
                        if (which == 1 && ++reads > 5) {
                            throw new edu.ember.client.call.AudioDeviceException("unplugged");
                        }
                        java.util.Arrays.fill(samples, (short) 1000);
                    }

                    @Override public void close() { closed.incrementAndGet(); }
                };
            }

            @Override
            public edu.ember.client.call.AudioDevices.Speaker openSpeaker(String preferred) {
                return new edu.ember.client.call.AudioDevices.Speaker() {
                    @Override public int sampleRate() { return 48_000; }
                    @Override public String name() { return "speaker"; }
                    @Override public void write(short[] samples) { sleepQuietly(20); }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
        List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger framesAfterSwitch = new AtomicInteger();
        var engine = new edu.ember.client.call.VoiceEngine(devices, settings, edu.ember.client.call.Clock.SYSTEM,
                (sequence, codec, frame) -> {
                    if (microphonesOpened.get() > 1) {
                        framesAfterSwitch.incrementAndGet();
                    }
                    assertEquals(4, codec, "Engine must send the negotiated codec");
                    assertEquals(323, frame.length, "32 kHz ADPCM frames are 323 bytes");
                },
                statuses::add);
        engine.setSendCodec(new edu.ember.client.call.AdpcmCodec(32_000));
        engine.start();
        waitFor(() -> framesAfterSwitch.get() >= 5, "Audio did not continue on the fallback microphone");
        engine.stop();
        waitFor(() -> closed.get() >= 3, "Devices were not released after stop (closed " + closed.get() + ")");
        assertTrue(statuses.stream().anyMatch(message -> message.contains("disconnected")),
                "The user was not told the microphone dropped");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The v2 flag is off unless the server enables it, and AUTH_OK reports it either way. */
    private void testCallsV2Flag() throws Exception {
        Path temporary = Files.createTempDirectory("ember-flag");
        EmberServer server = new EmberServer(null, null, 0, new InMemoryStorageEngine(),
                new SecurityAuditLogger(temporary.resolve("audit.json")), temporary.resolve("media"),
                86_400, java.util.Set.of(), "127.0.0.1");
        try {
            server.start();
            int port = server.startHttp(0);
            for (boolean enabled : new boolean[] {false, true}) {
                server.setCallsV2(enabled);
                try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(10_000);
                    var in = new java.io.BufferedInputStream(socket.getInputStream());
                    var out = socket.getOutputStream();
                    out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                            + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    StringBuilder head = new StringBuilder();
                    while (!head.toString().endsWith("\r\n\r\n")) {
                        head.append((char) in.read());
                    }
                    String user = enabled ? "flagon" : "flagoff";
                    wsSend(out, FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                            Map.of("user", user, "pass", user + "-pass-42", "displayName", user, "calls", "1")));
                    assertEquals(Boolean.toString(enabled),
                            KvPayloadCodec.decode(wsRead(in, WireOpcodes.EVT_AUTH_OK).payload()).get("callsV2"),
                            "AUTH_OK did not report the calls v2 flag");
                }
            }
        } finally {
            server.close();
        }
    }

    /** Calls v2 hand-off: only the same person, only once, only before expiry; the first commit wins. */
    private void testCallHandoff() throws Exception {
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path trust = config.resolve("ember-client-trust.p12");
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0);
        try {
            server.start();
            server.setCallsV2(true);
            int port = server.port();
            try (WsClient alice = wsOpen(trust, password, port, null);
                    WsClient desk = wsOpen(trust, password, port, null);
                    WsClient tabA = wsOpen(trust, password, port, null);
                    WsClient tabB = wsOpen(trust, password, port, null);
                    WsClient carol = wsOpen(trust, password, port, null)) {
                wsSend(alice.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "alice", "pass", "alice-pass-42", "displayName", "Alice", "calls", "1")));
                wsRead(alice.in(), WireOpcodes.EVT_AUTH_OK);
                wsSend(desk.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "bob", "pass", "bob-pass-42", "displayName", "Bob", "calls", "1")));
                wsRead(desk.in(), WireOpcodes.EVT_AUTH_OK);
                for (WsClient tab : List.of(tabA, tabB)) {
                    wsSend(tab.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_LOGIN, 1,
                            Map.of("user", "bob", "pass", "bob-pass-42")));
                    wsRead(tab.in(), WireOpcodes.EVT_AUTH_OK);
                }
                wsSend(carol.out(), FrameFactory.command(WireOpcodes.CMD_AUTH_REGISTER, 1,
                        Map.of("user", "carol", "pass", "carol-pass-42", "displayName", "Carol", "calls", "1")));
                wsRead(carol.in(), WireOpcodes.EVT_AUTH_OK);

                callCommand(alice, 2, Map.of("kind", "ring", "convId", "dm:alice:bob"));
                String callId = wsFields(alice, WireOpcodes.EVT_CALL).get("callId");
                callCommand(tabA, 3, Map.of("kind", "handoff-prepare", "callId", callId));
                assertEquals("404", wsFields(tabA, WireOpcodes.EVT_ERROR).get("code"),
                        "A hand-off before the call was answered must be refused");
                callCommand(desk, 2, Map.of("kind", "accept", "callId", callId));
                wsFields(desk, WireOpcodes.EVT_CALL); // roster
                assertEquals("joined", wsFields(alice, WireOpcodes.EVT_CALL).get("kind"), "Desktop did not join");

                callCommand(carol, 2, Map.of("kind", "handoff-prepare", "callId", callId));
                assertEquals("404", wsFields(carol, WireOpcodes.EVT_ERROR).get("code"), "Someone else could take over Bob's call");
                callCommand(desk, 3, Map.of("kind", "handoff-prepare", "callId", callId));
                assertEquals("404", wsFields(desk, WireOpcodes.EVT_ERROR).get("code"), "A device cannot hand off to itself");

                callCommand(tabA, 4, Map.of("kind", "handoff-prepare", "callId", callId));
                Map<String, String> readyA = wsCallKind(tabA, "handoff-ready");
                assertEquals("handoff-ready", readyA.get("kind"), "Bob's web tab could not prepare the hand-off");
                assertEquals("dm:alice:bob", readyA.get("convId"), "Hand-off did not say which chat");
                callCommand(tabB, 4, Map.of("kind", "handoff-prepare", "callId", callId));
                String tokenB = wsCallKind(tabB, "handoff-ready").get("token");
                callCommand(tabB, 5, Map.of("kind", "handoff-commit", "callId", callId, "token", readyA.get("token")));
                assertEquals("404", wsFields(tabB, WireOpcodes.EVT_ERROR).get("code"), "A ticket worked on another device");

                callCommand(tabA, 5, Map.of("kind", "handoff-commit", "callId", callId, "token", readyA.get("token")));
                Map<String, String> roster = wsCallKind(tabA, "roster");
                assertEquals("roster", roster.get("kind"), "The new device did not get the roster");
                assertEquals("alice", roster.get("user.0"), "The roster did not name Alice");
                Map<String, String> released = wsCallKind(desk, "end");
                assertEquals("elsewhere", released.get("reason"), "The desktop was not told to let go of the call");
                Map<String, String> moved = wsFields(alice, WireOpcodes.EVT_CALL);
                assertEquals("moved", moved.get("kind"), "Alice was not told to re-key with the new device");
                assertEquals("bob", moved.get("userId"), "Alice was told the wrong person moved");

                callCommand(tabB, 6, Map.of("kind", "handoff-commit", "callId", callId, "token", tokenB));
                assertEquals("404", wsFields(tabB, WireOpcodes.EVT_ERROR).get("code"), "The second tab won a race already lost");
                callCommand(tabA, 6, Map.of("kind", "handoff-commit", "callId", callId, "token", readyA.get("token")));
                assertEquals("404", wsFields(tabA, WireOpcodes.EVT_ERROR).get("code"), "A ticket was used twice");

                byte[] packet = new byte[64];
                wsSend(tabA.out(), new WireFrame.Builder(WireOpcodes.CMD_CALL_MEDIA).payload(packet).build());
                assertTrue(java.util.Arrays.equals(stamp("bob", packet), wsRead(alice.in(), WireOpcodes.EVT_CALL_MEDIA).payload()),
                        "Media did not flow from the new device");

                callCommand(desk, 4, Map.of("kind", "end", "callId", callId));
                wsSend(tabA.out(), new WireFrame.Builder(WireOpcodes.CMD_CALL_MEDIA).payload(packet).build());
                assertTrue(java.util.Arrays.equals(stamp("bob", packet), wsRead(alice.in(), WireOpcodes.EVT_CALL_MEDIA).payload()),
                        "The old device could still hang up the moved call");

                server.setCallsV2(false);
                callCommand(tabB, 7, Map.of("kind", "handoff-prepare", "callId", callId));
                assertEquals("400", wsFields(tabB, WireOpcodes.EVT_ERROR).get("code"), "Hand-off worked with calls v2 off");
                server.setCallsV2(true);
                server.setHandoffTtlMs(1);
                callCommand(tabB, 8, Map.of("kind", "handoff-prepare", "callId", callId));
                String stale = wsCallKind(tabB, "handoff-ready").get("token");
                Thread.sleep(20);
                callCommand(tabB, 9, Map.of("kind", "handoff-commit", "callId", callId, "token", stale));
                assertEquals("404", wsFields(tabB, WireOpcodes.EVT_ERROR).get("code"), "An expired ticket was accepted");
            }
        } finally {
            server.close();
        }
    }

    /** Speaker mode (opt-in): while the other person is heard, the microphone is turned down about 20 dB. */
    private void testSpeakerMode() throws Exception {
        var settings = edu.ember.client.call.CallSettings.load(Files.createTempDirectory("ember-speaker").resolve("s.properties"));
        var devices = new edu.ember.client.call.AudioDevices.Provider() {
            @Override
            public edu.ember.client.call.AudioDevices.Microphone openMicrophone(String preferred) {
                return new edu.ember.client.call.AudioDevices.Microphone() {
                    long phase;

                    @Override public int sampleRate() { return 48_000; }
                    @Override public String name() { return "mic"; }

                    @Override public void read(short[] samples) {
                        sleepQuietly(20);
                        for (int index = 0; index < samples.length; index++) {
                            samples[index] = (short) (8000 * Math.sin(2 * Math.PI * 440 * phase++ / 48_000.0));
                        }
                    }

                    @Override public void close() { }
                };
            }

            @Override
            public edu.ember.client.call.AudioDevices.Speaker openSpeaker(String preferred) {
                return new edu.ember.client.call.AudioDevices.Speaker() {
                    @Override public int sampleRate() { return 48_000; }
                    @Override public String name() { return "speaker"; }
                    @Override public void write(short[] samples) { sleepQuietly(20); }
                    @Override public void close() { }
                };
            }
        };
        var codec = new edu.ember.client.call.AdpcmCodec(32_000);
        List<double[]> sent = new java.util.concurrent.CopyOnWriteArrayList<>(); // {time, rms}
        var engine = new edu.ember.client.call.VoiceEngine(devices, settings, edu.ember.client.call.Clock.SYSTEM,
                (sequence, codecId, frame) -> {
                    short[] pcm = codec.fresh().decode(frame);
                    double sum = 0;
                    for (short sample : pcm) {
                        sum += (double) sample * sample;
                    }
                    sent.add(new double[] {System.nanoTime() / 1e6, Math.sqrt(sum / Math.max(1, pcm.length))});
                }, message -> { });
        engine.setSendCodec(codec);
        short[] loud = new short[codec.frameSamples()];
        for (int index = 0; index < loud.length; index++) {
            loud[index] = (short) (16000 * Math.sin(2 * Math.PI * 1000 * index / 32_000.0));
        }
        byte[] remote = codec.fresh().encode(loud);
        AtomicBoolean talking = new AtomicBoolean(true);
        Thread other = new Thread(() -> {
            long sequence = 0;
            while (talking.get()) {
                engine.receive("peer", ++sequence, codec.id(), remote);
                sleepQuietly(20);
            }
        });
        engine.start();
        other.start();
        try {
            Thread.sleep(700); // settings default: Speaker mode off
            double normal = averageRms(sent, 300);
            settings.setSpeakerMode(true);
            Thread.sleep(700);
            double ducked = averageRms(sent, 300);
            assertTrue(normal > 2000, "The test microphone did not reach the sender (rms " + normal + ")");
            assertTrue(ducked < normal * 0.2, "Speaker mode did not turn the mic down (" + normal + " -> " + ducked + ")");
            talking.set(false);
            other.join();
            Thread.sleep(700); // the other side went quiet: full level again
            assertTrue(averageRms(sent, 300) > normal * 0.7, "The mic stayed ducked after the other person stopped");
        } finally {
            talking.set(false);
            engine.stop();
        }
    }

    private static double averageRms(List<double[]> frames, double lastMs) {
        double now = System.nanoTime() / 1e6, sum = 0;
        int count = 0;
        for (double[] frame : frames) {
            if (now - frame[0] <= lastMs) {
                sum += frame[1];
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
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
