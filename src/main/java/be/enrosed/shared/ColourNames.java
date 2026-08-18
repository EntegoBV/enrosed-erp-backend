package be.enrosed.shared;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standard product colours, translated once for everyone.
 *
 * Colour is the one product attribute that appears on every quote and in
 * every catalogue, and it is always the same twenty words. Translating
 * "Rood" per product through the CSV file would mean typing "Rouge" forty
 * times and misspelling it twice. A product-specific translation still wins
 * when one exists — "Vintage roze" is not in this list and never will be.
 *
 * The translations live in i18n/colour-names.csv; keys are the Dutch names
 * as the colour pick-list stores them.
 */
public final class ColourNames {

    private ColourNames() {}

    /** The pick-list, in the order the screen shows it. */
    public static final List<String> STANDARD = List.of(
            "Rood", "Roze", "Fuchsia", "Bordeaux", "Wit", "Ivoor", "Champagne",
            "Geel", "Oranje", "Groen", "Blauw", "Paars", "Lila",
            "Zwart", "Grijs", "Zilver", "Goud", "Gemengd");

    private static final Map<Language, Map<String, String>> NAMES =
            TranslationCsv.load("/i18n/colour-names.csv");

    /** The colour in the given language; unknown colours come back unchanged. */
    public static String translate(String dutchName, Language language) {
        if (dutchName == null || dutchName.isBlank() || language == Language.NL) {
            return dutchName;
        }
        String name = NAMES.get(language).get(normalise(dutchName));
        return name == null ? dutchName : name;
    }

    private static String normalise(String name) {
        String trimmed = name.trim();
        return trimmed.isEmpty() ? trimmed
                : trimmed.substring(0, 1).toUpperCase(Locale.ROOT)
                    + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }
}
