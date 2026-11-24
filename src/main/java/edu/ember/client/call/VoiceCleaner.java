package edu.ember.client.call;

/**
 * Cleans microphone audio before it is encoded: a DC-blocking high-pass filter removes the
 * constant offset many cheap microphones add (it wastes codec bits and makes clicks), and a peak
 * limiter stops loud bursts from clipping (clipped audio sounds harsh and encodes badly).
 * @concept encapsulation, state, filters as objects
 */
public final class VoiceCleaner {
    /** Below 1 dBFS: the loudest a sample may leave the limiter. */
    private static final float CEILING = 0.89f;
    private static final float DC_POLE = 0.995f;
    private float previousInput;
    private float previousOutput;
    private float gain = 1f;
    private final float release;

    /** @param sampleRate rate of the audio passed to {@link #process}; sets a 100 ms limiter release */
    public VoiceCleaner(int sampleRate) {
        release = 1f / (0.1f * sampleRate);
    }

    /** Filters the samples in place and returns the same array. */
    public float[] process(float[] samples) {
        for (int index = 0; index < samples.length; index++) {
            float input = samples[index];
            float highPassed = input - previousInput + DC_POLE * previousOutput;
            previousInput = input;
            previousOutput = highPassed;
            float peak = Math.abs(highPassed) * gain;
            if (peak > CEILING) {
                gain = CEILING / Math.abs(highPassed); // instant attack: never let a peak through
            } else if (gain < 1f) {
                gain = Math.min(1f, gain + release); // slow release: no pumping
            }
            samples[index] = highPassed * gain;
        }
        return samples;
    }
}
