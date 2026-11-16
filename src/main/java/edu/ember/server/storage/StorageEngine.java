package edu.ember.server.storage;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.GroupChat;
import edu.ember.common.model.UserRecord;
import edu.ember.common.model.UserProfile;
import edu.ember.common.model.MediaAsset;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.PostComment;
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
    Optional<Message> findBySenderAndClientMsgId(String senderUserId, String clientMsgId)
            throws IOException;
    Optional<Message> findByClientMsgId(String clientMsgId) throws IOException;
    void saveGroup(GroupChat group) throws IOException;
    Optional<GroupChat> findGroup(String groupId) throws IOException;
    void saveProfile(UserProfile profile) throws IOException;
    Optional<UserProfile> findProfile(String userId) throws IOException;
    void setFollow(String followerId, String followedId, boolean following) throws IOException;
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
    int purgeExpiredRecords(long nowMs) throws IOException;
    void flush() throws IOException;
    @Override
    void close() throws IOException;
}
