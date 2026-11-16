package edu.ember.common.model;

import java.util.Objects;

/** A persisted direct message with server-assigned conversation order. @concept encapsulation, synchronized state */
public final class Message {
    private final String clientMsgId;
    private final String senderUserId;
    private final String conversationId;
    private volatile String body;
    private final long createdAtMs;
    private final int ttlSec;
    private final String replyToId;
    private final String replyPreview;
    private final java.util.Map<String, java.util.Set<String>> reactions = new java.util.HashMap<>();
    private volatile long expiresAtMs;
    private long conversationSequence;
    private long globalSequence;
    private MessageStatus status;
    private boolean pinned;
    private final java.util.Set<String> starredBy = new java.util.HashSet<>();

    public Message(String clientMsgId, String senderUserId, String conversationId,
            String body, long createdAtMs) {
        this(clientMsgId, senderUserId, conversationId, body, createdAtMs, 0);
    }

    public Message(String clientMsgId, String senderUserId, String conversationId,
            String body, long createdAtMs, int ttlSec) {
        this(clientMsgId, senderUserId, conversationId, body, createdAtMs, ttlSec, "");
    }

    public Message(String clientMsgId, String senderUserId, String conversationId,
            String body, long createdAtMs, int ttlSec, String replyToId) {
        this(clientMsgId, senderUserId, conversationId, body, createdAtMs, ttlSec, replyToId, "");
    }

    public Message(String clientMsgId, String senderUserId, String conversationId,
            String body, long createdAtMs, int ttlSec, String replyToId, String replyPreview) {
        this.clientMsgId = Objects.requireNonNull(clientMsgId);
        this.senderUserId = Objects.requireNonNull(senderUserId);
        this.conversationId = Objects.requireNonNull(conversationId);
        this.body = Objects.requireNonNull(body);
        this.createdAtMs = createdAtMs;
        if (ttlSec != 0 && ttlSec != 15) {
            throw new IllegalArgumentException("Supported message TTL values are 0 and 15 seconds");
        }
        this.ttlSec = ttlSec;
        this.replyToId = replyToId == null ? "" : replyToId;
        this.replyPreview = replyPreview == null ? "" : replyPreview;
        expiresAtMs = ttlSec == 0 ? 0 : createdAtMs + ttlSec * 1000L;
        status = MessageStatus.PENDING_LOCAL;
    }

    public Message(Message source, String newClientMsgId, String newSenderUserId,
            String targetConversationId, long forwardedAtMs) {
        this(newClientMsgId, newSenderUserId, targetConversationId,
                new String(Objects.requireNonNull(source).body()), forwardedAtMs);
    }

    public String clientMsgId() { return clientMsgId; }
    public String senderUserId() { return senderUserId; }
    public String conversationId() { return conversationId; }
    public String body() { return body; }
    public void editBody(String updatedBody) { body = Objects.requireNonNull(updatedBody); }
    public long createdAtMs() { return createdAtMs; }
    public int ttlSec() { return ttlSec; }
    public String replyToId() { return replyToId; }
    public String replyPreview() { return replyPreview; }
    public long expiresAtMs() { return expiresAtMs; }
    public void setExpiresAtMs(long expiresAt) { expiresAtMs = expiresAt; }
    public synchronized long conversationSequence() { return conversationSequence; }
    public synchronized long globalSequence() { return globalSequence; }
    public synchronized MessageStatus status() { return status; }
    public synchronized boolean pinned() { return pinned; }
    public synchronized boolean starredBy(String userId) { return starredBy.contains(userId); }
    public synchronized void setPinned(boolean value) { pinned = value; }
    public synchronized void setStarred(String userId, boolean value) {
        if (value) { starredBy.add(userId); } else { starredBy.remove(userId); }
    }

    public synchronized void assignSequences(long conversationSeq, long globalSeq) {
        conversationSequence = conversationSeq;
        globalSequence = globalSeq;
    }

    public synchronized boolean advanceStatus(MessageStatus next) {
        if (next == MessageStatus.FAILED || next.ordinal() < status.ordinal()) {
            return false;
        }
        status = next;
        return true;
    }

    public synchronized void setReaction(String userId, String emoji, boolean selected) {
        java.util.Set<String> users = reactions.computeIfAbsent(emoji,
                ignored -> new java.util.HashSet<>());
        if (selected) { users.add(userId); } else { users.remove(userId); }
        if (users.isEmpty()) { reactions.remove(emoji); }
    }

    public synchronized java.util.Map<String, Integer> reactionCounts() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        reactions.forEach((emoji, users) -> counts.put(emoji, users.size()));
        return java.util.Map.copyOf(counts);
    }

    public synchronized void setReactionCount(String emoji, int count) {
        java.util.Set<String> users = reactions.computeIfAbsent(emoji,
                ignored -> new java.util.HashSet<>());
        users.clear();
        for (int index = 0; index < count; index++) { users.add("remote-" + index); }
        if (users.isEmpty()) { reactions.remove(emoji); }
    }
}
