package edu.ember.server.storage;

import java.io.IOException;
import java.util.Map;

/**
 * A remote place that keeps named, immutable files. Ember's replica only needs these four
 * operations, so any file host (rootz.so, or an in-memory fake in tests) can plug in.
 * @concept interfaces, Strategy pattern
 */
public interface BlobStore {
    /** Returns every stored file whose name starts with the prefix, as name to file ID. */
    Map<String, String> list(String prefix) throws IOException;

    /** Uploads a new file and returns its ID. */
    String put(String name, byte[] data) throws IOException;

    byte[] get(String id) throws IOException;

    void delete(String id) throws IOException;
}
