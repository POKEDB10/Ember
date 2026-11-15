package edu.ember.server;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Applies optional per-IP throttling while leaving shared loopback demos unaffected. @concept Strategy, maps */
public final class AuthRateLimiter {
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public boolean allow(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return true;
        }
        String key = address.getHostAddress();
        return buckets.computeIfAbsent(key, ignored -> new TokenBucket(10, 1.0)).tryConsume();
    }
}
