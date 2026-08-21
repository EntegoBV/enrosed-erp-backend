package be.enrosed.catalog.domain;

import be.enrosed.shared.Language;

/** Customer-facing category copy in one supported language. */
public record CategoryText(
        Language language,
        String name,
        String description,
        String eyebrow,
        String mobileName,
        String navigationName,
        String footerName
) {
    /** Compatibility constructor for the initial translated-category contract. */
    public CategoryText(Language language, String name, String description, String eyebrow,
                        String mobileName) {
        this(language, name, description, eyebrow, mobileName, null, null);
    }

    /** Compatibility constructor for clients from before footer placement copy. */
    public CategoryText(Language language, String name, String description, String eyebrow,
                        String mobileName, String navigationName) {
        this(language, name, description, eyebrow, mobileName, navigationName, null);
    }

    public boolean isEmpty() {
        return blank(name) && blank(description) && blank(eyebrow) && blank(mobileName)
                && blank(navigationName) && blank(footerName);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
