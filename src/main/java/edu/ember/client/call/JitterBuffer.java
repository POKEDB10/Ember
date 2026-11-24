package edu.ember.client.call;

import java.util.Map;
import java.util.TreeMap;

/**
 * Holds incoming voice frames just long enough to play them smoothly. The network thread adds
 * frames; the playback thread takes exactly one frame every 20 ms and must never wait, so the
 * methods are {@code synchronized} (short critical sections) and {@link #poll()} never blocks: if
 * nothing is ready it conceals the gap instead. A {@code BlockingQueue} would block the playback
 * thread and keeps arrival order, not sequence order.
 * <p>Target delay adapts between 40 and 200 ms from measured arrival jitter (RFC 3550 style
 * estimate). The relay is TCP, so a stall delivers a burst: afterwards the buffer shrinks back by
 * dropping the quietest early frames, so delay never creeps up for good. On an underrun the last
 * frame is repeated with a fade, then silence (packet-loss concealment).
 * @concept synchronization, collections (TreeMap, sorted by Comparable keys), Strategy for timing via Clock
 */
public final class JitterBuffer {
    public static final int FRAME_MS = 20;
    public static final int MIN_TARGET_MS = 40;
    public static final int MAX_TARGET_MS = 200;
    /** Concealment fades over three frames: 70%, 49%, 34%, then silence. */
    private static final double FADE = 0.7;
    private static final int MAX_CONCEALED = 3;

    private final Clock clock;
    private final int frameSamples;
    private final TreeMap<Long, AudioFrame> frames = new TreeMap<>();
    private long lastPlayed = -1;
    private boolean playing;
    private double jitterMs;
    private long lastArrivalMs = -1;
    private long lastArrivalSequence = -1;
    private short[] lastSamples;
    private int concealedRun;
    private long concealedTotal;
    private long lateDropped;
    private long trimDropped;

    public JitterBuffer(Clock clock, int frameSamples) {
        if (frameSamples <= 0) {
            throw new IllegalArgumentException("frame size must be positive");
        }
        this.clock = clock;
        this.frameSamples = frameSamples;
    }

    /** Adds a decoded frame (network thread). Late or duplicate frames are dropped. */
    public synchronized void put(long sequence, short[] samples) {
        long now = clock.nowMs();
        if (sequence <= lastPlayed || frames.containsKey(sequence) || samples.length != frameSamples) {
            lateDropped++;
            return;
        }
        if (lastArrivalMs >= 0 && sequence > lastArrivalSequence) {
            long transitDelta = (now - lastArrivalMs) - (sequence - lastArrivalSequence) * FRAME_MS;
            jitterMs += (Math.abs(transitDelta) - jitterMs) / 16.0;
        }
        if (sequence > lastArrivalSequence) {
            lastArrivalMs = now;
            lastArrivalSequence = sequence;
        }
        frames.put(sequence, new AudioFrame(sequence, samples, now));
    }

    /** The delay the buffer aims for, from measured jitter. */
    public synchronized int targetMs() {
        int target = (int) Math.round(FRAME_MS + 3 * jitterMs);
        return Math.max(MIN_TARGET_MS, Math.min(MAX_TARGET_MS, target));
    }

    public synchronized int depthMs() {
        return frames.size() * FRAME_MS;
    }

    /**
     * The next 20 ms to play (playback thread, never blocks). While starting it returns silence
     * until the target is reached.
     */
    public synchronized short[] poll() {
        if (!playing) {
            if (depthMs() < targetMs() || frames.isEmpty()) {
                return AudioFrame.silence(frameSamples);
            }
            playing = true;
        }
        trimIfTooDeep();
        Map.Entry<Long, AudioFrame> next = frames.pollFirstEntry();
        if (next == null) {
            return conceal();
        }
        lastPlayed = next.getKey();
        concealedRun = 0;
        lastSamples = next.getValue().samples();
        return lastSamples.clone();
    }

    /** After a burst, drop one frame per 20 ms tick (the quietest of the oldest half) until back on target. */
    private void trimIfTooDeep() {
        if (depthMs() <= targetMs() + 2 * FRAME_MS || frames.size() < 2) {
            return;
        }
        AudioFrame quietest = null;
        int checked = 0;
        for (AudioFrame frame : frames.values()) {
            if (checked++ > frames.size() / 2) {
                break;
            }
            if (quietest == null || frame.energy() < quietest.energy()) {
                quietest = frame;
            }
        }
        frames.remove(quietest.sequence());
        trimDropped++;
    }

    private short[] conceal() {
        concealedTotal++;
        if (lastSamples == null || concealedRun >= MAX_CONCEALED) {
            playing = !frames.isEmpty();
            if (concealedRun >= MAX_CONCEALED) {
                playing = false; // rebuild the cushion before playing again
            }
            return AudioFrame.silence(frameSamples);
        }
        concealedRun++;
        return AudioFrame.faded(lastSamples, Math.pow(FADE, concealedRun));
    }

    /** Snapshot for the stats overlay and log. */
    public synchronized Stats stats() {
        return new Stats(depthMs(), targetMs(), (int) Math.round(jitterMs), concealedTotal, lateDropped, trimDropped);
    }

    /** Immutable copy of the buffer's counters at one moment. */
    public record Stats(int depthMs, int targetMs, int jitterMs, long concealed, long lateDropped, long trimDropped) { }
}
