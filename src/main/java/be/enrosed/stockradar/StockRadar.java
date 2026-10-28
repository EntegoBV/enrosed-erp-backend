package be.enrosed.stockradar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stock radar: one product (iPhone Duo, 256 GB, Star White) watched at a
 * handful of Belgian and Dutch shops. Pure records and parsing helpers; the
 * service owns timing, persistence and notifications.
 */
public final class StockRadar {

    private StockRadar() {}

    /** How a shop page is read: a plain HTTP request, the headless Chromium shell, or the full Chromium (for shops that detect the shell). */
    public enum Method { HTTP, BROWSER, CHROME }

    /** One watched shop. */
    public record Store(String key, String name, String kindLabel, Method method, String url, String orderUrl,
                        int intervalSeconds, BigDecimal listPriceEur, Checker checker) {}

    /** What one page read said. A missing delivery date means "no date was promised". */
    public record Observation(boolean inStock, LocalDate deliveryBy, String statusText, BigDecimal priceEur, String foundUrl) {
        public Observation {
            if (statusText == null || statusText.isBlank()) statusText = inStock ? "Op voorraad" : "Niet beschikbaar";
            if (statusText.length() > 280) statusText = statusText.substring(0, 277) + "…";
        }
        public static Observation out(String text, BigDecimal price) { return new Observation(false, null, text, price, null); }
    }

    /** The fetched page: the raw HTML (or JSON) and, from a browser, the rendered text. */
    public record Page(int status, String html, String text) {}

    @FunctionalInterface
    public interface Checker { Observation check(Page page, LocalDate today); }

    /** A shop answered, but not with a usable page (blocked, down, unknown layout). */
    public static final class FetchFailure extends RuntimeException {
        public FetchFailure(String message) { super(message); }
    }

    // ------------------------------------------------------------- text helpers

    static final Pattern TAG = Pattern.compile("<[^>]+>");

    static String stripTags(String html) {
        if (html == null) return "";
        return TAG.matcher(html.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\""))
                .replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    static String firstGroup(Pattern pattern, CharSequence text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    static BigDecimal euro(String raw) {
        if (raw == null) return null;
        String digits = raw.replace("€", "").replace(" ", "").replace(" ", "").trim();
        if (digits.isEmpty()) return null;
        // "2.339,00" / "2339,00" / "2339" / "2339.00"
        if (digits.contains(",")) digits = digits.replace(".", "").replace(",", ".");
        else if (digits.chars().filter(c -> c == '.').count() == 1 && digits.length() - digits.indexOf('.') - 1 == 3) digits = digits.replace(".", "");
        try { return new BigDecimal(digits).setScale(2, java.math.RoundingMode.HALF_UP); } catch (NumberFormatException ignored) { return null; }
    }

    // ------------------------------------------------------------- Dutch dates

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januari", 1), Map.entry("jan", 1), Map.entry("februari", 2), Map.entry("feb", 2),
            Map.entry("maart", 3), Map.entry("mrt", 3), Map.entry("mar", 3), Map.entry("april", 4), Map.entry("apr", 4),
            Map.entry("mei", 5), Map.entry("juni", 6), Map.entry("jun", 6), Map.entry("juli", 7), Map.entry("jul", 7),
            Map.entry("augustus", 8), Map.entry("aug", 8), Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("oktober", 10), Map.entry("okt", 10), Map.entry("oct", 10), Map.entry("november", 11), Map.entry("nov", 11),
            Map.entry("december", 12), Map.entry("dec", 12));

    static final Pattern DAY_MONTH = Pattern.compile("(\\d{1,2})\\s+([a-zA-Z]{3,9})\\.?(?:\\s+(\\d{4}))?");

    /** "18 november", "25 jan 2027": this year, or next year when that day is already more than a month behind us. */
    static LocalDate dayMonth(String text, LocalDate today) {
        if (text == null) return null;
        Matcher matcher = DAY_MONTH.matcher(text);
        while (matcher.find()) {
            Integer month = MONTHS.get(matcher.group(2).toLowerCase(Locale.ROOT));
            if (month == null) continue;
            int day = Integer.parseInt(matcher.group(1));
            try {
                if (matcher.group(3) != null) return LocalDate.of(Integer.parseInt(matcher.group(3)), month, day);
                LocalDate date = LocalDate.of(today.getYear(), month, day);
                return date.isBefore(today.minusDays(31)) ? date.plusYears(1) : date;
            } catch (java.time.DateTimeException ignored) { /* 31 februari */ }
        }
        return null;
    }

    static final Pattern DD_MM_YYYY = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{4})");

    static LocalDate ddMmYyyy(String text) {
        if (text == null) return null;
        Matcher matcher = DD_MM_YYYY.matcher(text);
        if (!matcher.find()) return null;
        try { return LocalDate.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1))); }
        catch (java.time.DateTimeException ignored) { return null; }
    }

    static LocalDate yyyyMmDd(String compact) {
        if (compact == null || !compact.matches("\\d{8}")) return null;
        try { return LocalDate.of(Integer.parseInt(compact.substring(0, 4)), Integer.parseInt(compact.substring(4, 6)), Integer.parseInt(compact.substring(6, 8))); }
        catch (java.time.DateTimeException ignored) { return null; }
    }

    /** "vandaag", "morgen", "overmorgen", "binnen 24 uur", "binnen 2 werkdagen", "binnen 3 weken". */
    static LocalDate relative(String text, LocalDate today) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("overmorgen")) return today.plusDays(2);
        if (lower.contains("morgen")) return today.plusDays(1);
        if (lower.contains("vandaag")) return today;
        Matcher hours = Pattern.compile("binnen\\s+(\\d{1,3})\\s*(u|uur)\\b").matcher(lower);
        if (hours.find()) return today.plusDays(Math.max(1, (Integer.parseInt(hours.group(1)) + 23) / 24));
        Matcher days = Pattern.compile("(?:binnen|na|in)\\s+(?:minstens\\s+)?(\\d{1,3})\\s*(?:werk)?dag").matcher(lower);
        if (days.find()) return today.plusDays(Integer.parseInt(days.group(1)));
        Matcher weeks = Pattern.compile("(?:binnen|na|in|>|minstens)\\s*(?:minstens\\s+)?(\\d{1,2})\\s*we(?:e)?k").matcher(lower);
        if (weeks.find()) return today.plusWeeks(Integer.parseInt(weeks.group(1)));
        return null;
    }

    static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    static String snippet(String text, String around, int radius) {
        if (text == null) return "";
        int at = text.toLowerCase(Locale.ROOT).indexOf(around.toLowerCase(Locale.ROOT));
        if (at < 0) return "";
        return text.substring(Math.max(0, at - radius), Math.min(text.length(), at + around.length() + radius));
    }

    static List<String> lines(String text) { return text == null ? List.of() : List.of(text.split("\\r?\\n")); }

    /** The page title, for an error that says which page a shop served instead of the product. */
    static String pageTitle(String html) {
        String title = stripTags(firstGroup(Pattern.compile("(?is)<title[^>]*>(.*?)</title>"), html == null ? "" : html));
        return title.length() > 80 ? title.substring(0, 80) + "…" : title;
    }

    /** A bot gate instead of the shop: Amazon's robot check, Akamai's "Access Denied", Cloudflare's challenge. */
    static boolean looksBlocked(String html, String text) {
        return containsAny(html, "Type the characters you see", "Enter the characters you see below", "api-services-support@amazon.com",
                "not a robot", "Access Denied", "Toegang geweigerd", "cf-browser-verification", "challenge-platform", "Reference #18.",
                "Even geduld", "Just a moment")
                || containsAny(text, "not a robot", "Access Denied", "Toegang geweigerd", "Even geduld");
    }
}
