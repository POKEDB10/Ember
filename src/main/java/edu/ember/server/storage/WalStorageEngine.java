package edu.ember.server.storage;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.UserRecord;
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.WireFrame;
import java.io.IOException;
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
    private static final int MAX_RECORD_BYTES = 6 * 1024 * 1024;
    private static final byte USER_SAVE = 1;
    private static final byte MESSAGE_APPEND = 2;
    private static final byte MESSAGE_STATUS = 3;
    private final Path walPath;
    private final boolean strictDurability;
    private final InMemoryStorageEngine state = new InMemoryStorageEngine();
    private final Map<String, Long> conversationSequences = new HashMap<>();
    private FileChannel channel;
    private long globalSequence;

    public WalStorageEngine(Path dataDirectory, boolean strictDurability) {
        walPath = dataDirectory.resolve("wal").resolve("events.wal");
        this.strictDurability = strictDurability;
    }

    @Override
    public synchronized void init() throws IOException {
        Files.createDirectories(walPath.getParent());
        channel = FileChannel.open(walPath, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        replay();
        channel.position(channel.size());
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
    public synchronized List<WireFrame> getDeltaFramesSince(String userId, long sinceGlobalSeq)
            throws IOException {
        return state.getDeltaFramesSince(userId, sinceGlobalSeq);
    }

    @Override
    public synchronized List<Message> getHistory(String conversationId, long beforeSequence,
            int limit) throws IOException {
        return state.getHistory(conversationId, beforeSequence, limit);
    }

    @Override public synchronized int purgeExpiredRecords(long nowMs) { return 0; }

    @Override
    public synchronized void flush() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(false);
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
    }

    private void replay() throws IOException {
        long size = channel.size();
        long position = 0;
        while (position < size) {
            long recordStart = position;
            if (size - position < RECORD_HEADER_BYTES) {
                break;
            }
            ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_BYTES);
            if (!readFully(header, position)) {
                break;
            }
            header.flip();
            int length = header.getInt();
            long expectedCrc = header.getLong();
            position += RECORD_HEADER_BYTES;
            if (length < 1 || length > MAX_RECORD_BYTES || size - position < length) {
                position = recordStart;
                break;
            }
            ByteBuffer bytes = ByteBuffer.allocate(length);
            if (!readFully(bytes, position)) {
                position = recordStart;
                break;
            }
            bytes.flip();
            byte[] recordBytes = new byte[length];
            bytes.get(recordBytes);
            if (crc(recordBytes) != expectedCrc) {
                position = recordStart;
                break;
            }
            byte type = recordBytes[0];
            byte[] payload = java.util.Arrays.copyOfRange(recordBytes, 1, recordBytes.length);
            apply(new WalRecord(type, payload));
            position += length;
        }
        if (position < size) {
            channel.truncate(position);
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
        fields.put("convSeq", Long.toString(message.conversationSequence()));
        fields.put("globalSeq", Long.toString(message.globalSequence()));
        return fields;
    }

    private static Message messageFrom(Map<String, String> fields) {
        Message message = new Message(fields.get("clientMsgId"), fields.get("senderUserId"),
                fields.get("convId"), fields.get("body"), Long.parseLong(fields.get("serverTs")));
        message.assignSequences(Long.parseLong(fields.get("convSeq")),
                Long.parseLong(fields.get("globalSeq")));
        return message;
    }
}
