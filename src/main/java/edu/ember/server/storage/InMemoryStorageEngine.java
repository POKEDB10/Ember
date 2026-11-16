package edu.ember.server.storage;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.GroupChat;
import edu.ember.common.model.UserRecord;
import edu.ember.common.model.UserProfile;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.PostComment;
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
    private final Map<String, GroupChat> groups = new HashMap<>();
    private final Map<String, UserProfile> profiles = new HashMap<>();
    private final Map<String, MediaAsset> media = new HashMap<>();
    private final Map<String, SocialPost> posts = new HashMap<>();
    private final Map<String, java.util.Set<String>> postLikes = new HashMap<>();
    private final Map<String, List<PostComment>> comments = new HashMap<>();
    private final Map<String, java.util.Set<String>> follows = new HashMap<>();
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
        profiles.putIfAbsent(user.userId(), new UserProfile(user.userId(), user.displayName(), "", ""));
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
    public synchronized void saveGroup(GroupChat group) {
        groups.put(group.groupId(), new GroupChat(group.groupId(), group.name(),
                group.description(), group.members()));
    }

    @Override
    public synchronized Optional<GroupChat> findGroup(String groupId) {
        GroupChat group = groups.get(groupId);
        return group == null ? Optional.empty() : Optional.of(new GroupChat(group.groupId(),
                group.name(), group.description(), group.members()));
    }

    @Override public synchronized void saveProfile(UserProfile profile) {
        profiles.put(profile.userId(), profile);
    }

    @Override public synchronized Optional<UserProfile> findProfile(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override public synchronized void setFollow(String followerId, String followedId,
            boolean following) {
        java.util.Set<String> targets = follows.computeIfAbsent(followerId,
                ignored -> new java.util.HashSet<>());
        if (following) { targets.add(followedId); } else { targets.remove(followedId); }
    }

    @Override public synchronized List<String> followersOf(String userId) {
        return follows.entrySet().stream().filter(entry -> entry.getValue().contains(userId))
                .map(Map.Entry::getKey).sorted().toList();
    }

    @Override public synchronized List<String> followingOf(String userId) {
        return follows.getOrDefault(userId, java.util.Set.of()).stream().sorted().toList();
    }

    @Override public synchronized void saveMedia(MediaAsset asset) { media.put(asset.mediaId(), asset); }

    @Override public synchronized Optional<MediaAsset> findMedia(String mediaId) {
        return Optional.ofNullable(media.get(mediaId));
    }

    @Override public synchronized void savePost(SocialPost post) {
        posts.put(post.postId(), new SocialPost(post.postId(), post.ownerUserId(), post.mediaId(),
                post.caption(), post.createdAtMs(), 0, 0, false));
        postLikes.put(post.postId(), new java.util.HashSet<>());
        comments.put(post.postId(), new ArrayList<>());
    }

    @Override public synchronized Optional<SocialPost> findPost(String postId, String viewerId) {
        SocialPost post = posts.get(postId);
        return post == null ? Optional.empty() : Optional.of(viewPost(post, viewerId));
    }

    @Override public synchronized List<SocialPost> listPosts(String ownerUserId, String viewerId,
            int limit) {
        return posts.values().stream().filter(post -> ownerUserId == null
                || ownerUserId.isBlank() || post.ownerUserId().equals(ownerUserId))
                .sorted(java.util.Comparator.comparingLong(SocialPost::createdAtMs).reversed())
                .limit(Math.max(0, limit)).map(post -> viewPost(post, viewerId)).toList();
    }

    @Override public synchronized void setPostLike(String postId, String userId, boolean liked)
            throws IOException {
        if (!posts.containsKey(postId)) { throw new IOException("Post not found"); }
        java.util.Set<String> users = postLikes.get(postId);
        if (liked) { users.add(userId); } else { users.remove(userId); }
    }

    @Override public synchronized List<PostComment> addComment(PostComment comment)
            throws IOException {
        List<PostComment> list = comments.get(comment.postId());
        if (list == null) { throw new IOException("Post not found"); }
        list.add(comment);
        return List.copyOf(list);
    }

    @Override public synchronized List<PostComment> commentsForPost(String postId, int limit) {
        List<PostComment> list = comments.getOrDefault(postId, List.of());
        return List.copyOf(list.subList(0, Math.min(Math.max(0, limit), list.size())));
    }

    private SocialPost viewPost(SocialPost post, String viewerId) {
        java.util.Set<String> likes = postLikes.getOrDefault(post.postId(), java.util.Set.of());
        return new SocialPost(post.postId(), post.ownerUserId(), post.mediaId(), post.caption(),
                post.createdAtMs(), likes.size(), comments.getOrDefault(post.postId(), List.of()).size(),
                likes.contains(viewerId));
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
                    && message.conversationSequence() < beforeSequence) {
                result.add(message);
            }
        }
        int start = Math.max(0, result.size() - limit);
        return List.copyOf(result.subList(start, result.size()));
    }

    @Override public int purgeExpiredRecords(long nowMs) { return 0; }
    @Override public void flush() { }
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

    private boolean isParticipant(Message message, String userId) {
        if (message.senderUserId().equals(userId)) {
            return true;
        }
        String conversation = message.conversationId();
        if (conversation.startsWith("grp:")) {
            GroupChat group = groups.get(conversation.substring(4));
            return group != null && group.contains(userId);
        }
        if (!conversation.startsWith("dm:")) {
            return false;
        }
        String[] participants = conversation.substring(3).split(":", -1);
        return participants.length == 2
                && (participants[0].equals(userId) || participants[1].equals(userId));
    }
}
