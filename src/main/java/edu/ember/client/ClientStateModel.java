package edu.ember.client;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
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
    private String userId;
    private String activeConversationId;
    private String errorMessage;

    public synchronized String userId() { return userId; }
    public synchronized String activeConversationId() { return activeConversationId; }
    public synchronized String errorMessage() { return errorMessage; }

    public void addListener(ClientModelListener listener) { listeners.add(listener); }
    public void removeListener(ClientModelListener listener) { listeners.remove(listener); }

    public synchronized List<Message> messages(String conversationId) {
        return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
    }

    synchronized void authenticated(String newUserId) {
        userId = newUserId;
        errorMessage = null;
        fire("AUTH_OK", null);
    }

    synchronized void openConversation(String conversationId) {
        activeConversationId = conversationId;
        fire("CONVERSATION_OPEN", null);
    }

    synchronized void addMessage(Message message) {
        List<Message> list = conversations.computeIfAbsent(message.conversationId(),
                ignored -> new ArrayList<>());
        if (list.stream().noneMatch(item -> item.clientMsgId().equals(message.clientMsgId()))) {
            list.add(message);
        }
        fire("MESSAGE", message);
    }

    synchronized void updateStatus(String clientMsgId, MessageStatus status) {
        Message found = findMessage(clientMsgId);
        if (found != null) {
            found.advanceStatus(status);
            fire("MESSAGE_STATUS", found);
        }
    }

    synchronized void setError(String message) {
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
}
