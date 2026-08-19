package be.enrosed.catalog.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Validates product photos before any bytes reach persistent storage.
 *
 * Browser supplied MIME types and file extensions are only hints. The format
 * is therefore detected from the file signature and stored under a canonical
 * image MIME type. The bytes themselves are not decoded or recompressed.
 */
public final class PhotoUploadPolicy {

    public static final int MAX_BYTES = 25 * 1024 * 1024;

    private static final int SIGNATURE_BYTES = 12;
    private static final int BUFFER_BYTES = 16 * 1024;

    private PhotoUploadPolicy() {}

    public static ValidatedPhoto validate(String originalFilename, InputStream data) {
        if (data == null) throw invalid("Geen fotobestand meegestuurd");

        try {
            byte[] signature = data.readNBytes(SIGNATURE_BYTES);
            if (signature.length == 0) throw invalid("De foto is leeg");

            String contentType = detectContentType(signature);
            if (contentType == null) {
                throw invalid("Alleen JPEG-, PNG-, GIF- en WebP-foto's zijn toegestaan");
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            bytes.write(signature);
            int size = signature.length;
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = data.read(buffer)) != -1) {
                if (read == 0) continue;
                if (size > MAX_BYTES - read) {
                    throw invalid("Een foto mag maximaal 25 MB groot zijn");
                }
                bytes.write(buffer, 0, read);
                size += read;
            }

            return new ValidatedPhoto(
                    safeFilename(originalFilename, contentType), contentType, bytes.toByteArray());
        } catch (IOException exception) {
            throw invalid("De foto kon niet worden ingelezen", exception);
        }
    }

    private static String detectContentType(byte[] bytes) {
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return "image/png";
        }
        if (startsWithAscii(bytes, "GIF87a") || startsWithAscii(bytes, "GIF89a")) {
            return "image/gif";
        }
        if (startsWithAscii(bytes, "RIFF") && startsWithAscii(bytes, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] bytes, String signature) {
        return startsWithAscii(bytes, 0, signature);
    }

    private static boolean startsWithAscii(byte[] bytes, int offset, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private static String safeFilename(String originalFilename, String contentType) {
        String filename = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        filename = filename.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint) && codePoint != '"')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .trim();

        String extension = extensionFor(contentType);
        if (filename.isBlank()) filename = "foto" + extension;
        if (!hasMatchingExtension(filename, contentType)) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0) filename = filename.substring(0, dot);
            filename = filename + extension;
        }

        int maximumCodePoints = 180;
        int count = filename.codePointCount(0, filename.length());
        if (count > maximumCodePoints) {
            int keep = Math.max(1, maximumCodePoints - extension.codePointCount(0, extension.length()));
            int end = filename.offsetByCodePoints(0, keep);
            filename = filename.substring(0, end) + extension;
        }
        return filename;
    }

    private static boolean hasMatchingExtension(String filename, String contentType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return switch (contentType) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            case "image/gif" -> lower.endsWith(".gif");
            case "image/webp" -> lower.endsWith(".webp");
            default -> false;
        };
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    private static InvalidPhotoException invalid(String message) {
        return new InvalidPhotoException(message, null);
    }

    private static InvalidPhotoException invalid(String message, Throwable cause) {
        return new InvalidPhotoException(message, cause);
    }

    public record ValidatedPhoto(String originalFilename, String contentType, byte[] bytes) {}

    public static final class InvalidPhotoException extends RuntimeException {
        public InvalidPhotoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
