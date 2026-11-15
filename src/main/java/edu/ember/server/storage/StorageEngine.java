package edu.ember.server.storage;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.UserRecord;
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
    List<WireFrame> getDeltaFramesSince(String userId, long sinceGlobalSeq) throws IOException;
    List<Message> getHistory(String conversationId, long beforeSequence, int limit)
            throws IOException;
    int purgeExpiredRecords(long nowMs) throws IOException;
    @Override
    void close() throws IOException;
}
