package edu.ember.client.call;

/**
 * Changes the sample rate of a mono stream by a rational factor (for example 48 kHz to 32 kHz is
 * up 2, down 3) with a polyphase windowed-sinc low-pass filter. The filter removes everything
 * above the lower rate's Nyquist frequency before it can fold back as audible aliasing, which
 * plain linear interpolation does not do. Streaming: call {@link #process} with any chunk size;
 * the filter history carries across calls. Rebuild it when the device rate changes.
 * @concept encapsulation, arrays, program by contract
 */
public final class Resampler {
    /** Filter taps per output phase; 64 gives a transition band of about 4 kHz at 48 kHz. */
    private static final int TAPS = 64;
    private final int inRate;
    private final int outRate;
    private final int up;
    private final int down;
    private final float[][] bank;
    private final float[] history = new float[TAPS - 1];
    /** Position of the next output sample, in up-sampled units, relative to the next input chunk. */
    private long position;

    public Resampler(int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        this.inRate = inRate;
        this.outRate = outRate;
        int divisor = gcd(inRate, outRate);
        up = outRate / divisor;
        down = inRate / divisor;
        int length = TAPS * up;
        // Cut-off at 85% of the lower Nyquist frequency, in cycles per up-sampled sample.
        double cutoff = 0.85 * 0.5 / Math.max(up, down);
        double centre = (length - 1) / 2.0;
        bank = new float[up][TAPS];
        for (int index = 0; index < length; index++) {
            double x = index - centre;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            double blackman = 0.42 - 0.5 * Math.cos(2 * Math.PI * index / (length - 1))
                    + 0.08 * Math.cos(4 * Math.PI * index / (length - 1));
            bank[index % up][index / up] = (float) (sinc * blackman * up);
        }
    }

    public int inRate() { return inRate; }
    public int outRate() { return outRate; }

    /** Resamples one chunk; the output length varies by at most one sample from in * out / in. */
    public float[] process(float[] input) {
        if (inRate == outRate) {
            return input.clone();
        }
        float[] joined = new float[history.length + input.length];
        System.arraycopy(history, 0, joined, 0, history.length);
        System.arraycopy(input, 0, joined, history.length, input.length);
        int estimate = (int) ((input.length * (long) up - position) / down) + 2;
        float[] out = new float[Math.max(0, estimate)];
        int produced = 0;
        while (position < (long) input.length * up) {
            int inputIndex = (int) (position / up) + history.length;
            int phase = (int) (position % up);
            float[] taps = bank[phase];
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                sum += taps[tap] * joined[inputIndex - tap];
            }
            if (produced == out.length) {
                out = java.util.Arrays.copyOf(out, out.length + 4);
            }
            out[produced++] = (float) sum;
            position += down;
        }
        position -= (long) input.length * up;
        System.arraycopy(joined, joined.length - history.length, history, 0, history.length);
        return java.util.Arrays.copyOf(out, produced);
    }

    private static int gcd(int first, int second) {
        return second == 0 ? first : gcd(second, first % second);
    }
}
