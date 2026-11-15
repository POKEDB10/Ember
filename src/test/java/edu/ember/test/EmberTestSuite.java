package edu.ember.test;

import edu.ember.common.exceptions.EmberException;
import edu.ember.common.protocol.FrameCodec;
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.ProtocolFrameException;
import edu.ember.common.protocol.WireFrame;
import edu.ember.common.security.InputValidator;
import edu.ember.common.security.Pbkdf2PasswordHasher;
import edu.ember.common.security.PasswordHasher;
import edu.ember.common.security.TlsSocketFactory;
import edu.ember.client.ClientController;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.Message;
import edu.ember.common.model.UserRecord;
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
        runTest("WAL restart recovers Arabic and emoji records and trims torn tail",
                this::testWalTornTailRecovery);
        runTest("WAL CRC failure truncates invalid tail", this::testWalCrcRecovery);
        runTest("server restart restores accounts and messages from WAL",
                this::testWalServerRestart);
        runTest("five bad logins lock account and audit stays one JSON line",
                this::testLockoutAndAudit);
        runTest("concurrent password hashing is capped at four", this::testHashConcurrencyLimit);
        runTest("message flood receives 429", this::testMessageFlood);
        runTest("loopback IP limiter remains disabled", this::testLoopbackRateLimit);
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
        try {
            firstServer.start();
            alice = new ClientController("127.0.0.1", firstServer.port(),
                    config.resolve("ember-client-trust.p12"), password);
            bob = new ClientController("127.0.0.1", firstServer.port(),
                    config.resolve("ember-client-trust.p12"), password);
            CountDownLatch firstAuth = new CountDownLatch(2);
            CountDownLatch delivered = new CountDownLatch(1);
            alice.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) {
                    firstAuth.countDown();
                }
            });
            bob.addListener((model, event, message) -> {
                if (event.equals("AUTH_OK")) {
                    firstAuth.countDown();
                }
                if (event.equals("MESSAGE") && message != null
                        && message.body().equals("persist me 🌋")) {
                    delivered.countDown();
                }
            });
            alice.register("alice", "alice-pass-42".toCharArray(), "Alice");
            bob.register("bob", "bob-pass-42".toCharArray(), "Bob");
            assertTrue(firstAuth.await(30, TimeUnit.SECONDS), "Initial users did not authenticate");
            alice.sendMessage("dm:alice:bob", "persist me 🌋");
            assertTrue(delivered.await(30, TimeUnit.SECONDS), "Persisted message was not delivered");
            alice.close();
            bob.close();
            alice = null;
            bob = null;
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
            firstServer.close();
            if (restartedServer != null) { restartedServer.close(); }
            Files.deleteIfExists(auditPath);
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
            ClientStateModel aliceModel = alice.model();
            ClientStateModel bobModel = bob.model();
            alice.addListener((model, event, message) -> {
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
