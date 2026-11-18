package edu.ember.common.model;

/**
 * A browser's Web Push subscription: where to deliver, plus the keys used to encrypt for it.
 * @concept records, immutability
 */
public record PushSubscription(String userId, String endpoint, String p256dh, String auth) {
}
