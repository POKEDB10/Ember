package edu.ember.server;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Issues and validates reconnect tokens associated with authenticated users. @concept maps, encapsulation */
public final class SessionStore {
    private static final long IDLE_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final long ABSOLUTE_TIMEOUT_MS = Duration.ofHours(12).toMillis();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public String create(String userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        sessions.put(token, new Session(userId, now, now));
        return token;
    }

    public String findUserId(String token) {
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastUsedMs > IDLE_TIMEOUT_MS
                || now - session.createdAtMs > ABSOLUTE_TIMEOUT_MS) {
            sessions.remove(token);
            return null;
        }
        session.lastUsedMs = now;
        return session.userId;
    }

    public void remove(String token) {
        sessions.remove(token);
    }

    /** Stores a token's user and timeout timestamps. @concept inner classes, encapsulation */
    private static final class Session {
        private final String userId;
        private final long createdAtMs;
        private volatile long lastUsedMs;

        private Session(String userId, long createdAtMs, long lastUsedMs) {
            this.userId = userId;
            this.createdAtMs = createdAtMs;
            this.lastUsedMs = lastUsedMs;
        }
    }
}
