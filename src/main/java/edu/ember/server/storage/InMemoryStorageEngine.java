package edu.ember.server.storage;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.UserRecord;
import edu.ember.common.protocol.FrameFactory;
import edu.ember.common.protocol.WireOpcodes;
import edu.ember.common.protocol.WireFrame;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores account and message state in process memory for the walking skeleton. @concept DAO, synchronized collections */
public final class InMemoryStorageEngine implements StorageEngine {
    private final Map<String, UserRecord> users = new HashMap<>();
    private final Map<String, Message> messagesById = new HashMap<>();
    private final Map<String, Long> conversationSequences = new HashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private long globalSequence;

    @Override public void init() { }

    @Override
    public synchronized void saveUser(UserRecord user) throws IOException {
        String key = user.username().toLowerCase(java.util.Locale.ROOT);
        if (users.containsKey(key)) {
            throw new IOException("Username already exists");
        }
        users.put(key, user);
    }

    @Override
    public synchronized Optional<UserRecord> findUser(String username) {
        return Optional.ofNullable(users.get(username.toLowerCase(java.util.Locale.ROOT)));
    }

    @Override
    public synchronized long appendMessage(Message message) throws IOException {
        String idempotencyKey = message.senderUserId() + "\n" + message.clientMsgId();
        Message existing = messagesById.get(idempotencyKey);
        if (existing != null) {
            return existing.conversationSequence();
        }
        long nextConversation = conversationSequences.getOrDefault(message.conversationId(), 0L) + 1;
        long nextGlobal = ++globalSequence;
        message.assignSequences(nextConversation, nextGlobal);
        message.advanceStatus(MessageStatus.SENT);
        conversationSequences.put(message.conversationId(), nextConversation);
        messagesById.put(idempotencyKey, message);
        messages.add(message);
        return nextConversation;
    }

    @Override
    public synchronized void updateMessageStatus(String clientMsgId, MessageStatus status) {
        for (Message message : messages) {
            if (message.clientMsgId().equals(clientMsgId)) {
                message.advanceStatus(status);
            }
        }
    }

    @Override
    public synchronized Optional<Message> findBySenderAndClientMsgId(String senderUserId,
            String clientMsgId) {
        return Optional.ofNullable(messagesById.get(senderUserId + "\n" + clientMsgId));
    }

    @Override
    public synchronized Optional<Message> findByClientMsgId(String clientMsgId) {
        return messages.stream().filter(message -> message.clientMsgId().equals(clientMsgId))
                .findFirst();
    }

    @Override
    public synchronized List<WireFrame> getDeltaFramesSince(String userId, long sinceGlobalSeq) {
        List<WireFrame> result = new ArrayList<>();
        for (Message message : messages) {
            if (message.globalSequence() > sinceGlobalSeq && isParticipant(message, userId)) {
                Map<String, String> fields = messageFields(message);
                result.add(FrameFactory.event(WireOpcodes.EVT_SYNC_ITEM,
                        message.globalSequence(), fields));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<Message> getHistory(String conversationId, long beforeSequence,
            int limit) {
        List<Message> result = new ArrayList<>();
        for (Message message : messages) {
            if (message.conversationId().equals(conversationId)
                    && message.conversationSequence() < beforeSequence
                    && result.size() < limit) {
                result.add(message);
            }
        }
        return List.copyOf(result);
    }

    @Override public int purgeExpiredRecords(long nowMs) { return 0; }
    @Override public void close() { }

    private static Map<String, String> messageFields(Message message) {
        Map<String, String> fields = new HashMap<>();
        fields.put("clientMsgId", message.clientMsgId());
        fields.put("senderUserId", message.senderUserId());
        fields.put("convId", message.conversationId());
        fields.put("body", message.body());
        fields.put("convSeq", Long.toString(message.conversationSequence()));
        fields.put("globalSeq", Long.toString(message.globalSequence()));
        fields.put("serverTs", Long.toString(message.createdAtMs()));
        return fields;
    }

    private static boolean isParticipant(Message message, String userId) {
        if (message.senderUserId().equals(userId)) {
            return true;
        }
        String conversation = message.conversationId();
        if (!conversation.startsWith("dm:")) {
            return false;
        }
        String[] participants = conversation.substring(3).split(":", -1);
        return participants.length == 2
                && (participants[0].equals(userId) || participants[1].equals(userId));
    }
}
