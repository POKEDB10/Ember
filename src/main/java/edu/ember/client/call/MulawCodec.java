package edu.ember.client.call;

/** G.711 mu-law at 8 kHz (64 kbit/s): the last-resort voice codec every Ember build understands. @concept interfaces */
public final class MulawCodec implements AudioCodec {
    public static final int ID = 2;

    @Override public int id() { return ID; }
    @Override public String name() { return "G.711 8 kHz"; }
    @Override public int sampleRate() { return 8_000; }
    @Override public AudioCodec fresh() { return new MulawCodec(); }

    @Override
    public byte[] encode(short[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int index = 0; index < pcm.length; index++) {
            int sample = Math.max(-32635, Math.min(32635, (int) pcm[index]));
            int sign = sample < 0 ? 0x80 : 0;
            if (sign != 0) {
                sample = -sample;
            }
            sample += 0x84;
            int exponent = 7;
            for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; mask >>= 1) {
                exponent--;
            }
            out[index] = (byte) (~(sign | (exponent << 4) | ((sample >> (exponent + 3)) & 0x0F)) & 0xFF);
        }
        return out;
    }

    @Override
    public short[] decode(byte[] frame) {
        short[] out = new short[frame.length];
        for (int index = 0; index < frame.length; index++) {
            int bits = ~frame[index] & 0xFF;
            int exponent = (bits >> 4) & 7;
            int sample = ((((bits & 0x0F) << 3) + 0x84) << exponent) - 0x84;
            out[index] = (short) ((bits & 0x80) != 0 ? -sample : sample);
        }
        return out;
    }
}
