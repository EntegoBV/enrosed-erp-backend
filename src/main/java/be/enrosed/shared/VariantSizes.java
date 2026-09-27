package be.enrosed.shared;

import java.util.Locale;

/** Localizes common merchandising size labels while preserving technical size codes. */
public final class VariantSizes {
    private VariantSizes() {}

    /** A dimension such as 4.8*4.8cm, 12x25 or 33.5 × 7.5 × 5 cm. */
    private static final String DIMENSIONS =
            "(?i)[0-9]+(?:[.,][0-9]+)?(?:\\s*[x×*]\\s*[0-9]+(?:[.,][0-9]+)?){1,2}\\s*(?:mm|cm|m)?";

    /**
     * The per-language value for a base size. Measurements are language-neutral: they are
     * copied as they are, because the strict locale projection still wants an explicit row
     * per language. Size codes and Small/Medium/Large follow {@link #translate}; any other
     * label needs a real translation and yields null.
     */
    public static String localize(String raw, Language language) {
        if (raw != null && raw.strip().matches(DIMENSIONS)) {
            return raw.strip();
        }
        return translate(raw, language);
    }

    public static String translate(String raw, Language language) {
        if (raw == null || raw.isBlank() || language == null) return null;
        String value = raw.strip();
        if (value.matches("(?i)(?:XXS|XS|S|M|L|XL|XXL|XXXL|[0-9]+(?:[.,][0-9]+)?\\s*(?:MM|CM|M)?)")) {
            return value;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "small" -> switch (language) {
                case NL -> "Klein"; case FR -> "Petit"; case EN -> "Small"; case DE -> "Klein";
                case ES -> "Pequeño"; case PL -> "Mały"; case PT -> "Pequeno"; case TR -> "Küçük";
                case EL -> "Μικρό";
            };
            case "medium" -> switch (language) {
                case NL -> "Middelgroot"; case FR -> "Moyen"; case EN -> "Medium"; case DE -> "Mittel";
                case ES -> "Mediano"; case PL -> "Średni"; case PT -> "Médio"; case TR -> "Orta";
                case EL -> "Μεσαίο";
            };
            case "large" -> switch (language) {
                case NL -> "Groot"; case FR -> "Grand"; case EN -> "Large"; case DE -> "Groß";
                case ES -> "Grande"; case PL -> "Duży"; case PT -> "Grande"; case TR -> "Büyük";
                case EL -> "Μεγάλο";
            };
            default -> null;
        };
    }
}
