package edu.ember.tools;

import edu.ember.client.ClientController;
import edu.ember.common.model.Message;
import edu.ember.common.security.TlsSocketFactory;
import edu.ember.server.EmberServer;
import edu.ember.server.SecurityAuditLogger;
import edu.ember.server.storage.InMemoryStorageEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Runs real TLS clients concurrently and checks persisted message ordering. @concept concurrency, integration testing */
public final class ConcurrencyStressHarness {
    private static final int CLIENTS = 10;
    private static final int PER_CLIENT = 50;

    private ConcurrencyStressHarness() { throw new AssertionError("No instances"); }

    public static void main(String[] args) throws Exception {
        for (int run = 1; run <= 2; run++) {
            runOnce(run);
            System.out.println("Run " + run + " PASS: 10 TLS clients, 500 messages, no loss, duplicates, or ordering errors.");
        }
    }

    private static void runOnce(int run) throws Exception {
        Path root = Path.of("build", "phase8", "stress-" + run + "-" + System.nanoTime());
        Files.createDirectories(root);
        char[] password = readPassword();
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        EmberServer server = new EmberServer(Path.of("config", "ember-server.p12"), password,
                0, storage, new SecurityAuditLogger(root.resolve("audit.json")), root.resolve("media"));
        List<ClientController> clients = new ArrayList<>();
        try {
            server.start();
            for (int index = 0; index < CLIENTS; index++) {
                ClientController client = new ClientController("localhost", server.port(),
                        Path.of("config", "ember-client-trust.p12"), password,
                        root.resolve("client-" + index));
                clients.add(client);
                String name = username(index);
                client.register(name, "stress-pass-42".toCharArray(), "Stress " + index);
                await(() -> name.equals(client.model().userId()), "authentication for " + name);
            }
            clients.get(0).createGroup("Concurrency Study", "Phase 8 stress group");
            await(() -> clients.get(0).model().groupIds().size() == 1, "group creation");
            String groupId = clients.get(0).model().groupIds().iterator().next();
            for (int index = 1; index < CLIENTS; index++) {
                clients.get(0).changeGroupMember(groupId, username(index), "ADD");
            }
            await(() -> {
                try {
                    return storage.findGroup(groupId).map(group -> group.members().size() == CLIENTS)
                            .orElse(false);
                } catch (Exception exception) {
                    return false;
                }
            }, "ten-member group");
            sendConcurrently(clients, groupId);
            await(() -> storage.getHistory("grp:" + groupId, Long.MAX_VALUE, CLIENTS * PER_CLIENT)
                    .size() == CLIENTS * PER_CLIENT, "all 500 persisted messages");
            verify(storage.getHistory("grp:" + groupId, Long.MAX_VALUE, CLIENTS * PER_CLIENT));
        } finally {
            for (ClientController client : clients) { client.close(); }
            server.close();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static void sendConcurrently(List<ClientController> clients, String groupId)
            throws InterruptedException {
        List<Thread> senders = new ArrayList<>();
        for (int clientIndex = 0; clientIndex < CLIENTS; clientIndex++) {
            final int sender = clientIndex;
            Thread thread = new Thread(() -> {
                for (int messageIndex = 0; messageIndex < PER_CLIENT; messageIndex++) {
                    clients.get(sender).sendMessageWithId("grp:" + groupId,
                            "stress-" + sender + "-" + messageIndex,
                            "stress-" + sender + "-" + messageIndex);
                    try {
                        Thread.sleep(220);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "phase8-stress-sender-" + sender);
            senders.add(thread);
            thread.start();
        }
        for (Thread sender : senders) { sender.join(TimeUnit.SECONDS.toMillis(30)); }
        if (senders.stream().anyMatch(Thread::isAlive)) {
            throw new IllegalStateException("A stress sender did not finish");
        }
    }

    private static void verify(List<Message> messages) {
        if (messages.size() != CLIENTS * PER_CLIENT) {
            throw new IllegalStateException("Expected 500 messages, found " + messages.size());
        }
        Set<String> ids = new HashSet<>();
        long expectedSequence = 1;
        int[][] lastIndex = new int[CLIENTS][1];
        for (int index = 0; index < CLIENTS; index++) { lastIndex[index][0] = -1; }
        for (Message message : messages) {
            if (!ids.add(message.clientMsgId())) {
                throw new IllegalStateException("Duplicate message " + message.clientMsgId());
            }
            if (message.conversationSequence() != expectedSequence++) {
                throw new IllegalStateException("Conversation sequence is not strictly increasing");
            }
            String[] parts = message.body().split("-");
            int sender = Integer.parseInt(parts[1]);
            int senderIndex = Integer.parseInt(parts[2]);
            if (!message.senderUserId().equals(username(sender))
                    || senderIndex <= lastIndex[sender][0]) {
                throw new IllegalStateException("Per-sender order changed for " + message.body());
            }
            lastIndex[sender][0] = senderIndex;
        }
        if (ids.size() != CLIENTS * PER_CLIENT) {
            throw new IllegalStateException("Message IDs are not unique");
        }
    }

    private static String username(int index) { return "stressa" + index; }

    private static char[] readPassword() throws Exception {
        String prefix = "EMBER_KEYSTORE_PASS=";
        for (String line : Files.readAllLines(Path.of("config", "secrets.properties"),
                StandardCharsets.UTF_8)) {
            if (line.startsWith(prefix)) { return line.substring(prefix.length()).toCharArray(); }
        }
        throw new IllegalStateException("Keystore password missing from config/secrets.properties");
    }

    private static void await(Check check, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (check.ready()) { return; }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Timed out waiting for " + label);
    }

    @FunctionalInterface
    private interface Check { boolean ready() throws Exception; }
}
