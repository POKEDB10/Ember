package edu.ember.common.protocol;

import edu.ember.common.exceptions.EmberException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Reads and writes bounded, length-prefixed protocol frames. @concept streams, binary protocol */
public final class FrameCodec {
    public static final int MAGIC = 0x454D4252;
    public static final int VERSION = 0x01;
    public static final int HEADER_BYTES = 18;

    private FrameCodec() {
        throw new AssertionError("No instances");
    }

    public static WireFrame readFrame(InputStream input, int maxPayloadBytes)
            throws IOException, EmberException {
        return readFrame(input, maxPayloadBytes, -1, maxPayloadBytes);
    }

    public static WireFrame readFrame(InputStream input, int maxPayloadBytes,
            int largeOpcode, int largePayloadBytes) throws IOException, EmberException {
        DataInputStream data = input instanceof DataInputStream stream
                ? stream : new DataInputStream(input);
        int magic = data.readInt();
        if (magic != MAGIC) {
            throw new ProtocolFrameException("Invalid frame magic");
        }
        int version = data.readUnsignedByte();
        if (version != VERSION) {
            throw new ProtocolFrameException("Unsupported protocol version");
        }
        int opcode = data.readUnsignedByte();
        long correlationId = data.readLong();
        int length = data.readInt();
        int limit = opcode == largeOpcode ? largePayloadBytes : maxPayloadBytes;
        if (length < 0 || length > limit) {
            throw new ProtocolFrameException("Frame length exceeds the allowed limit");
        }
        // readNBytes grows as bytes arrive, so a forged huge length cannot pre-allocate memory.
        byte[] payload = data.readNBytes(length);
        if (payload.length != length) {
            throw new java.io.EOFException("Frame ended early");
        }
        return new WireFrame.Builder(opcode).correlationId(correlationId).payload(payload).build();
    }

    public static void writeFrame(OutputStream output, WireFrame frame) throws IOException {
        DataOutputStream data = output instanceof DataOutputStream stream
                ? stream : new DataOutputStream(output);
        byte[] payload = frame.payload();
        data.writeInt(MAGIC);
        data.writeByte(VERSION);
        data.writeByte(frame.opcode());
        data.writeLong(frame.correlationId());
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }
}
