package edu.ember.client.call;

/**
 * How good a call may be in one quality mode. The abstract class holds what every mode shares
 * (the hard limits); each subclass fills in its own ceilings. The website mirrors these numbers in
 * {@code quality.js}; {@code docs/ladder.md} is the single table both follow.
 * @concept abstract classes, inheritance, overriding, Strategy pattern, static nested classes
 */
public abstract class QualityPolicy {
    /** Video ceiling: resolution height, frames per second. */
    public record VideoCeiling(int height, int fps) {
        public VideoCeiling {
            if (height < 180 || fps < 1) {
                throw new IllegalArgumentException("ceiling below the lowest rung");
            }
        }
    }

    /** Desktop voice sample rate (ADPCM 16 or 32 kHz). */
    public abstract int desktopVoiceRate();

    /** Website Opus bitrate in bits per second. */
    public abstract int opusBitrate();

    /** Video ceiling for a direct (peer-to-peer) or relayed call. */
    public abstract VideoCeiling videoCeiling(boolean relay);

    /** True when video should also follow the other side's display size. */
    public boolean followsDisplaySize() {
        return true;
    }

    /** Voice codec preference for a desktop sender, best first. Data saver drops 32 kHz. */
    public int[] desktopCodecPreference() {
        return desktopVoiceRate() == 32_000
                ? new int[] {AdpcmCodec.ID_32K, AdpcmCodec.ID_16K, MulawCodec.ID}
                : new int[] {AdpcmCodec.ID_16K, MulawCodec.ID};
    }

    /** 360p at 15 fps, Opus 24 kbit/s, desktop voice 16 kHz. */
    public static final class DataSaver extends QualityPolicy {
        @Override public int desktopVoiceRate() { return 16_000; }
        @Override public int opusBitrate() { return 24_000; }
        @Override public VideoCeiling videoCeiling(boolean relay) { return new VideoCeiling(360, 15); }
    }

    /** 720p/30 direct, 540p relayed, limited by the other side's display; Opus 32 kbit/s; desktop 32 kHz. */
    public static final class Auto extends QualityPolicy {
        @Override public int desktopVoiceRate() { return 32_000; }
        @Override public int opusBitrate() { return 32_000; }
        @Override public VideoCeiling videoCeiling(boolean relay) {
            return relay ? new VideoCeiling(540, 30) : new VideoCeiling(720, 30);
        }
    }

    /** Up to the camera: 1080p/30 direct, 720p relayed; Opus 40 kbit/s; desktop 32 kHz. */
    public static final class Max extends QualityPolicy {
        @Override public int desktopVoiceRate() { return 32_000; }
        @Override public int opusBitrate() { return 40_000; }
        @Override public VideoCeiling videoCeiling(boolean relay) {
            return relay ? new VideoCeiling(720, 30) : new VideoCeiling(1080, 30);
        }
        @Override public boolean followsDisplaySize() { return false; }
    }
}
