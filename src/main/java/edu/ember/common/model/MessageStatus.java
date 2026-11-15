package edu.ember.common.model;

/** States a message can reach on its route from sender to recipient. @concept enums, state machine */
public enum MessageStatus {
    PENDING_LOCAL,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
