package edu.ember.common.security;

import edu.ember.common.exceptions.MediaValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Checks an uploaded video before it is stored. The JDK cannot decode video, so the server cannot
 * re-encode it the way it re-encodes images; instead it accepts only the two containers every
 * browser plays (MP4 and WebM), recognised by their first bytes, not by the file name, under a size cap.
 * The browser that plays the file decodes it in its own sandbox.
 * @concept static utility class, byte-level validation, allowlists
 */
public final class VideoValidator {
    /** About a minute of phone video at a sensible bitrate; base64 of this still fits in one 25 MB frame. */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    private VideoValidator() {
    }

    /** Returns the file extension to store ("mp4" or "webm"); throws when the bytes are not an allowed video. */
    public static String validate(byte[] bytes, String suppliedName) throws MediaValidationException {
        if (bytes == null || bytes.length < 64) {
            throw new MediaValidationException("Video is empty or damaged");
        }
        if (bytes.length > MAX_BYTES) {
            throw new MediaValidationException("Video is larger than 16 MB");
        }
        String name = suppliedName == null ? "" : suppliedName.toLowerCase(Locale.ROOT);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (isMp4(bytes) && (extension.equals("mp4") || extension.equals("m4v") || extension.equals("mov"))) {
            return "mp4";
        }
        if (isWebm(bytes) && extension.equals("webm")) {
            return "webm";
        }
        throw new MediaValidationException("Only MP4 and WebM videos are allowed");
    }

    /** ISO base media file: a box of type "ftyp" first, with a printable brand. */
    static boolean isMp4(byte[] bytes) {
        int boxSize = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        if (boxSize < 8 || boxSize > 4096 || !"ftyp".equals(new String(bytes, 4, 4, StandardCharsets.US_ASCII))) {
            return false;
        }
        for (int index = 8; index < 12; index++) {
            if (bytes[index] < 0x20 || bytes[index] > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** Matroska/EBML header whose document type is "webm". */
    static boolean isWebm(byte[] bytes) {
        if ((bytes[0] & 0xFF) != 0x1A || (bytes[1] & 0xFF) != 0x45 || (bytes[2] & 0xFF) != 0xDF || (bytes[3] & 0xFF) != 0xA3) {
            return false;
        }
        return new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.ISO_8859_1).contains("webm");
    }
}
