package be.enrosed.shared;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one piece of a product is called on customer documents: "stuk"
 * unless the product says it is a bowl, a dome, a box, ...
 *
 * A short fixed list instead of free text per product, for the same reason
 * as {@link ColourNames}: eight bowl SKUs should not each need the word
 * "bowl" typed in nine languages and four grammatical forms. The list also
 * keeps every language complete by construction - the parity test proves
 * it - so strict website builds never wait for a unit translation.
 *
 * The translations live in i18n/unit-names.csv, one row per
 * {@code <unit>.<form>}. The forms are the plural categories we need
 * ({@code one}, {@code few}, {@code many}, {@code other}; few and many only
 * differ in Polish), a compact {@code short} form for tables and the whole
 * {@code per} phrase, because several languages change the noun after "per"
 * ("za sztukę", "adet başına"). The "stuk" rows reproduce the wording the
 * documents used before units existed, so default products read exactly as
 * they always did.
 */
public final class UnitNames {

    private UnitNames() {}

    /** The unit of every product that never chose one. */
    public static final String DEFAULT = "stuk";

    private static final List<String> FORMS = List.of("one", "few", "many", "other", "short", "per");

    private static final Map<Language, Map<String, String>> NAMES =
            TranslationCsv.load("/i18n/unit-names.csv");

    /** The pick-list, in the order the file lists the units; "stuk" first. */
    public static final List<String> KEYS = keys();

    /** One unit in one language, ready for a DTO or a template. */
    public record Localized(String key, String one, String few, String many, String other,
                            String shortForm, String per) {}

    /** The stored key as a known unit; blank or unknown input means "stuk", never a raw key on a quote. */
    public static String normalize(String key) {
        String wanted = clean(key);
        return wanted != null && KEYS.contains(wanted) ? wanted : DEFAULT;
    }

    public static boolean isKnown(String key) {
        String wanted = clean(key);
        return wanted != null && KEYS.contains(wanted);
    }

    public static String one(String key, Language language) {
        return form(key, "one", language);
    }

    public static String few(String key, Language language) {
        return form(key, "few", language);
    }

    public static String many(String key, Language language) {
        return form(key, "many", language);
    }

    public static String other(String key, Language language) {
        return form(key, "other", language);
    }

    /** "st.", "pcs"; units without an abbreviation of their own repeat their plural. */
    public static String shortForm(String key, Language language) {
        return form(key, "short", language);
    }

    /** The complete phrase: "per bowl", "za miseczkę", "kase başına". */
    public static String per(String key, Language language) {
        return form(key, "per", language);
    }

    /** The noun that belongs after this number: "1 bowl", "16 bowls", "2 miseczki", "5 miseczek". */
    public static String noun(String key, long count, Language language) {
        return form(key, pluralForm(count, language), language);
    }

    /** Number and noun, the number formatted the way the language writes it: "1.200 bowls". */
    public static String count(String key, long count, Language language) {
        return number(count, language) + " " + noun(key, count, language);
    }

    /**
     * The compact variant for tables: "16 st.". A unit whose short form is
     * just its plural gets the grammatical {@link #count} instead, so Polish
     * still reads "2 miseczki" and not "2 miseczek".
     */
    public static String shortCount(String key, long count, Language language) {
        String abbreviation = shortForm(key, language);
        return abbreviation.equals(other(key, language))
                ? count(key, count, language)
                : number(count, language) + " " + abbreviation;
    }

    public static Localized in(String key, Language language) {
        String unit = normalize(key);
        return new Localized(unit, one(unit, language), few(unit, language), many(unit, language),
                other(unit, language), shortForm(unit, language), per(unit, language));
    }

    /**
     * The plural category of a whole number, reduced to the forms the file
     * carries. Polish: 1 is one; 2-4, 22-24, ... are few, except 12-14; the
     * rest is many. French counts 0 and 1 as one. The other languages only
     * distinguish 1 from everything else.
     */
    static String pluralForm(long count, Language language) {
        long n = Math.abs(count);
        return switch (language) {
            case PL -> n == 1 ? "one"
                    : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? "few"
                    : "many";
            case FR -> n <= 1 ? "one" : "other";
            default -> n == 1 ? "one" : "other";
        };
    }

    private static String form(String key, String form, Language language) {
        return NAMES.get(language == null ? Language.NL : language).get(normalize(key) + "." + form);
    }

    private static String number(long count, Language language) {
        return NumberFormat.getIntegerInstance(
                (language == null ? Language.NL : language).locale()).format(count);
    }

    private static String clean(String key) {
        if (key == null || key.isBlank()) return null;
        return key.strip().toLowerCase(Locale.ROOT);
    }

    /* Strict like the loader itself: a unit that misses a form fails at class
       initialisation, not as an empty spot on a quote that is already sent. */
    private static List<String> keys() {
        List<String> units = new ArrayList<>();
        for (String row : NAMES.get(Language.NL).keySet()) {
            int dot = row.lastIndexOf('.');
            if (dot <= 0 || !FORMS.contains(row.substring(dot + 1))) {
                throw new IllegalStateException("unit-names.csv: unexpected key " + row);
            }
            String unit = row.substring(0, dot);
            if (!units.contains(unit)) units.add(unit);
        }
        for (String unit : units) {
            for (String form : FORMS) {
                for (Language language : Language.values()) {
                    String text = NAMES.get(language).get(unit + "." + form);
                    if (text == null || text.isBlank()) {
                        throw new IllegalStateException("unit-names.csv misses " + unit + "."
                                + form + " for " + language);
                    }
                }
            }
        }
        if (!units.contains(DEFAULT)) {
            throw new IllegalStateException("unit-names.csv misses the default unit " + DEFAULT);
        }
        return List.copyOf(units);
    }
}
