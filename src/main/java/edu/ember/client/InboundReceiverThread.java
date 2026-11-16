package edu.ember.client;

import edu.ember.common.exceptions.EmberException;
import edu.ember.common.protocol.FrameCodec;
import java.io.IOException;
import java.io.InputStream;

/** Reads server frames on a single named thread and dispatches them to the controller. @concept threads, producer-consumer */
public final class InboundReceiverThread extends Thread {
    private final InputStream input;
    private final ClientController controller;

    public InboundReceiverThread(InputStream input, ClientController controller) {
        super("ember-client-inbound");
        this.input = input;
        this.controller = controller;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            while (!isInterrupted()) {
                controller.handleFrame(FrameCodec.readFrame(input, 64 * 1024,
                        edu.ember.common.protocol.WireOpcodes.EVT_MEDIA_DATA, 7 * 1024 * 1024));
            }
        } catch (IOException | EmberException exception) {
            controller.connectionEnded(exception);
        }
    }
}
