package be.enrosed.shared;

import java.util.Map;

/**
 * Fixed sentences and labels of the product-photo export's read-me file, per
 * language.
 *
 * The texts live in i18n/photo-export-text.csv - one row per key, one column
 * per language - exactly like {@link DocumentText}, so they are reviewed in
 * Excel and never in code. The parity test guards that no language misses a
 * key. Product names, colours and sizes are deliberately absent: they come
 * from the product translations.
 */
public final class PhotoExportText {

    private PhotoExportText() {}

    private static final Map<Language, Map<String, String>> TEXT =
            TranslationCsv.load("/i18n/photo-export-text.csv");

    /** Every text of this language, keyed as in the CSV. */
    public static Map<String, String> of(Language language) {
        return TEXT.get(language == null ? Language.NL : language);
    }

    /** One text; a missing key is a programming error the parity test catches first. */
    public static String text(Language language, String key) {
        String value = of(language).get(key);
        if (value == null) {
            throw new IllegalStateException("photo-export-text.csv mist sleutel " + key);
        }
        return value;
    }
}
