package be.enrosed.shared;

import io.quarkus.qute.TemplateExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formatting helpers for the quote template.
 *
 * A customer document should look Belgian: dot for thousands, comma for
 * decimals. And percentages with one decimal, not four.
 */
@TemplateExtension
public class DocumentFormat {

    private static final Locale LOCALE = Locale.forLanguageTag("nl-BE");
    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Bedrag in euro: 8.710,42 EUR */
    public static String eur(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(LOCALE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value == null ? BigDecimal.ZERO : value) + " EUR";
    }

    /** Piece price with three decimals; that is how the purchase list has them. */
    public static String unit(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(LOCALE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(3);
        return format.format(value == null ? BigDecimal.ZERO : value) + " EUR";
    }

    /** Percentage without superfluous zeroes: 6 %, 6.5 %. */
    public static String pct(BigDecimal value) {
        if (value == null) return "0 %";
        BigDecimal rounded = value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
        NumberFormat format = NumberFormat.getNumberInstance(LOCALE);
        format.setMaximumFractionDigits(1);
        return format.format(rounded) + " %";
    }

    public static String amount(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(LOCALE);
        format.setMaximumFractionDigits(1);
        return format.format(value == null ? BigDecimal.ZERO : value);
    }

    /** Datum in Belgische notatie: 25/05/2026. */
    public static String be(java.time.LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }

    /** Also works on the dates that arrive as text. */
    public static String beDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return "";
        try {
            return java.time.LocalDate.parse(isoDate).format(DATE);
        } catch (Exception e) {
            return isoDate;
        }
    }

    /**
     * Turns 2026-W42 into "week 42 (12/10 - 18/10/2026)".
     *
     * The raw notation is fine in a field but not on a quote: a customer
     * should not have to look up when week 42 falls.
     */
    public static String week(String isoWeek) {
        if (isoWeek == null || isoWeek.isBlank()) return "";
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("^(\\d{4})-W(\\d{1,2})$").matcher(isoWeek.trim());
        if (!match.matches()) return isoWeek;

        int year = Integer.parseInt(match.group(1));
        int number = Integer.parseInt(match.group(2));
        try {
            java.time.LocalDate monday = java.time.LocalDate.of(year, 1, 4)
                    .with(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear(), number)
                    .with(java.time.DayOfWeek.MONDAY);
            java.time.LocalDate sunday = monday.plusDays(6);
            return "week %d (%s - %s)".formatted(number,
                    monday.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")),
                    sunday.format(DATE));
        } catch (RuntimeException e) {
            return "week " + number;
        }
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
