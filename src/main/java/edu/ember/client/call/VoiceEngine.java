package edu.ember.client.call;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The calls v2 voice pipeline for the desktop app. Two threads, both off the Swing thread:
 * <ul>
 *   <li><b>capture</b>: microphone (48 kHz when the device offers it) → DC blocker and limiter →
 *   anti-aliased resampling to the codec rate → encode → {@link Transport};</li>
 *   <li><b>playout</b>: every 20 ms, one frame per person from their {@link JitterBuffer}
 *   (concealing gaps) → resampled to the speaker rate → mixed → speaker.</li>
 * </ul>
 * A device that fails (unplugged, taken by another app) is reopened, falling back to the system
 * default, and the resamplers are rebuilt for the new device's rate. Devices are always released
 * in {@code finally}.
 * @concept threads, synchronization, Observer pattern, exceptions with finally, composition
 */
public final class VoiceEngine {
    private final AudioDevices.Provider devices;
    private final CallSettings settings;
    private final Clock clock;
    private final Transport transport;
    private final Consumer<String> status;
    private final List<CallStatsListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, PeerVoice> peers = new ConcurrentHashMap<>();
    private volatile AudioCodec sendCodec;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile int rttMs = -1;
    private volatile String transportName = "relay";
    private volatile String deviceNames = "";
    private volatile double remoteLevel;
    private long sequence;
    private long sentBytes;
    private Thread capture;
    private Thread playout;

    /** One other person: their decoder, jitter buffer, and resampler to the speaker rate. */
    private static final class PeerVoice {
        final AudioCodec decoder;
        final JitterBuffer buffer;
        Resampler toSpeaker;
        final ArrayList<Float> pending = new ArrayList<>();

        PeerVoice(AudioCodec decoder, Clock clock) {
            this.decoder = decoder;
            this.buffer = new JitterBuffer(clock, decoder.frameSamples());
        }
    }

    public VoiceEngine(AudioDevices.Provider devices, CallSettings settings, Clock clock, Transport transport,
            Consumer<String> status) {
        this.devices = devices;
        this.settings = settings;
        this.clock = clock;
        this.transport = transport;
        this.status = status;
    }

    public void addStatsListener(CallStatsListener listener) { listeners.add(listener); }
    public void setMuted(boolean value) { muted = value; }
    public void setRttMs(int value) { rttMs = value; }
    public void setTransportName(String name) { transportName = name; }

    /** The codec this side sends, chosen once at connect (see {@link CodecFactory}). */
    public void setSendCodec(AudioCodec codec) {
        if (sendCodec == null) {
            sendCodec = codec.fresh();
        }
    }

    public AudioCodec sendCodec() { return sendCodec; }

    /** Opens the devices and starts both threads. */
    public synchronized void start() throws AudioDeviceException {
        if (running) {
            return;
        }
        AudioDevices.Microphone microphone = devices.openMicrophone(settings.microphone());
        AudioDevices.Speaker speaker;
        try {
            speaker = devices.openSpeaker(settings.speaker());
        } catch (AudioDeviceException failure) {
            microphone.close();
            throw failure;
        }
        running = true;
        deviceNames = microphone.name() + " / " + speaker.name();
        capture = new Thread(() -> captureLoop(microphone), "ember-voice-capture");
        playout = new Thread(() -> playoutLoop(speaker), "ember-voice-playout");
        capture.setDaemon(true);
        playout.setDaemon(true);
        capture.start();
        playout.start();
    }

    /** Stops both threads; each closes its own device on the way out. */
    public void stop() {
        running = false;
    }

    public boolean running() { return running; }

    /** A frame arrived from someone (network thread). Unknown codecs are ignored. */
    public void receive(String peer, long sequence, int codecId, byte[] frame) {
        PeerVoice voice = peers.get(peer);
        if (voice == null || voice.decoder.id() != codecId) {
            AudioCodec decoder = CodecFactory.forId(codecId);
            if (decoder == null) {
                return;
            }
            voice = new PeerVoice(decoder, clock);
            peers.put(peer, voice);
        }
        short[] pcm = voice.decoder.decode(frame);
        if (pcm.length == voice.decoder.frameSamples()) {
            voice.buffer.put(sequence, pcm);
        }
    }

    public void removePeer(String peer) {
        peers.remove(peer);
    }

    private void captureLoop(AudioDevices.Microphone first) {
        AudioDevices.Microphone microphone = first;
        try {
            VoiceCleaner cleaner = new VoiceCleaner(microphone.sampleRate());
            Resampler resampler = null;
            float[] pending = new float[0];
            short[] block = new short[microphone.sampleRate() / 50];
            while (running) {
                try {
                    microphone.read(block);
                } catch (AudioDeviceException lost) {
                    microphone.close();
                    microphone = reopenMicrophone();
                    if (microphone == null) {
                        return;
                    }
                    cleaner = new VoiceCleaner(microphone.sampleRate());
                    resampler = null; // the new device may run at another rate
                    pending = new float[0];
                    block = new short[microphone.sampleRate() / 50];
                    continue;
                }
                AudioCodec codec = sendCodec;
                if (codec == null) {
                    continue; // nothing agreed yet: keep the device warm, send nothing
                }
                if (resampler == null || resampler.inRate() != microphone.sampleRate()
                        || resampler.outRate() != codec.sampleRate()) {
                    resampler = new Resampler(microphone.sampleRate(), codec.sampleRate());
                }
                float[] cleaned = cleaner.process(toFloat(block));
                if (settings.speakerMode() && remoteLevel > 0.02) {
                    for (int index = 0; index < cleaned.length; index++) {
                        cleaned[index] *= 0.1f; // Speaker mode: duck the mic while the other person talks
                    }
                }
                pending = concat(pending, resampler.process(cleaned));
                while (pending.length >= codec.frameSamples()) {
                    short[] frame = toShort(pending, codec.frameSamples());
                    pending = java.util.Arrays.copyOfRange(pending, codec.frameSamples(), pending.length);
                    if (!muted) {
                        byte[] encoded = codec.encode(frame);
                        synchronized (this) {
                            sentBytes += encoded.length;
                        }
                        transport.send(++sequence, codec.id(), encoded);
                    }
                }
            }
        } finally {
            if (microphone != null) {
                microphone.close();
            }
        }
    }

    private AudioDevices.Microphone reopenMicrophone() {
        status.accept("Microphone disconnected: switching to the default");
        while (running) {
            try {
                AudioDevices.Microphone next = devices.openMicrophone(settings.microphone());
                status.accept("Microphone: " + next.name());
                return next;
            } catch (AudioDeviceException none) {
                sleep(500);
            }
        }
        return null;
    }

    private void playoutLoop(AudioDevices.Speaker first) {
        AudioDevices.Speaker speaker = first;
        long lastStats = clock.nowMs();
        try {
            while (running) {
                int frameSamples = speaker.sampleRate() / 50;
                float[] mix = new float[frameSamples];
                double level = 0;
                for (PeerVoice voice : peers.values()) {
                    short[] frame = voice.buffer.poll();
                    if (voice.toSpeaker == null || voice.toSpeaker.outRate() != speaker.sampleRate()) {
                        voice.toSpeaker = new Resampler(voice.decoder.sampleRate(), speaker.sampleRate());
                        voice.pending.clear();
                    }
                    for (float sample : voice.toSpeaker.process(toFloat(frame))) {
                        voice.pending.add(sample);
                    }
                    int take = Math.min(frameSamples, voice.pending.size());
                    for (int index = 0; index < take; index++) {
                        float sample = voice.pending.get(index);
                        mix[index] += sample;
                        level += sample * sample;
                    }
                    voice.pending.subList(0, take).clear();
                }
                remoteLevel = Math.sqrt(level / frameSamples);
                try {
                    speaker.write(toShort(mix, frameSamples));
                } catch (AudioDeviceException lost) {
                    speaker.close();
                    speaker = reopenSpeaker();
                    if (speaker == null) {
                        return;
                    }
                }
                if (clock.nowMs() - lastStats >= 1000) {
                    lastStats = clock.nowMs();
                    CallStats stats = stats();
                    listeners.forEach(listener -> listener.statsChanged(stats));
                }
            }
        } finally {
            if (speaker != null) {
                speaker.close();
            }
        }
    }

    private AudioDevices.Speaker reopenSpeaker() {
        status.accept("Speaker disconnected: switching to the default");
        while (running) {
            try {
                AudioDevices.Speaker next = devices.openSpeaker(settings.speaker());
                status.accept("Speaker: " + next.name());
                return next;
            } catch (AudioDeviceException none) {
                sleep(500);
            }
        }
        return null;
    }

    /** A snapshot for the overlay and log; numbers only. */
    public CallStats stats() {
        JitterBuffer.Stats buffer = peers.values().stream().findFirst().map(voice -> voice.buffer.stats())
                .orElse(new JitterBuffer.Stats(0, JitterBuffer.MIN_TARGET_MS, 0, 0, 0, 0));
        long bytes;
        synchronized (this) {
            bytes = sentBytes;
            sentBytes = 0;
        }
        AudioCodec codec = sendCodec;
        return new CallStats(settings.mode().key(), codec == null ? "-" : codec.name(), transportName,
                (int) (bytes * 8 / 1000), rttMs, buffer.depthMs(), buffer.targetMs(), buffer.jitterMs(),
                buffer.concealed(), buffer.lateDropped(), deviceNames);
    }

    private static float[] toFloat(short[] samples) {
        float[] out = new float[samples.length];
        for (int index = 0; index < samples.length; index++) {
            out[index] = samples[index] / 32768f;
        }
        return out;
    }

    private static short[] toShort(float[] samples, int length) {
        short[] out = new short[length];
        for (int index = 0; index < length && index < samples.length; index++) {
            out[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(samples[index] * 32768f)));
        }
        return out;
    }

    private static float[] concat(float[] first, float[] second) {
        float[] out = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
