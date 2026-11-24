package edu.ember.client.call;

/**
 * Where call code gets the time. Production uses the system's monotonic clock; tests pass a fake
 * clock so timing behaviour (jitter, catch-up, ladder delays) is exact and repeatable.
 * @concept interfaces, dependency injection, testability
 */
@FunctionalInterface
public interface Clock {
    Clock SYSTEM = () -> System.nanoTime() / 1_000_000;

    long nowMs();
}
