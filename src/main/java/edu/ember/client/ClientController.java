package edu.ember.client;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import edu.ember.common.protocol.FrameFactory;
import edu.ember.common.protocol.KvPayloadCodec;
import edu.ember.common.protocol.WireFrame;
import edu.ember.common.protocol.WireOpcodes;
import edu.ember.common.security.TlsSocketFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSocket;

/** Facade for client authentication, messaging, and model observation. @concept Facade, MVC */
public final class ClientController implements AutoCloseable {
    private final SSLSocket socket;
    private final ClientStateModel model = new ClientStateModel();
    private final OutboundSenderThread outbound;
    private final InboundReceiverThread inbound;
    private final AtomicLong correlationIds = new AtomicLong();
    private volatile String activeConversationId;
    private volatile String sessionToken;

    public ClientController(String host, int port, Path trustStore, char[] password)
            throws Exception {
        socket = TlsSocketFactory.createClientSocket(trustStore, password, host, port);
        socket.setSoTimeout(45_000);
        socket.startHandshake();
        outbound = new OutboundSenderThread(socket.getOutputStream());
        inbound = new InboundReceiverThread(socket.getInputStream(), this);
        outbound.start();
        inbound.start();
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
        if (model.userId() == null) {
            throw new IllegalStateException("Authenticate before sending a message");
        }
        String clientMsgId = UUID.randomUUID().toString();
        Message message = new Message(clientMsgId, model.userId(), conversationId, body,
                System.currentTimeMillis());
        message.advanceStatus(MessageStatus.PENDING_LOCAL);
        model.addMessage(message);
        sendCommand(WireOpcodes.CMD_MSG_SEND, Map.of("clientMsgId", clientMsgId,
                "convId", conversationId, "body", body));
        return clientMsgId;
    }

    public void openConversation(String conversationId) {
        activeConversationId = conversationId;
        model.openConversation(conversationId);
    }

    public void markRead(String conversationId, String messageId) {
        sendCommand(WireOpcodes.CMD_MSG_RECEIPT, Map.of("convId", conversationId,
                "clientMsgId", messageId, "status", "READ"));
    }

    public void setTyping(String conversationId, boolean typing) {
        if (typing) {
            sendCommand(WireOpcodes.CMD_TYPING, Map.of("convId", conversationId));
        }
    }

    public void logout() {
        sendCommand(WireOpcodes.CMD_AUTH_LOGOUT, Map.of());
        close();
    }

    public void addListener(ClientModelListener listener) { model.addListener(listener); }
    public void removeListener(ClientModelListener listener) { model.removeListener(listener); }

    @Override
    public void close() {
        outbound.stopSending();
        inbound.interrupt();
        try {
            socket.close();
        } catch (IOException exception) {
            model.setError("Connection close failed");
        }
    }

    void handleFrame(WireFrame frame) {
        Map<String, String> fields = KvPayloadCodec.decode(frame.payload());
        if (frame.opcode() == WireOpcodes.EVT_AUTH_OK) {
            sessionToken = fields.get("token");
            model.authenticated(fields.get("userId"));
        } else if (frame.opcode() == WireOpcodes.EVT_MSG_ACK) {
            model.updateStatus(fields.get("clientMsgId"), MessageStatus.SENT);
        } else if (frame.opcode() == WireOpcodes.EVT_MSG_DELIVER) {
            handleDelivery(fields);
        } else if (frame.opcode() == WireOpcodes.EVT_RECEIPT_UPDATE) {
            model.updateStatus(fields.get("clientMsgId"), parseStatus(fields.get("status")));
        } else if (frame.opcode() == WireOpcodes.EVT_ERROR) {
            model.setError(fields.getOrDefault("message", "Request failed"));
        }
    }

    void connectionEnded(Exception exception) {
        if (!socket.isClosed()) {
            model.setError("Connection ended: " + exception.getMessage());
        }
    }

    private void handleDelivery(Map<String, String> fields) {
        Message message = new Message(fields.get("clientMsgId"), fields.get("senderUserId"),
                fields.get("convId"), fields.get("body"),
                Long.parseLong(fields.get("serverTs")));
        message.assignSequences(Long.parseLong(fields.get("convSeq")),
                Long.parseLong(fields.get("globalSeq")));
        message.advanceStatus(MessageStatus.DELIVERED);
        model.addMessage(message);
        sendCommand(WireOpcodes.CMD_MSG_RECEIPT, Map.of("convId", message.conversationId(),
                "clientMsgId", message.clientMsgId(), "status", "DELIVERED"));
    }

    private MessageStatus parseStatus(String value) {
        return "READ".equals(value) ? MessageStatus.READ : MessageStatus.DELIVERED;
    }

    private void sendCommand(int opcode, Map<String, String> fields) {
        byte[] payload = edu.ember.common.protocol.KvPayloadCodec.encode(new HashMap<>(fields));
        outbound.enqueue(new WireFrame.Builder(opcode).correlationId(correlationIds.incrementAndGet())
                .payload(payload).build());
    }
}
