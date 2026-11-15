package edu.ember.server;

import java.util.HashMap;
import java.util.Map;

/** Tracks failed logins and temporary per-username lockouts. @concept synchronized state, maps */
public final class UserLockManager {
    private static final int FAILURE_LIMIT = 5;
    private static final long FAILURE_WINDOW_MS = 10 * 60 * 1000L;
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000L;
    private final Map<String, FailureWindow> failures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();

    public synchronized boolean isLocked(String username) {
        String key = normalize(username);
        Long until = lockedUntil.get(key);
        if (until != null && until <= System.currentTimeMillis()) {
            lockedUntil.remove(key);
            return false;
        }
        return until != null;
    }

    public synchronized boolean recordFailure(String username) {
        String key = normalize(username);
        long now = System.currentTimeMillis();
        FailureWindow window = failures.get(key);
        if (window == null || now - window.startedAtMs > FAILURE_WINDOW_MS) {
            window = new FailureWindow(now, 0);
            failures.put(key, window);
        }
        window.count++;
        if (window.count >= FAILURE_LIMIT) {
            lockedUntil.put(key, now + LOCK_DURATION_MS);
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess(String username) {
        String key = normalize(username);
        failures.remove(key);
    }

    public synchronized boolean unlock(String username) {
        String key = normalize(username);
        failures.remove(key);
        return lockedUntil.remove(key) != null;
    }

    private static String normalize(String username) {
        return username.toLowerCase(java.util.Locale.ROOT);
    }

    private static final class FailureWindow {
        private final long startedAtMs;
        private int count;

        private FailureWindow(long startedAtMs, int count) {
            this.startedAtMs = startedAtMs;
            this.count = count;
        }
    }
}
