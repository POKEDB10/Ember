package edu.ember.client.call;

/**
 * IMA ADPCM, 4 bits per sample, at 16 kHz (64 kbit/s) or 32 kHz (128 kbit/s). Byte for byte the
 * same as the website's {@code adpcmEncode}: each frame starts with the first sample (2 bytes,
 * little-endian) and the step index (1 byte), so a dropped or late frame cannot desynchronize
 * the decoder; then one 4-bit code per further sample, low nibble first.
 * @concept interfaces, encapsulation, arrays, program by contract
 */
public final class AdpcmCodec implements AudioCodec {
    public static final int ID_16K = 3;
    public static final int ID_32K = 4;
    private static final int[] STEP = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41,
        45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371,
        408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272,
        2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
    private static final int[] INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

    private final int sampleRate;
    /** Step index carried between frames by the encoder: better quality, still self-contained frames. */
    private int encoderIndex;

    public AdpcmCodec(int sampleRate) {
        if (sampleRate != 16_000 && sampleRate != 32_000) {
            throw new IllegalArgumentException("ADPCM runs at 16 or 32 kHz");
        }
        this.sampleRate = sampleRate;
    }

    @Override public int id() { return sampleRate == 16_000 ? ID_16K : ID_32K; }
    @Override public String name() { return "ADPCM " + sampleRate / 1000 + " kHz"; }
    @Override public int sampleRate() { return sampleRate; }
    @Override public AudioCodec fresh() { return new AdpcmCodec(sampleRate); }

    /** Bytes in an encoded frame: 3-byte header plus half a byte per further sample. */
    public int frameBytes() {
        return 3 + frameSamples() / 2;
    }

    @Override
    public byte[] encode(short[] pcm) {
        if (pcm.length != frameSamples()) {
            throw new IllegalArgumentException("Expected " + frameSamples() + " samples, got " + pcm.length);
        }
        byte[] out = new byte[frameBytes()];
        int predicted = pcm[0];
        int index = encoderIndex;
        out[0] = (byte) predicted;
        out[1] = (byte) (predicted >> 8);
        out[2] = (byte) index;
        for (int sample = 1; sample < pcm.length; sample++) {
            int diff = pcm[sample] - predicted;
            int code = 0;
            int step = STEP[index];
            int delta = step >> 3;
            if (diff < 0) {
                code = 8;
                diff = -diff;
            }
            if (diff >= step) {
                code |= 4;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 2;
                diff -= step;
                delta += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 1;
                delta += step;
            }
            predicted = clamp16((code & 8) != 0 ? predicted - delta : predicted + delta);
            index = Math.max(0, Math.min(88, index + INDEX[code]));
            int at = 3 + ((sample - 1) >> 1);
            out[at] = (byte) (out[at] | (((sample - 1) & 1) != 0 ? code << 4 : code));
        }
        encoderIndex = index;
        assert out.length == frameBytes() : "frame size contract";
        return out;
    }

    @Override
    public short[] decode(byte[] frame) {
        if (frame.length != frameBytes()) {
            return new short[0]; // wrong size for this codec: treated as lost, never as garbage audio
        }
        short[] out = new short[frameSamples()];
        int predicted = (short) ((frame[0] & 0xFF) | (frame[1] << 8));
        int index = Math.min(88, frame[2] & 0xFF);
        out[0] = (short) predicted;
        for (int sample = 1; sample < out.length; sample++) {
            int packed = frame[3 + ((sample - 1) >> 1)] & 0xFF;
            int code = (((sample - 1) & 1) != 0 ? packed >> 4 : packed) & 15;
            int step = STEP[index];
            int delta = step >> 3;
            if ((code & 4) != 0) {
                delta += step;
            }
            if ((code & 2) != 0) {
                delta += step >> 1;
            }
            if ((code & 1) != 0) {
                delta += step >> 2;
            }
            predicted = clamp16((code & 8) != 0 ? predicted - delta : predicted + delta);
            index = Math.max(0, Math.min(88, index + INDEX[code]));
            out[sample] = (short) predicted;
        }
        return out;
    }

    private static int clamp16(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }
}
