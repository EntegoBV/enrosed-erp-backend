package be.enrosed.media;

import be.enrosed.shared.BusinessRuleException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Validates manager uploads without trusting browser supplied MIME types. */
public final class MediaUploadPolicy {
    public static final int MAX_BYTES = 25 * 1024 * 1024;

    private static final Map<String, String> OFFICE_TYPES = Map.of(
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private MediaUploadPolicy() {}

    public static ValidatedFile validate(String filename, String browserContentType, InputStream input) {
        if (input == null) throw new BusinessRuleException("Geen bestand meegestuurd");
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0) throw new BusinessRuleException("Het bestand is leeg");
            if (bytes.length > MAX_BYTES) {
                throw new BusinessRuleException("Een bestand mag maximaal 25 MB groot zijn");
            }
            String safeName = safeFilename(filename);
            String type = detect(bytes, safeName, browserContentType);
            if (type == null) {
                throw new BusinessRuleException(
                        "Dit bestandstype is niet toegestaan; gebruik een foto, PDF, Office-, CSV- of tekstbestand");
            }
            return new ValidatedFile(safeName, type,
                    type.startsWith("image/") ? MediaKind.IMAGE : MediaKind.DOCUMENT, bytes);
        } catch (IOException exception) {
            throw new BusinessRuleException("Het bestand kon niet worden ingelezen");
        }
    }

    static String detect(byte[] bytes, String filename, String browserContentType) {
        if (starts(bytes, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a")) return "image/gif";
        if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return "image/webp";
        if (ascii(bytes, 0, "%PDF-")) return "application/pdf";

        String extension = extension(filename);
        if ((starts(bytes, 0x50, 0x4b, 0x03, 0x04)
                || starts(bytes, 0x50, 0x4b, 0x05, 0x06)
                || starts(bytes, 0x50, 0x4b, 0x07, 0x08))
                && OFFICE_TYPES.containsKey(extension)) {
            return OFFICE_TYPES.get(extension);
        }
        if (("csv".equals(extension) || "txt".equals(extension)) && textLike(bytes)) {
            return "csv".equals(extension) ? "text/csv" : "text/plain";
        }

        /* HTML, SVG and XML remain rejected even if a browser labels them as text or image. */
        String hint = browserContentType == null ? "" : browserContentType.toLowerCase(Locale.ROOT);
        if (hint.contains("html") || hint.contains("svg") || hint.contains("xml")) return null;
        return null;
    }

    public static boolean safeInline(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf" -> true;
            default -> false;
        };
    }

    /** Returns a safe raster MIME detected from bytes, never from untrusted legacy metadata. */
    static String sniffSafeRaster(byte[] prefix) {
        String detected = detect(prefix, "voorbeeld.bin", null);
        return switch (detected == null ? "" : detected) {
            case "image/jpeg", "image/png", "image/gif", "image/webp" -> detected;
            default -> null;
        };
    }

    private static boolean textLike(byte[] bytes) {
        int checked = Math.min(bytes.length, 8_192);
        for (int i = 0; i < checked; i++) {
            int value = bytes[i] & 0xff;
            if (value == 0) return false;
            if (value < 0x09 || (value > 0x0d && value < 0x20)) return false;
        }
        return true;
    }

    static String safeFilename(String value) {
        String filename = value == null ? "" : value.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        filename = filename.codePoints()
                .filter(point -> !Character.isISOControl(point) && point != '"')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().strip();
        if (filename.isBlank() || ".".equals(filename) || "..".equals(filename)) filename = "bestand";
        if (filename.codePointCount(0, filename.length()) > 180) {
            filename = filename.substring(0, filename.offsetByCodePoints(0, 180));
        }
        return filename;
    }

    static String contentDispositionFilename(String value) {
        return safeFilename(value).replace("\\", "'").replace("\r", "").replace("\n", "");
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String deterministicExtension(String filename, String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "text/csv" -> ".csv";
            case "text/plain" -> ".txt";
            default -> ".bin";
        };
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes == null || bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes == null || offset < 0 || bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) return false;
        }
        return true;
    }

    public record ValidatedFile(
            String originalFilename, String contentType, MediaKind kind, byte[] bytes) {}
}
