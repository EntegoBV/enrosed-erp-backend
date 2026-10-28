package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.Method;
import be.enrosed.stockradar.StockRadar.Observation;
import be.enrosed.stockradar.StockRadar.Page;
import be.enrosed.stockradar.StockRadar.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.enrosed.stockradar.StockRadar.*;

/**
 * The watched shops and how each page is read. Article numbers were looked up
 * on 2026-10-28; a shop that changes its page layout shows up as "Pagina niet
 * herkend" on the radar screen instead of a false alarm.
 */
public final class StockRadarStores {

    private StockRadarStores() {}

    public static final String PRODUCT = "iPhone Duo · 256 GB · Sterrenwit (Star White)";
    /** Apple part number of iPhone Duo 256 GB Star White in the EU store. */
    public static final String APPLE_PART = "MK2D4ZD/A";
    /** Apple Store Brussels. */
    public static final String APPLE_STORE = "R486";
    static final BigDecimal LIST_PRICE = new BigDecimal("2339.00");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final List<Store> STORES = List.of(
            new Store("apple-brussels", "Apple Store Brussel", "Afhalen in de winkel", Method.HTTP,
                    "https://www.apple.com/be-nl/shop/retail/pickup-message?pl=true&parts.0=" + APPLE_PART + "&location=1000",
                    "https://www.apple.com/be-nl/shop/buy-iphone/iphone-duo", 20, LIST_PRICE, StockRadarStores::applePickup),
            new Store("apple-online", "Apple online", "Levering", Method.HTTP,
                    "https://www.apple.com/be-nl/shop/delivery-message?parts.0=" + APPLE_PART + "&mt=regular",
                    "https://www.apple.com/be-nl/shop/buy-iphone/iphone-duo", 60, LIST_PRICE, StockRadarStores::appleDelivery),
            new Store("mediamarkt-be", "MediaMarkt België", "Levering · art. 2253422", Method.HTTP,
                    "https://www.mediamarkt.be/nl/product/_apple-iphone-duo-5g-256-gb-esim-star-white-2253422.html",
                    "https://www.mediamarkt.be/nl/product/_apple-iphone-duo-5g-256-gb-esim-star-white-2253422.html", 60, LIST_PRICE,
                    (page, today) -> mediaMarkt(page, today, "2253422")),
            new Store("mediamarkt-nl", "MediaMarkt Nederland", "Levering · art. 1914026", Method.BROWSER,
                    "https://www.mediamarkt.nl/nl/product/_apple-iphone-duo-5g-256-gb-esim-star-white-256-gb-star-white-1914026.html",
                    "https://www.mediamarkt.nl/nl/product/_apple-iphone-duo-5g-256-gb-esim-star-white-256-gb-star-white-1914026.html", 180, LIST_PRICE,
                    (page, today) -> mediaMarkt(page, today, "1914026")),
            new Store("coolblue-be", "Coolblue België", "Levering · art. 986123", Method.HTTP,
                    "https://www.coolblue.be/nl/product/986123/apple-iphone-duo-256gb-wit.html",
                    "https://www.coolblue.be/nl/product/986123/apple-iphone-duo-256gb-wit.html", 60, LIST_PRICE, StockRadarStores::coolblue),
            new Store("coolblue-nl", "Coolblue Nederland", "Levering · art. 986123", Method.HTTP,
                    "https://www.coolblue.nl/product/986123/apple-iphone-duo-256gb-wit.html",
                    "https://www.coolblue.nl/product/986123/apple-iphone-duo-256gb-wit.html", 90, LIST_PRICE, StockRadarStores::coolblue),
            new Store("krefel", "Krëfel", "Levering · art. 51008017", Method.CHROME,
                    "https://www.krefel.be/nl/p/51008017-apple-iphone-duo-256gb-star-white",
                    "https://www.krefel.be/nl/p/51008017-apple-iphone-duo-256gb-star-white", 180, LIST_PRICE, StockRadarStores::krefel),
            new Store("vandenborre", "Vanden Borre", "Levering of Click&Collect · sku 7592493", Method.BROWSER,
                    "https://www.vandenborre.be/smartphone/iphone-duo-256gb-sw",
                    "https://www.vandenborre.be/smartphone/iphone-duo-256gb-sw", 150, LIST_PRICE, StockRadarStores::vandenBorre),
            new Store("amazon-be", "Amazon.com.be", "Levering · ASIN B0HJB16BGD", Method.HTTP,
                    "https://www.amazon.com.be/dp/B0HJB16BGD?language=nl_BE",
                    "https://www.amazon.com.be/dp/B0HJB16BGD?language=nl_BE", 90, LIST_PRICE, StockRadarStores::amazon),
            new Store("bol-be", "bol.com", "Zoekresultaten (nog niet in het assortiment)", Method.HTTP,
                    "https://www.bol.com/be/nl/s/?searchtext=apple+iphone+duo+256gb",
                    "https://www.bol.com/be/nl/s/?searchtext=apple+iphone+duo+256gb", 120, LIST_PRICE, StockRadarStores::bolSearch));

    public static Store byKey(String key) {
        for (Store store : STORES) if (store.key().equals(key)) return store;
        return null;
    }

    // ------------------------------------------------------------------ Apple

    /** apple.com retail pickup-message: per store the pickupDisplay of the part. */
    static Observation applePickup(Page page, LocalDate today) {
        JsonNode root = json(page.html());
        JsonNode stores = root.path("body").path("stores");
        JsonNode chosen = null;
        for (JsonNode store : stores) {
            if (APPLE_STORE.equals(store.path("storeNumber").asText())) { chosen = store; break; }
            if (chosen == null) chosen = store;
        }
        if (chosen == null) {
            String error = root.path("body").path("errorMessage").asText("");
            throw new IllegalStateException(error.isBlank() ? "Apple gaf geen winkelgegevens terug" : stripTags(error));
        }
        JsonNode part = chosen.path("partsAvailability").path(APPLE_PART);
        if (part.isMissingNode()) throw new IllegalStateException("Apple kent het artikelnummer niet (" + APPLE_PART + ")");
        String display = part.path("pickupDisplay").asText("");
        String quote = stripTags(part.path("pickupSearchQuote").asText(""));
        boolean in = "available".equalsIgnoreCase(display);
        String storeName = chosen.path("storeName").asText("Brussels");
        String text = (quote.isBlank() ? (in ? "Beschikbaar voor afhalen" : "Momenteel niet beschikbaar") : quote) + " · Apple " + storeName;
        return new Observation(in, in ? today : null, text, LIST_PRICE, null);
    }

    /** apple.com delivery-message: the first delivery option carries the promised date as yyyyMMdd. */
    static Observation appleDelivery(Page page, LocalDate today) {
        JsonNode root = json(page.html());
        JsonNode regular = root.path("body").path("content").path("deliveryMessage").path(APPLE_PART).path("regular");
        if (regular.isMissingNode()) throw new IllegalStateException("Apple gaf geen leverinformatie voor " + APPLE_PART);
        boolean buyable = regular.path("isBuyable").asBoolean(regular.path("buyability").path("isBuyable").asBoolean(true));
        LocalDate by = yyyyMmDd(regular.path("deliveryOptionMessages").path(0).path("encodedUpperDateString").asText(""));
        String sticky = stripTags(regular.path("stickyMessageSTH").asText(""));
        String option = stripTags(regular.path("deliveryOptionMessages").path(0).path("displayName").asText(""));
        String text = !sticky.isBlank() ? sticky : !option.isBlank() ? "Levering " + option : by == null ? "Geen leverdatum" : "Levering " + by;
        if (!buyable) text = "Niet te bestellen · " + text;
        return new Observation(buyable && by != null, by, text, LIST_PRICE, null);
    }

    // ------------------------------------------------------------- MediaMarkt

    private static final Pattern MM_DELIVERY = Pattern.compile("\"deliveryStatus\":\"([A-Z_]+)\"");
    private static final Pattern MM_PRICE = Pattern.compile("\"priceCurrency\":\"EUR\",\"price\":([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Server-rendered Apollo state: the product's own delivery feature
     * ("CofrDeliveryFeature:Media:nl:<article>"), never the page's loose
     * texts - those also carry recommended products and a translation table.
     */
    static Observation mediaMarkt(Page page, LocalDate today, String productId) {
        String html = page.html() == null ? "" : page.html();
        int anchor = html.indexOf("\"CofrDeliveryFeature:Media:nl:" + productId + "\":{");
        String status = null;
        String release = null;
        if (anchor >= 0) {
            String object = html.substring(anchor, Math.min(html.length(), anchor + 1500));
            status = firstGroup(MM_DELIVERY, object);
            release = firstGroup(Pattern.compile("\"releaseDate\":\"(\\d{4}-\\d{2}-\\d{2})"), object);
        }
        if (status == null) {
            if (containsAny(html, "Access Denied", "Toegang geweigerd", "Reference #")) throw new FetchFailure("MediaMarkt blokkeert de controle (Akamai)");
            throw new IllegalStateException("Pagina niet herkend (geen bezorgstatus voor artikel " + productId + ")");
        }
        // The promised date is only rendered text: the browser's text is the product's own; in raw HTML, look after the article number.
        String text = page.text() != null ? page.text() : stripTags(html);
        int from = page.text() != null ? 0 : Math.max(0, text.indexOf("Art.-Nr. " + productId));
        String region = text.substring(from, Math.min(text.length(), from + 6000));
        LocalDate by = ddMmYyyy(firstGroup(Pattern.compile("Bezorging vanaf\\s*(\\d{2}-\\d{2}-\\d{4})"), region));
        if (by == null) by = relative(snippet(region, "Bezorging", 50), today);
        boolean preorder = containsAny(region, "pre-order") || (release != null && LocalDate.parse(release).isAfter(today));
        boolean in = status.startsWith("AVAILABLE") || status.equals("PRE_ORDER") || status.equals("COMING_SOON");
        if (status.equals("NOT_AVAILABLE") || status.equals("SOLD_OUT") || status.equals("UNAVAILABLE")) in = false;
        if (in && status.equals("COMING_SOON") && by == null) in = false;
        StringBuilder label = new StringBuilder();
        if (!in) label.append(status.equals("NOT_AVAILABLE") ? "Helaas geen bezorging mogelijk" : "Niet leverbaar (" + status.toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
        else label.append(status.startsWith("AVAILABLE") ? "Online op voorraad" : status.toLowerCase(Locale.ROOT).replace('_', ' '));
        if (by != null) label.append(" · bezorging vanaf ").append(by);
        if (preorder && in) label.append(" · pre-order");
        BigDecimal price = euro(firstGroup(MM_PRICE, html));
        return new Observation(in, by, label.toString(), price == null ? LIST_PRICE : price, null);
    }

    // --------------------------------------------------------------- Coolblue

    private static final Pattern CB_AVAILABILITY = Pattern.compile("\"availability\":\"https://schema.org/(\\w+)\"");
    private static final Pattern CB_OFFER_PRICE = Pattern.compile("\"@type\":\"Offer\",\"priceCurrency\":\"EUR\",\"price\":\"?([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern CB_FROM = Pattern.compile("(?:Geleverd|Bezorgd) vanaf ([0-9]{1,2} [a-z]+)");

    /** The JSON-LD offer says InStock / BackOrder / PreOrder / OutOfStock; the page text adds the promised date. */
    static Observation coolblue(Page page, LocalDate today) {
        String html = page.html() == null ? "" : page.html();
        String availability = firstGroup(CB_AVAILABILITY, html);
        if (availability == null) {
            if (containsAny(html, "Access Denied", "cf-browser-verification")) throw new FetchFailure("Coolblue blokkeert de controle");
            throw new IllegalStateException("Pagina niet herkend (geen JSON-LD aanbod)");
        }
        String text = page.text() == null ? html.replace("\\\"", "\"") : page.text();
        LocalDate by = dayMonth(firstGroup(CB_FROM, text), today);
        if (by == null && containsAny(text, "Morgen bezorgd", "morgen in huis", "Vandaag besteld, morgen")) by = today.plusDays(1);
        boolean in = switch (availability) {
            case "InStock" -> true;
            case "BackOrder", "PreOrder", "LimitedAvailability" -> by != null;
            default -> false;
        };
        String label = switch (availability) {
            case "InStock" -> "Op voorraad" + (by == null ? "" : " · geleverd vanaf " + by);
            case "BackOrder" -> by == null ? "Nabestelling zonder datum" : "Geleverd vanaf " + by;
            case "PreOrder" -> by == null ? "Pre-order zonder datum" : "Pre-order · geleverd vanaf " + by;
            case "OutOfStock", "SoldOut" -> "Tijdelijk uitverkocht";
            case "Discontinued" -> "Niet meer leverbaar";
            default -> availability + (by == null ? "" : " · " + by);
        };
        BigDecimal price = euro(firstGroup(CB_OFFER_PRICE, html));
        return new Observation(in, by, label, price == null ? LIST_PRICE : price, null);
    }

    // ----------------------------------------------------------------- Krëfel

    /** Rendered text: "Pre order", "Levertijd > 3 weken", "Verwachte levering eind januari 2027" or "Op voorraad". */
    static Observation krefel(Page page, LocalDate today) {
        String text = page.text() == null ? stripTags(page.html()) : page.text();
        if (!containsAny(text, "iPhone Duo")) throw new IllegalStateException("Pagina niet herkend (productnaam ontbreekt)");
        boolean preorder = containsAny(text, "Pre order", "Pre-order", "Preorder");
        boolean inStockWord = containsAny(text, "Op voorraad", "In voorraad") && !containsAny(text, "Niet op voorraad");
        String lead = firstGroup(Pattern.compile("(Levertijd[^\\n]{0,40})"), text);
        String expected = firstGroup(Pattern.compile("(Verwachte levering[^\\n]{0,50})"), text);
        LocalDate by = relative(lead, today);
        if (by == null && expected != null) by = monthWord(expected, today);
        if (by == null && expected != null) by = dayMonth(expected, today);
        List<String> parts = new ArrayList<>();
        if (preorder) parts.add("Pre-order");
        if (inStockWord) parts.add("Op voorraad");
        if (lead != null) parts.add(lead.trim());
        if (expected != null) parts.add(expected.trim());
        boolean in = inStockWord && !preorder;
        if (!in && !preorder && lead == null && expected == null) {
            if (containsAny(text, "Niet beschikbaar", "Uitverkocht", "Niet leverbaar")) parts.add("Niet leverbaar");
            else parts.add("Status onbekend");
        }
        return new Observation(in, by, String.join(" · ", parts), euro(firstGroup(Pattern.compile("€[\\s\\u00a0]?([0-9.]+,[0-9]{2})"), text)), null);
    }

    /** "eind januari 2027", "begin november", "midden december": the last, first or middle day of that month. */
    static LocalDate monthWord(String text, LocalDate today) {
        Matcher matcher = Pattern.compile("(eind|begin|midden|half)?\\s*(januari|februari|maart|april|mei|juni|juli|augustus|september|oktober|november|december)(?:\\s+(\\d{4}))?", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!matcher.find()) return null;
        LocalDate first = dayMonth("1 " + matcher.group(2) + (matcher.group(3) == null ? "" : " " + matcher.group(3)), today);
        if (first == null) return null;
        String word = matcher.group(1) == null ? "eind" : matcher.group(1).toLowerCase(Locale.ROOT);
        return switch (word) {
            case "begin" -> first.plusDays(4);
            case "midden", "half" -> first.plusDays(14);
            default -> first.withDayOfMonth(first.lengthOfMonth());
        };
    }

    // ----------------------------------------------------------- Vanden Borre

    /** Rendered text: "Levering aan huis : binnen minstens 3 weken", "Click&Collect : …", or "Op voorraad". */
    static Observation vandenBorre(Page page, LocalDate today) {
        String text = page.text() == null ? stripTags(page.html()) : page.text();
        if (!containsAny(text, "iPhone Duo")) throw new IllegalStateException("Pagina niet herkend (productnaam ontbreekt)");
        String home = firstGroup(Pattern.compile("Levering aan huis\\s*:\\s*([^\\n]{2,60}?)(?=\\s+(?:Gratis|Express|Afhaling|Click)|\\n|$)"), text);
        String collect = firstGroup(Pattern.compile("Click&Collect\\s*:[\\s\\S]{0,220}?((?:binnen|na|vanaf)[^\\n.]{3,40}|morgen|vandaag|niet mogelijk)"), text);
        boolean inStockWord = containsAny(text, "Op voorraad", "In voorraad") && !containsAny(text, "Niet op voorraad");
        LocalDate by = relative(home, today);
        if (by == null) by = dayMonth(home, today);
        boolean soon = by != null && !by.isAfter(today.plusDays(3));
        boolean in = inStockWord || soon;
        List<String> parts = new ArrayList<>();
        if (home != null) parts.add("Levering: " + home.trim());
        if (collect != null) parts.add("Afhalen: " + collect.trim().replaceAll("\\s+", " "));
        if (inStockWord) parts.add(0, "Op voorraad");
        if (containsAny(text, "niet zeker")) parts.add("levering niet zeker");
        if (parts.isEmpty()) parts.add("Status onbekend");
        return new Observation(in, by, String.join(" · ", parts), euro(firstGroup(Pattern.compile("€[\\s\\u00a0]?([0-9.]+,[0-9]{2})"), text)), null);
    }

    // ----------------------------------------------------------------- Amazon

    /** The #availability block and the delivery block of the buy box. */
    static Observation amazon(Page page, LocalDate today) {
        String html = page.html() == null ? "" : page.html();
        if (containsAny(html, "Type the characters you see", "api-services-support@amazon.com", "Enter the characters you see below"))
            throw new FetchFailure("Amazon vraagt een captcha (controle geblokkeerd)");
        String availability = stripTags(firstGroup(Pattern.compile("(?s)<div id=\"availability\"[^>]*>(.*?)</div>"), html));
        String delivery = stripTags(firstGroup(Pattern.compile("(?s)id=\"mir-layout-DELIVERY_BLOCK[^\"]*\"[^>]*>(.*?)</div>\\s*</div>"), html));
        String deliveryAttr = firstGroup(Pattern.compile("data-csa-c-delivery-time=\"([^\"]+)\""), html);
        if (availability.isBlank() && delivery.isBlank() && deliveryAttr == null) {
            if (!containsAny(html, "iPhone Duo")) throw new IllegalStateException("Pagina niet herkend (productnaam ontbreekt)");
            availability = containsAny(html, "Momenteel niet beschikbaar") ? "Momenteel niet beschikbaar" : "Geen koopblok";
        }
        String lower = availability.toLowerCase(Locale.ROOT);
        boolean in = (lower.startsWith("op voorraad") || lower.contains("nog maar") || lower.startsWith("in stock")) && !lower.contains("niet op voorraad");
        String promise = deliveryAttr != null ? deliveryAttr : delivery;
        LocalDate by = relative(promise, today);
        if (by == null) by = dayMonth(promise, today);
        StringBuilder label = new StringBuilder(availability.isBlank() ? (in ? "Op voorraad" : "Niet op voorraad") : availability);
        if (in && promise != null && !promise.isBlank()) label.append(" · ").append(promise.length() > 80 ? promise.substring(0, 80) : promise);
        String whole = firstGroup(Pattern.compile("<span class=\"a-price-whole\">([0-9.,]+)"), html);
        String fraction = firstGroup(Pattern.compile("<span class=\"a-price-fraction\">([0-9]{2})"), html);
        BigDecimal price = whole == null ? null : euro(whole.replace(",", "").replace(".", "") + "," + (fraction == null ? "00" : fraction));
        return new Observation(in, by, label.toString(), price == null ? LIST_PRICE : price, null);
    }

    // -------------------------------------------------------------- bol.com

    private static final Pattern BOL_LINK = Pattern.compile("href=\"(/be/nl/p/([^\"/]+)/(\\d{8,20})/)[^\"]*\"");
    private static final Pattern BOL_PRICE = Pattern.compile("De prijs van dit product is '([0-9]+)' euro(?: en '([0-9]{1,2})' cent)?");

    /** bol.com has no iPhone Duo yet: watch the search results until a white 256 GB listing appears. */
    static Observation bolSearch(Page page, LocalDate today) {
        String html = page.html() == null ? "" : page.html();
        if (!containsAny(html, "Zoekresultaten", "zoekresultaten", "searchtext")) throw new IllegalStateException("Pagina niet herkend (geen zoekresultaten)");
        Matcher matcher = BOL_LINK.matcher(html);
        while (matcher.find()) {
            String slug = matcher.group(2).toLowerCase(Locale.ROOT);
            if (!slug.contains("iphone-duo") || !slug.contains("256")) continue;
            if (!(slug.contains("wit") || slug.contains("white") || slug.contains("sterrenwit"))) continue;
            if (containsAny(slug, "hoes", "case", "screen", "protector", "folie", "glas", "glass", "cover", "lader", "kabel", "charger",
                    "magsafe-duo", "bescherm", "houder", "standaard", "folio", "sleeve", "pack")) continue;
            String url = "https://www.bol.com" + matcher.group(1);
            String window = stripTags(html.substring(Math.max(0, matcher.start() - 2500), Math.min(html.length(), matcher.end() + 5000)));
            boolean preorder = containsAny(window, "Pre-order", "Pre order");
            boolean unavailable = containsAny(window, "Niet leverbaar", "Uitverkocht", "Tijdelijk niet leverbaar");
            String promise = firstGroup(Pattern.compile("((?:Uiterlijk [0-9]{1,2} [a-z]+|Morgen|Vandaag|Overmorgen)[^.]{0,20}in huis)"), window);
            LocalDate by = promise == null ? null : relative(promise, today);
            if (by == null && promise != null) by = dayMonth(promise, today);
            boolean in = !preorder && !unavailable && (promise != null || containsAny(window, "Op voorraad"));
            Matcher price = BOL_PRICE.matcher(window);
            BigDecimal priceEur = price.find() ? euro(price.group(1) + "," + (price.group(2) == null ? "00" : String.format("%02d", Integer.parseInt(price.group(2))))) : LIST_PRICE;
            String label = "In het assortiment" + (preorder ? " · pre-order" : "") + (unavailable ? " · niet leverbaar" : "") + (promise != null ? " · " + promise : "");
            return new Observation(in, by, label, priceEur, url);
        }
        return new Observation(false, null, "Nog niet in het assortiment van bol.com", LIST_PRICE, null);
    }

    private static JsonNode json(String body) {
        try {
            return JSON.readTree(body == null ? "" : body);
        } catch (Exception e) {
            if (containsAny(body, "Page Not Found", "<html")) throw new FetchFailure("Apple gaf een webpagina in plaats van gegevens (endpoint gewijzigd?)");
            throw new IllegalStateException("Antwoord is geen JSON");
        }
    }
}
