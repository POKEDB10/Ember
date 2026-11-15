package edu.ember.client;

import edu.ember.common.exceptions.EmberException;
import edu.ember.common.protocol.FrameCodec;
import edu.ember.common.protocol.WireFrame;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.List;

/** Persists pending frames in FIFO order using the framed KV wire format. @concept LinkedList, file I/O */
public final class LocalOutbox {
    private static final int MAX_FRAME_BYTES = 5 * 1024 * 1024;
    private final Path file;
    private final LinkedList<WireFrame> frames = new LinkedList<>();

    public LocalOutbox(Path file) throws IOException, EmberException {
        this.file = file;
        load();
    }

    public synchronized void enqueue(WireFrame frame) throws IOException {
        frames.addLast(frame);
        persist();
    }

    public synchronized WireFrame peek() {
        return frames.peekFirst();
    }

    public synchronized WireFrame removeFirst() throws IOException {
        WireFrame removed = frames.pollFirst();
        persist();
        return removed;
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized List<WireFrame> snapshot() {
        return List.copyOf(frames);
    }

    private void load() throws IOException, EmberException {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream input = Files.newInputStream(file)) {
            while (input.available() > 0) {
                frames.addLast(FrameCodec.readFrame(input, MAX_FRAME_BYTES));
            }
        }
    }

    private void persist() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            for (WireFrame frame : frames) {
                FrameCodec.writeFrame(output, frame);
            }
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
