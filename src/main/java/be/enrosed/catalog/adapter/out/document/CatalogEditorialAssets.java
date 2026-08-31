package be.enrosed.catalog.adapter.out.document;

import jakarta.enterprise.context.ApplicationScoped;

import java.awt.Color;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Website-owned editorial imagery bundled with the backend; never hotlinked at render time. */
@ApplicationScoped
public class CatalogEditorialAssets {

    private static final int A4_PRINT_WIDTH = 2_480;
    private static final int A4_PRINT_HEIGHT = 3_508;
    private static final Color PAGE_BACKGROUND = new Color(18, 12, 10);
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
            byte[] source = in.readAllBytes();
            String encoded = isFullPage(name)
                    ? images.encodeContained(source, A4_PRINT_WIDTH, A4_PRINT_HEIGHT,
                            PAGE_BACKGROUND)
                    : images.encode(source);
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

    private static boolean isFullPage(String name) {
        return name != null && (name.startsWith("catalog-cover-")
                || name.startsWith("catalog-back-cover-"));
    }
}
