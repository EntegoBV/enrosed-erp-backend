package be.enrosed.catalog.application;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Folder and file names inside the photo ZIP that unpack cleanly on Windows
 * and macOS.
 *
 * Windows forbids / \ : * ? " < > |, control characters, trailing dots and
 * spaces and a handful of device names (CON, NUL, COM1 ...). Both systems
 * compare names case-insensitively, so two SKUs that only differ in case
 * must still land in separate folders.
 */
public final class PhotoExportNames {

    /** Keeps the full path well below Windows' 260-character limit. */
    public static final int MAX_FOLDER_LENGTH = 120;
    /** Appended to the first photo of every folder. */
    public static final String LEAD_SUFFIX = "-hoofdfoto";

    private static final String FORBIDDEN = "/\\:*?\"<>|";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern RESERVED = Pattern.compile(
            "(?i)(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(\\..*)?");
    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,5}");

    private PhotoExportNames() {}

    /** "SKU - Name - Colour - Size" with blank parts left out, made safe for a file system. */
    public static String folder(String sku, String name, String colour, String size) {
        StringBuilder joined = new StringBuilder();
        for (String part : new String[] {sku, name, colour, size}) {
            if (part == null || part.isBlank()) continue;
            if (!joined.isEmpty()) joined.append(" - ");
            joined.append(part.strip());
        }
        return sanitize(joined.toString());
    }

    /** One safe path segment: never empty, never longer than {@link #MAX_FOLDER_LENGTH}. */
    public static String sanitize(String raw) {
        String value = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                safe.append(' ');
            } else if (FORBIDDEN.indexOf(codePoint) >= 0) {
                safe.append('-');
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        String cleaned = trimEnds(WHITESPACE.matcher(safe).replaceAll(" "));
        cleaned = trimEnds(truncate(cleaned, MAX_FOLDER_LENGTH));
        if (cleaned.isEmpty()) cleaned = "product";
        if (RESERVED.matcher(cleaned).matches()) cleaned = "_" + cleaned;
        return cleaned;
    }

    /** "01-hoofdfoto.jpg", "02.png": numbered in folder order, padded to the folder's photo count. */
    public static String file(int position, int total, boolean lead, String extension) {
        int digits = Math.max(2, String.valueOf(Math.max(total, 1)).length());
        return String.format(Locale.ROOT, "%0" + digits + "d", position)
                + (lead ? LEAD_SUFFIX : "") + "." + extension;
    }

    /** The extension that belongs to the stored bytes; the original name only as a fallback. */
    public static String extension(String contentType, String originalFilename) {
        String type = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        int parameters = type.indexOf(';');
        if (parameters >= 0) type = type.substring(0, parameters).strip();
        String known = switch (type) {
            case "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "image/heic" -> "heic";
            case "image/heif" -> "heif";
            case "image/tiff" -> "tif";
            case "image/bmp" -> "bmp";
            default -> null;
        };
        if (known != null) return known;
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot >= 0 && dot < originalFilename.length() - 1) {
                String candidate = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (EXTENSION.matcher(candidate).matches()) return candidate;
            }
        }
        return "bin";
    }

    /** Hands out case-insensitively unique names: the second "X" becomes "X (2)". */
    public static final class Unique {
        private final Set<String> used = new HashSet<>();

        public String claim(String name) {
            String candidate = name;
            for (int counter = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); counter++) {
                String suffix = " (" + counter + ")";
                candidate = trimEnds(truncate(name, MAX_FOLDER_LENGTH - suffix.length())) + suffix;
            }
            return candidate;
        }
    }

    private static String truncate(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    /** Windows drops trailing dots and spaces; a leading dot hides a folder on macOS. */
    private static String trimEnds(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '.')) start++;
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '.')) end--;
        return value.substring(start, end);
    }
}
