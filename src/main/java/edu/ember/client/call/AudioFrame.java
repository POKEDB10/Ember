package edu.ember.client.call;

import java.util.Arrays;

/**
 * One decoded 20 ms frame waiting in the jitter buffer. Frames sort by sequence number, and two
 * frames are equal when their sequence numbers are equal, so a duplicate cannot be stored twice
 * (equals, hashCode and compareTo agree). The samples are copied in and out (deep copy), so no
 * caller can change a frame after it is queued.
 * @concept Comparable, Object class (equals/hashCode/toString), immutability, deep copy
 */
public final class AudioFrame implements Comparable<AudioFrame> {
    private final long sequence;
    private final short[] samples;
    private final long arrivalMs;

    public AudioFrame(long sequence, short[] samples, long arrivalMs) {
        this.sequence = sequence;
        this.samples = samples.clone();
        this.arrivalMs = arrivalMs;
    }

    public long sequence() { return sequence; }
    public long arrivalMs() { return arrivalMs; }
    public int length() { return samples.length; }

    /** A copy of the samples (the frame itself never changes). */
    public short[] samples() {
        return samples.clone();
    }

    /** Average power, used to drop the quietest frame first when the buffer must shrink. */
    public double energy() {
        double sum = 0;
        for (short sample : samples) {
            sum += (double) sample * sample;
        }
        return samples.length == 0 ? 0 : sum / samples.length;
    }

    @Override
    public int compareTo(AudioFrame other) {
        return Long.compare(sequence, other.sequence);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioFrame frame && frame.sequence == sequence;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(sequence);
    }

    @Override
    public String toString() {
        return "AudioFrame[" + sequence + ", " + samples.length + " samples]";
    }

    /** Silence of the given length. */
    public static short[] silence(int length) {
        return new short[length];
    }

    /** A quieter copy of some samples (for loss concealment fade-out). */
    public static short[] faded(short[] samples, double gain) {
        short[] out = Arrays.copyOf(samples, samples.length);
        for (int index = 0; index < out.length; index++) {
            out[index] = (short) Math.round(out[index] * gain);
        }
        return out;
    }
}
