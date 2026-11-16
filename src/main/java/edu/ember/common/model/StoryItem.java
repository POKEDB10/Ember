package edu.ember.common.model;

/** A photo story that is visible only until its expiry timestamp. @concept record, time-based state */
public record StoryItem(String storyId, String ownerUserId, String mediaId, String caption,
        long createdAtMs, long expiresAtMs) { }
