package edu.ember.common.protocol;

import java.util.Arrays;

/** Immutable message exchanged by an Ember client and server. @concept immutable objects, Builder pattern */
public final class WireFrame {
    private final int opcode;
    private final long correlationId;
    private final byte[] payload;

    private WireFrame(int opcode, long correlationId, byte[] payload) {
        if (opcode < 0 || opcode > 0xFF) {
            throw new IllegalArgumentException("Opcode must fit one byte");
        }
        this.opcode = opcode;
        this.correlationId = correlationId;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public int opcode() {
        return opcode;
    }

    public long correlationId() {
        return correlationId;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public static final class Builder {
        private final int opcode;
        private long correlationId;
        private byte[] payload = new byte[0];

        public Builder(int opcode) {
            this.opcode = opcode;
        }

        public Builder correlationId(long value) {
            correlationId = value;
            return this;
        }

        public Builder payload(byte[] value) {
            payload = Arrays.copyOf(value, value.length);
            return this;
        }

        public WireFrame build() {
            return new WireFrame(opcode, correlationId, payload);
        }
    }
}
