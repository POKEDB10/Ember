package edu.ember.client;

import java.util.concurrent.ThreadLocalRandom;

/** Retries a connection with bounded exponential backoff and jitter. @concept threads, backoff algorithm */
public final class ReconnectManager extends Thread {
    private final ConnectionAttempt connectionAttempt;
    private final ReconnectListener listener;
    private volatile boolean cancelled;

    public ReconnectManager(ConnectionAttempt connectionAttempt, ReconnectListener listener) {
        super("ember-reconnect-manager");
        this.connectionAttempt = connectionAttempt;
        this.listener = listener;
        setDaemon(true);
    }

    public void cancelReconnect() {
        cancelled = true;
        interrupt();
    }

    @Override
    public void run() {
        int attempt = 0;
        while (!cancelled) {
            try {
                Thread.sleep(nextDelayMillis(attempt++));
                if (cancelled) {
                    return;
                }
                ClientController controller = connectionAttempt.connect();
                listener.onReconnected(controller);
                return;
            } catch (InterruptedException exception) {
                interrupt();
                return;
            } catch (Exception exception) {
                listener.onAttemptFailed(exception);
            }
        }
    }

    public static long nextDelayMillis(int attempt) {
        int exponent = Math.min(Math.max(attempt, 0), 5);
        long backoff = Math.min(15_000L, 500L * (1L << exponent));
        return backoff + ThreadLocalRandom.current().nextLong(251);
    }

    @FunctionalInterface
    public interface ConnectionAttempt {
        ClientController connect() throws Exception;
    }

    public interface ReconnectListener {
        void onReconnected(ClientController controller);
        void onAttemptFailed(Exception exception);
    }
}
