package edu.ember.common.model;

import java.util.Objects;

/** A persisted direct message with server-assigned conversation order. @concept encapsulation, synchronized state */
public final class Message {
    private final String clientMsgId;
    private final String senderUserId;
    private final String conversationId;
    private final String body;
    private final long createdAtMs;
    private long conversationSequence;
    private long globalSequence;
    private MessageStatus status;

    public Message(String clientMsgId, String senderUserId, String conversationId,
            String body, long createdAtMs) {
        this.clientMsgId = Objects.requireNonNull(clientMsgId);
        this.senderUserId = Objects.requireNonNull(senderUserId);
        this.conversationId = Objects.requireNonNull(conversationId);
        this.body = Objects.requireNonNull(body);
        this.createdAtMs = createdAtMs;
        status = MessageStatus.PENDING_LOCAL;
    }

    public String clientMsgId() { return clientMsgId; }
    public String senderUserId() { return senderUserId; }
    public String conversationId() { return conversationId; }
    public String body() { return body; }
    public long createdAtMs() { return createdAtMs; }
    public synchronized long conversationSequence() { return conversationSequence; }
    public synchronized long globalSequence() { return globalSequence; }
    public synchronized MessageStatus status() { return status; }

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
}
