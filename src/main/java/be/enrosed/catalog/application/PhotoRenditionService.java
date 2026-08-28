package be.enrosed.catalog.application;

import be.enrosed.catalog.application.PhotoUploadPolicy.InvalidPhotoException;
import be.enrosed.catalog.application.PhotoUploadPolicy.ValidatedPhoto;
import jakarta.enterprise.context.ApplicationScoped;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;

/** Builds the bandwidth-sized rendition while retaining the exact upload as the large source. */
@ApplicationScoped
public class PhotoRenditionService {

    /** Canonical gallery manifests define the browser rendition by width, preserving aspect. */
    public static final int MAX_SMALL_WIDTH = 480;
    public static final String POLICY_VERSION = "website-small-v1";

    private static final float JPEG_QUALITY = 0.85f;
    private static final long MAX_DECODED_PIXELS = 40_000_000L;

    public Rendition small(ValidatedPhoto source) {
        if (source == null || source.bytes() == null || source.bytes().length == 0) {
            throw invalid("De kleine fotoversie kon niet worden gemaakt");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(source.bytes()))) {
            if (input == null) throw invalid("De foto kon niet worden gedecodeerd");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("De foto kon niet worden gedecodeerd");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requireSafeDimensions(width, height);
                if (fits(width, height)) {
                    return original(source, width, height, ReuseReason.ALREADY_SMALL);
                }
                int frameCount = frameCount(reader);
                if (frameCount != 1) {
                    return original(source, width, height,
                            frameCount > 1 ? ReuseReason.ANIMATED
                                    : ReuseReason.FRAME_COUNT_UNKNOWN);
                }
                if ("image/jpeg".equals(source.contentType())) {
                    int orientation = jpegExifOrientation(source.bytes());
                    if (orientation != 1) {
                        return original(source, width, height, ReuseReason.JPEG_ORIENTATION);
                    }
                }

                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw invalid("De foto kon niet worden gedecodeerd");
                BufferedImage scaled = scale(decoded);
                boolean alpha = decoded.getColorModel().hasAlpha();
                String contentType = alpha ? "image/png" : "image/jpeg";
                byte[] bytes = alpha ? png(scaled) : jpeg(scaled);
                if (bytes.length == 0) throw invalid("De kleine fotoversie is leeg");
                /* A browser rendition must never make the transfer heavier. This also avoids a
                   duplicate blob for already well-compressed or tiny alpha sources. */
                if (bytes.length >= source.bytes().length) {
                    return original(source, width, height, ReuseReason.NOT_SMALLER);
                }
                return new Rendition(
                        filename(source.originalFilename(), contentType), contentType, bytes,
                        sha256(bytes), scaled.getWidth(), scaled.getHeight(), true, null);
            } finally {
                reader.dispose();
            }
        } catch (InvalidPhotoException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidPhotoException(
                    "De kleine fotoversie kon niet worden gemaakt", exception);
        }
    }

    private static boolean fits(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_SMALL_WIDTH;
    }

    private static void requireSafeDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_DECODED_PIXELS) {
            throw invalid("De foto heeft te veel pixels om veilig te verwerken");
        }
    }

    private static int frameCount(ImageReader reader) {
        try {
            return reader.getNumImages(true);
        } catch (Exception ignored) {
            /* Unknown is not equivalent to static: retaining the exact source is the only way
               to guarantee that an animation is never collapsed to frame zero. */
            return -1;
        }
    }

    private static Rendition original(
            ValidatedPhoto source, int width, int height, ReuseReason reason) {
        return new Rendition(
                source.originalFilename(), source.contentType(), source.bytes(),
                sha256(source.bytes()), width, height, false, reason);
    }

    private static BufferedImage scale(BufferedImage source) {
        double factor = Math.min(1d, (double) MAX_SMALL_WIDTH / source.getWidth());
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(
                width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            if (!alpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw invalid("PNG-encoder voor de kleine fotoversie ontbreekt");
            }
            return output.toByteArray();
        }
    }

    private static byte[] jpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw invalid("JPEG-encoder voor de kleine fotoversie ontbreekt");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(JPEG_QUALITY);
            }
            if (parameters.canWriteProgressive()) {
                parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Missing EXIF means normal orientation; malformed EXIF fails safe by retaining the source. */
    static int jpegExifOrientation(byte[] bytes) {
        if (bytes == null || bytes.length < 4
                || unsigned(bytes[0]) != 0xff || unsigned(bytes[1]) != 0xd8) return 0;
        int offset = 2;
        while (offset + 4 <= bytes.length) {
            while (offset < bytes.length && unsigned(bytes[offset]) == 0xff) offset++;
            if (offset >= bytes.length) return 1;
            int marker = unsigned(bytes[offset++]);
            if (marker == 0xd9 || marker == 0xda) return 1;
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (offset + 2 > bytes.length) return 0;
            int length = bigEndian16(bytes, offset);
            if (length < 2 || offset + length > bytes.length) return 0;
            int payload = offset + 2;
            int payloadLength = length - 2;
            if (marker == 0xe1 && payloadLength >= 6
                    && asciiEquals(bytes, payload, "Exif\0\0")) {
                return exifOrientation(bytes, payload + 6, payloadLength - 6);
            }
            offset += length;
        }
        return 1;
    }

    private static int exifOrientation(byte[] bytes, int tiff, int length) {
        if (length < 8 || tiff < 0 || tiff + length > bytes.length) return 0;
        boolean little;
        if (bytes[tiff] == 'I' && bytes[tiff + 1] == 'I') little = true;
        else if (bytes[tiff] == 'M' && bytes[tiff + 1] == 'M') little = false;
        else return 0;
        if (read16(bytes, tiff + 2, little) != 42) return 0;
        long ifdRelative = read32(bytes, tiff + 4, little);
        if (ifdRelative < 8 || ifdRelative > length - 2L) return 0;
        int ifd = tiff + (int) ifdRelative;
        int count = read16(bytes, ifd, little);
        if (count < 0 || ifd + 2L + count * 12L > tiff + length) return 0;
        for (int index = 0; index < count; index++) {
            int entry = ifd + 2 + index * 12;
            if (read16(bytes, entry, little) != 0x0112) continue;
            if (read16(bytes, entry + 2, little) != 3
                    || read32(bytes, entry + 4, little) != 1) return 0;
            int orientation = read16(bytes, entry + 8, little);
            return orientation >= 1 && orientation <= 8 ? orientation : 0;
        }
        return 1;
    }

    private static int bigEndian16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
    }

    private static int read16(byte[] bytes, int offset, boolean little) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return little
                ? unsigned(bytes[offset]) | unsigned(bytes[offset + 1]) << 8
                : unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
    }

    private static long read32(byte[] bytes, int offset, boolean little) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        if (little) {
            return (long) unsigned(bytes[offset])
                    | (long) unsigned(bytes[offset + 1]) << 8
                    | (long) unsigned(bytes[offset + 2]) << 16
                    | (long) unsigned(bytes[offset + 3]) << 24;
        }
        return (long) unsigned(bytes[offset]) << 24
                | (long) unsigned(bytes[offset + 1]) << 16
                | (long) unsigned(bytes[offset + 2]) << 8
                | unsigned(bytes[offset + 3]);
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.ISO_8859_1);
        if (offset < 0 || offset + expected.length > bytes.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) return false;
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 ontbreekt", impossible);
        }
    }

    static String filename(String original, String contentType) {
        String value = original == null || original.isBlank() ? "foto" : original;
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        String stem = dot > 0 ? value.substring(0, dot) : value;
        String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
        return stem + "-small" + extension;
    }

    private static InvalidPhotoException invalid(String message) {
        return new InvalidPhotoException(message, null);
    }

    public enum ReuseReason {
        ALREADY_SMALL,
        ANIMATED,
        FRAME_COUNT_UNKNOWN,
        JPEG_ORIENTATION,
        NOT_SMALLER
    }

    public record Rendition(
            String filename,
            String contentType,
            byte[] bytes,
            String sha256,
            int width,
            int height,
            boolean resized,
            ReuseReason reuseReason) {
        public String extension() {
            return switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                default -> "";
            };
        }
    }
}
