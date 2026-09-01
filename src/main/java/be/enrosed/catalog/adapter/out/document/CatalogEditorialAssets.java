package be.enrosed.catalog.adapter.out.document;

import jakarta.enterprise.context.ApplicationScoped;

import java.awt.Color;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Website-owned editorial imagery bundled with the backend; never hotlinked at render time. */
@ApplicationScoped
public class CatalogEditorialAssets {

    private static final Color PRODUCT_BACKGROUND = new Color(255, 252, 248);

    private final PdfImageEncoder images;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CatalogEditorialAssets(PdfImageEncoder images) {
        this.images = images;
    }

    public String image(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    /** Curated product imagery on a stable canvas keeps every family page aligned. */
    public String contained(String name, int width, int height) {
        if (name == null || name.isBlank() || width < 1 || height < 1) return "";
        String key = name + "@" + width + "x" + height;
        return cache.computeIfAbsent(key, ignored -> loadContained(name, width, height));
    }

    private String load(String name) {
        String resource = "catalog-assets/" + name;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) return "";
            /* Editorial helpers such as the logo keep their natural resolution. Full-page
               catalogue photography is selected from the export and rendered by PhotoResolver,
               so a small bundled asset is never inflated to a fake A4 pixel canvas. */
            String encoded = images.encode(in.readAllBytes());
            return encoded == null ? "" : encoded;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String loadContained(String name, int width, int height) {
        String resource = "catalog-assets/" + name;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) return "";
            String encoded = images.encodeContained(
                    in.readAllBytes(), width, height, PRODUCT_BACKGROUND);
            return encoded == null ? "" : encoded;
        } catch (Exception ignored) {
            return "";
        }
    }
}
