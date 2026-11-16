package edu.ember.common.model;

/** A user-submitted report for a profile, post, or message. @concept record, data modeling */
public record ModerationReport(String reportId, String reporterUserId, String targetType,
        String targetId, String reason, long createdAtMs) { }
