package edu.ember.client;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.model.SocialPost;
import edu.ember.common.model.UserProfile;
import edu.ember.common.exceptions.EmberException;
import edu.ember.common.protocol.FrameFactory;
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.WireFrame;
import edu.ember.common.protocol.WireOpcodes;
import edu.ember.common.security.TlsSocketFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/** Facade for client authentication, messaging, and model observation. @concept Facade, MVC */
public final class ClientController implements AutoCloseable {
    private final SSLSocket socket;
    private final ClientStateModel model = new ClientStateModel();
    private final OutboundSenderThread outbound;
    private final InboundReceiverThread inbound;
    private final AtomicLong correlationIds = new AtomicLong();
    private final ExecutorService outboxWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ember-client-outbox-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ember-client-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService mediaWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ember-client-media-worker");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String activeConversationId;
    private volatile String sessionToken;
    private volatile LocalOutbox outbox;
    private volatile boolean closing;
    private volatile boolean readReceiptsEnabled = true;
    private final Path clientDataRoot;

    public ClientController(String host, int port, Path trustStore, char[] password)
            throws Exception {
        this(host, port, trustStore, password, Path.of("client_data"));
    }

    public ClientController(String host, int port, Path trustStore, char[] password,
            Path clientDataRoot) throws Exception {
        this.clientDataRoot = clientDataRoot;
        socket = TlsSocketFactory.createClientSocket(trustStore, password, host, port);
        socket.setSoTimeout(45_000);
        socket.startHandshake();
        outbound = new OutboundSenderThread(socket.getOutputStream());
        inbound = new InboundReceiverThread(socket.getInputStream(), this);
        outbound.start();
        inbound.start();
        heartbeat.scheduleAtFixedRate(() -> {
            if (model.userId() != null && !socket.isClosed()) {
                ping();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public ClientStateModel model() { return model; }

    public void login(String username, char[] password) {
        sendCommand(WireOpcodes.CMD_AUTH_LOGIN, Map.of("user", username,
                "pass", new String(password)));
    }

    public void register(String username, char[] password, String displayName) {
        sendCommand(WireOpcodes.CMD_AUTH_REGISTER, Map.of("user", username,
                "pass", new String(password), "displayName", displayName));
    }

    public String sendMessage(String conversationId, String body) {
        return sendMessageWithId(conversationId, body, UUID.randomUUID().toString());
    }

    public String sendMessageWithId(String conversationId, String body, String clientMsgId) {
        if (model.userId() == null) {
            throw new IllegalStateException("Authenticate before sending a message");
        }
        if (model.messages(conversationId).stream()
                .noneMatch(existing -> existing.clientMsgId().equals(clientMsgId))) {
            Message message = new Message(clientMsgId, model.userId(), conversationId, body,
                    System.currentTimeMillis());
            message.advanceStatus(MessageStatus.PENDING_LOCAL);
            model.addMessage(message);
        }
        WireFrame frame = commandFrame(WireOpcodes.CMD_MSG_SEND,
                Map.of("clientMsgId", clientMsgId, "convId", conversationId, "body", body));
        outboxWriter.execute(() -> persistAndSend(frame));
        return clientMsgId;
    }

    public void resume(String token, long lastGlobalSeq) {
        sendCommand(WireOpcodes.CMD_AUTH_RESUME,
                Map.of("token", token, "lastGlobalSeq", Long.toString(lastGlobalSeq)));
    }

    public void resumeStoredSession(String userId) throws IOException {
        Path tokenFile = clientDataRoot.resolve(userId).resolve("session.token");
        if (!Files.exists(tokenFile)) {
            throw new IOException("No saved session token");
        }
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8);
        resume(token, model.lastGlobalSequence());
    }

    public void ping() {
        sendCommand(WireOpcodes.CMD_PING, Map.of());
    }

    public void simulateNetworkDrop() {
        try {
            socket.close();
        } catch (IOException exception) {
            model.setError("500", "Could not simulate connection drop");
        }
    }

    public void requestHistory(String conversationId, long beforeSequence, int limit) {
        sendCommand(WireOpcodes.CMD_HISTORY_PULL, Map.of("convId", conversationId,
                "beforeSeq", Long.toString(beforeSequence), "limit", Integer.toString(limit)));
    }

    public String sessionToken() { return sessionToken; }
    public boolean readReceiptsEnabled() { return readReceiptsEnabled; }
    public void setReadReceiptsEnabled(boolean enabled) { readReceiptsEnabled = enabled; }

    public void openConversation(String conversationId) {
        activeConversationId = conversationId;
        model.openConversation(conversationId);
    }

    public void markRead(String conversationId, String messageId) {
        sendCommand(WireOpcodes.CMD_MSG_RECEIPT, Map.of("convId", conversationId,
                "clientMsgId", messageId, "status", "READ"));
    }

    public void setTyping(String conversationId, boolean typing) {
        sendCommand(WireOpcodes.CMD_TYPING, Map.of("convId", conversationId,
                "typing", Boolean.toString(typing)));
    }

    public void createGroup(String name, String description) {
        sendCommand(WireOpcodes.CMD_GROUP_CREATE,
                Map.of("name", name, "desc", description, "member.n", "0"));
    }

    public void changeGroupMember(String groupId, String targetUser, String action) {
        sendCommand(WireOpcodes.CMD_GROUP_MEMBER,
                Map.of("groupId", groupId, "targetUser", targetUser, "action", action));
    }

    public void updateProfile(String displayName, String bio) {
        sendCommand(WireOpcodes.CMD_PROFILE_UPDATE, Map.of("userId", model.userId(),
                "displayName", displayName, "bio", bio));
    }

    public void follow(String targetUserId, boolean following) {
        sendCommand(WireOpcodes.CMD_FOLLOW_ACTION, Map.of("targetUserId", targetUserId,
                "follow", Boolean.toString(following)));
    }

    public void uploadMedia(byte[] imageBytes, String fileName) {
        mediaWorker.execute(() -> {
            String encoded = java.util.Base64.getEncoder().encodeToString(imageBytes);
            sendCommand(WireOpcodes.CMD_MEDIA_UPLOAD,
                    Map.of("fileName", fileName, "data", encoded));
        });
    }

    public void createPost(String mediaId, String caption) {
        sendCommand(WireOpcodes.CMD_POST_CREATE, Map.of("mediaId", mediaId, "caption", caption));
    }

    public void interactPost(String postId, String action, String text) {
        sendCommand(WireOpcodes.CMD_POST_INTERACT,
                Map.of("postId", postId, "action", action, "text", text == null ? "" : text));
    }

    public void requestFeed(String ownerUserId) {
        sendCommand(WireOpcodes.CMD_FEED_PULL,
                Map.of("ownerUserId", ownerUserId == null ? "" : ownerUserId, "limit", "30"));
    }

    public void requestProfile(String userId) {
        sendCommand(WireOpcodes.CMD_PROFILE_GET, Map.of("userId", userId));
    }

    public void requestMedia(String mediaId, boolean thumbnail) {
        sendCommand(WireOpcodes.CMD_MEDIA_GET, Map.of("mediaId", mediaId,
                "thumbnail", Boolean.toString(thumbnail)));
    }

    public void logout() {
        sendCommand(WireOpcodes.CMD_AUTH_LOGOUT, Map.of());
        close();
    }

    public void addListener(ClientModelListener listener) { model.addListener(listener); }
    public void removeListener(ClientModelListener listener) { model.removeListener(listener); }

    @Override
    public void close() {
        closing = true;
        heartbeat.shutdownNow();
        mediaWorker.shutdownNow();
        outbound.stopSending();
        outboxWriter.shutdown();
        try {
            socket.close();
        } catch (IOException exception) {
            model.setError("500", "Connection close failed");
        }
        inbound.interrupt();
    }

    void handleFrame(WireFrame frame) {
        Map<String, String> fields = KvPayloadCodec.decode(frame.payload());
        if (frame.opcode() == WireOpcodes.EVT_AUTH_OK) {
            sessionToken = fields.get("token");
            String userId = fields.get("userId");
            initializeOutbox(userId);
            model.authenticated(userId);
        } else if (frame.opcode() == WireOpcodes.EVT_MSG_ACK) {
            model.updateAcknowledgement(fields.get("clientMsgId"),
                    Long.parseLong(fields.get("convSeq")), Long.parseLong(fields.get("globalSeq")));
            model.updateStatus(fields.get("clientMsgId"), MessageStatus.SENT);
            acknowledgeOutbox();
        } else if (frame.opcode() == WireOpcodes.EVT_MSG_DELIVER) {
            handleDelivery(fields);
        } else if (frame.opcode() == WireOpcodes.EVT_RECEIPT_UPDATE) {
            String names = fields.getOrDefault("readBy", "");
            model.updateReceipt(fields.get("clientMsgId"),
                    Integer.parseInt(fields.getOrDefault("delivered", "0")),
                    Integer.parseInt(fields.getOrDefault("read", "0")),
                    Integer.parseInt(fields.getOrDefault("total", "1")), names);
        } else if (frame.opcode() == WireOpcodes.EVT_SYNC_ITEM) {
            handleDelivery(fields);
        } else if (frame.opcode() == WireOpcodes.EVT_SYNC_DONE) {
            drainOutbox();
        } else if (frame.opcode() == WireOpcodes.EVT_GROUP_UPDATE) {
            model.updateGroup(fields.get("groupId"), Integer.parseInt(fields.get("member.n")));
        } else if (frame.opcode() == WireOpcodes.EVT_HISTORY_PAGE) {
            model.addHistoryMessage(readMessage(fields));
        } else if (frame.opcode() == WireOpcodes.EVT_PONG) {
            model.heartbeatReceived();
        } else if (frame.opcode() == WireOpcodes.EVT_PRESENCE_UPDATE) {
            model.updatePresence(fields.get("userId"), Boolean.parseBoolean(fields.get("online")));
        } else if (frame.opcode() == WireOpcodes.EVT_TYPING) {
            model.updateTyping(fields.get("convId"), fields.get("userId"),
                    Boolean.parseBoolean(fields.get("typing")));
        } else if (frame.opcode() == WireOpcodes.EVT_PROFILE_DATA) {
            if (fields.containsKey("displayName")) {
                model.setProfile(new UserProfile(fields.get("userId"), fields.get("displayName"),
                        fields.getOrDefault("bio", ""), fields.getOrDefault("avatarMediaId", "")));
            }
        } else if (frame.opcode() == WireOpcodes.EVT_FEED_PAGE) {
            if (fields.containsKey("comment.n")) {
                int count = Integer.parseInt(fields.getOrDefault("comment.n", "0"));
                java.util.List<edu.ember.common.model.PostComment> comments = new java.util.ArrayList<>();
                for (int index = 0; index < count; index++) {
                    comments.add(new edu.ember.common.model.PostComment(
                            fields.get("comment.id." + index), fields.get("postId"),
                            fields.get("comment.author." + index),
                            fields.get("comment.text." + index),
                            Long.parseLong(fields.get("comment.created." + index))));
                }
                model.setComments(fields.get("postId"), comments);
                return;
            }
            int count = Integer.parseInt(fields.getOrDefault("post.n", "0"));
            java.util.List<SocialPost> posts = new java.util.ArrayList<>();
            for (int index = 0; index < count; index++) {
                posts.add(new SocialPost(fields.get("post." + index), fields.get("owner." + index),
                        fields.get("media." + index), fields.getOrDefault("caption." + index, ""),
                        Long.parseLong(fields.getOrDefault("created." + index, "0")),
                        Integer.parseInt(fields.getOrDefault("likes." + index, "0")),
                        Integer.parseInt(fields.getOrDefault("comments." + index, "0")),
                        Boolean.parseBoolean(fields.getOrDefault("liked." + index, "false"))));
            }
            model.setSocialPosts(posts);
        } else if (frame.opcode() == WireOpcodes.EVT_MEDIA_OK) {
            model.mediaUploaded(fields.get("mediaId"));
        } else if (frame.opcode() == WireOpcodes.EVT_MEDIA_DATA) {
            model.setMedia(fields.get("mediaId"), java.util.Base64.getDecoder().decode(fields.get("data")));
        } else if (frame.opcode() == WireOpcodes.EVT_ERROR) {
            model.setError(fields.getOrDefault("code", "500"),
                    fields.getOrDefault("message", "Request failed"));
        }
    }

    void connectionEnded(Exception exception) {
        if (!closing && !socket.isClosed()) {
            model.setError("503", "Connection ended: " + exception.getMessage());
        }
    }

    private void handleDelivery(Map<String, String> fields) {
        Message message = readMessage(fields);
        message.advanceStatus(MessageStatus.DELIVERED);
        model.addMessage(message);
        if (!message.senderUserId().equals(model.userId())) {
            sendCommand(WireOpcodes.CMD_MSG_RECEIPT, Map.of("convId", message.conversationId(),
                    "clientMsgId", message.clientMsgId(), "status", "DELIVERED"));
        }
    }

    private Message readMessage(Map<String, String> fields) {
        Message message = new Message(fields.get("clientMsgId"), fields.get("senderUserId"),
                fields.get("convId"), fields.get("body"),
                Long.parseLong(fields.get("serverTs")));
        message.assignSequences(Long.parseLong(fields.get("convSeq")),
                Long.parseLong(fields.get("globalSeq")));
        message.advanceStatus(MessageStatus.DELIVERED);
        return message;
    }

    private void sendCommand(int opcode, Map<String, String> fields) {
        outbound.enqueue(commandFrame(opcode, fields));
    }

    private WireFrame commandFrame(int opcode, Map<String, String> fields) {
        byte[] payload = edu.ember.common.protocol.KvPayloadCodec.encode(new HashMap<>(fields));
        return new WireFrame.Builder(opcode).correlationId(correlationIds.incrementAndGet())
                .payload(payload).build();
    }

    private void initializeOutbox(String userId) {
        try {
            Path userDirectory = clientDataRoot.resolve(userId);
            Files.createDirectories(userDirectory);
            outbox = new LocalOutbox(userDirectory.resolve("outbox.dat"));
            Files.writeString(userDirectory.resolve("session.token"), sessionToken,
                    StandardCharsets.UTF_8);
        } catch (IOException | EmberException exception) {
            model.setError("500", "Could not open local message outbox");
        }
    }

    private void persistAndSend(WireFrame frame) {
        try {
            LocalOutbox current = outbox;
            if (current == null) {
                throw new IOException("Outbox is not initialized");
            }
            current.enqueue(frame);
            outbound.enqueue(frame);
        } catch (IOException exception) {
            model.setError("507", "Could not save pending message");
        }
    }

    private void acknowledgeOutbox() {
        outboxWriter.execute(() -> {
            try {
                if (outbox != null && outbox.size() > 0) {
                    outbox.removeFirst();
                }
            } catch (IOException exception) {
                model.setError("507", "Could not update local message outbox");
            }
        });
    }

    private void drainOutbox() {
        LocalOutbox current = outbox;
        if (current == null) {
            return;
        }
        for (WireFrame frame : current.snapshot()) {
            outbound.enqueue(frame);
        }
    }
}
