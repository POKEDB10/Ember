package edu.ember.client;

import edu.ember.common.protocol.FrameCodec;
import edu.ember.common.protocol.WireFrame;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

/** Serializes all writes through one queue and one named connection thread. @concept threads, wait/notify, producer-consumer */
public final class OutboundSenderThread extends Thread {
    private final LinkedList<WireFrame> queue = new LinkedList<>();
    private final Object queueLock = new Object();
    private final OutputStream output;
    private volatile boolean running = true;
    private volatile IOException failure;

    public OutboundSenderThread(OutputStream output) {
        super("ember-client-outbound");
        this.output = output;
        setDaemon(true);
    }

    public void enqueue(WireFrame frame) {
        synchronized (queueLock) {
            queue.addLast(frame);
            queueLock.notifyAll();
        }
    }

    public void stopSending() {
        synchronized (queueLock) {
            running = false;
            queueLock.notifyAll();
        }
    }

    public IOException failure() { return failure; }

    @Override
    public void run() {
        while (running) {
            WireFrame frame = nextFrame();
            if (frame == null) {
                continue;
            }
            try {
                FrameCodec.writeFrame(output, frame);
            } catch (IOException exception) {
                failure = exception;
                stopSending();
            }
        }
    }

    private WireFrame nextFrame() {
        synchronized (queueLock) {
            while (queue.isEmpty() && running) {
                try {
                    queueLock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
            return queue.isEmpty() ? null : queue.removeFirst();
        }
    }
}
