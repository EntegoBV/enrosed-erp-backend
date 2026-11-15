package be.enrosed.stockradar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stock radar: a few products Emre wants to buy (the iPhone Duo in white
 * and blue, 256 GB and 512 GB; the PS5 Pro), watched at Belgian and Dutch
 * shops that answer a plain HTTP request. Pure records and parsing helpers;
 * the service owns timing, persistence and notifications.
 */
public final class StockRadar {

    private StockRadar() {}

    /** A product with its variants and its own table on the screen: "iPhone Duo", "PS5 Pro". {@code icon} leads its alerts. */
    public record Family(String key, String label, String subtitle, String icon) {}

    /**
     * One watched variant. {@code key} ends every watch key of this item,
     * {@code label} heads its column ("Blauw 256 GB"), {@code title} names it in
     * an alert ("iPhone Duo blauw 256 GB"), {@code size} is how shops write the
     * storage in a name (256, 512; empty when it does not apply).
     */
    public record Item(String key, Family family, String label, String title, String colour, String product, String applePart,
                       BigDecimal listPrice, String size) {}

    /** One shop that is read automatically; {@code kind} says what it can do (levering, afhalen, winkel). */
    public record Shop(String key, String name, String shortName, String kind) {}

    /** A shop that refuses automatic reads: shown with its reason and a link, never asked. */
    public record ManualShop(String key, String family, String name, String reason, String url) {}

    /**
     * One watched page: a shop for one item. Items may share a page: Apple
     * answers for all four iPhones in one request, and that request is made once.
     * Watches with a {@code group} take turns: one page of the group a minute
     * (Coolblue's robot check comes quickly when a shop is asked faster).
     */
    public record Watch(String key, Shop shop, Item item, String url, String orderUrl, Checker checker, String group) {
        /** What the rhythm counts: the group, or the page itself. */
        public String slot() { return group != null ? group : url; }
    }

    /**
     * What one page read said. A missing delivery date means "no date was
     * promised". {@code places} names the physical stores that have it now; a
     * store that joins them is news of its own, even when the shop already counted.
     */
    public record Observation(boolean inStock, LocalDate deliveryBy, String statusText, BigDecimal priceEur, List<String> places, boolean dateUnknown) {
        public Observation {
            if (statusText == null || statusText.isBlank()) statusText = inStock ? "Op voorraad" : "Niet beschikbaar";
            if (statusText.length() > 280) statusText = statusText.substring(0, 277) + "…";
            places = places == null ? List.of() : List.copyOf(places);
        }
        public Observation(boolean inStock, LocalDate deliveryBy, String statusText, BigDecimal priceEur, List<String> places) {
            this(inStock, deliveryBy, statusText, priceEur, places, false);
        }
        public Observation(boolean inStock, LocalDate deliveryBy, String statusText, BigDecimal priceEur) {
            this(inStock, deliveryBy, statusText, priceEur, List.of(), false);
        }
    }

    /** The fetched page: the raw HTML or JSON. */
    public record Page(int status, String html) {}

    @FunctionalInterface
    public interface Checker { Observation check(Page page, LocalDate today); }

    /**
     * A shop answered, but not with a usable page. {@code refused} marks a shop
     * that said no (403, 429, 503, Apple's 541): it is asked again later, not harder.
     */
    public static final class FetchFailure extends RuntimeException {
        private final boolean refused;
        public FetchFailure(String message) { this(message, false); }
        public FetchFailure(String message, boolean refused) { super(message); this.refused = refused; }
        public boolean refused() { return refused; }
    }

    // ------------------------------------------------------------- text helpers

    static final Pattern TAG = Pattern.compile("<[^>]+>");

    static String stripTags(String html) {
        if (html == null) return "";
        String text = TAG.matcher(html).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&euro;", "€");
        return text.replaceAll("\\s+", " ").trim();
    }

    static String firstGroup(Pattern pattern, CharSequence text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    static BigDecimal euro(String raw) {
        if (raw == null) return null;
        String digits = raw.replace("€", "").replace(" ", "").replace(" ", "").trim();
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

    private static final Pattern NUMERIC_DATE = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");

    /** "18 november", "25 jan 2027", "28.11.2026", "28/11/2026": this year, or next year when that day is already more than a month behind us. */
    static LocalDate dayMonth(String text, LocalDate today) {
        if (text == null) return null;
        Matcher numeric = NUMERIC_DATE.matcher(text);
        if (numeric.find()) {
            try { return LocalDate.of(Integer.parseInt(numeric.group(3)), Integer.parseInt(numeric.group(2)), Integer.parseInt(numeric.group(1))); }
            catch (java.time.DateTimeException ignored) { /* 31.02 */ }
        }
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

    static LocalDate yyyyMmDd(String compact) {
        if (compact == null || !compact.matches("\\d{8}")) return null;
        try { return LocalDate.of(Integer.parseInt(compact.substring(0, 4)), Integer.parseInt(compact.substring(4, 6)), Integer.parseInt(compact.substring(6, 8))); }
        catch (java.time.DateTimeException ignored) { return null; }
    }

    private static final Pattern WITHIN_HOURS = Pattern.compile("(?:binnen|na)\\s+(\\d{1,3})\\s*(?:u|uur)\\b");
    private static final Pattern WITHIN_MINUTES = Pattern.compile("(?:binnen|na)\\s+(\\d{1,3})\\s*min");
    private static final Pattern WITHIN_DAYS = Pattern.compile("(?:binnen|na|in)\\s+(?:minstens\\s+|max\\.?\\s*|maximaal\\s+)?(\\d{1,3})\\s*(?:werk)?dag");
    /** "binnen 1 à 2 dagen", "2 tot 4 werkdagen", "1-2 dagen": the latest day counts, so a window is never promised too early. */
    private static final Pattern DAY_RANGE = Pattern.compile("(\\d{1,3})\\s*(?:à|a|tot|-|–)\\s*(\\d{1,3})\\s*(?:werk)?dag");
    private static final Pattern WITHIN_WEEKS = Pattern.compile("(?:binnen|na|in|>|minstens)\\s*(?:minstens\\s+)?(\\d{1,2})\\s*we(?:e)?k");

    /**
     * "vandaag", "morgen", "overmorgen", "na 30 min", "binnen 24 uur",
     * "binnen 2 werkdagen", "binnen minstens 3 weken".
     */
    static LocalDate relative(String text, LocalDate today) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("overmorgen")) return today.plusDays(2);
        if (lower.contains("morgen")) return today.plusDays(1);
        if (lower.contains("vandaag")) return today;
        if (WITHIN_MINUTES.matcher(lower).find()) return today;
        Matcher range = DAY_RANGE.matcher(lower);
        if (range.find()) return today.plusDays(Math.max(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2))));
        Matcher hours = WITHIN_HOURS.matcher(lower);
        if (hours.find()) return today.plusDays(Math.max(0, Integer.parseInt(hours.group(1)) / 24));
        Matcher days = WITHIN_DAYS.matcher(lower);
        if (days.find()) return today.plusDays(Integer.parseInt(days.group(1)));
        Matcher weeks = WITHIN_WEEKS.matcher(lower);
        if (weeks.find()) return today.plusWeeks(Integer.parseInt(weeks.group(1)));
        return null;
    }

    static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** The page title, for an error that says which page a shop served instead of the product. */
    static String pageTitle(String html) {
        String title = stripTags(firstGroup(Pattern.compile("(?is)<title[^>]*>(.*?)</title>"), html == null ? "" : html));
        return title.length() > 80 ? title.substring(0, 80) + "…" : title;
    }

    /** A robot check served instead of the page (AWS WAF at Coolblue): the shop says no for now. Never solved, only waited out. */
    static boolean robotCheck(String html) {
        return containsAny(html, "awsWafCookieDomainList", "gokuProps", "aws-waf-token", "captcha.awswaf", "/errors/validateCaptcha", "opfcaptcha");
    }

    /**
     * A bot gate instead of the shop: Akamai's "Access Denied", Cloudflare's
     * challenge. Only consulted once a parser has established that the product
     * itself is not on the page: an ordinary shop page may carry these words in its scripts.
     */
    static boolean looksBlocked(String html) {
        String title = pageTitle(html);
        return containsAny(title, "Access Denied", "Toegang geweigerd", "Even geduld", "Just a moment", "Attention Required", "Robot Check", "Server Busy")
                || containsAny(html, "cf-browser-verification", "Reference #18.", "make sure you're not a robot");
    }
}
