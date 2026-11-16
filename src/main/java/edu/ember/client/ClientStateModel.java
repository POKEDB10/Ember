package edu.ember.client;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.UserProfile;
import edu.ember.common.model.PostComment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import javax.swing.SwingUtilities;

/** Thread-safe client state observed by Swing views and headless tests. @concept MVC, Vector, Observer pattern */
public final class ClientStateModel {
    private final Vector<ClientModelListener> listeners = new Vector<>();
    private final Map<String, List<Message>> conversations = new HashMap<>();
    private final Map<String, ReceiptSummary> receiptSummaries = new HashMap<>();
    private final Map<String, Integer> groupMemberCounts = new HashMap<>();
    private final Map<String, Boolean> presence = new HashMap<>();
    private final Map<String, String> typingUsers = new HashMap<>();
    private final Map<String, UserProfile> profiles = new HashMap<>();
    private final Map<String, byte[]> mediaBytes = new HashMap<>();
    private final Map<String, List<PostComment>> comments = new HashMap<>();
    private List<SocialPost> socialPosts = List.of();
    private String lastUploadedMediaId;
    private String userId;
    private String activeConversationId;
    private String errorMessage;
    private String errorCode;
    private long lastGlobalSequence;

    public synchronized String userId() { return userId; }
    public synchronized String activeConversationId() { return activeConversationId; }
    public synchronized String errorMessage() { return errorMessage; }
    public synchronized String errorCode() { return errorCode; }
    public synchronized long lastGlobalSequence() { return lastGlobalSequence; }

    public void addListener(ClientModelListener listener) { listeners.add(listener); }
    public void removeListener(ClientModelListener listener) { listeners.remove(listener); }

    public synchronized List<Message> messages(String conversationId) {
        return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
    }

    public synchronized java.util.Set<String> conversationIds() {
        return java.util.Set.copyOf(conversations.keySet());
    }

    public synchronized ReceiptSummary receiptSummary(String clientMsgId) {
        return receiptSummaries.get(clientMsgId);
    }

    public synchronized int groupMemberCount(String groupId) {
        return groupMemberCounts.getOrDefault(groupId, 0);
    }

    public synchronized java.util.Set<String> groupIds() {
        return java.util.Set.copyOf(groupMemberCounts.keySet());
    }

    public synchronized boolean isOnline(String userId) {
        return presence.getOrDefault(userId, false);
    }

    public synchronized String typingUser(String conversationId) {
        return typingUsers.get(conversationId);
    }

    public synchronized List<SocialPost> socialPosts() { return List.copyOf(socialPosts); }
    public synchronized UserProfile profile(String userId) { return profiles.get(userId); }
    public synchronized List<PostComment> comments(String postId) {
        return List.copyOf(comments.getOrDefault(postId, List.of()));
    }
    public synchronized byte[] mediaBytes(String mediaId) {
        byte[] bytes = mediaBytes.get(mediaId);
        return bytes == null ? null : bytes.clone();
    }
    public synchronized String lastUploadedMediaId() { return lastUploadedMediaId; }

    synchronized void setSocialPosts(List<SocialPost> posts) {
        socialPosts = List.copyOf(posts);
        fire("SOCIAL_FEED", null);
    }

    synchronized void setProfile(UserProfile profile) {
        profiles.put(profile.userId(), profile);
        fire("PROFILE", null);
    }

    synchronized void setComments(String postId, List<PostComment> postComments) {
        comments.put(postId, List.copyOf(postComments));
        fire("COMMENTS", null);
    }

    synchronized void setMedia(String mediaId, byte[] bytes) {
        mediaBytes.put(mediaId, bytes.clone());
        fire("MEDIA", null);
    }

    synchronized void mediaUploaded(String mediaId) {
        lastUploadedMediaId = mediaId;
        fire("MEDIA_UPLOADED", null);
    }

    synchronized void authenticated(String newUserId) {
        userId = newUserId;
        errorMessage = null;
        errorCode = null;
        fire("AUTH_OK", null);
    }

    synchronized void openConversation(String conversationId) {
        activeConversationId = conversationId;
        fire("CONVERSATION_OPEN", null);
    }

    synchronized void addMessage(Message message) {
        addMessage(message, "MESSAGE");
    }

    synchronized void addHistoryMessage(Message message) {
        addMessage(message, "HISTORY_MESSAGE");
    }

    private void addMessage(Message message, String eventType) {
        lastGlobalSequence = Math.max(lastGlobalSequence, message.globalSequence());
        List<Message> list = conversations.computeIfAbsent(message.conversationId(),
                ignored -> new ArrayList<>());
        if (list.stream().noneMatch(item -> item.clientMsgId().equals(message.clientMsgId()))) {
            list.add(message);
        }
        fire(eventType, message);
    }

    synchronized void updateStatus(String clientMsgId, MessageStatus status) {
        Message found = findMessage(clientMsgId);
        if (found != null) {
            found.advanceStatus(status);
            fire("MESSAGE_STATUS", found);
        }
    }

    synchronized void updateAcknowledgement(String clientMsgId, long conversationSequence,
            long globalSequence) {
        lastGlobalSequence = Math.max(lastGlobalSequence, globalSequence);
        Message found = findMessage(clientMsgId);
        if (found != null) {
            found.assignSequences(conversationSequence, globalSequence);
        }
    }

    synchronized void updateReceipt(String clientMsgId, int delivered, int read, int total,
            String readBy) {
        ReceiptSummary previous = receiptSummaries.get(clientMsgId);
        java.util.LinkedHashSet<String> readers = new java.util.LinkedHashSet<>();
        if (previous != null && !previous.readBy().isBlank()) {
            readers.addAll(java.util.List.of(previous.readBy().split(", ")));
        }
        if (!readBy.isBlank()) {
            readers.addAll(java.util.List.of(readBy.split(", ")));
        }
        int newestDelivered = previous == null ? delivered : Math.max(previous.delivered(), delivered);
        int newestRead = previous == null ? read : Math.max(previous.read(), read);
        int newestTotal = Math.max(total, previous == null ? 0 : previous.total());
        String newestReaders = String.join(", ", readers);
        receiptSummaries.put(clientMsgId,
                new ReceiptSummary(newestDelivered, newestRead, newestTotal, newestReaders));
        if (newestRead >= newestTotal) {
            updateStatus(clientMsgId, MessageStatus.READ);
        } else if (newestDelivered >= newestTotal) {
            updateStatus(clientMsgId, MessageStatus.DELIVERED);
        }
        fire("RECEIPT", findMessage(clientMsgId));
    }

    synchronized void updateGroup(String groupId, int memberCount) {
        groupMemberCounts.put(groupId, memberCount);
        fire("GROUP_UPDATE", null);
    }

    synchronized void updatePresence(String userId, boolean online) {
        presence.put(userId, online);
        fire("PRESENCE", null);
    }

    synchronized void updateTyping(String conversationId, String userId, boolean typing) {
        if (typing) {
            typingUsers.put(conversationId, userId);
        } else {
            typingUsers.remove(conversationId, userId);
        }
        fire("TYPING", null);
    }

    synchronized void heartbeatReceived() {
        fire("PONG", null);
    }

    synchronized void setError(String code, String message) {
        errorCode = code;
        errorMessage = message;
        fire("ERROR", null);
    }

    private Message findMessage(String clientMsgId) {
        for (List<Message> list : conversations.values()) {
            for (Message message : list) {
                if (message.clientMsgId().equals(clientMsgId)) {
                    return message;
                }
            }
        }
        return null;
    }

    private void fire(String eventType, Message message) {
        Runnable notification = () -> {
            for (ClientModelListener listener : listeners) {
                listener.onModelChanged(this, eventType, message);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            notification.run();
        } else {
            SwingUtilities.invokeLater(notification);
        }
    }

    public record ReceiptSummary(int delivered, int read, int total, String readBy) {
    }
}
