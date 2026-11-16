package edu.ember.common.model;

/** Public biography and media reference for one account. @concept records, encapsulation */
public record UserProfile(String userId, String displayName, String bio, String avatarMediaId,
        boolean privateAccount) {
    public UserProfile(String userId, String displayName, String bio, String avatarMediaId) {
        this(userId, displayName, bio, avatarMediaId, false);
    }
}
