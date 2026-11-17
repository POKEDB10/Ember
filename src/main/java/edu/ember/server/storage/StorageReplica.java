package edu.ember.server.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Keeps an off-server copy of the WAL, snapshots and media so the server's own disk can be
 * thrown away (free hosts wipe it on every restart). @concept interfaces, Strategy pattern
 */
public interface StorageReplica extends AutoCloseable {
    /** Writes the newest remote snapshot and log into place; false when the replica is empty. */
    boolean restore(Path snapshotPath, Path walPath) throws IOException;

    /** Queues raw WAL record bytes for the next batched upload. */
    void appendLog(byte[] records);

    /** Uploads a new snapshot that covers every record queued so far. */
    void replaceSnapshot(byte[] snapshotFile);

    void putMedia(String name, byte[] bytes);

    /** Returns the media file, or null when the replica does not hold it. */
    byte[] getMedia(String name) throws IOException;

    boolean hasMedia(String name);

    /** Uploads everything still queued, then stops. */
    @Override
    void close() throws IOException;
}
