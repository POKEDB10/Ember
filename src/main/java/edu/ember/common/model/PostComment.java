package edu.ember.common.model;

/** Represents one plain-text comment attached to a social post. @concept records, UTF-8 text */
public record PostComment(String commentId, String postId, String authorUserId, String text,
        long createdAtMs) {
}
