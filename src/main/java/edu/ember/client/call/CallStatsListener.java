package edu.ember.client.call;

/** Receives a stats snapshot about once a second during a call (Observer pattern). @concept interfaces, Observer pattern */
@FunctionalInterface
public interface CallStatsListener {
    void statsChanged(CallStats stats);
}
