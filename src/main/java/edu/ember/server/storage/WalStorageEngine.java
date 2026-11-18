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
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.WireFrame;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;

/** Replays and appends CRC-protected records, truncating an invalid or torn tail. @concept DAO, file channels, WAL */
public final class WalStorageEngine implements StorageEngine {
    private static final int RECORD_HEADER_BYTES = Integer.BYTES + Long.BYTES;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    /** With a remote replica, snapshot only after this much new WAL, to save upload bandwidth. */
    private static final long REPLICA_COMPACT_BYTES = 1024 * 1024;
    private static final int SNAPSHOT_MAGIC = 0x454D4252;
    private static final int MAX_SNAPSHOT_BYTES = 512 * 1024 * 1024;
    private static final byte USER_SAVE = 1;
    private static final byte MESSAGE_APPEND = 2;
    private static final byte MESSAGE_STATUS = 3;
    private static final byte GROUP_SAVE = 4;
    private static final byte PROFILE_SAVE = 5;
    private static final byte FOLLOW_SET = 6;
    private static final byte MEDIA_SAVE = 7;
    private static final byte POST_SAVE = 8;
    private static final byte POST_LIKE = 9;
    private static final byte POST_COMMENT = 10;
    private static final byte MESSAGE_REACTION = 11;
    private static final byte MESSAGE_EDIT = 12;
    private static final byte MESSAGE_DELETE = 13;
    private static final byte BLOCK_SET = 14;
    private static final byte REPORT_SAVE = 15;
    private static final byte MESSAGE_PIN = 16;
    private static final byte MESSAGE_STAR = 17;
    private static final byte STORY_SAVE = 18;
    private static final byte POLL_SAVE = 19;
    private static final byte POLL_VOTE = 20;
    private static final byte PUSH_SUBSCRIPTION = 21;
    private static final byte SETTING = 22;
    private final Path walPath;
    private final Path snapshotPath;
    private final boolean strictDurability;
    private final StorageReplica replica;
    private final InMemoryStorageEngine state = new InMemoryStorageEngine();
    private final Map<String, Long> conversationSequences = new HashMap<>();
    private FileChannel channel;
    private long globalSequence;
    private int snapshotCoveredLength;
    private long snapshotCoveredCrc;

    public WalStorageEngine(Path dataDirectory, boolean strictDurability) {
        this(dataDirectory, strictDurability, null);
    }

    /** replica may be null; when set, the local folder is only a cache of the remote copy. */
    public WalStorageEngine(Path dataDirectory, boolean strictDurability, StorageReplica replica) {
        walPath = dataDirectory.resolve("wal").resolve("events.wal");
        snapshotPath = dataDirectory.resolve("snapshots").resolve("state.snap");
        this.strictDurability = strictDurability;
        this.replica = replica;
    }

    @Override
    public synchronized void init() throws IOException {
        boolean restored = replica != null && replica.restore(snapshotPath, walPath);
        Files.createDirectories(walPath.getParent());
        channel = FileChannel.open(walPath, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        byte[] snapshot = readSnapshot();
        if (snapshot.length > 0) { replayBytes(snapshot, false); }
        replay();
        state.purgeExpiredRecords(System.currentTimeMillis());
        channel.position(channel.size());
        if (replica != null && !restored) {
            seedReplica();
        }
    }

    /** First run against an empty replica: upload the local data as its first snapshot. */
    private void seedReplica() throws IOException {
        if (channel.size() > 0) {
            compactNow();
        } else if (Files.exists(snapshotPath)) {
            replica.replaceSnapshot(Files.readAllBytes(snapshotPath));
        }
    }

    @Override public void mirrorMedia(String name, byte[] bytes) {
        if (replica != null) {
            replica.putMedia(name, bytes);
        }
    }

    @Override public byte[] fetchMirroredMedia(String name) throws IOException {
        return replica == null ? null : replica.getMedia(name);
    }

    @Override public boolean isMediaMirrored(String name) {
        return replica == null || replica.hasMedia(name);
    }

    @Override
    public synchronized void saveUser(UserRecord user) throws IOException {
        if (state.findUser(user.username()).isPresent()) {
            throw new IOException("Username already exists");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", user.userId());
        fields.put("username", user.username());
        fields.put("displayName", user.displayName());
        fields.put("passwordHash", user.passwordHash());
        appendRecord(USER_SAVE, fields);
        state.saveUser(user);
    }

    @Override
    public synchronized Optional<UserRecord> findUser(String username) throws IOException {
        return state.findUser(username);
    }

    @Override
    public synchronized long appendMessage(Message message) throws IOException {
        Optional<Message> duplicate = state.findBySenderAndClientMsgId(
                message.senderUserId(), message.clientMsgId());
        if (duplicate.isPresent()) {
            return duplicate.get().conversationSequence();
        }
        long conversationSequence = conversationSequences.getOrDefault(
                message.conversationId(), 0L) + 1;
        long nextGlobalSequence = globalSequence + 1;
        message.assignSequences(conversationSequence, nextGlobalSequence);
        Map<String, String> fields = messageFields(message);
        appendRecord(MESSAGE_APPEND, fields);
        long assigned = state.appendMessage(message);
        conversationSequences.put(message.conversationId(), conversationSequence);
        globalSequence = nextGlobalSequence;
        return assigned;
    }

    @Override
    public synchronized void updateMessageStatus(String clientMsgId, MessageStatus status)
            throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("clientMsgId", clientMsgId);
        fields.put("status", status.name());
        appendRecord(MESSAGE_STATUS, fields);
        state.updateMessageStatus(clientMsgId, status);
    }

    @Override public synchronized void setMessageReaction(String clientMsgId, String userId,
            String emoji, boolean selected) throws IOException {
        if (state.findByClientMsgId(clientMsgId).isEmpty()) {
            throw new IOException("Message not found");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put("clientMsgId", clientMsgId); fields.put("userId", userId);
        fields.put("emoji", emoji); fields.put("selected", Boolean.toString(selected));
        appendRecord(MESSAGE_REACTION, fields);
        state.setMessageReaction(clientMsgId, userId, emoji, selected);
    }

    @Override public synchronized void editMessage(String clientMsgId, String newBody)
            throws IOException {
        if (state.findByClientMsgId(clientMsgId).isEmpty()) {
            throw new IOException("Message not found");
        }
        appendRecord(MESSAGE_EDIT, Map.of("clientMsgId", clientMsgId, "body", newBody));
        state.editMessage(clientMsgId, newBody);
    }

    @Override public synchronized void deleteMessage(String clientMsgId) throws IOException {
        if (state.findByClientMsgId(clientMsgId).isEmpty()) {
            throw new IOException("Message not found");
        }
        appendRecord(MESSAGE_DELETE, Map.of("clientMsgId", clientMsgId));
        state.deleteMessage(clientMsgId);
    }

    @Override public synchronized void setMessagePinned(String clientMsgId, boolean pinned)
            throws IOException {
        appendRecord(MESSAGE_PIN, Map.of("clientMsgId", clientMsgId,
                "pinned", Boolean.toString(pinned)));
        state.setMessagePinned(clientMsgId, pinned);
    }

    @Override public synchronized void setMessageStarred(String clientMsgId, String userId,
            boolean starred) throws IOException {
        appendRecord(MESSAGE_STAR, Map.of("clientMsgId", clientMsgId, "userId", userId,
                "starred", Boolean.toString(starred)));
        state.setMessageStarred(clientMsgId, userId, starred);
    }

    @Override
    public synchronized Optional<Message> findBySenderAndClientMsgId(String senderUserId,
            String clientMsgId) throws IOException {
        return state.findBySenderAndClientMsgId(senderUserId, clientMsgId);
    }

    @Override
    public synchronized Optional<Message> findByClientMsgId(String clientMsgId) throws IOException {
        return state.findByClientMsgId(clientMsgId);
    }

    @Override
    public synchronized void saveGroup(GroupChat group) throws IOException {
        appendRecord(GROUP_SAVE, groupFields(group));
        state.saveGroup(group);
    }

    @Override
    public synchronized Optional<GroupChat> findGroup(String groupId) throws IOException {
        return state.findGroup(groupId);
    }

    @Override public synchronized void saveProfile(UserProfile profile) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", profile.userId()); fields.put("displayName", profile.displayName());
        fields.put("bio", profile.bio()); fields.put("avatarMediaId", profile.avatarMediaId());
        fields.put("privateAccount", Boolean.toString(profile.privateAccount()));
        appendRecord(PROFILE_SAVE, fields); state.saveProfile(profile);
    }

    @Override public synchronized Optional<UserProfile> findProfile(String userId) throws IOException {
        return state.findProfile(userId);
    }

    @Override public synchronized void setFollow(String followerId, String followedId,
            boolean following) throws IOException {
        setFollowStatus(followerId, followedId, following ? FollowStatus.ACCEPTED : null);
    }

    @Override public synchronized void setFollowStatus(String followerId, String followedId,
            FollowStatus status) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("follower", followerId); fields.put("followed", followedId);
        fields.put("status", status == null ? "NONE" : status.name());
        appendRecord(FOLLOW_SET, fields); state.setFollowStatus(followerId, followedId, status);
    }

    @Override public synchronized Optional<FollowStatus> followStatus(String followerId,
            String followedId) throws IOException {
        return state.followStatus(followerId, followedId);
    }

    @Override public synchronized List<String> pendingFollowersOf(String userId) throws IOException {
        return state.pendingFollowersOf(userId);
    }

    @Override public synchronized void setBlocked(String blockerId, String blockedId,
            boolean blocked) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("blocker", blockerId); fields.put("blocked", blockedId);
        fields.put("active", Boolean.toString(blocked));
        appendRecord(BLOCK_SET, fields);
        state.setBlocked(blockerId, blockedId, blocked);
        if (blocked) {
            setFollowStatus(blockerId, blockedId, null);
            setFollowStatus(blockedId, blockerId, null);
        }
    }

    @Override public synchronized boolean isBlockedEitherDirection(String firstUserId,
            String secondUserId) throws IOException {
        return state.isBlockedEitherDirection(firstUserId, secondUserId);
    }

    @Override public synchronized void saveReport(ModerationReport report) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("reportId", report.reportId()); fields.put("reporter", report.reporterUserId());
        fields.put("targetType", report.targetType()); fields.put("targetId", report.targetId());
        fields.put("reason", report.reason()); fields.put("created", Long.toString(report.createdAtMs()));
        appendRecord(REPORT_SAVE, fields); state.saveReport(report);
    }

    @Override public synchronized List<ModerationReport> listReports() throws IOException {
        return state.listReports();
    }

    @Override public synchronized void saveStory(StoryItem story) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("storyId", story.storyId()); fields.put("owner", story.ownerUserId());
        fields.put("mediaId", story.mediaId()); fields.put("caption", story.caption());
        fields.put("created", Long.toString(story.createdAtMs()));
        fields.put("expires", Long.toString(story.expiresAtMs()));
        appendRecord(STORY_SAVE, fields); state.saveStory(story);
    }

    @Override public synchronized Optional<StoryItem> findStory(String storyId) throws IOException {
        return state.findStory(storyId);
    }

    @Override public synchronized List<StoryItem> listStories() throws IOException {
        return state.listStories();
    }

    @Override public synchronized void savePoll(PollData poll) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("pollId", poll.pollId()); fields.put("groupId", poll.groupId());
        fields.put("question", poll.question());
        fields.put("option.n", Integer.toString(poll.options().size()));
        fields.put("member.n", Integer.toString(poll.memberIds().size()));
        for (int index = 0; index < poll.options().size(); index++) {
            fields.put("option." + index, poll.options().get(index));
        }
        for (int index = 0; index < poll.memberIds().size(); index++) {
            fields.put("member." + index, poll.memberIds().get(index));
        }
        appendRecord(POLL_SAVE, fields); state.savePoll(poll);
    }

    @Override public synchronized Optional<PollData> findPoll(String pollId) throws IOException {
        return state.findPoll(pollId);
    }

    @Override public synchronized List<PollData> pollsForGroup(String groupId) throws IOException {
        return state.pollsForGroup(groupId);
    }

    @Override public synchronized void votePoll(String pollId, String userId, int optionIndex)
            throws IOException {
        PollData poll = state.findPoll(pollId).orElseThrow(() -> new IOException("Poll not found"));
        if (!poll.memberIds().contains(userId) || optionIndex < 0
                || optionIndex >= poll.options().size()) {
            throw new IOException("Poll vote is invalid");
        }
        appendRecord(POLL_VOTE, Map.of("pollId", pollId, "userId", userId,
                "option", Integer.toString(optionIndex)));
        state.votePoll(pollId, userId, optionIndex);
    }

    @Override public synchronized List<String> followersOf(String userId) throws IOException {
        return state.followersOf(userId);
    }

    @Override public synchronized List<String> followingOf(String userId) throws IOException {
        return state.followingOf(userId);
    }

    @Override public synchronized void saveMedia(MediaAsset asset) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("mediaId", asset.mediaId()); fields.put("owner", asset.ownerUserId());
        fields.put("fileName", asset.fileName()); fields.put("thumbnail", asset.thumbnailFileName());
        fields.put("width", Integer.toString(asset.width())); fields.put("height", Integer.toString(asset.height()));
        fields.put("created", Long.toString(asset.createdAtMs()));
        appendRecord(MEDIA_SAVE, fields); state.saveMedia(asset);
    }

    @Override public synchronized Optional<MediaAsset> findMedia(String mediaId) throws IOException {
        return state.findMedia(mediaId);
    }

    @Override public synchronized void savePost(SocialPost post) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("postId", post.postId()); fields.put("owner", post.ownerUserId());
        fields.put("mediaId", post.mediaId()); fields.put("caption", post.caption());
        fields.put("created", Long.toString(post.createdAtMs()));
        appendRecord(POST_SAVE, fields); state.savePost(post);
    }

    @Override public synchronized Optional<SocialPost> findPost(String postId, String viewerId)
            throws IOException { return state.findPost(postId, viewerId); }

    @Override public synchronized List<SocialPost> listPosts(String ownerUserId, String viewerId,
            int limit) throws IOException { return state.listPosts(ownerUserId, viewerId, limit); }

    @Override public synchronized void setPostLike(String postId, String userId, boolean liked)
            throws IOException {
        Map<String, String> fields = new HashMap<>(); fields.put("postId", postId);
        fields.put("userId", userId); fields.put("liked", Boolean.toString(liked));
        appendRecord(POST_LIKE, fields); state.setPostLike(postId, userId, liked);
    }

    @Override public synchronized List<PostComment> addComment(PostComment comment) throws IOException {
        Map<String, String> fields = new HashMap<>(); fields.put("commentId", comment.commentId());
        fields.put("postId", comment.postId()); fields.put("author", comment.authorUserId());
        fields.put("text", comment.text()); fields.put("created", Long.toString(comment.createdAtMs()));
        appendRecord(POST_COMMENT, fields); return state.addComment(comment);
    }

    @Override public synchronized List<PostComment> commentsForPost(String postId, int limit)
            throws IOException { return state.commentsForPost(postId, limit); }

    @Override
    public synchronized List<WireFrame> getDeltaFramesSince(String userId, long sinceGlobalSeq)
            throws IOException {
        return state.getDeltaFramesSince(userId, sinceGlobalSeq);
    }

    @Override
    public synchronized List<Message> getHistory(String conversationId, long beforeSequence,
            int limit) throws IOException {
        return state.getHistory(conversationId, beforeSequence, limit);
    }

    @Override public synchronized List<WireFrame> getChangesSince(String userId, long sinceRevision) {
        return state.getChangesSince(userId, sinceRevision);
    }

    @Override public synchronized long currentRevision() {
        return state.currentRevision();
    }

    @Override public synchronized void savePushSubscription(
            edu.ember.common.model.PushSubscription subscription, boolean active) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", subscription.userId()); fields.put("endpoint", subscription.endpoint());
        fields.put("p256dh", subscription.p256dh()); fields.put("auth", subscription.auth());
        fields.put("active", Boolean.toString(active));
        appendRecord(PUSH_SUBSCRIPTION, fields);
        state.savePushSubscription(subscription, active);
    }

    @Override public synchronized List<edu.ember.common.model.PushSubscription> pushSubscriptions(
            String userId) {
        return state.pushSubscriptions(userId);
    }

    @Override public synchronized void saveSetting(String key, String value) throws IOException {
        appendRecord(SETTING, Map.of("key", key, "value", value));
        state.saveSetting(key, value);
    }

    @Override public synchronized Optional<String> setting(String key) {
        return state.setting(key);
    }

    @Override public synchronized List<Message> expiredMessages(long nowMs) {
        return state.expiredMessages(nowMs);
    }

    @Override public synchronized int purgeExpiredRecords(long nowMs) {
        return state.purgeExpiredRecords(nowMs);
    }

    @Override
    public synchronized void flush() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(false);
        }
    }

    @Override
    public synchronized void compact() throws IOException {
        if (channel == null || !channel.isOpen() || channel.size() == 0) { return; }
        if (replica != null && channel.size() < REPLICA_COMPACT_BYTES) { return; }
        compactNow();
    }

    private void compactNow() throws IOException {
        if (strictDurability) { channel.force(false); }
        byte[] previous = readSnapshot();
        byte[] wal = Files.readAllBytes(walPath);
        int alreadyCovered = coveredWalPrefixLength(wal);
        byte[] newWalRecords = java.util.Arrays.copyOfRange(wal, alreadyCovered, wal.length);
        if ((long) previous.length + newWalRecords.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Snapshot exceeds its size limit");
        }
        ByteArrayOutputStream combined = new ByteArrayOutputStream(previous.length + newWalRecords.length);
        combined.write(previous);
        combined.write(newWalRecords);
        byte[] rawSnapshot = combined.toByteArray();
        byte[] compressed = gzip(rawSnapshot);
        CRC32 checksum = new CRC32();
        checksum.update(rawSnapshot);
        CRC32 covered = new CRC32();
        covered.update(wal);
        Files.createDirectories(snapshotPath.getParent());
        Path temporary = snapshotPath.resolveSibling("state.snap.tmp");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(rawSnapshot.length);
            output.writeInt(wal.length);
            output.writeLong(covered.getValue());
            output.writeLong(checksum.getValue());
            output.write(compressed);
        }
        moveSnapshotIntoPlace(temporary);
        channel.truncate(0);
        channel.position(0);
        if (strictDurability) { channel.force(false); }
        if (replica != null) {
            replica.replaceSnapshot(Files.readAllBytes(snapshotPath));
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (channel != null) {
            if (channel.isOpen() && strictDurability) {
                channel.force(false);
            }
            channel.close();
        }
        if (replica != null) {
            replica.close();
        }
    }

    private void replay() throws IOException {
        byte[] wal = Files.readAllBytes(walPath);
        int offset = coveredWalPrefixLength(wal);
        byte[] remaining = java.util.Arrays.copyOfRange(wal, offset, wal.length);
        long validBytes = replayBytes(remaining, true);
        long fullValidLength = offset + validBytes;
        if (fullValidLength < wal.length) { channel.truncate(fullValidLength); }
    }

    private long replayBytes(byte[] bytes, boolean tolerateTail) throws IOException {
        int position = 0;
        while (position < bytes.length) {
            int start = position;
            if (bytes.length - position < RECORD_HEADER_BYTES) { break; }
            ByteBuffer header = ByteBuffer.wrap(bytes, position, RECORD_HEADER_BYTES);
            int length = header.getInt();
            long expectedCrc = header.getLong();
            position += RECORD_HEADER_BYTES;
            if (length < 1 || length > MAX_RECORD_BYTES || bytes.length - position < length) {
                position = start;
                break;
            }
            byte[] recordBytes = java.util.Arrays.copyOfRange(bytes, position, position + length);
            if (crc(recordBytes) != expectedCrc) {
                position = start;
                break;
            }
            apply(new WalRecord(recordBytes[0],
                    java.util.Arrays.copyOfRange(recordBytes, 1, recordBytes.length)));
            position += length;
        }
        if (position != bytes.length && !tolerateTail) {
            throw new IOException("Snapshot contains an invalid WAL record");
        }
        return position;
    }

    private byte[] readSnapshot() throws IOException {
        if (!Files.exists(snapshotPath)) { return new byte[0]; }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(snapshotPath))) {
            if (input.readInt() != SNAPSHOT_MAGIC) { throw new IOException("Snapshot magic is invalid"); }
            int rawLength = input.readInt();
            snapshotCoveredLength = input.readInt();
            snapshotCoveredCrc = input.readLong();
            long expectedCrc = input.readLong();
            if (rawLength < 0 || snapshotCoveredLength < 0 || rawLength > MAX_SNAPSHOT_BYTES) {
                throw new IOException("Snapshot size is invalid");
            }
            byte[] compressed = input.readAllBytes();
            byte[] raw = gunzip(compressed);
            CRC32 checksum = new CRC32();
            checksum.update(raw);
            if (raw.length != rawLength || checksum.getValue() != expectedCrc) {
                throw new IOException("Snapshot checksum does not match");
            }
            return raw;
        }
    }

    private int coveredWalPrefixLength(byte[] wal) {
        if (snapshotCoveredLength == 0 || wal.length < snapshotCoveredLength) { return 0; }
        CRC32 checksum = new CRC32();
        checksum.update(wal, 0, snapshotCoveredLength);
        return checksum.getValue() == snapshotCoveredCrc ? snapshotCoveredLength : 0;
    }

    private void moveSnapshotIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, snapshotPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, snapshotPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        return bytes.toByteArray();
    }

    private byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream expanded = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = gzip.read(buffer)) >= 0) {
                if (expanded.size() + count > MAX_SNAPSHOT_BYTES) {
                    throw new IOException("Snapshot expands beyond its size limit");
                }
                expanded.write(buffer, 0, count);
            }
            return expanded.toByteArray();
        }
    }

    private void apply(WalRecord record) throws IOException {
        Map<String, String> fields = KvPayloadCodec.decode(record.payload());
        if (record.type() == USER_SAVE) {
            state.saveUser(new UserRecord(fields.get("userId"), fields.get("username"),
                    fields.get("displayName"), fields.get("passwordHash")));
        } else if (record.type() == MESSAGE_APPEND) {
            Message message = messageFrom(fields);
            long expectedConvSequence = Long.parseLong(fields.get("convSeq"));
            long expectedGlobalSequence = Long.parseLong(fields.get("globalSeq"));
            long actualConvSequence = state.appendMessage(message);
            if (actualConvSequence != expectedConvSequence
                    || message.globalSequence() != expectedGlobalSequence) {
                throw new IOException("WAL sequence does not match replayed state");
            }
            conversationSequences.put(message.conversationId(), expectedConvSequence);
            globalSequence = expectedGlobalSequence;
        } else if (record.type() == MESSAGE_STATUS) {
            state.updateMessageStatus(fields.get("clientMsgId"),
                    MessageStatus.valueOf(fields.get("status")));
        } else if (record.type() == GROUP_SAVE) {
            state.saveGroup(groupFrom(fields));
        } else if (record.type() == PROFILE_SAVE) {
            state.saveProfile(new UserProfile(fields.get("userId"), fields.get("displayName"),
                    fields.get("bio"), fields.get("avatarMediaId"),
                    Boolean.parseBoolean(fields.getOrDefault("privateAccount", "false"))));
        } else if (record.type() == FOLLOW_SET) {
            if (fields.containsKey("status")) {
                String status = fields.get("status");
                state.setFollowStatus(fields.get("follower"), fields.get("followed"),
                        status.equals("NONE") ? null : FollowStatus.valueOf(status));
            } else {
                state.setFollow(fields.get("follower"), fields.get("followed"),
                        Boolean.parseBoolean(fields.get("following")));
            }
        } else if (record.type() == MEDIA_SAVE) {
            state.saveMedia(new MediaAsset(fields.get("mediaId"), fields.get("owner"),
                    fields.get("fileName"), fields.get("thumbnail"),
                    Integer.parseInt(fields.get("width")), Integer.parseInt(fields.get("height")),
                    Long.parseLong(fields.get("created"))));
        } else if (record.type() == POST_SAVE) {
            state.savePost(new SocialPost(fields.get("postId"), fields.get("owner"),
                    fields.get("mediaId"), fields.get("caption"),
                    Long.parseLong(fields.get("created")), 0, 0, false));
        } else if (record.type() == POST_LIKE) {
            state.setPostLike(fields.get("postId"), fields.get("userId"),
                    Boolean.parseBoolean(fields.get("liked")));
        } else if (record.type() == POST_COMMENT) {
            state.addComment(new PostComment(fields.get("commentId"), fields.get("postId"),
                    fields.get("author"), fields.get("text"), Long.parseLong(fields.get("created"))));
        } else if (record.type() == MESSAGE_REACTION) {
            state.setMessageReaction(fields.get("clientMsgId"), fields.get("userId"),
                    fields.get("emoji"), Boolean.parseBoolean(fields.get("selected")));
        } else if (record.type() == MESSAGE_EDIT) {
            state.editMessage(fields.get("clientMsgId"), fields.get("body"));
        } else if (record.type() == MESSAGE_DELETE) {
            state.deleteMessage(fields.get("clientMsgId"));
        } else if (record.type() == BLOCK_SET) {
            state.setBlocked(fields.get("blocker"), fields.get("blocked"),
                    Boolean.parseBoolean(fields.get("active")));
        } else if (record.type() == REPORT_SAVE) {
            state.saveReport(new ModerationReport(fields.get("reportId"), fields.get("reporter"),
                    fields.get("targetType"), fields.get("targetId"), fields.get("reason"),
                    Long.parseLong(fields.get("created"))));
        } else if (record.type() == MESSAGE_PIN) {
            state.setMessagePinned(fields.get("clientMsgId"), Boolean.parseBoolean(fields.get("pinned")));
        } else if (record.type() == MESSAGE_STAR) {
            state.setMessageStarred(fields.get("clientMsgId"), fields.get("userId"),
                    Boolean.parseBoolean(fields.get("starred")));
        } else if (record.type() == STORY_SAVE) {
            state.saveStory(new StoryItem(fields.get("storyId"), fields.get("owner"),
                    fields.get("mediaId"), fields.get("caption"),
                    Long.parseLong(fields.get("created")), Long.parseLong(fields.get("expires"))));
        } else if (record.type() == POLL_SAVE) {
            int optionCount = Integer.parseInt(fields.get("option.n"));
            int memberCount = Integer.parseInt(fields.get("member.n"));
            List<String> options = new ArrayList<>();
            List<String> members = new ArrayList<>();
            for (int index = 0; index < optionCount; index++) {
                options.add(fields.get("option." + index));
            }
            for (int index = 0; index < memberCount; index++) {
                members.add(fields.get("member." + index));
            }
            state.savePoll(new PollData(fields.get("pollId"), fields.get("groupId"),
                    fields.get("question"), options, members));
        } else if (record.type() == POLL_VOTE) {
            state.votePoll(fields.get("pollId"), fields.get("userId"),
                    Integer.parseInt(fields.get("option")));
        } else if (record.type() == PUSH_SUBSCRIPTION) {
            state.savePushSubscription(new edu.ember.common.model.PushSubscription(fields.get("userId"),
                    fields.get("endpoint"), fields.get("p256dh"), fields.get("auth")),
                    Boolean.parseBoolean(fields.get("active")));
        } else if (record.type() == SETTING) {
            state.saveSetting(fields.get("key"), fields.get("value"));
        } else {
            throw new IOException("Unknown WAL record type");
        }
    }

    private void appendRecord(byte type, Map<String, String> fields) throws IOException {
        if (channel == null || !channel.isOpen()) {
            throw new IOException("WAL is not initialized");
        }
        byte[] payload = KvPayloadCodec.encode(fields);
        byte[] content = new byte[payload.length + 1];
        content[0] = type;
        System.arraycopy(payload, 0, content, 1, payload.length);
        if (content.length > MAX_RECORD_BYTES) {
            throw new IOException("WAL record exceeds size limit");
        }
        ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_BYTES + content.length);
        record.putInt(content.length).putLong(crc(content)).put(content).flip();
        channel.position(channel.size());
        while (record.hasRemaining()) {
            channel.write(record);
        }
        if (strictDurability) {
            channel.force(false);
        }
        if (replica != null) {
            replica.appendLog(record.array());
        }
    }

    private boolean readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                return false;
            }
        }
        return true;
    }

    private static long crc(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        return checksum.getValue();
    }

    private static Map<String, String> messageFields(Message message) {
        Map<String, String> fields = new HashMap<>();
        fields.put("clientMsgId", message.clientMsgId());
        fields.put("senderUserId", message.senderUserId());
        fields.put("convId", message.conversationId());
        fields.put("body", message.body());
        fields.put("serverTs", Long.toString(message.createdAtMs()));
        fields.put("ttlSec", Integer.toString(message.ttlSec()));
        fields.put("expiresAtMs", Long.toString(message.expiresAtMs()));
        fields.put("replyToId", message.replyToId());
        fields.put("replyPreview", message.replyPreview());
        fields.put("convSeq", Long.toString(message.conversationSequence()));
        fields.put("globalSeq", Long.toString(message.globalSequence()));
        Map<String, Integer> reactionCounts = message.reactionCounts();
        fields.put("reaction.n", Integer.toString(reactionCounts.size()));
        int index = 0;
        for (Map.Entry<String, Integer> reaction : reactionCounts.entrySet()) {
            fields.put("reaction.emoji." + index, reaction.getKey());
            fields.put("reaction.count." + index, Integer.toString(reaction.getValue()));
            index++;
        }
        return fields;
    }

    private static Message messageFrom(Map<String, String> fields) {
        Message message = new Message(fields.get("clientMsgId"), fields.get("senderUserId"),
                fields.get("convId"), fields.get("body"), Long.parseLong(fields.get("serverTs")),
                Integer.parseInt(fields.getOrDefault("ttlSec", "0")),
                fields.getOrDefault("replyToId", ""), fields.getOrDefault("replyPreview", ""));
        message.setExpiresAtMs(Long.parseLong(fields.getOrDefault("expiresAtMs", "0")));
        message.assignSequences(Long.parseLong(fields.get("convSeq")),
                Long.parseLong(fields.get("globalSeq")));
        return message;
    }

    private static Map<String, String> groupFields(GroupChat group) {
        Map<String, String> fields = new HashMap<>();
        fields.put("groupId", group.groupId());
        fields.put("name", group.name());
        fields.put("description", group.description());
        Map<String, edu.ember.common.model.UserRole> members = group.members();
        fields.put("member.n", Integer.toString(members.size()));
        int index = 0;
        for (Map.Entry<String, edu.ember.common.model.UserRole> member : members.entrySet()) {
            fields.put("member." + index, member.getKey());
            fields.put("role." + index, member.getValue().name());
            index++;
        }
        return fields;
    }

    private static GroupChat groupFrom(Map<String, String> fields) {
        int count = Integer.parseInt(fields.get("member.n"));
        Map<String, edu.ember.common.model.UserRole> members = new HashMap<>();
        for (int index = 0; index < count; index++) {
            members.put(fields.get("member." + index),
                    edu.ember.common.model.UserRole.valueOf(fields.get("role." + index)));
        }
        return new GroupChat(fields.get("groupId"), fields.get("name"),
                fields.get("description"), members);
    }
}
