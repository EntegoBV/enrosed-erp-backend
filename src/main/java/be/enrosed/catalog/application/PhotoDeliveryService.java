package be.enrosed.catalog.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded, read-only delivery cache. Original objects and catalogue sources never change. */
@ApplicationScoped
public class PhotoDeliveryService {
    private static final long CACHE_BYTES = 32L * 1024 * 1024;
    private final PhotoRenditionService renditions;
    private final Map<String, PhotoRenditionService.Rendition> cache = new LinkedHashMap<>(32, .75f, true);
    private long cachedBytes;

    public PhotoDeliveryService(PhotoRenditionService renditions) { this.renditions = renditions; }

    public record Source(String key, String filename, String contentType, long sizeBytes,
                         Integer widthPx, Integer heightPx, Supplier<InputStream> open) {}
    public record Image(String url, Integer widthPx, Integer heightPx, long sizeBytes, String contentType) {}
    public record Images(Image original, Image small, Image medium, Image custom) {}

    public Images metadata(Source source, String profileBase, String originalUrl,
                           Integer width, Integer quality) {
        return metadata(source, profileBase, originalUrl, width, quality, null);
    }

    public Images metadata(Source source, String profileBase, String originalUrl,
                           Integer width, Integer quality, Image storedSmall) {
        if (width != null || quality != null) { width(width); quality(quality); }
        Image original = new Image(originalUrl, source.widthPx(), source.heightPx(),
                source.sizeBytes(), source.contentType());
        Image small = storedSmall != null ? storedSmall
                : image(profileBase + "/small", render(source, "small", null, null));
        Image medium = image(profileBase + "/medium", render(source, "medium", null, null));
        Image custom = width == null && quality == null ? null
                : image(profileBase + "/custom?width=" + width(width) + "&quality=" + quality(quality),
                        render(source, "custom", width, quality));
        return new Images(original, small, medium, custom);
    }

    private static Image image(String url, PhotoRenditionService.Rendition rendition) {
        return new Image(url, rendition.width(), rendition.height(), rendition.bytes().length,
                rendition.contentType());
    }

    /** Serialize first-time decodes so a mobile grid cannot allocate many full-resolution rasters at once. */
    public synchronized PhotoRenditionService.Rendition render(
            Source source, String profile, Integer requestedWidth, Integer requestedQuality) {
        int width = switch (profile) {
            case "small" -> 480;
            case "medium" -> 1280;
            case "custom" -> width(requestedWidth);
            default -> throw new BadRequestException("Onbekende fotoversie");
        };
        int quality = "custom".equals(profile) ? quality(requestedQuality) : 85;
        String key = source.key() + ':' + source.sizeBytes() + ':' + profile + ':' + width + ':' + quality;
        var existing = cache.get(key);
        if (existing != null) return existing;
        PhotoRenditionService.Rendition rendered;
        try (InputStream input = source.open().get()) {
            var upload = PhotoUploadPolicy.validate(source.filename(), input);
            rendered = switch (profile) {
                case "small" -> renditions.small(upload);
                case "medium" -> renditions.medium(upload);
                default -> renditions.custom(upload, width, quality);
            };
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Fotoversie kon niet worden gelezen", failure);
        }
        if (rendered.bytes().length <= CACHE_BYTES) {
            while (!cache.isEmpty() && (cache.size() >= 96
                    || cachedBytes + rendered.bytes().length > CACHE_BYTES)) {
                var oldest = cache.entrySet().iterator();
                cachedBytes -= oldest.next().getValue().bytes().length;
                oldest.remove();
            }
            cache.put(key, rendered);
            cachedBytes += rendered.bytes().length;
        }
        return rendered;
    }

    private static int width(Integer width) {
        int value = width == null ? 1280 : width;
        if (value < 160 || value > 2400) throw new BadRequestException("Breedte moet 160–2400 pixels zijn");
        return value;
    }

    private static int quality(Integer quality) {
        int value = quality == null ? 80 : quality;
        if (value < 40 || value > 95) throw new BadRequestException("JPEG-kwaliteit moet 40–95 zijn");
        return value;
    }
}
