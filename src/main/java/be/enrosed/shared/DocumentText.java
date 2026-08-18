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
     * The date in the shape that fits the language.
     *
     * Dutch, French and German write day-month-year; English gets the month
     * spelled out, because a British and an American reader read 03/04
     * differently, and a delivery term leaves no room for that doubt.
     */
    public static String date(LocalDate date, Language language) {
        if (date == null) return "";
        return switch (language) {
            /* Most of Europe writes day-month-year. */
            case NL, FR, DE, ES, PT, TR -> DocumentFormat.be(date);
            /* Poland writes with dots: 25.05.2026. */
            case PL -> date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            /* English gets the month spelled out: 05/25 and 25/05 read
               differently on either side of the ocean, and a delivery term
               leaves no room for that doubt. */
            case EN -> date.format(java.time.format.DateTimeFormatter
                    .ofPattern("d MMMM yyyy", java.util.Locale.UK));
        };
    }

    /** Amount with the separator that belongs to the language. */
    public static String money(java.math.BigDecimal amount, Language language) {
        if (amount == null) return "";
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(language.locale());
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    /**
     * A delivery week written out: "week 42 (12/10 - 18/10/2026)".
     *
     * The week numbering itself is the same everywhere; only the word in
     * front and the date format differ.
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
