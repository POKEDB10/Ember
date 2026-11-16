package edu.ember.common.model;

/** Represents a public campus-feed post with viewer-specific like state. @concept records, model */
public record SocialPost(String postId, String ownerUserId, String mediaId, String caption,
        long createdAtMs, int likeCount, int commentCount, boolean likedByViewer) {
}
