package be.enrosed.shared;

import java.util.Locale;

/** Localizes common merchandising size labels while preserving technical size codes. */
public final class VariantSizes {
    private VariantSizes() {}

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
            };
            case "medium" -> switch (language) {
                case NL -> "Middelgroot"; case FR -> "Moyen"; case EN -> "Medium"; case DE -> "Mittel";
                case ES -> "Mediano"; case PL -> "Średni"; case PT -> "Médio"; case TR -> "Orta";
            };
            case "large" -> switch (language) {
                case NL -> "Groot"; case FR -> "Grand"; case EN -> "Large"; case DE -> "Groß";
                case ES -> "Grande"; case PL -> "Duży"; case PT -> "Grande"; case TR -> "Büyük";
            };
            default -> null;
        };
    }
}
