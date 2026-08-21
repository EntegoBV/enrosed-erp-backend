package be.enrosed.catalog.application.port.out;

import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Read-only, customer-safe family content used by document exports. */
public interface CatalogFamilyReader {

    List<Family> findByIds(Set<Long> ids);

    record Family(
            Long id,
            String familyKey,
            String publicHandle,
            Long categoryId,
            String categoryKey,
            String categoryName,
            int categoryPosition,
            int productPosition,
            String name,
            String summary,
            String description,
            String format,
            List<String> highlights,
            Dimensions dimensions,
            List<Text> texts,
            List<PackageInfo> packages,
            List<GalleryPhoto> photos) {

        public Family {
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
            texts = texts == null ? List.of() : List.copyOf(texts);
            packages = packages == null ? List.of() : List.copyOf(packages);
            photos = photos == null ? List.of() : List.copyOf(photos);
        }

        public String nameIn(Language language) {
            return nameResolved(language).value();
        }

        public LanguageFallback.Resolved<String> nameResolved(Language language) {
            return translated(language, Text::name, name);
        }

        public String summaryIn(Language language) {
            return summaryResolved(language).value();
        }

        public LanguageFallback.Resolved<String> summaryResolved(Language language) {
            return translated(language, Text::summary, summary);
        }

        public String descriptionIn(Language language) {
            return descriptionResolved(language).value();
        }

        public LanguageFallback.Resolved<String> descriptionResolved(Language language) {
            return translated(language, Text::description, description);
        }

        public String formatIn(Language language) {
            return formatResolved(language).value();
        }

        public LanguageFallback.Resolved<String> formatResolved(Language language) {
            return translated(language, Text::format, format);
        }

        public List<String> highlightsIn(Language language) {
            return highlightsResolved(language).value();
        }

        public LanguageFallback.Resolved<List<String>> highlightsResolved(Language language) {
            return LanguageFallback.resolve(texts, language, Text::language, Text::highlights,
                    candidate -> candidate != null && !candidate.isEmpty(), highlights);
        }

        private LanguageFallback.Resolved<String> translated(
                Language language, java.util.function.Function<Text, String> field, String base) {
            return LanguageFallback.text(texts, language, Text::language, field, base);
        }

    }

    record Text(Language language, String name, String summary, String description,
                String format, List<String> highlights) {
        public Text {
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
        }
    }

    /** Legacy axis storage remains B x D x H at the presentation boundary. */
    record Dimensions(BigDecimal width, BigDecimal depth, BigDecimal height, String unit) {}

    record PackageInfo(Long productId, String packageType, int position,
                       BigDecimal width, BigDecimal depth, BigDecimal height,
                       String dimensionUnit, Integer piecesPerPackage,
                       BigDecimal weight, String weightUnit, Boolean operational) {}

    record GalleryPhoto(Long id, String storageKey, String contentType, int position,
                        Long variantProductId) {}
}
