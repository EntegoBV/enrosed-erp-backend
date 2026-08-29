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

    private final PdfImageEncoder images;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CatalogEditorialAssets(PdfImageEncoder images) {
        this.images = images;
    }

    public String image(String name) {
        return cache.computeIfAbsent(name, this::load);
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

    private static boolean isFullPage(String name) {
        return name != null && (name.startsWith("catalog-cover-")
                || name.startsWith("catalog-back-cover-"));
    }
}
