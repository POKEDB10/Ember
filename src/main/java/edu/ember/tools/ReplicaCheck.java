package edu.ember.tools;

import edu.ember.common.model.Message;
import edu.ember.common.model.UserRecord;
import edu.ember.server.storage.BlobStoreReplica;
import edu.ember.server.storage.RootzBlobStore;
import edu.ember.server.storage.WalStorageEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

/**
 * Live check of the rootz.so replica: raw API calls, then a full encrypted save-and-restore under
 * a throwaway file prefix, then cleanup. Needs ROOTZ_API_KEY (environment or
 * config/secrets.properties). Never touches the real server's "ember-" files.
 * @concept integration testing, exception handling
 */
public final class ReplicaCheck {
    private ReplicaCheck() {
    }

    public static void main(String[] args) throws Exception {
        RootzBlobStore store = new RootzBlobStore(secret("ROOTZ_API_KEY"), secret("ROOTZ_FOLDER_ID"));
        String prefix = "embercheck-" + Long.toHexString(new SecureRandom().nextLong()) + "-";
        try {
            String id = store.put(prefix + "raw.one", "hello".getBytes(StandardCharsets.UTF_8));
            check(store.list(prefix).containsValue(id), "uploaded file is listed");
            check("hello".equals(new String(store.get(id), StandardCharsets.UTF_8)),
                    "downloaded bytes match");
            store.delete(id);
            boolean gone;
            try {
                store.get(id);
                gone = false;
            } catch (java.io.IOException expected) {
                gone = true; // rootz keeps listing deleted files for a while, but they 404
            }
            check(gone, "deleted file is gone");

            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            WalStorageEngine writer = new WalStorageEngine(Files.createTempDirectory("ember-check-a"),
                    false, new BlobStoreReplica(store, key, 500, prefix));
            writer.init();
            writer.saveUser(new UserRecord("check-user", "check-user", "Check", "hash"));
            writer.appendMessage(new Message("check-msg", "check-user", "dm:bob:check-user",
                    "rootz round trip 🌋", System.currentTimeMillis()));
            writer.mirrorMedia("check.jpg", new byte[] {7, 7, 7});
            writer.close();
            WalStorageEngine reader = new WalStorageEngine(Files.createTempDirectory("ember-check-b"),
                    false, new BlobStoreReplica(store, key, 500, prefix));
            reader.init();
            check("rootz round trip 🌋".equals(reader.findByClientMsgId("check-msg")
                    .map(Message::body).orElse(null)), "fresh server restored the message");
            check(java.util.Arrays.equals(new byte[] {7, 7, 7}, reader.fetchMirroredMedia("check.jpg")),
                    "fresh server restored the media file");
            reader.close();
            System.out.println("Replica check passed.");
        } finally {
            for (Map.Entry<String, String> file : store.list(prefix).entrySet()) {
                store.delete(file.getValue());
            }
            System.out.println("Cleaned up test files with prefix " + prefix);
        }
    }

    private static void check(boolean condition, String step) {
        if (!condition) {
            throw new IllegalStateException("FAILED: " + step);
        }
        System.out.println("OK: " + step);
    }

    private static String secret(String name) throws java.io.IOException {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value.strip();
        }
        Path secrets = Path.of("config", "secrets.properties");
        if (Files.exists(secrets)) {
            for (String line : Files.readAllLines(secrets, StandardCharsets.UTF_8)) {
                if (line.startsWith(name + "=")) {
                    return line.substring(name.length() + 1).strip();
                }
            }
        }
        return null;
    }
}
