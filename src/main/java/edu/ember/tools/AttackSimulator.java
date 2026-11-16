package edu.ember.tools;

import edu.ember.common.protocol.FrameCodec;
import edu.ember.common.protocol.FrameFactory;
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.WireFrame;
import edu.ember.common.protocol.WireOpcodes;
import edu.ember.common.security.TlsSocketFactory;
import edu.ember.server.EmberServer;
import edu.ember.server.SecurityAuditLogger;
import edu.ember.server.storage.InMemoryStorageEngine;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/** Sends hostile but bounded protocol inputs to a local TLS server. @concept integration testing, exception handling */
public final class AttackSimulator {
    private static final char[] LOGIN_PASSWORD = "attack-pass-42".toCharArray();

    private AttackSimulator() { throw new AssertionError("No instances"); }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "phase8", "attack-" + System.nanoTime());
        Files.createDirectories(root);
        char[] storePassword = readPassword();
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        EmberServer server = new EmberServer(Path.of("config", "ember-server.p12"), storePassword,
                0, storage, new SecurityAuditLogger(root.resolve("audit.json")), root.resolve("media"));
        server.start();
        try {
            malformedMagic(server.port(), storePassword);
            System.out.println("HANDLED: invalid frame magic closed the TLS connection");
            oversizedPreAuth(server.port(), storePassword);
            System.out.println("HANDLED: pre-auth oversized frame was rejected before its payload");
            invalidUsername(server.port(), storePassword);
            System.out.println("HANDLED: username allowlist rejected CR/LF input with 400");
            simulateLockout(server.port(), storePassword);
            simulateIdentityAndAuthorization(server.port(), storePassword, storage);
        } finally {
            server.close();
            java.util.Arrays.fill(storePassword, '\0');
            java.util.Arrays.fill(LOGIN_PASSWORD, '\0');
        }
    }

    private static void malformedMagic(int port, char[] password) throws Exception {
        try (SSLSocket socket = connect(port, password)) {
            socket.getOutputStream().write(new byte[] {0, 0, 0, 0});
            socket.getOutputStream().flush();
            socket.setSoTimeout(4_000);
            if (socket.getInputStream().read() != -1) {
                throw new IllegalStateException("Server did not close malformed frame");
            }
        }
    }

    private static void oversizedPreAuth(int port, char[] password) throws Exception {
        try (SSLSocket socket = connect(port, password)) {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(FrameCodec.MAGIC);
            output.writeByte(FrameCodec.VERSION);
            output.writeByte(WireOpcodes.CMD_AUTH_LOGIN);
            output.writeLong(2);
            output.writeInt(4097);
            output.flush();
            socket.setSoTimeout(4_000);
            if (socket.getInputStream().read() != -1) {
                throw new IllegalStateException("Server did not close oversized pre-auth frame");
            }
        }
    }

    private static void invalidUsername(int port, char[] password) throws Exception {
        try (SSLSocket socket = connect(port, password)) {
            send(socket, WireOpcodes.CMD_AUTH_REGISTER, 3,
                    Map.of("user", "bad\r\nuser", "pass", new String(LOGIN_PASSWORD),
                            "displayName", "Invalid User"));
            Map<String, String> error = expectEvent(socket, 3, WireOpcodes.EVT_ERROR);
            if (!"400".equals(error.get("code"))) {
                throw new IllegalStateException("Invalid username was not rejected with 400");
            }
        }
    }

    private static void simulateLockout(int port, char[] password) throws Exception {
        try (SSLSocket socket = connect(port, password)) {
            register(socket, "attacklock", "Lockout Demo");
            for (int attempt = 1; attempt <= 5; attempt++) {
                long correlation = 20 + attempt;
                send(socket, WireOpcodes.CMD_AUTH_LOGIN, correlation,
                        Map.of("user", "attacklock", "pass", "wrong-pass-42"));
                Map<String, String> error = expectEvent(socket, correlation, WireOpcodes.EVT_ERROR);
                if (!"401".equals(error.get("code"))) {
                    throw new IllegalStateException("Unexpected code for failed login attempt " + attempt);
                }
            }
            send(socket, WireOpcodes.CMD_AUTH_LOGIN, 26,
                    Map.of("user", "attacklock", "pass", new String(LOGIN_PASSWORD)));
            Map<String, String> locked = expectEvent(socket, 26, WireOpcodes.EVT_ERROR);
            if (!"423".equals(locked.get("code"))) {
                throw new IllegalStateException("Correct password was not refused after lockout");
            }
        }
        System.out.println("HANDLED: five bad logins locked the account, including its correct password");
    }

    private static void simulateIdentityAndAuthorization(int port, char[] password,
            InMemoryStorageEngine storage) throws Exception {
        try (SSLSocket alice = connect(port, password); SSLSocket bob = connect(port, password)) {
            register(alice, "attackalice", "Attack Alice");
            register(bob, "attackbob", "Attack Bob");
            String hostile = "' OR '1'='1 --\\r\\nbob\\r\\n{\"event\":\"admin_grant\"}";
            Map<String, String> fields = new HashMap<>();
            fields.put("clientMsgId", "attack-identity-1");
            fields.put("convId", "dm:attackalice:attackbob");
            fields.put("body", hostile);
            fields.put("ttlSec", "0");
            fields.put("replyToId", "");
            fields.put("senderUserId", "attackbob");
            send(alice, WireOpcodes.CMD_MSG_SEND, 10, fields);
            expectEvent(alice, 10, WireOpcodes.EVT_MSG_ACK);
            await(() -> storage.findBySenderAndClientMsgId("attackalice", "attack-identity-1").isPresent(),
                    "server-bound sender identity");
            var stored = storage.findBySenderAndClientMsgId("attackalice", "attack-identity-1")
                    .orElseThrow();
            if (!stored.body().equals(hostile)) {
                throw new IllegalStateException("Hostile text changed: " + stored.body());
            }
            if (storage.findBySenderAndClientMsgId("attackbob", "attack-identity-1").isPresent()) {
                throw new IllegalStateException("Forged sender identity was accepted");
            }
            System.out.println("HANDLED: hostile text stayed literal; forged sender identity was ignored");
            send(alice, WireOpcodes.CMD_MSG_SEND, 11, fields);
            expectEvent(alice, 11, WireOpcodes.EVT_MSG_ACK);
            if (storage.getHistory(stored.conversationId(), Long.MAX_VALUE, 20).stream()
                    .filter(message -> message.clientMsgId().equals("attack-identity-1")).count() != 1) {
                throw new IllegalStateException("Duplicate resend created another message");
            }
            System.out.println("HANDLED: duplicate resend remained idempotent");
            send(bob, WireOpcodes.CMD_MSG_EDIT, 12,
                    Map.of("clientMsgId", "attack-identity-1", "newBody", "unauthorized edit"));
            Map<String, String> error = expectEvent(bob, 12, WireOpcodes.EVT_ERROR);
            if (!"403".equals(error.get("code"))) {
                throw new IllegalStateException("Cross-user edit was not denied with 403");
            }
            System.out.println("HANDLED: cross-user message edit received 403");
            simulateUploadRejections(alice);
        }
    }

    private static void simulateUploadRejections(SSLSocket socket) throws Exception {
        String script = java.util.Base64.getEncoder().encodeToString(
                "#!/bin/sh\necho unsafe".getBytes(StandardCharsets.UTF_8));
        send(socket, WireOpcodes.CMD_MEDIA_UPLOAD, 13,
                Map.of("fileName", "avatar.jpg", "data", script));
        expectUploadRejected(socket, 13);
        System.out.println("HANDLED: renamed shell upload was rejected by image signature checks");
        String largePng = java.util.Base64.getEncoder().encodeToString(pngHeader(12_000, 12_000));
        send(socket, WireOpcodes.CMD_MEDIA_UPLOAD, 14,
                Map.of("fileName", "large.png", "data", largePng));
        expectUploadRejected(socket, 14);
        System.out.println("HANDLED: 12,000 x 12,000 PNG header was rejected before pixel decode");
    }

    private static void expectUploadRejected(SSLSocket socket, long correlation) throws Exception {
        Map<String, String> error = expectEvent(socket, correlation, WireOpcodes.EVT_ERROR);
        if (!"400".equals(error.get("code"))) {
            throw new IllegalStateException("Invalid image upload was not rejected with 400");
        }
    }

    private static byte[] pngHeader(int width, int height) {
        ByteBuffer header = ByteBuffer.allocate(33);
        header.put(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        header.putInt(13).put(new byte[] {0x49, 0x48, 0x44, 0x52});
        header.putInt(width).putInt(height).put(new byte[] {8, 2, 0, 0, 0});
        java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
        checksum.update(header.array(), 12, 17);
        header.putInt((int) checksum.getValue());
        return header.array();
    }

    private static void register(SSLSocket socket, String username, String displayName)
            throws Exception {
        send(socket, WireOpcodes.CMD_AUTH_REGISTER, 1,
                Map.of("user", username, "pass", new String(LOGIN_PASSWORD),
                        "displayName", displayName));
        expectEvent(socket, 1, WireOpcodes.EVT_AUTH_OK);
    }

    private static void send(SSLSocket socket, int opcode, long correlation,
            Map<String, String> fields) throws Exception {
        WireFrame frame = FrameFactory.command(opcode, correlation, fields);
        FrameCodec.writeFrame(socket.getOutputStream(), frame);
    }

    private static Map<String, String> expectEvent(SSLSocket socket, long correlation, int opcode)
            throws Exception {
        socket.setSoTimeout(10_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            WireFrame frame = FrameCodec.readFrame(socket.getInputStream(), 64 * 1024);
            if (frame.correlationId() == correlation
                    && (frame.opcode() == opcode || frame.opcode() == WireOpcodes.EVT_ERROR)) {
                return KvPayloadCodec.decode(frame.payload());
            }
        }
        throw new IllegalStateException("Expected event " + opcode + " was not received");
    }

    private static SSLSocket connect(int port, char[] password) throws Exception {
        SSLSocket socket = TlsSocketFactory.createClientSocket(
                Path.of("config", "ember-client-trust.p12"), password, "localhost", port);
        socket.startHandshake();
        return socket;
    }

    private static char[] readPassword() throws Exception {
        String prefix = "EMBER_KEYSTORE_PASS=";
        for (String line : Files.readAllLines(Path.of("config", "secrets.properties"),
                StandardCharsets.UTF_8)) {
            if (line.startsWith(prefix)) { return line.substring(prefix.length()).toCharArray(); }
        }
        throw new IllegalStateException("Keystore password missing from config/secrets.properties");
    }

    private static void await(Check check, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (check.ready()) { return; }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Timed out waiting for " + label);
    }

    @FunctionalInterface
    private interface Check { boolean ready() throws Exception; }
}
