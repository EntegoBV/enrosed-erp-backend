package be.enrosed.catalog.adapter.out.document;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Website-owned editorial imagery bundled with the backend; never hotlinked at render time. */
@ApplicationScoped
public class CatalogEditorialAssets {

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
            String encoded = images.encode(in.readAllBytes());
            return encoded == null ? "" : encoded;
        } catch (Exception ignored) {
            return "";
        }
    }
}
