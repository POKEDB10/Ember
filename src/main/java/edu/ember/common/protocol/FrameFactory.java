package edu.ember.common.protocol;

import java.util.Map;

/** Creates command and event frames from text fields. @concept Factory pattern */
public final class FrameFactory {
    private FrameFactory() {
        throw new AssertionError("No instances");
    }

    public static WireFrame command(int opcode, long correlationId, Map<String, String> fields) {
        if (!WireOpcodes.isCommand(opcode)) {
            throw new IllegalArgumentException("Opcode is outside the command range");
        }
        return create(opcode, correlationId, fields);
    }

    public static WireFrame event(int opcode, long correlationId, Map<String, String> fields) {
        if (!WireOpcodes.isEvent(opcode)) {
            throw new IllegalArgumentException("Opcode is outside the event range");
        }
        return create(opcode, correlationId, fields);
    }

    private static WireFrame create(int opcode, long correlationId, Map<String, String> fields) {
        return new WireFrame.Builder(opcode).correlationId(correlationId)
                .payload(KvPayloadCodec.encode(fields)).build();
    }
}
