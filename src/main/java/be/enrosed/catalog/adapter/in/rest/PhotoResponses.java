package be.enrosed.catalog.adapter.in.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Safe HTTP response headers shared by every product-photo endpoint. */
public final class PhotoResponses {

    private static final String NOSNIFF = "X-Content-Type-Options";

    private PhotoResponses() {}

    public static Response.ResponseBuilder inline(InputStream data, String contentType) {
        return base(data, contentType).header("Content-Disposition", "inline");
    }

    public static Response.ResponseBuilder inline(
            InputStream data, String contentType, String filename) {
        return base(data, contentType)
                .header("Content-Disposition", contentDisposition(
                        "inline", compatibleFilename(filename, contentType)));
    }

    public static Response.ResponseBuilder attachment(
            InputStream data, String contentType, String filename) {
        return base(data, contentType)
                .header("Content-Disposition", contentDisposition(
                        "attachment", compatibleFilename(filename, contentType)));
    }

    private static Response.ResponseBuilder base(InputStream data, String contentType) {
        return Response.ok(data)
                .type(safeContentType(contentType))
                .header(NOSNIFF, "nosniff");
    }

    static String safeContentType(String contentType) {
        if (contentType == null) return MediaType.APPLICATION_OCTET_STREAM;
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "image/jpeg";
            case "image/png" -> "image/png";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    static String contentDisposition(String disposition, String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint) && codePoint != '"')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .trim();
        if (name.isBlank()) name = "foto";
        StringBuilder fallback = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            boolean safe = character < 128
                    && (Character.isLetterOrDigit(character)
                    || character == '.' || character == '-' || character == '_');
            fallback.append(safe ? character : '_');
        }
        if (fallback.isEmpty()) fallback.append("foto");
        return disposition + "; filename=\"" + fallback + "\"; filename*=UTF-8''"
                + encodeRfc5987(name);
    }

    /** A generated JPEG/PNG small may differ from the exact WebP large source. */
    static String compatibleFilename(String filename, String contentType) {
        if (filename == null || filename.isBlank()) return filename;
        String extension = switch (safeContentType(contentType)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> null;
        };
        if (extension == null) return filename;
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean matches = extension.equals(".jpg")
                ? lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                : lower.endsWith(extension);
        if (matches) return filename;
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        String stem = dot > slash ? filename.substring(0, dot) : filename;
        return stem + extension;
    }

    private static String encodeRfc5987(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = valueByte & 0xff;
            boolean safe = unsigned >= 'a' && unsigned <= 'z'
                    || unsigned >= 'A' && unsigned <= 'Z'
                    || unsigned >= '0' && unsigned <= '9'
                    || unsigned == '-' || unsigned == '.' || unsigned == '_';
            if (safe) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return encoded.toString();
    }
}
