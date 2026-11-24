package edu.ember.client.call;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Per-device call settings, saved as a {@code .properties} file next to the app's other local
 * data: quality mode, chosen microphone and speaker, Speaker mode, stats overlay, and the calls v2
 * switch. Thread-safe: the settings screen writes while a call reads.
 * @concept file handling (Properties, try-with-resources), encapsulation, synchronization
 */
public final class CallSettings {
    private final Path file;
    private final Properties values = new Properties();

    private CallSettings(Path file) {
        this.file = file;
    }

    /** Loads the settings file, or starts with defaults when it does not exist or cannot be read. */
    public static CallSettings load(Path file) {
        CallSettings settings = new CallSettings(file);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                settings.values.load(reader);
            } catch (IOException | IllegalArgumentException unreadable) {
                settings.values.clear(); // a damaged file falls back to defaults rather than blocking calls
            }
        }
        return settings;
    }

    public synchronized QualityMode mode() {
        return QualityMode.fromKey(values.getProperty("mode", "auto"));
    }

    public synchronized void setMode(QualityMode mode) {
        values.setProperty("mode", mode.key());
        save();
    }

    /** Microphone name ({@link javax.sound.sampled.Mixer.Info#getName()}), empty for the system default. */
    public synchronized String microphone() {
        return values.getProperty("microphone", "");
    }

    public synchronized void setMicrophone(String name) {
        values.setProperty("microphone", name == null ? "" : name);
        save();
    }

    public synchronized String speaker() {
        return values.getProperty("speaker", "");
    }

    public synchronized void setSpeaker(String name) {
        values.setProperty("speaker", name == null ? "" : name);
        save();
    }

    /** Echo ducking for calls without headphones; off by default (it can clip people who talk over each other). */
    public synchronized boolean speakerMode() {
        return Boolean.parseBoolean(values.getProperty("speakerMode", "false"));
    }

    public synchronized void setSpeakerMode(boolean on) {
        values.setProperty("speakerMode", Boolean.toString(on));
        save();
    }

    public synchronized boolean statsOverlay() {
        return Boolean.parseBoolean(values.getProperty("statsOverlay", "false"));
    }

    public synchronized void setStatsOverlay(boolean on) {
        values.setProperty("statsOverlay", Boolean.toString(on));
        save();
    }

    /** This device's half of the calls v2 flag (the server must enable it too). Default on. */
    public synchronized boolean callsV2() {
        return Boolean.parseBoolean(values.getProperty("callsV2", "true"));
    }

    public synchronized void setCallsV2(boolean on) {
        values.setProperty("callsV2", Boolean.toString(on));
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(writer, "Ember call settings (this device only)");
            }
        } catch (IOException unwritable) {
            // Settings still apply for this session; only persistence failed.
        }
    }
}
