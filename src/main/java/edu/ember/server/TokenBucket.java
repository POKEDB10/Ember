package edu.ember.server;

/** Refills tokens over monotonic time to limit request bursts. @concept concurrency, synchronized state */
public final class TokenBucket {
    private final double capacity;
    private final double tokensPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double tokensPerSecond) {
        if (capacity <= 0 || tokensPerSecond < 0) {
            throw new IllegalArgumentException("Invalid token bucket settings");
        }
        this.capacity = capacity;
        this.tokensPerSecond = tokensPerSecond;
        tokens = capacity;
        lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        return tryConsume(1.0);
    }

    /** Takes {@code amount} tokens (for example bytes) or none at all. */
    public synchronized boolean tryConsume(double amount) {
        refill();
        if (tokens < amount) {
            return false;
        }
        tokens -= amount;
        return true;
    }

    public synchronized double availableTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSeconds * tokensPerSecond);
        lastRefillNanos = now;
    }
}
