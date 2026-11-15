package edu.ember.server;

import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Periodically asks the storage engine to flush durable records. @concept daemon threads, interruption */
public final class AutoSaveThread extends Thread {
    private static final Logger LOGGER = Logger.getLogger(AutoSaveThread.class.getName());
    private final StorageEngine storage;
    private final long intervalMs;

    public AutoSaveThread(StorageEngine storage, long intervalMs) {
        super("ember-autosave");
        this.storage = storage;
        this.intervalMs = intervalMs;
        setDaemon(true);
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
                storage.flush();
            } catch (InterruptedException exception) {
                interrupt();
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Storage flush failed", exception);
            }
        }
    }
}
