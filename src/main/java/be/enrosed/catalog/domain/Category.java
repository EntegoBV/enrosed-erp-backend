package be.enrosed.catalog.domain;

import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;

import java.util.List;

/**
 * Product category from a fixed list - "Preserved", "Glas", "Acryl".
 * A free text field turned into a collection of typos too quickly.
 */
public record Category(
        Long id,
        String code,
        String name,
        String description,
        String eyebrow,
        int position,
        String mobileName,
        String navigationName,
        String footerName,
        Long featuredProductId,
        List<CategoryText> texts,
        Long revision
) {
    public Category {
        texts = texts == null ? List.of() : List.copyOf(texts);
    }

    /** Backward-compatible shape for clients that predate optimistic category revisions. */
    public Category(Long id, String code, String name, String description, String eyebrow,
                    int position, String mobileName, String navigationName, String footerName,
                    Long featuredProductId, List<CategoryText> texts) {
        this(id, code, name, description, eyebrow, position, mobileName, navigationName,
                footerName, featuredProductId, texts, null);
    }

    /** Compatibility constructor for clients from before category translations. */
    public Category(Long id, String code, String name, String description, String eyebrow,
                    int position, String mobileName, Long featuredProductId) {
        this(id, code, name, description, eyebrow, position, mobileName, null, null,
                featuredProductId, List.of(), null);
    }

    /** Compatibility constructor for the first translated-category contract. */
    public Category(Long id, String code, String name, String description, String eyebrow,
                    int position, String mobileName, Long featuredProductId,
                    List<CategoryText> texts) {
        this(id, code, name, description, eyebrow, position, mobileName, null, null,
                featuredProductId, texts, null);
    }

    /** Compatibility constructor for clients from before footer placement copy. */
    public Category(Long id, String code, String name, String description, String eyebrow,
                    int position, String mobileName, String navigationName,
                    Long featuredProductId, List<CategoryText> texts) {
        this(id, code, name, description, eyebrow, position, mobileName, navigationName,
                null, featuredProductId, texts, null);
    }

    /** Compatibility constructor for existing clients and seed data. */
    public Category(Long id, String code, String name, String description, int position) {
        this(id, code, name, description, null, position, null, null, null, null,
                List.of(), null);
    }

    /** Compatibility constructor for clients from before category-owned eyebrow copy. */
    public Category(Long id, String code, String name, String description, int position,
                    String mobileName, Long featuredProductId) {
        this(id, code, name, description, null, position, mobileName, null, null,
                featuredProductId, List.of(), null);
    }

    public LanguageFallback.Resolved<String> nameResolved(Language language) {
        return translated(language, CategoryText::name, name);
    }

    public LanguageFallback.Resolved<String> descriptionResolved(Language language) {
        return translated(language, CategoryText::description, description);
    }

    public LanguageFallback.Resolved<String> eyebrowResolved(Language language) {
        return translated(language, CategoryText::eyebrow, eyebrow);
    }

    public LanguageFallback.Resolved<String> mobileNameResolved(Language language) {
        return translated(language, CategoryText::mobileName, mobileName);
    }

    public LanguageFallback.Resolved<String> navigationNameResolved(Language language) {
        return translated(language, CategoryText::navigationName, navigationName);
    }

    public LanguageFallback.Resolved<String> footerNameResolved(Language language) {
        return translated(language, CategoryText::footerName, footerName);
    }

    public String nameIn(Language language) { return nameResolved(language).value(); }
    public String descriptionIn(Language language) { return descriptionResolved(language).value(); }
    public String eyebrowIn(Language language) { return eyebrowResolved(language).value(); }
    public String mobileNameIn(Language language) { return mobileNameResolved(language).value(); }
    public String navigationNameIn(Language language) {
        return navigationNameResolved(language).value();
    }
    public String footerNameIn(Language language) { return footerNameResolved(language).value(); }

    private LanguageFallback.Resolved<String> translated(
            Language language,
            java.util.function.Function<CategoryText, String> field,
            String base) {
        return LanguageFallback.text(texts, language, CategoryText::language, field, base);
    }
}
