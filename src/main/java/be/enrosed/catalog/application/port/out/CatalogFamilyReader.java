package be.enrosed.catalog.application.port.out;

import be.enrosed.shared.Language;

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
            return translated(language, Text::name, name);
        }

        public String summaryIn(Language language) {
            return translated(language, Text::summary, summary);
        }

        public String descriptionIn(Language language) {
            return translated(language, Text::description, description);
        }

        public String formatIn(Language language) {
            return translated(language, Text::format, format);
        }

        public List<String> highlightsIn(Language language) {
            for (Language candidate : fallbackLanguages(language)) {
                Text text = textIn(candidate);
                if (text != null && !text.highlights().isEmpty()) return text.highlights();
            }
            return highlights;
        }

        private String translated(Language language,
                                  java.util.function.Function<Text, String> field,
                                  String base) {
            for (Language candidate : fallbackLanguages(language)) {
                Text text = textIn(candidate);
                if (text != null && usable(field.apply(text), null) != null) {
                    return field.apply(text);
                }
            }
            return base;
        }

        private static List<Language> fallbackLanguages(Language requested) {
            java.util.LinkedHashSet<Language> languages = new java.util.LinkedHashSet<>();
            if (requested != null) languages.add(requested);
            languages.add(Language.EN);
            languages.add(Language.NL);
            return List.copyOf(languages);
        }

        private Text textIn(Language language) {
            return texts.stream().filter(item -> item.language() == language).findFirst().orElse(null);
        }

        private static String usable(String preferred, String fallback) {
            return preferred == null || preferred.isBlank() ? fallback : preferred;
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
