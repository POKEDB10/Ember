package edu.ember.server.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Mirrors the WAL to a {@link BlobStore}: records are batched, compressed, AES-GCM encrypted and
 * uploaded every few seconds, so the remote host only ever holds unreadable data.
 *
 * <p>Remote layout (every file name starts with {@code ember-}):
 * <ul>
 *   <li>{@code snap-G}: snapshot generation G, covering every log of earlier generations</li>
 *   <li>{@code log-G-K}: WAL bytes written after snapshot G; K increases across all logs</li>
 *   <li>{@code media-NAME}: an uploaded image</li>
 * </ul>
 * A blob is stored as {@code NAME.one}, or as parts {@code NAME.p0..pN} followed by a marker
 * {@code NAME.nCOUNT}, so a half-uploaded blob is never read. Restore takes the newest complete
 * snapshot S plus every log of generation S or later, in K order.
 * @concept interfaces, threads, encryption, collections
 */
public final class BlobStoreReplica implements StorageReplica {
    private static final Logger LOGGER = Logger.getLogger(BlobStoreReplica.class.getName());
    /** rootz's simple upload takes files under 4 MB; bigger blobs are split. */
    private static final int PART_BYTES = 3_500_000;
    private static final int IV_BYTES = 12;
    /** Only names this replica writes; anything else in the account is ignored. */
    private final java.util.regex.Pattern file;
    private static final java.util.regex.Pattern SNAP = java.util.regex.Pattern.compile("snap-(\\d+)");
    private static final java.util.regex.Pattern LOG =
            java.util.regex.Pattern.compile("log-(\\d+)-(\\d+)");
    private final String prefix;
    private final BlobStore store;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    /** Remote file name to file ID; the only list call is made at start-up. */
    private final Map<String, String> remote = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final ScheduledExecutorService uploader = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "ember-replica-upload");
                thread.setDaemon(true);
                return thread;
            });
    private long writeGeneration;
    private long nextLogNumber;

    public BlobStoreReplica(BlobStore store, SecretKey key, long flushMillis) {
        this(store, key, flushMillis, "ember-");
    }

    /** prefix namespaces the remote files, so a test run can never touch real data. */
    public BlobStoreReplica(BlobStore store, SecretKey key, long flushMillis, String prefix) {
        this.prefix = prefix;
        this.file = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(prefix) + ".+\\.(one|p\\d+|n\\d+)");
        this.store = store;
        this.key = key;
        uploader.scheduleWithFixedDelay(this::flushLog, flushMillis, flushMillis,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean restore(Path snapshotPath, Path walPath) throws IOException {
        for (Map.Entry<String, String> file : store.list(prefix).entrySet()) {
            if (this.file.matcher(file.getKey()).matches()) {
                remote.put(file.getKey(), file.getValue());
            }
        }
        long snapshotGeneration = -1;
        List<long[]> logs = new ArrayList<>(); // {generation, number}
        for (String logical : completeBlobs()) {
            java.util.regex.Matcher snap = SNAP.matcher(logical);
            java.util.regex.Matcher log = LOG.matcher(logical);
            if (snap.matches()) {
                snapshotGeneration = Math.max(snapshotGeneration, Long.parseLong(snap.group(1)));
            } else if (log.matches()) {
                logs.add(new long[] {Long.parseLong(log.group(1)), Long.parseLong(log.group(2))});
            }
        }
        long firstGeneration = Math.max(snapshotGeneration, 0);
        logs.removeIf(log -> log[0] < firstGeneration);
        logs.sort((first, second) -> Long.compare(first[1], second[1]));
        if (snapshotGeneration < 0 && logs.isEmpty()) {
            return false;
        }
        Files.createDirectories(snapshotPath.getParent());
        Files.createDirectories(walPath.getParent());
        if (snapshotGeneration >= 0) {
            Files.write(snapshotPath, getBlob("snap-" + snapshotGeneration));
        } else {
            Files.deleteIfExists(snapshotPath);
        }
        ByteArrayOutputStream wal = new ByteArrayOutputStream();
        long generation = firstGeneration;
        long number = -1;
        for (long[] log : logs) {
            wal.write(getBlob("log-" + log[0] + "-" + log[1]));
            generation = Math.max(generation, log[0]);
            number = Math.max(number, log[1]);
        }
        Files.write(walPath, wal.toByteArray());
        synchronized (lock) {
            writeGeneration = generation;
            nextLogNumber = number + 1;
        }
        return true;
    }

    @Override
    public void appendLog(byte[] records) {
        synchronized (lock) {
            pending.writeBytes(records);
        }
    }

    @Override
    public void replaceSnapshot(byte[] snapshotFile) {
        byte[] covered;
        String coveredName;
        long generation;
        synchronized (lock) {
            covered = pending.toByteArray();
            pending.reset();
            coveredName = covered.length == 0 ? null
                    : "log-" + writeGeneration + "-" + nextLogNumber++;
            generation = ++writeGeneration;
        }
        uploader.execute(() -> {
            try {
                // Upload the covered records first, so a failed snapshot upload loses nothing.
                if (coveredName != null) {
                    putBlob(coveredName, covered);
                }
                putBlob("snap-" + generation, snapshotFile);
                deleteGenerationsBefore(generation);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Snapshot upload failed; logs still cover the data",
                        exception);
            }
        });
    }

    @Override
    public void putMedia(String name, byte[] bytes) {
        uploader.execute(() -> {
            try {
                putBlob("media-" + name, bytes);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Media upload failed: " + name, exception);
            }
        });
    }

    @Override
    public byte[] getMedia(String name) throws IOException {
        return hasMedia(name) ? getBlob("media-" + name) : null;
    }

    @Override
    public boolean hasMedia(String name) {
        return completeParts("media-" + name) > 0;
    }

    @Override
    public void close() throws IOException {
        uploader.execute(this::flushLog);
        uploader.shutdown();
        try {
            if (!uploader.awaitTermination(110, TimeUnit.SECONDS)) {
                throw new IOException("Replica upload did not finish in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while finishing replica upload", exception);
        }
    }

    /** Runs on the uploader thread only, so logs leave in K order. */
    private void flushLog() {
        byte[] data;
        String name;
        synchronized (lock) {
            if (pending.size() == 0) {
                return;
            }
            data = pending.toByteArray();
            pending.reset();
            name = "log-" + writeGeneration + "-" + nextLogNumber++;
        }
        try {
            putBlob(name, data);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Log upload failed; will retry with the next batch", exception);
            synchronized (lock) {
                byte[] newer = pending.toByteArray();
                pending.reset();
                pending.writeBytes(data);
                pending.writeBytes(newer);
            }
        }
    }

    private void deleteGenerationsBefore(long generation) {
        for (Map.Entry<String, String> file : remote.entrySet()) {
            String logical = logicalName(file.getKey());
            java.util.regex.Matcher snap = SNAP.matcher(logical);
            java.util.regex.Matcher log = LOG.matcher(logical);
            boolean old = (snap.matches() && Long.parseLong(snap.group(1)) < generation)
                    || (log.matches() && Long.parseLong(log.group(1)) < generation);
            if (old) {
                try {
                    store.delete(file.getValue());
                    remote.remove(file.getKey());
                } catch (IOException exception) {
                    LOGGER.log(Level.FINE, "Old replica file not deleted yet", exception);
                }
            }
        }
    }

    private void putBlob(String logical, byte[] plain) throws IOException {
        boolean compress = !logical.startsWith("media-"); // images are already compressed
        byte[] sealed = seal(logical, compress ? gzip(plain) : plain);
        if (sealed.length <= PART_BYTES) {
            upload(prefix + logical + ".one", sealed);
            return;
        }
        int count = (sealed.length + PART_BYTES - 1) / PART_BYTES;
        for (int part = 0; part < count; part++) {
            int start = part * PART_BYTES;
            upload(prefix + logical + ".p" + part, java.util.Arrays.copyOfRange(sealed, start,
                    Math.min(sealed.length, start + PART_BYTES)));
        }
        upload(prefix + logical + ".n" + count, new byte[] {1});
    }

    private byte[] getBlob(String logical) throws IOException {
        int count = completeParts(logical);
        if (count == 0) {
            throw new IOException("Replica file is missing: " + logical);
        }
        byte[] sealed;
        if (remote.containsKey(prefix + logical + ".one")) {
            sealed = retry(() -> store.get(remote.get(prefix + logical + ".one")));
        } else {
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (int part = 0; part < count; part++) {
                String id = remote.get(prefix + logical + ".p" + part);
                joined.writeBytes(retry(() -> store.get(id)));
            }
            sealed = joined.toByteArray();
        }
        byte[] plain = open(logical, sealed);
        return logical.startsWith("media-") ? plain : gunzip(plain);
    }

    private void upload(String name, byte[] data) throws IOException {
        remote.put(name, retry(() -> store.put(name, data)));
    }

    /** Logical names whose upload finished, i.e. a .one file or a complete set of parts. */
    private List<String> completeBlobs() {
        List<String> names = new ArrayList<>();
        for (String file : remote.keySet()) {
            String logical = logicalName(file);
            String suffix = file.substring(prefix.length() + logical.length() + 1);
            if ((suffix.equals("one") || suffix.startsWith("n")) && completeParts(logical) > 0) {
                names.add(logical);
            }
        }
        return names;
    }

    /** Part count of a finished blob, or 0 when it is missing or half uploaded. */
    private int completeParts(String logical) {
        if (remote.containsKey(prefix + logical + ".one")) {
            return 1;
        }
        for (String file : remote.keySet()) {
            if (file.startsWith(prefix + logical + ".n") && logicalName(file).equals(logical)) {
                int count = Integer.parseInt(file.substring(prefix.length() + logical.length() + 2));
                for (int part = 0; part < count; part++) {
                    if (!remote.containsKey(prefix + logical + ".p" + part)) {
                        return 0;
                    }
                }
                return count;
            }
        }
        return 0;
    }

    private String logicalName(String file) {
        return file.substring(prefix.length(), file.lastIndexOf('.'));
    }

    /** AES-256-GCM with a fresh IV; the logical name is bound in, so files cannot be swapped. */
    private byte[] seal(String logical, byte[] plain) throws IOException {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(logical.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain);
            byte[] result = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, result, 0, IV_BYTES);
            System.arraycopy(sealed, 0, result, IV_BYTES, sealed.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Encryption failed", exception);
        }
    }

    private byte[] open(String logical, byte[] sealed) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, IV_BYTES));
            cipher.updateAAD(logical.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Replica data could not be decrypted; check EMBER_STORAGE_KEY",
                    exception);
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(raw);
        }
        return bytes.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    /** A remote call that may fail on a flaky network. @concept functional interfaces */
    @FunctionalInterface
    private interface RemoteCall<T> {
        T run() throws IOException;
    }

    private static <T> T retry(RemoteCall<T> call) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return call.run();
            } catch (IOException exception) {
                last = exception;
                try {
                    Thread.sleep(1_000L << (attempt * 2)); // 1 s, 4 s
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        throw last;
    }
}
