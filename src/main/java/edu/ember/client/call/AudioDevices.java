package edu.ember.client.call;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * Microphones and speakers. The engine only sees the small {@link Microphone} and {@link Speaker}
 * interfaces, so tests can pass fakes (for example a microphone that "unplugs" after a few reads).
 * The real ones open a Java Sound line at the best rate the device offers (48 kHz first), and fall
 * back to the system default device when the chosen one is missing.
 * @concept interfaces, static nested classes, Adapter pattern (wraps Java Sound lines), exceptions
 */
public final class AudioDevices {
    /** Capture/playback rates to try, best first. */
    private static final float[] RATES = {48_000f, 44_100f, 32_000f, 16_000f};

    private AudioDevices() {
    }

    /** A source of 16-bit mono samples. */
    public interface Microphone extends AutoCloseable {
        int sampleRate();

        /** Fills the array with the next samples; throws when the device has gone away. */
        void read(short[] samples) throws AudioDeviceException;

        String name();

        @Override void close();
    }

    /** A sink for 16-bit mono samples. */
    public interface Speaker extends AutoCloseable {
        int sampleRate();

        /** Plays the samples; blocks while the device's small buffer is full (this paces playback). */
        void write(short[] samples) throws AudioDeviceException;

        String name();

        @Override void close();
    }

    /** Opens microphones and speakers; tests replace it. */
    public interface Provider {
        Microphone openMicrophone(String preferredName) throws AudioDeviceException;

        Speaker openSpeaker(String preferredName) throws AudioDeviceException;
    }

    /** Names of the microphones (input = true) or speakers on this computer. */
    public static List<String> names(boolean input) {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(new DataLine.Info(input ? TargetDataLine.class : SourceDataLine.class, null))) {
                names.add(info.getName());
            }
        }
        return names;
    }

    /** The real Java Sound provider. */
    public static final Provider JAVA_SOUND = new Provider() {
        @Override
        public Microphone openMicrophone(String preferredName) throws AudioDeviceException {
            LineUnavailableException last = null;
            for (Mixer.Info info : candidates(preferredName)) {
                for (float rate : RATES) {
                    AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
                    try {
                        TargetDataLine line = info == null ? AudioSystem.getTargetDataLine(format)
                                : AudioSystem.getTargetDataLine(format, info);
                        line.open(format, (int) (rate / 50) * 2 * 4);
                        line.start();
                        return new LineMicrophone(line, (int) rate, info == null ? "default" : info.getName());
                    } catch (LineUnavailableException exception) {
                        last = exception;
                    } catch (IllegalArgumentException | SecurityException unsupported) {
                        // try the next rate or device
                    }
                }
            }
            throw new AudioDeviceException("No microphone could be opened", last);
        }

        @Override
        public Speaker openSpeaker(String preferredName) throws AudioDeviceException {
            LineUnavailableException last = null;
            for (Mixer.Info info : candidates(preferredName)) {
                for (float rate : RATES) {
                    AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
                    try {
                        SourceDataLine line = info == null ? AudioSystem.getSourceDataLine(format)
                                : AudioSystem.getSourceDataLine(format, info);
                        line.open(format, (int) (rate / 50) * 2 * 3); // 60 ms: low delay, still smooth
                        line.start();
                        return new LineSpeaker(line, (int) rate, info == null ? "default" : info.getName());
                    } catch (LineUnavailableException exception) {
                        last = exception;
                    } catch (IllegalArgumentException | SecurityException unsupported) {
                        // try the next rate or device
                    }
                }
            }
            throw new AudioDeviceException("No speaker could be opened", last);
        }
    };

    /** The preferred device (when it exists) followed by the system default (null). */
    private static List<Mixer.Info> candidates(String preferredName) {
        List<Mixer.Info> list = new ArrayList<>();
        if (preferredName != null && !preferredName.isEmpty()) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (info.getName().equals(preferredName)) {
                    list.add(info);
                }
            }
        }
        list.add(null);
        return list;
    }

    private static final class LineMicrophone implements Microphone {
        private final TargetDataLine line;
        private final int rate;
        private final String name;
        private byte[] buffer = new byte[0];
        private int silentReads;

        LineMicrophone(TargetDataLine line, int rate, String name) {
            this.line = line;
            this.rate = rate;
            this.name = name;
        }

        @Override public int sampleRate() { return rate; }
        @Override public String name() { return name; }

        @Override
        public void read(short[] samples) throws AudioDeviceException {
            if (buffer.length != samples.length * 2) {
                buffer = new byte[samples.length * 2];
            }
            byte[] bytes = buffer;
            int got;
            try {
                got = line.read(bytes, 0, bytes.length);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw new AudioDeviceException("Microphone stopped", failure);
            }
            // An unplugged device often returns nothing instead of throwing: give up after ~100 ms.
            silentReads = got <= 0 ? silentReads + 1 : 0;
            if (silentReads > 5 || !line.isOpen()) {
                throw new AudioDeviceException("Microphone disconnected");
            }
            for (int index = 0; index < samples.length; index++) {
                samples[index] = index * 2 + 1 < got ? (short) ((bytes[2 * index] & 0xFF) | (bytes[2 * index + 1] << 8)) : 0;
            }
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }

    private static final class LineSpeaker implements Speaker {
        private final SourceDataLine line;
        private final int rate;
        private final String name;

        LineSpeaker(SourceDataLine line, int rate, String name) {
            this.line = line;
            this.rate = rate;
            this.name = name;
        }

        @Override public int sampleRate() { return rate; }
        @Override public String name() { return name; }

        @Override
        public void write(short[] samples) throws AudioDeviceException {
            byte[] bytes = new byte[samples.length * 2];
            for (int index = 0; index < samples.length; index++) {
                bytes[2 * index] = (byte) samples[index];
                bytes[2 * index + 1] = (byte) (samples[index] >> 8);
            }
            if (!line.isOpen()) {
                throw new AudioDeviceException("Speaker disconnected");
            }
            try {
                line.write(bytes, 0, bytes.length);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw new AudioDeviceException("Speaker stopped", failure);
            }
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }
}
