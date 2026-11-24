package edu.ember.client.call;

/**
 * Where encoded voice packets go. Production sends them through the encrypted relay; tests use a
 * simulated transport that adds delay, loss and reordering on a fake clock.
 * @concept interfaces, dependency injection, testability
 */
@FunctionalInterface
public interface Transport {
    /** Sends one encoded frame of the given codec, with its sequence number. */
    void send(long sequence, int codecId, byte[] frame);
}
