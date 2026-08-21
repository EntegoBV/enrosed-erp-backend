package be.enrosed.shared;

import java.util.Locale;

/**
 * The languages we communicate to a customer in.
 *
 * Sending a German or French customer a Dutch quote reads as sloppiness,
 * even when the amount is right. The language therefore hangs on the
 * customer, not on our screen: we keep working in Dutch internally while
 * the document leaves in theirs.
 *
 * Deliberately a short list instead of every locale Java knows. Every added
 * language must actually be translated - a half-translated quote is worse
 * than an English one.
 */
public enum Language {

    NL("nl", "Nederlands", "nl-BE"),
    FR("fr", "Frans", "fr-BE"),
    EN("en", "Engels", "en-GB"),
    DE("de", "Duits", "de-DE"),
    ES("es", "Spaans", "es-ES"),
    PL("pl", "Pools", "pl-PL"),
    PT("pt", "Portugees", "pt-PT"),
    TR("tr", "Turks", "tr-TR");

    private final String code;
    private final String label;
    private final String localeTag;

    Language(String code, String label, String localeTag) {
        this.code = code;
        this.label = label;
        this.localeTag = localeTag;
    }

    /** Two-letter code as stored in the database and the CSV. */
    public String code() {
        return code;
    }

    /** What we call the language internally, in Dutch. */
    public String label() {
        return label;
    }

    /**
     * Locale for numbers and dates.
     *
     * Note: this only drives the formatting of amounts. Thousands with a dot
     * and decimals with a comma are the same across most of Europe; an
     * English customer gets the reverse, as they expect.
     */
    public Locale locale() {
        return Locale.forLanguageTag(localeTag);
    }

    /** Unknown or empty falls back to Dutch. */
    public static Language of(String code) {
        if (code == null || code.isBlank()) return NL;
        return find(code).orElse(NL);
    }

    /**
     * Parses a public/API language without silently turning an unsupported value into Dutch.
     * A missing value uses the supplied endpoint default for backward compatibility.
     */
    public static Language requireSupported(String code, Language defaultLanguage) {
        if (code == null || code.isBlank()) return defaultLanguage;
        return find(code).orElseThrow(() ->
                new IllegalArgumentException("Unsupported language: " + code));
    }

    private static java.util.Optional<Language> find(String code) {
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        for (Language language : values()) {
            if (language.code.equals(wanted) || language.name().equalsIgnoreCase(wanted)) {
                return java.util.Optional.of(language);
            }
        }
        return java.util.Optional.empty();
    }
}
