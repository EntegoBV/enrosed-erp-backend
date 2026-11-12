package be.enrosed.partnerportal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** The Dutch words a melding is told with in the bell, the push message and the activity log. */
public final class PartnerSaleTexts {

    private static final Locale NL = Locale.forLanguageTag("nl-NL");

    private PartnerSaleTexts() {}

    /** "Veiling verkocht", "Direct verkocht", or "Teruggekocht op de klok" for a melding with only buybacks. */
    public static String title(String channel, boolean buybackOnly) {
        if (buybackOnly) return "Teruggekocht op de klok";
        return PartnerPortalRules.CHANNEL_DIRECT.equals(channel) ? "Direct verkocht" : "Veiling verkocht";
    }

    /** The short kind on a lock screen: Veiling, Direct or Terugkoop. */
    public static String kind(String channel, boolean buybackOnly) {
        if (buybackOnly) return "Terugkoop";
        return PartnerPortalRules.CHANNEL_DIRECT.equals(channel) ? "Direct" : "Veiling";
    }

    /** The ERP's sign for an amount below zero (Analyses, Kaslijn and the staff screens): "− € 220,29". */
    private static final char MINUS = '−';
    /** Between the sign, the euro sign and the number: an amount never wraps over two lines. */
    private static final char NBSP = ' ';

    /**
     * "€ 1.234,50", "− € 220,29": two decimals, Dutch separators, below zero the minus sign U+2212 before
     * the euro sign as the ERP writes it (frontend {@code signedEur}), never "€ -220,29". No-break spaces keep
     * the amount on one line. A value that rounds to zero has no sign.
     */
    public static String euro(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        return signed(amount, format("#,##0.00"));
    }

    /** A unit price: two decimals, up to four when it has them ("€ 0,355"); the sign as {@link #euro}. */
    public static String unitEuro(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(4, RoundingMode.HALF_UP);
        return signed(amount, format("#,##0.00##"));
    }

    private static String signed(BigDecimal amount, DecimalFormat format) {
        String text = "€" + NBSP + format.format(amount.abs());
        return amount.signum() < 0 ? "" + MINUS + NBSP + text : text;
    }

    /** "1 stuk" / "3 stuks" with the product's own unit words. */
    public static String quantity(int quantity, String unitOne, String unitOther) {
        return quantity + " " + (quantity == 1 ? unitOne : unitOther);
    }

    /** "4,5 %", "100 %": a percent as the documents print it. */
    public static String pct(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (amount.scale() < 0) amount = amount.setScale(0);
        return amount.toPlainString().replace('.', ',') + " %";
    }

    /**
     * The melding numbers of a document, collapsing only consecutive ids: "VV-12, VV-14, VV-17–19"
     * (the same rule as the frontend's meldingNumbersLine). Ids are sorted; duplicates count once.
     */
    public static String numbers(java.util.Collection<Long> reportIds) {
        List<Long> ids = reportIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        List<String> parts = new java.util.ArrayList<>();
        for (int start = 0; start < ids.size(); start++) {
            int end = start;
            while (end + 1 < ids.size() && ids.get(end + 1) == ids.get(end) + 1) end++;
            parts.add(end > start ? PartnerPortalRules.reportNumber(ids.get(start)) + "–" + ids.get(end)
                    : PartnerPortalRules.reportNumber(ids.get(start)));
            start = end;
        }
        return String.join(", ", parts);
    }

    /** "10/11/2026", "01/11–10/11/2026", or "28/12/2026–03/01/2027" across a year; "" without dates. */
    public static String period(LocalDate first, LocalDate last) {
        if (first == null && last == null) return "";
        if (first == null) first = last;
        if (last == null) last = first;
        if (first.equals(last)) return DAY.format(first);
        if (first.getYear() == last.getYear()) return DAY_MONTH.format(first) + "–" + DAY.format(last);
        return DAY.format(first) + "–" + DAY.format(last);
    }

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final java.time.format.DateTimeFormatter DAY_MONTH = java.time.format.DateTimeFormatter.ofPattern("dd/MM");

    private static final java.util.regex.Pattern LEGAL = java.util.regex.Pattern.compile(
            "^(b\\.?v\\.?b\\.?a\\.?|b\\.?v\\.?|n\\.?v\\.?|v\\.?o\\.?f\\.?|c\\.?v\\.?|s\\.?a\\.?r\\.?l\\.?|s\\.?a\\.?|s\\.?r\\.?l\\.?|gmbh|ltd|bvba|comm\\.?v\\.?|srl|sprl)\\.?$",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> TRADE = java.util.Set.of("bloemen", "bloemenhandel", "bloemist", "flowers",
            "flower", "fleurs", "kwekerij", "handel", "tuincentrum", "groothandel", "plants", "planten", "trading", "(test)", "test");

    /**
     * A partner customer's company in one or two words for a document line: "Frans Verhoeven Bloemen BV"
     * reads "Verhoeven". The frontend's partnerShortName, rule for rule: trade words and a trailing legal
     * form go, a rest of up to 12 characters stays whole, a longer one keeps its last word (4+ letters).
     */
    public static String shortName(String company, String fallback) {
        String cleaned = company == null ? "" : company.replace(',', ' ').strip();
        List<String> words = cleaned.isEmpty() ? List.of() : List.of(cleaned.split("\\s+"));
        List<String> kept = new java.util.ArrayList<>(words.stream()
                .filter(word -> !TRADE.contains(word.toLowerCase(Locale.ROOT))).toList());
        while (kept.size() > 1 && LEGAL.matcher(kept.getLast()).matches()) kept.removeLast();
        if (kept.isEmpty()) kept = new java.util.ArrayList<>(words);
        String rest = String.join(" ", kept);
        if (rest.isEmpty()) return fallback;
        if (rest.length() <= 12) return rest;
        String last = kept.getLast();
        return last.length() >= 4 ? last : rest.substring(0, 12).strip();
    }

    private static DecimalFormat format(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(NL));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }
}
