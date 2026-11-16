package edu.ember.common.security;

import edu.ember.common.exceptions.MediaValidationException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Checks image signatures and dimensions before decode, then writes fresh RGB JPEG data. @concept ImageIO, validation */
public final class ImageValidator {
    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    public static final int MAX_SIDE = 4096;
    public static final long MAX_PIXELS = 16_000_000L;
    public static final int THUMBNAIL_SIDE = 300;

    public ValidatedImage validate(byte[] input, String suppliedName)
            throws MediaValidationException {
        checkInput(input, suppliedName);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(input))) {
            return readAndReencode(imageInput);
        } catch (IOException | RuntimeException exception) {
            throw new MediaValidationException("Image could not be read safely");
        }
    }

    private void checkInput(byte[] bytes, String suppliedName) throws MediaValidationException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) {
            throw new MediaValidationException("Image upload exceeds the 5 MB limit");
        }
        String name = suppliedName == null ? "" : suppliedName.toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
            throw new MediaValidationException("Only PNG and JPEG images are accepted");
        }
        boolean png = startsWith(bytes, new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        boolean jpeg = startsWith(bytes, new int[] {0xFF, 0xD8, 0xFF});
        if (!png && !jpeg) {
            throw new MediaValidationException("Image signature does not match PNG or JPEG");
        }
        if (png != name.endsWith(".png")) {
            throw new MediaValidationException("Image extension does not match its signature");
        }
    }

    private ValidatedImage readAndReencode(ImageInputStream input)
            throws IOException, MediaValidationException {
        if (input == null) {
            throw new MediaValidationException("Image stream is invalid");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new MediaValidationException("No supported image reader found");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                    || (long) width * height > MAX_PIXELS) {
                throw new MediaValidationException("Image dimensions exceed the allowed limit");
            }
            BufferedImage decoded = reader.read(0);
            if (decoded == null) {
                throw new MediaValidationException("Image decoder returned no pixels");
            }
            BufferedImage rgb = toRgb(decoded, width, height);
            byte[] full = writeJpeg(rgb);
            byte[] thumbnail = writeJpeg(toThumbnail(rgb));
            return new ValidatedImage(full, thumbnail, width, height);
        } finally {
            reader.dispose();
        }
    }

    private BufferedImage toRgb(BufferedImage source, int width, int height) {
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    private BufferedImage toThumbnail(BufferedImage source) {
        double scale = Math.min(1.0, (double) THUMBNAIL_SIDE
                / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return thumbnail;
    }

    private byte[] writeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpeg", output)) {
            throw new IOException("JPEG writer is unavailable");
        }
        return output.toByteArray();
    }

    private static boolean startsWith(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xFF) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    public static final class ValidatedImage {
        private final byte[] fullJpeg;
        private final byte[] thumbnailJpeg;
        private final int width;
        private final int height;

        private ValidatedImage(byte[] fullJpeg, byte[] thumbnailJpeg, int width, int height) {
            this.fullJpeg = fullJpeg.clone();
            this.thumbnailJpeg = thumbnailJpeg.clone();
            this.width = width;
            this.height = height;
        }

        public byte[] fullJpeg() { return fullJpeg.clone(); }
        public byte[] thumbnailJpeg() { return thumbnailJpeg.clone(); }
        public int width() { return width; }
        public int height() { return height; }
    }
}
