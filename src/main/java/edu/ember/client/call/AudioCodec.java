package edu.ember.client.call;

/**
 * A voice codec: turns one 20 ms frame of 16-bit PCM into bytes and back. Implementations are
 * chosen per call by {@link CodecFactory}; adding Opus later means adding one class.
 * @concept interfaces, polymorphism, program by contract
 */
public interface AudioCodec {
    /** Wire id, the same number the website uses (1 Opus, 2 G.711, 3 ADPCM 16 kHz, 4 ADPCM 32 kHz). */
    int id();

    String name();

    int sampleRate();

    /** Samples in one 20 ms frame. */
    default int frameSamples() {
        return sampleRate() / 50;
    }

    /** Encodes exactly {@link #frameSamples()} samples. Encoders may keep state between frames. */
    byte[] encode(short[] pcm);

    /** Decodes one frame on its own (a lost frame never breaks the next one). */
    short[] decode(byte[] frame);

    /** A fresh encoder/decoder pair with no history, for a new call. */
    AudioCodec fresh();
}
