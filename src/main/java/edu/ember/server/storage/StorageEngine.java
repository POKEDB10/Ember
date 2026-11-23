package edu.ember.server.storage;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.GroupChat;
import edu.ember.common.model.UserRecord;
import edu.ember.common.model.UserProfile;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.PostComment;
import edu.ember.common.model.FollowStatus;
import edu.ember.common.model.ModerationReport;
import edu.ember.common.model.StoryItem;
import edu.ember.common.model.PollData;
import edu.ember.common.protocol.WireFrame;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Persistence boundary shared by server services and storage implementations. @concept interfaces, DAO pattern */
public interface StorageEngine extends AutoCloseable {
    void init() throws IOException;
    void saveUser(UserRecord user) throws IOException;
    Optional<UserRecord> findUser(String username) throws IOException;
    long appendMessage(Message message) throws IOException;
    void updateMessageStatus(String clientMsgId, MessageStatus status) throws IOException;
    void setMessageReaction(String clientMsgId, String userId, String emoji, boolean selected)
            throws IOException;
    void editMessage(String clientMsgId, String newBody) throws IOException;
    void deleteMessage(String clientMsgId) throws IOException;
    void setMessagePinned(String clientMsgId, boolean pinned) throws IOException;
    void setMessageStarred(String clientMsgId, String userId, boolean starred) throws IOException;
    Optional<Message> findBySenderAndClientMsgId(String senderUserId, String clientMsgId)
            throws IOException;
    Optional<Message> findByClientMsgId(String clientMsgId) throws IOException;
    void saveGroup(GroupChat group) throws IOException;
    Optional<GroupChat> findGroup(String groupId) throws IOException;
    void saveProfile(UserProfile profile) throws IOException;
    Optional<UserProfile> findProfile(String userId) throws IOException;
    void setFollow(String followerId, String followedId, boolean following) throws IOException;
    void setFollowStatus(String followerId, String followedId, FollowStatus status) throws IOException;
    Optional<FollowStatus> followStatus(String followerId, String followedId) throws IOException;
    List<String> pendingFollowersOf(String userId) throws IOException;
    void setBlocked(String blockerId, String blockedId, boolean blocked) throws IOException;
    boolean isBlockedEitherDirection(String firstUserId, String secondUserId) throws IOException;
    void saveReport(ModerationReport report) throws IOException;
    List<ModerationReport> listReports() throws IOException;
    void saveStory(StoryItem story) throws IOException;
    Optional<StoryItem> findStory(String storyId) throws IOException;
    List<StoryItem> listStories() throws IOException;
    void savePoll(PollData poll) throws IOException;
    Optional<PollData> findPoll(String pollId) throws IOException;
    List<PollData> pollsForGroup(String groupId) throws IOException;
    void votePoll(String pollId, String userId, int optionIndex) throws IOException;
    List<String> followersOf(String userId) throws IOException;
    List<String> followingOf(String userId) throws IOException;
    void saveMedia(MediaAsset asset) throws IOException;
    Optional<MediaAsset> findMedia(String mediaId) throws IOException;
    void savePost(SocialPost post) throws IOException;
    Optional<SocialPost> findPost(String postId, String viewerId) throws IOException;
    List<SocialPost> listPosts(String ownerUserId, String viewerId, int limit) throws IOException;
    void setPostLike(String postId, String userId, boolean liked) throws IOException;
    List<PostComment> addComment(PostComment comment) throws IOException;
    List<PostComment> commentsForPost(String postId, int limit) throws IOException;
    List<WireFrame> getDeltaFramesSince(String userId, long sinceGlobalSeq) throws IOException;
    List<Message> getHistory(String conversationId, long beforeSequence, int limit)
            throws IOException;
    List<Message> expiredMessages(long nowMs) throws IOException;
    int purgeExpiredRecords(long nowMs) throws IOException;
    void flush() throws IOException;
    /**
     * Messages created or changed after {@code sinceRevision}, plus deletion markers, as seen by
     * one user (pin and per-user star state included). Lets a device with a local cache
     * download only what changed. @concept change tracking
     */
    default List<WireFrame> getChangesSince(String userId, long sinceRevision) throws IOException {
        return getDeltaFramesSince(userId, 0);
    }
    /** Highest revision handed out so far. */
    default long currentRevision() throws IOException { return 0; }
    default void savePushSubscription(edu.ember.common.model.PushSubscription subscription,
            boolean active) throws IOException { }
    default List<edu.ember.common.model.PushSubscription> pushSubscriptions(String userId)
            throws IOException { return List.of(); }
    /**
     * Everyone this user has a reason to know: direct-chat partners, people in the same groups,
     * and follows in either direction. Presence and key updates go only to these people.
     */
    default java.util.Set<String> contactsOf(String userId) throws IOException { return java.util.Set.of(); }
    default void saveSetting(String key, String value) throws IOException { }
    default Optional<String> setting(String key) throws IOException { return Optional.empty(); }
    /** Copies a stored media file off-server, when the engine has a replica. */
    default void mirrorMedia(String name, byte[] bytes) { }
    /** Media file from the off-server replica, or null. */
    default byte[] fetchMirroredMedia(String name) throws IOException { return null; }
    default boolean isMediaMirrored(String name) { return true; }
    default void compact() throws IOException { flush(); }
    @Override
    void close() throws IOException;
}
