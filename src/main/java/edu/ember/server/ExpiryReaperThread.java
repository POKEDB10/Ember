package edu.ember.server;

import edu.ember.common.model.Message;
import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Purges expired messages and asks the server to notify live clients. @concept daemon thread, Iterator */
public final class ExpiryReaperThread extends Thread {
    private static final Logger LOGGER = Logger.getLogger(ExpiryReaperThread.class.getName());
    private static final long INTERVAL_MS = 5_000;
    private final StorageEngine storage;
    private final Consumer<Message> expiredNotifier;

    public ExpiryReaperThread(StorageEngine storage, Consumer<Message> expiredNotifier) {
        super("ember-expiry-reaper");
        this.storage = storage;
        this.expiredNotifier = expiredNotifier;
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
    }

    @Override public void run() {
        while (!isInterrupted()) {
            try {
                Thread.sleep(INTERVAL_MS);
                reap(System.currentTimeMillis());
            } catch (InterruptedException exception) {
                interrupt();
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Message expiry scan failed", exception);
            }
        }
    }

    private void reap(long nowMs) throws IOException {
        List<Message> expired = storage.expiredMessages(nowMs);
        storage.purgeExpiredRecords(nowMs);
        expired.forEach(expiredNotifier);
    }
}
