package edu.ember.client;

import edu.ember.common.model.Message;

/** Observes client model changes and is always called on Swing's event dispatch thread. @concept interfaces, Observer pattern */
public interface ClientModelListener {
    void onModelChanged(ClientStateModel model, String eventType, Message message);
}
