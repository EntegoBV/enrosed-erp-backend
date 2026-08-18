package be.enrosed.shared;

import java.time.LocalDate;
import java.util.Map;

/**
 * Every piece of text that appears on a quote, in a mail or in the customer
 * portal, per language.
 *
 * The translations themselves live in i18n/document-text.csv - one row per
 * key, one column per language - so they can be reviewed and edited in Excel
 * without touching code. The parity test guards that no language misses a
 * key, because a missing key is not an error but an empty spot on a quote
 * that is already with a customer.
 *
 * Product names and colours are deliberately absent: those come from the
 * product translations the user maintains through the CSV import.
 */
public final class DocumentText {

    private DocumentText() {}

    private static final Map<Language, Map<String, String>> TEXT =
            TranslationCsv.load("/i18n/document-text.csv");

    /** The texts for this language, ready to hand to a template. */
    public static Map<String, String> of(Language language) {
        return TEXT.get(language);
    }

    /**
     * De datum in de vorm die bij de taal past.
     *
     * Nederlands, Frans en Duits schrijven dag-maand-jaar; Engels krijgt de
     * maand voluit, want 03/04 leest een Britse en een Amerikaanse lezer
     * verschillend en bij een levertermijn wil je daar geen twijfel over.
     */
    public static String date(LocalDate date, Language language) {
        if (date == null) return "";
        return switch (language) {
            /* Het grootste deel van Europa schrijft dag-maand-jaar. */
            case NL, FR, DE, ES, PT, TR -> DocumentFormat.be(date);
            /* Polen schrijft met punten: 25.05.2026. */
            case PL -> date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            /* Engels krijgt de maand voluit: 05/25 en 25/05 lezen aan weerszijden
               van de oceaan anders, en bij een levertermijn wil je daar geen
               twijfel over. */
            case EN -> date.format(java.time.format.DateTimeFormatter
                    .ofPattern("d MMMM yyyy", java.util.Locale.UK));
        };
    }

    /** Bedrag met het scheidingsteken dat bij de taal hoort. */
    public static String money(java.math.BigDecimal amount, Language language) {
        if (amount == null) return "";
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(language.locale());
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    /**
     * Een leverweek uitgeschreven: "week 42 (12/10 - 18/10/2026)".
     *
     * De weeknummering zelf is overal gelijk; alleen het woord ervoor en de
     * datumopmaak verschillen.
     */
    public static String week(String isoWeek, Language language) {
        if (isoWeek == null || isoWeek.isBlank()) return "";
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("^(\\d{4})-W(\\d{1,2})$").matcher(isoWeek.trim());
        if (!match.matches()) return isoWeek;

        int year = Integer.parseInt(match.group(1));
        int number = Integer.parseInt(match.group(2));
        String word = of(language).get("week");
        try {
            LocalDate monday = LocalDate.of(year, 1, 4)
                    .with(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear(), number)
                    .with(java.time.DayOfWeek.MONDAY);
            LocalDate sunday = monday.plusDays(6);
            return "%s %d (%s - %s)".formatted(word, number,
                    monday.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")),
                    date(sunday, language));
        } catch (RuntimeException e) {
            return word + " " + number;
        }
    }
}
