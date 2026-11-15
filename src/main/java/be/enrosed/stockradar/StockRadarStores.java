package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.Family;
import be.enrosed.stockradar.StockRadar.ManualShop;
import be.enrosed.stockradar.StockRadar.Observation;
import be.enrosed.stockradar.StockRadar.Page;
import be.enrosed.stockradar.StockRadar.Item;
import be.enrosed.stockradar.StockRadar.Shop;
import be.enrosed.stockradar.StockRadar.Watch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.enrosed.stockradar.StockRadar.*;

/**
 * The watched products, the shops and how each page is read. Article numbers
 * were looked up on 2026-10-28 (white iPhone Duo) and 2026-11-15 (blue). A
 * shop that changes its page layout shows up as "Pagina niet herkend" on the
 * radar screen instead of a false alarm.
 *
 * Only shops that answer an ordinary HTTP request are read. MediaMarkt,
 * Krëfel and bol.com only let a real browser through (or refuse the server
 * outright); the first radar ran Chromium for them twice a minute and that is
 * what made the backend grow until each redeploy. They are listed as manual
 * shops: a link, and the shop's own "stuur mij een bericht". Amazon refused
 * that Chromium with "Server Busy" nearly every time; it is now read as any
 * visitor and left alone whenever it refuses, never worked around.
 */
public final class StockRadarStores {

    private StockRadarStores() {}

    public static final Family IPHONE_DUO = new Family("iphone-duo", "iPhone Duo", "wit en blauw · 256 en 512 GB", "📱");

    public static final Item WIT_256 = new Item("wit-256", IPHONE_DUO, "Wit 256 GB", "iPhone Duo wit 256 GB", "Sterrenwit",
            "iPhone Duo · 256 GB · Sterrenwit", "MK2D4ZD/A", new BigDecimal("2339.00"), "256");
    public static final Item WIT_512 = new Item("wit-512", IPHONE_DUO, "Wit 512 GB", "iPhone Duo wit 512 GB", "Sterrenwit",
            "iPhone Duo · 512 GB · Sterrenwit", "MK2F4ZD/A", new BigDecimal("2589.00"), "512");
    public static final Item BLAUW_256 = new Item("blauw-256", IPHONE_DUO, "Blauw 256 GB", "iPhone Duo blauw 256 GB", "Nachthemel",
            "iPhone Duo · 256 GB · Nachthemel (blauw)", "MK2E4ZD/A", new BigDecimal("2339.00"), "256");
    public static final Item BLAUW_512 = new Item("blauw-512", IPHONE_DUO, "Blauw 512 GB", "iPhone Duo blauw 512 GB", "Nachthemel",
            "iPhone Duo · 512 GB · Nachthemel (blauw)", "MK2G4ZD/A", new BigDecimal("2589.00"), "512");
    /** The four iPhones, in the order of the table's columns and of Apple's parts. */
    public static final List<Item> DUO = List.of(WIT_256, WIT_512, BLAUW_256, BLAUW_512);

    public static final Family PS5 = new Family("ps5-pro", "PS5 Pro", "PlayStation 5 Pro · 2 TB", "🎮");
    public static final Item PS5_PRO = new Item("ps5-pro", PS5, "PS5 Pro", "PS5 Pro", null,
            "PlayStation 5 Pro · 2 TB", null, new BigDecimal("899.99"), "");

    public static final Shop APPLE_ONLINE = new Shop("apple-online", "Apple online", "Apple online", "Levering");
    public static final Shop APPLE_BRUSSELS = new Shop("apple-brussels", "Apple Store Brussel", "Apple Brussel", "Afhalen in de winkel");
    public static final Shop COOLBLUE_BE = new Shop("coolblue-be", "Coolblue België", "Coolblue BE", "Levering of winkel");
    public static final Shop COOLBLUE_NL = new Shop("coolblue-nl", "Coolblue Nederland", "Coolblue NL", "Levering of winkel");
    public static final Shop VANDEN_BORRE = new Shop("vandenborre", "Vanden Borre", "Vanden Borre", "Levering of Click&Collect");
    public static final Shop PS_DIRECT = new Shop("ps-direct-be", "PlayStation Direct België", "PS Direct", "Levering");
    public static final Shop AMAZON_BE = new Shop("amazon-be", "Amazon.com.be", "Amazon BE", "Levering");
    public static final Shop AMAZON_DE = new Shop("amazon-de", "Amazon.de", "Amazon DE", "Levering naar België niet bevestigd");
    public static final Shop AMAZON_NL = new Shop("amazon-nl", "Amazon.nl", "Amazon NL", "Levering naar België niet bevestigd");

    /** Apple Store Brussels. */
    public static final String APPLE_STORE = "R486";
    private static final String APPLE_PARTS = partsQuery();
    private static final String APPLE_ORDER = "https://www.apple.com/be-nl/shop/buy-iphone/iphone-duo";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Coolblue's article numbers (the same in Belgium and the Netherlands) and Vanden Borre's page names, per iPhone. */
    private record Articles(String coolblue, String coolblueSlug, String vandenBorre, String vandenBorreSku) {}
    private static final Map<Item, Articles> ARTICLES = Map.of(
            WIT_256, new Articles("986123", "apple-iphone-duo-256gb-wit", "iphone-duo-256gb-sw", "7592493"),
            WIT_512, new Articles("986125", "apple-iphone-duo-512gb-wit", "iphone-duo-512gb-sw", "7592418"),
            BLAUW_256, new Articles("986124", "apple-iphone-duo-256gb-donkerblauw", "iphone-duo-256gb-ns", "7592558"),
            BLAUW_512, new Articles("986126", "apple-iphone-duo-512gb-donkerblauw", "iphone-duo-512gb-ns", "7592442"));

    /** PlayStation Direct moved into playstation.com in 2025; direct.playstation.com answers with a 301 to this page. */
    private static final String PS5_PRO_PAGE = "https://www.playstation.com/nl-be/ps5/ps5-pro/buy-now/";
    /** The console's own SKU on that page (PS5 Pro 2 TB, Belgium). */
    private static final String PS5_PRO_SKU = "PSD001-PSDE01942_39-PS5PROCHASASIRSE-E000K3A7V";
    /** Coolblue's PS5 Pro (Digital Edition, the console itself), the same number in Belgium and the Netherlands. */
    private static final String PS5_PRO_COOLBLUE = "954519";
    /** Vanden Borre has no PS5 Pro page while it has none; this listing fills itself when stock arrives (2026-11-15). */
    private static final String PS5_PRO_VANDEN_BORRE = "https://www.vandenborre.be/gaming/spelconsole/sony-playstation-5-pro";

    /**
     * Amazon's ASINs for the four iPhones, shared by amazon.com.be, .nl and .de
     * (amazon.fr has its own). On 2026-11-15 amazon.com.be listed only the 2 TB
     * (the white 256 GB address showed it), amazon.nl the 256 GB, amazon.de all
     * eight variants; Amazon hides sold-out variants from its lists.
     */
    private static final Map<Item, String> AMAZON_ASINS = Map.of(
            WIT_256, "B0HJB16BGD", WIT_512, "B0HJB7WK3H", BLAUW_256, "B0HJB23TJP", BLAUW_512, "B0HJ9QHDLH");

    public static final List<Family> FAMILIES = List.of(IPHONE_DUO, PS5);
    public static final List<Item> ITEMS = java.util.stream.Stream.concat(DUO.stream(), java.util.stream.Stream.of(PS5_PRO)).toList();

    /** Family by family, shop by shop, item by item: the order of the screen's tables. */
    public static final List<Watch> WATCHES = watches();

    /** Every shop that is read, in the order of the tables. */
    public static final List<Shop> SHOPS = WATCHES.stream().map(Watch::shop).distinct().toList();

    public static final List<ManualShop> MANUAL = List.of(
            new ManualShop("mediamarkt-be", IPHONE_DUO.key(), "MediaMarkt België", "Weigert de server altijd (HTTP 403)",
                    "https://www.mediamarkt.be/nl/product/_apple-iphone-duo-5g-256-gb-esim-star-white-2253422.html"),
            new ManualShop("mediamarkt-nl", IPHONE_DUO.key(), "MediaMarkt Nederland", "Laat alleen een echte browser binnen",
                    "https://www.mediamarkt.nl/nl/product/_apple-iphone-duo-5g-256-gb-esim-star-white-256-gb-star-white-1914026.html"),
            new ManualShop("krefel", IPHONE_DUO.key(), "Krëfel", "Laat alleen een echte browser binnen",
                    "https://www.krefel.be/nl/p/51008017-apple-iphone-duo-256gb-star-white"),
            new ManualShop("bol", IPHONE_DUO.key(), "bol.com", "Weigert de server altijd (HTTP 403); zet daar 'Stuur mij een bericht' aan",
                    "https://www.bol.com/be/nl/p/apple-iphone-duo-256gb-star-white/9300000386857691/"),
            new ManualShop("amazon-fr", IPHONE_DUO.key(), "Amazon.fr", "Eigen artikelnummers; levert gratis in Frankrijk, België niet bevestigd",
                    "https://www.amazon.fr/dp/B0HJB3F7WB"),
            new ManualShop("mediamarkt-be-ps5", PS5.key(), "MediaMarkt België", "Weigert de server altijd (HTTP 403)",
                    "https://www.mediamarkt.be/nl/product/_playstation-playstationr5-pro-2202405.html"),
            new ManualShop("krefel-ps5", PS5.key(), "Krëfel", "Laat alleen een echte browser binnen",
                    "https://www.krefel.be/nl/p/43001107-playstation-5-pro-console"),
            new ManualShop("bol-ps5", PS5.key(), "bol.com", "Weigert de server altijd (HTTP 403); zet daar 'Stuur mij een bericht' aan",
                    "https://www.bol.com/be/nl/s/?searchtext=ps5+pro"));

    private static String partsQuery() {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < DUO.size(); i++) query.append(i == 0 ? "" : "&").append("parts.").append(i).append('=').append(DUO.get(i).applePart());
        return query.toString();
    }

    private static List<Watch> watches() {
        List<Watch> list = new ArrayList<>();
        String pickup = "https://www.apple.com/be-nl/shop/retail/pickup-message?pl=true&" + APPLE_PARTS + "&location=1000";
        String delivery = "https://www.apple.com/be-nl/shop/delivery-message?" + APPLE_PARTS + "&mt=regular";
        for (Item item : DUO) list.add(watch(APPLE_ONLINE, item, delivery, APPLE_ORDER, (page, today) -> appleDelivery(page, today, item), null));
        for (Item item : DUO) list.add(watch(APPLE_BRUSSELS, item, pickup, APPLE_ORDER, (page, today) -> applePickup(page, today, item), null));
        // Coolblue's pages take turns, one page a minute per country (the four iPhones and the PS5 Pro):
        // four pages a minute brought its robot check within minutes.
        for (Item item : DUO) {
            Articles articles = ARTICLES.get(item);
            String url = "https://www.coolblue.be/nl/product/" + articles.coolblue() + "/" + articles.coolblueSlug() + ".html";
            list.add(watch(COOLBLUE_BE, item, url, url, (page, today) -> coolblue(page, today, item, articles.coolblue()), COOLBLUE_BE.key()));
        }
        for (Item item : DUO) {
            Articles articles = ARTICLES.get(item);
            String url = "https://www.coolblue.nl/product/" + articles.coolblue() + "/" + articles.coolblueSlug() + ".html";
            list.add(watch(COOLBLUE_NL, item, url, url, (page, today) -> coolblue(page, today, item, articles.coolblue()), COOLBLUE_NL.key()));
        }
        for (Item item : DUO) {
            Articles articles = ARTICLES.get(item);
            String url = "https://www.vandenborre.be/smartphone/" + articles.vandenBorre();
            list.add(watch(VANDEN_BORRE, item, url, url, (page, today) -> vandenBorre(page, today, item, articles.vandenBorreSku()), null));
        }
        // Amazon: one page a minute per store, the four iPhones in turn; a store that answers "Server Busy" is left alone for a while.
        for (Item item : DUO) {
            String asin = AMAZON_ASINS.get(item);
            String url = "https://www.amazon.com.be/dp/" + asin + "?language=nl_BE";
            list.add(watch(AMAZON_BE, item, url, url, (page, today) -> amazon(page, today, item, asin), AMAZON_BE.key()));
        }
        for (Item item : DUO) {
            String asin = AMAZON_ASINS.get(item);
            String url = "https://www.amazon.de/dp/" + asin;
            list.add(watch(AMAZON_DE, item, url, url, (page, today) -> amazon(page, today, item, asin), AMAZON_DE.key()));
        }
        // Emre, 2026-11-15: "Amazon.nl … volg ook". Its list showed only the 256 GB (Amazon hides sold-out variants;
        // a verified Dutch purchase of the blue 512 GB exists), so all four are watched.
        for (Item item : DUO) {
            String asin = AMAZON_ASINS.get(item);
            String url = "https://www.amazon.nl/dp/" + asin;
            list.add(watch(AMAZON_NL, item, url, url, (page, today) -> amazon(page, today, item, asin), AMAZON_NL.key()));
        }
        list.add(watch(PS_DIRECT, PS5_PRO, PS5_PRO_PAGE, PS5_PRO_PAGE, (page, today) -> playStation(page, PS5_PRO, PS5_PRO_SKU), null));
        String ps5BE = "https://www.coolblue.be/nl/product/" + PS5_PRO_COOLBLUE + "/playstation-5-pro-digital-edition.html";
        String ps5NL = "https://www.coolblue.nl/product/" + PS5_PRO_COOLBLUE + "/playstation-5-pro-digital-edition.html";
        list.add(watch(COOLBLUE_BE, PS5_PRO, ps5BE, ps5BE, (page, today) -> coolblue(page, today, PS5_PRO, PS5_PRO_COOLBLUE), COOLBLUE_BE.key()));
        list.add(watch(COOLBLUE_NL, PS5_PRO, ps5NL, ps5NL, (page, today) -> coolblue(page, today, PS5_PRO, PS5_PRO_COOLBLUE), COOLBLUE_NL.key()));
        list.add(watch(VANDEN_BORRE, PS5_PRO, PS5_PRO_VANDEN_BORRE, PS5_PRO_VANDEN_BORRE, (page, today) -> vandenBorreListing(page, PS5_PRO), null));
        return List.copyOf(list);
    }

    private static Watch watch(Shop shop, Item item, String url, String orderUrl, StockRadar.Checker checker, String group) {
        return new Watch(shop.key() + ":" + item.key(), shop, item, url, orderUrl, checker, group);
    }

    /**
     * The online state Coolblue gives every colour and size on any of the
     * iPhone's pages ("InStock", "BackOrder", "TemporaryOutOfStock"), by item
     * key. No date and no store stock: an iPhone that turns InStock here gets
     * its own page read at once.
     */
    public static Map<String, String> coolblueVariants(String html) {
        Map<String, String> states = new java.util.HashMap<>();
        if (html == null) return states;
        String text = html.replace("\\\"", "\"");
        for (Map.Entry<Item, Articles> entry : ARTICLES.entrySet()) {
            String state = firstGroup(Pattern.compile("\"id\":" + entry.getValue().coolblue() + ",\"name\":\"[^\"]{0,120}\",\"availabilityState\":\"(\\w+)\""), text);
            if (state != null) states.put(entry.getKey().key(), state);
        }
        return states;
    }

    /** The shops of one family's table, in order. */
    public static List<Shop> shopsOf(Family family) {
        return WATCHES.stream().filter(watch -> watch.item().family().equals(family)).map(Watch::shop).distinct().toList();
    }

    public static List<Item> itemsOf(Family family) {
        return ITEMS.stream().filter(item -> item.family().equals(family)).toList();
    }

    public static Watch byKey(String key) {
        for (Watch watch : WATCHES) if (watch.key().equals(key)) return watch;
        return null;
    }

    public static Shop shopByKey(String key) {
        for (Shop shop : SHOPS) if (shop.key().equals(key)) return shop;
        return null;
    }

    // ------------------------------------------------------------------ Apple

    /** apple.com retail pickup-message: per store the pickupDisplay of each part. */
    static Observation applePickup(Page page, LocalDate today, Item item) {
        JsonNode root = json(page.html());
        JsonNode chosen = null;
        for (JsonNode store : root.path("body").path("stores")) {
            if (APPLE_STORE.equals(store.path("storeNumber").asText())) { chosen = store; break; }
        }
        if (chosen == null) {
            String error = root.path("body").path("errorMessage").asText("");
            throw new IllegalStateException(error.isBlank() ? "Apple gaf geen gegevens voor Apple Store Brussel" : stripTags(error));
        }
        JsonNode part = chosen.path("partsAvailability").path(item.applePart());
        if (part.isMissingNode()) throw new IllegalStateException("Apple kent het artikelnummer niet (" + item.applePart() + ")");
        boolean in = "available".equalsIgnoreCase(part.path("pickupDisplay").asText(""));
        String quote = stripTags(part.path("pickupSearchQuote").asText(""));
        String text = quote.isBlank() ? (in ? "Beschikbaar voor afhalen" : "Momenteel niet beschikbaar") : quote;
        // "Beschikbaar za 29 nov": Apple can say available for a later day (reservations, stock on its way).
        LocalDate by = relative(quote, today);
        if (by == null) by = dayMonth(quote, today);
        if (by == null) by = today;
        return new Observation(in, in ? by : null, text, item.listPrice());
    }

    /** apple.com delivery-message: the first delivery option carries the promised date as yyyyMMdd. */
    static Observation appleDelivery(Page page, LocalDate today, Item item) {
        JsonNode root = json(page.html());
        JsonNode regular = root.path("body").path("content").path("deliveryMessage").path(item.applePart()).path("regular");
        if (regular.isMissingNode()) throw new IllegalStateException("Apple gaf geen leverinformatie voor " + item.applePart());
        boolean buyable = regular.path("isBuyable").asBoolean(regular.path("buyability").path("isBuyable").asBoolean(true));
        LocalDate by = yyyyMmDd(regular.path("deliveryOptionMessages").path(0).path("encodedUpperDateString").asText(""));
        String sticky = stripTags(regular.path("stickyMessageSTH").asText(""));
        String option = stripTags(regular.path("deliveryOptionMessages").path(0).path("displayName").asText(""));
        String text = !sticky.isBlank() ? sticky : !option.isBlank() ? "Levering " + option : by == null ? "Geen leverdatum" : "Levering " + by;
        if (!buyable) text = "Niet te bestellen · " + text;
        return new Observation(buyable && by != null, by, text, item.listPrice());
    }

    // --------------------------------------------------------------- Coolblue

    private static final Pattern CB_AVAILABILITY = Pattern.compile("\"availability\":\"https://schema.org/(\\w+)\"");
    private static final Pattern CB_SKU = Pattern.compile("\"@type\":\"Product\"[^<]{0,2000}?\"sku\":\"?(\\d+)");
    private static final Pattern CB_OFFER_PRICE = Pattern.compile("\"@type\":\"Offer\",\"priceCurrency\":\"EUR\",\"price\":\"?([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern CB_FROM = Pattern.compile("(?:Geleverd|Bezorgd) vanaf ([0-9]{1,2} [a-z]+)");
    private static final Pattern CB_PROMISE = Pattern.compile("\"promise\":\"([^\"$][^\"]{2,80})\"");
    private static final Pattern CB_STORES = Pattern.compile("\"storeAvailabilities\":\\[(.*?)]", Pattern.DOTALL);
    /** A location's state and, when Coolblue gives it, the day the store starts taking orders (new stores show stock before they open). */
    private static final Pattern CB_STORE = Pattern.compile("\"stockLocationId\":\"(\\d+)\",\"availabilityState\":\"(\\w+)\"(?:,\"store\":\\{\"storeDates\":\\{\"orderStartDate\":\"(\\d{4}-\\d{2}-\\d{2})\")?");
    private static final Pattern CB_PICKUP = Pattern.compile("\"availableForPickup\":\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern CB_PICKUP_STORE = Pattern.compile("\"stockLocationId\":\"(\\d+)\",\"isAvailableForPickup\":true");
    /** Coolblue's stock location numbers, from its store pages (2026-10-31). */
    private static final Map<String, String> CB_STORE_NAMES = Map.ofEntries(
            Map.entry("373", "Brugge"), Map.entry("490", "Drogenbos"), Map.entry("215", "Gent"), Map.entry("558", "Gosselies"),
            Map.entry("271", "Hasselt"), Map.entry("408", "Hognoul"), Map.entry("307", "Kuurne"), Map.entry("369", "Mechelen"),
            Map.entry("380", "Olen"), Map.entry("430", "Schoten"), Map.entry("303", "Wilrijk"), Map.entry("152", "Zaventem"),
            Map.entry("470", "Alkmaar"), Map.entry("283", "Almere"), Map.entry("398", "Amsterdam West"), Map.entry("141", "Amsterdam Zuidas"),
            Map.entry("550", "Apeldoorn"), Map.entry("219", "Arnhem"), Map.entry("384", "Breda"), Map.entry("518", "Cruquius"),
            Map.entry("349", "Den Bosch"), Map.entry("176", "Den Haag"), Map.entry("121", "Eindhoven"), Map.entry("538", "Ekkersrijt"),
            Map.entry("327", "Groningen"), Map.entry("211", "Haarlem"), Map.entry("390", "Hengelo"), Map.entry("394", "Leeuwarden"),
            Map.entry("522", "Leiden"), Map.entry("458", "Middelburg"), Map.entry("319", "Nijmegen"), Map.entry("299", "Rotterdam Alexandrium"),
            Map.entry("404", "Rotterdam Centrum"), Map.entry("554", "Sittard-Geleen"), Map.entry("190", "Tilburg"), Map.entry("365", "Utrecht"));

    /**
     * The JSON-LD offer says InStock / BackOrder / PreOrder / OutOfStock; the
     * product's "promise" field adds the date. Never the page's loose words:
     * its translation table always holds "Morgen bezorgd" and "morgen in huis".
     * Without JSON-LD (Coolblue serves such a page now and then) the product's
     * own availabilityState in the page data stands in. Stock in a store ("Nog
     * sneller op te halen in 1 winkel") is on the page per location and counts
     * on its own, whatever the online promise says.
     */
    static Observation coolblue(Page page, LocalDate today, Item item, String article) {
        String html = page.html() == null ? "" : page.html();
        String text = html.replace("\\\"", "\"");
        // The page must be the watched article: a delisted product can land on another one.
        String ldSku = firstGroup(CB_SKU, html);
        if (ldSku != null && !ldSku.equals(article)) {
            return new Observation(false, null, "Coolblue toont een ander artikel (" + ldSku + ")", null);
        }
        String availability = firstGroup(CB_AVAILABILITY, html);
        if (availability == null) {
            // Without JSON-LD: the product's own object in the page data; other keys may sit between its name and state.
            String state = firstGroup(Pattern.compile("\"id\":" + article + ",\"name\":\"[^\"]{0,160}\"(?:,\"[A-Za-z]+\":(?:\"[^\"]{0,200}\"|true|false|null|-?\\d+))*?,\"availabilityState\":\"(\\w+)\""), text);
            if (state == null) {
                if (looksBlocked(html)) throw new FetchFailure("Coolblue weigert de controle: " + pageTitle(html), true);
                String title = pageTitle(html);
                throw new IllegalStateException("Pagina niet herkend (geen aanbod)" + (title.isBlank() ? "" : ": " + title));
            }
            availability = switch (state) {
                case "OnStock", "InStock" -> "InStock";
                case "TemporaryOutOfStock" -> "OutOfStock";
                default -> state;
            };
        }
        String promise = firstGroup(CB_PROMISE, text);
        LocalDate by = promise == null ? null : dayMonth(firstGroup(CB_FROM, promise), today);
        if (by == null) by = relative(promise, today);
        boolean in = switch (availability) {
            case "InStock" -> true;
            case "BackOrder", "PreOrder", "LimitedAvailability" -> by != null;
            default -> false;
        };
        String label = switch (availability) {
            case "InStock" -> "Op voorraad" + (by == null ? "" : " · geleverd vanaf " + day(by));
            case "BackOrder" -> by == null ? "Nabestelling zonder datum" : "Geleverd vanaf " + day(by);
            case "PreOrder" -> by == null ? "Pre-order zonder datum" : "Pre-order · geleverd vanaf " + day(by);
            case "OutOfStock", "SoldOut" -> "Tijdelijk uitverkocht";
            case "Discontinued" -> "Niet meer leverbaar";
            default -> availability + (by == null ? "" : " · " + day(by));
        };
        BigDecimal price = euro(firstGroup(CB_OFFER_PRICE, html));
        if (price == null) price = item.listPrice();
        String storeRows = firstGroup(CB_STORES, text);
        if (storeRows == null) return new Observation(in, by, label, price);
        List<String> stocked = coolblueStores(storeRows, firstGroup(CB_PICKUP, text), today);
        if (stocked.isEmpty()) return new Observation(in, by, label, price);
        String where = "In " + stocked.size() + (stocked.size() == 1 ? " winkel" : " winkels") + " op voorraad: " + String.join(", ", stocked);
        return new Observation(true, today, where + " · online: " + label, price, stocked);
    }

    /**
     * The stores with stock where it can be picked up today. When the page has
     * the product's pickup list, a location counts only if it is on it: closed
     * or non-shop locations (BE 234, NL 242) and stores not open yet (NL 550,
     * Apeldoorn, showed a PS5 Pro "OnStock" the day before it opened on
     * 2026-11-16) are not. A store whose first order day is still ahead never
     * counts. Without a pickup list only the named stores count.
     */
    private static List<String> coolblueStores(String storeRows, String pickupRows, LocalDate today) {
        Set<String> pickup = new java.util.HashSet<>();
        Matcher takesPickup = CB_PICKUP_STORE.matcher(pickupRows == null ? "" : pickupRows);
        while (takesPickup.find()) pickup.add(takesPickup.group(1));
        List<String> stocked = new ArrayList<>();
        Matcher row = CB_STORE.matcher(storeRows);
        while (row.find()) {
            if (!"OnStock".equals(row.group(2))) continue;
            if (row.group(3) != null && LocalDate.parse(row.group(3)).isAfter(today)) continue;
            String name = CB_STORE_NAMES.get(row.group(1));
            if (pickupRows != null && !pickup.contains(row.group(1))) continue;
            if (name != null) stocked.add(name);
            else if (pickup.contains(row.group(1))) stocked.add("winkel " + row.group(1));
        }
        return stocked;
    }

    // ----------------------------------------------------------- Vanden Borre

    private static final Pattern VDB_LD = Pattern.compile("(?s)<script type=\"application/ld\\+json\">(.*?)</script>");
    private static final Pattern VDB_LD_AVAILABILITY = Pattern.compile("\"availability\"\\s*:\\s*\"https?://schema.org/(\\w+)\"");
    private static final Pattern VDB_LD_PRICE = Pattern.compile("\"price\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern VDB_HOME = Pattern.compile("Levering aan huis\\s*:\\s*(.{2,60}?)\\s+(?:Gratis|Koop nu|Afhaling|€|Express)");
    /** "binnen 30 min.", "binnen 1 à 2 dagen", "binnen max. 2 dagen", "vanaf 17.11.2026": inner dots allowed, a closing one dropped. */
    private static final Pattern VDB_COLLECT = Pattern.compile("winkels\\s+((?:binnen|na|vanaf)\\s.{2,40}?|vandaag|morgen|overmorgen)\\.?\\s+(?:Toon alle|Afhaalpunt|Gratis|Koop nu)");
    private static final Pattern VDB_COLLECT_FAST = Pattern.compile("Click&Collect\\s*:?\\s*(?:Gratis\\s*)?((?:na|binnen) \\d+ min|vandaag|morgen)");
    private static final Pattern VDB_POINT = Pattern.compile("Afhaalpunt\\s*:\\s*(.{2,50}?)\\s+(?:Gratis|Koop nu|Stuur)");
    private static final Pattern VDB_UNSURE = Pattern.compile("(Levering in \\d{4} niet zeker)");

    /**
     * The product's own availability section, server-rendered: "Levering aan
     * huis : binnen minstens 3 weken", "Haal je bestelling af in een van onze 71
     * winkels binnen minstens 3 weken", "Afhaalpunt : …", and "Stuur mij bericht
     * bij voorraad" while nothing is in. The rest of the page holds other
     * products with the same words ("Beschikbaar - Bekijk voorraad"): never read
     * outside the section. The JSON-LD offer of the page's own article adds
     * InStock / BackOrder and the price.
     */
    static Observation vandenBorre(Page page, LocalDate today, Item item, String sku) {
        String html = page.html() == null ? "" : page.html();
        String offer = null;
        Matcher ld = VDB_LD.matcher(html);
        Pattern ownSku = Pattern.compile("\"sku\"\\s*:\\s*\"" + sku + "\"");
        while (ld.find()) {
            if (ownSku.matcher(ld.group(1)).find()) { offer = ld.group(1); break; }
        }
        int start = html.indexOf("id=\"availability\"");
        if (offer == null || start < 0) {
            if (looksBlocked(html)) throw new FetchFailure("Vanden Borre weigert de controle: " + pageTitle(html), true);
            throw new IllegalStateException("Pagina niet herkend (geen beschikbaarheid voor sku " + sku + "): " + pageTitle(html));
        }
        int end = html.indexOf("one-pager-section", start + 40);
        String section = stripTags(html.substring(start, end < 0 ? Math.min(html.length(), start + 12_000) : end));
        String availability = firstGroup(VDB_LD_AVAILABILITY, offer);
        String home = firstGroup(VDB_HOME, section);
        String collect = firstGroup(VDB_COLLECT_FAST, section);
        if (collect == null) collect = firstGroup(VDB_COLLECT, section);
        String point = firstGroup(VDB_POINT, section);
        if (availability == null && home == null && collect == null && point == null) throw new IllegalStateException("Pagina niet herkend (geen leverstatus)");
        LocalDate homeBy = dated(home, today);
        LocalDate collectBy = dated(collect, today);
        LocalDate pointBy = dated(point, today);
        LocalDate by = earliest(earliest(homeBy, collectBy), pointBy);
        // A delivery text the reader cannot date ("binnen max. 10 dagen" is read; a new wording is not) never means "now".
        boolean unread = by == null && (home != null || collect != null || point != null);
        boolean ldInStock = "InStock".equals(availability) || "LimitedAvailability".equals(availability);
        boolean gone = "OutOfStock".equals(availability) || "SoldOut".equals(availability) || "Discontinued".equals(availability);
        boolean in = !gone && (ldInStock || by != null);
        List<String> parts = new ArrayList<>();
        if (ldInStock) parts.add("Op voorraad");
        if (home != null) parts.add("levering " + home.trim());
        if (collect != null) parts.add("afhalen " + collect.trim());
        else if (point != null && !point.trim().equals(home == null ? "" : home.trim())) parts.add("afhaalpunt " + point.trim());
        String unsure = firstGroup(VDB_UNSURE, html);
        if (unsure != null) parts.add(unsure.toLowerCase(Locale.ROOT));
        if (parts.isEmpty() || (!ldInStock && home == null && collect == null && point == null)) parts.add(0, gone ? "Niet op voorraad" : "Niet leverbaar");
        if (unread && in) parts.add("levertermijn niet gelezen");
        String label = String.join(" · ", parts);
        label = label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
        BigDecimal price = euro(firstGroup(VDB_LD_PRICE, offer));
        return new Observation(in, by, label, price == null ? item.listPrice() : price, List.of(), unread && in);
    }

    private static final Pattern VDB_ITEM_NAME = Pattern.compile("\"@type\"\\s*:\\s*\"Product\",\\s*\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Vanden Borre's listing page for one product (it drops the product page
     * while it has none): "Momenteel hebben we helaas geen voorraad beschikbaar"
     * while empty, an ItemList JSON-LD of Products with offers.availability once
     * stock arrives. Only items whose name is the product count (a bundle with a
     * game does), never other consoles.
     */
    static Observation vandenBorreListing(Page page, Item item) {
        String html = page.html() == null ? "" : page.html();
        if (html.contains("Momenteel hebben we helaas geen voorraad beschikbaar")) {
            return new Observation(false, null, "Geen voorraad: de lijst is leeg", null);
        }
        String list = null;
        Matcher ld = VDB_LD.matcher(html);
        while (ld.find()) if (ld.group(1).contains("\"ItemList\"")) { list = ld.group(1); break; }
        if (list == null) {
            if (looksBlocked(html)) throw new FetchFailure("Vanden Borre weigert de controle: " + pageTitle(html), true);
            throw new IllegalStateException("Pagina niet herkend (geen productlijst): " + pageTitle(html));
        }
        String needle = item.label().toLowerCase(Locale.ROOT).replace("ps5", "playstation 5");
        List<String> inStock = new ArrayList<>();
        BigDecimal price = null;
        int listed = 0;
        // One ListItem at a time, so a name is never paired with the next product's price or stock.
        for (String entry : list.split("\"@type\"\\s*:\\s*\"ListItem\"")) {
            String name = firstGroup(VDB_ITEM_NAME, entry);
            if (name == null || !name.toLowerCase(Locale.ROOT).replace("ps5", "playstation 5").contains(needle)) continue;
            listed++;
            String state = firstGroup(VDB_LD_AVAILABILITY, entry);
            if ("InStock".equals(state) || "LimitedAvailability".equals(state)) {
                inStock.add(name.trim());
                BigDecimal each = euro(firstGroup(VDB_LD_PRICE, entry));
                if (each != null && (price == null || each.compareTo(price) < 0)) price = each;
            }
        }
        if (!inStock.isEmpty()) return new Observation(true, null, "Op voorraad: " + String.join(", ", inStock), price);
        return new Observation(false, null, listed == 0 ? "Niet in de lijst" : listed + " in de lijst, geen op voorraad", null);
    }

    // ----------------------------------------------------- PlayStation Direct

    private static final Pattern PS_LD = Pattern.compile("(?s)<script id=\"pdp-product-jsonld\" type=\"application/ld\\+json\">(.*?)</script>");
    private static final Pattern PS_LD_SKU = Pattern.compile("\"sku\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PS_LD_AVAILABILITY = Pattern.compile("\"availability\"\\s*:\\s*\"https?://schema.org/(\\w+)\"");
    private static final Pattern PS_LD_PRICE = Pattern.compile("\"price\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)");
    /** The sku entry with a displayPrice (the product's own sku list), up to its stockLevelStatus: never another product's level. */
    private static final Pattern PS_LEVEL = Pattern.compile("^\"id\":\"[^\"]+\",\"displayPrice\"[\\s\\S]{0,1200}?\"stockLevelStatus\":\"([A-Z_]+)\"");

    /**
     * playstation.com's product page, server-rendered: the JSON-LD block
     * "pdp-product-jsonld" names the console's own SKU, price and
     * schema.org availability; the page data repeats the SKU with its
     * "stockLevelStatus" (OUT_OF_STOCK, IN_STOCK, LOW_STOCK). Both are read
     * for the SKU itself, never for other products on the page (PS Plus
     * offers, accessories). In stock needs both to agree (the page is a render
     * up to ~4 min old; one marker alone is not trusted). A Queue-it waiting
     * room instead of the page usually means a sale is on: that is reported, so
     * Emre can join the queue himself; the radar never tries to pass it.
     */
    static Observation playStation(Page page, Item item, String sku) {
        String html = page.html() == null ? "" : page.html();
        String ld = firstGroup(PS_LD, html);
        if (ld == null) {
            // Not the product page: Queue-it's own waiting room (its host or its form fields), never a word somewhere in a page.
            if (html.contains(".queue-it.net/") || html.contains("queueit_") || pageTitle(html).toLowerCase(Locale.ROOT).contains("queue-it")) {
                return new Observation(true, null, "Wachtrij actief bij PlayStation Direct (waarschijnlijk verkoop)", item.listPrice());
            }
            if (looksBlocked(html)) throw new FetchFailure("PlayStation weigert de controle: " + pageTitle(html), true);
            throw new IllegalStateException("Pagina niet herkend (geen productgegevens): " + pageTitle(html));
        }
        String ldSku = firstGroup(PS_LD_SKU, ld);
        if (ldSku == null || !ldSku.equals(sku)) throw new IllegalStateException("Pagina niet herkend (ander artikel: " + ldSku + ")");
        String availability = firstGroup(PS_LD_AVAILABILITY, ld);
        // The page data escapes its quotes: \"id\":\"<sku>\" ... \"stockLevelStatus\":\"OUT_OF_STOCK\"
        String data = html.replace("\\\"", "\"");
        // The SKU's id appears several times (cache entries, buttons); the stock level follows the one in the product's sku list.
        String level = null;
        String id = "\"id\":\"" + sku + "\"";
        for (int at = data.indexOf(id); at >= 0 && level == null; at = data.indexOf(id, at + id.length())) {
            level = firstGroup(PS_LEVEL, data.substring(at, Math.min(data.length(), at + 1500)));
        }
        boolean ldIn = "InStock".equals(availability) || "LimitedAvailability".equals(availability);
        boolean preOrder = "PreOrder".equals(availability) || "PRE_ORDER".equals(level);
        boolean in = ldIn && !"OUT_OF_STOCK".equals(level) && !preOrder;
        String label = in ? ("LOW_STOCK".equals(level) ? "Op voorraad (nog maar een paar)" : "Op voorraad")
                : preOrder ? "Pre-order zonder leverdatum"
                : availability == null && level == null ? "Geen voorraadstatus" : "Uitverkocht";
        BigDecimal price = euro(firstGroup(PS_LD_PRICE, ld));
        return new Observation(in, null, label, price == null ? item.listPrice() : price);
    }

    // ------------------------------------------------------------------ Amazon

    private static final Pattern AMZ_ASIN = Pattern.compile("<input type=\"hidden\" id=\"ASIN\" name=\"ASIN\" value=\"([A-Z0-9]{10})\"");
    private static final Pattern AMZ_AVAILABILITY = Pattern.compile("(?s)<div id=\"availability\"[^>]*>(.*?)</div>");
    private static final Pattern AMZ_DELIVERY = Pattern.compile("data-csa-c-delivery-time=\"([^\"]+)\"");
    /** The whole euros as the store writes them: "2.589," (nl, de) or "2 589," with a (narrow) no-break space (fr). */
    private static final Pattern AMZ_PRICE_WHOLE = Pattern.compile("<span class=\"a-price-whole\">([0-9][0-9.,\\u00a0\\u202f ]*)");
    private static final Pattern AMZ_PRICE_FRACTION = Pattern.compile("<span class=\"a-price-fraction\">([0-9]{2})");
    /** The seller's name: the text element of the buy box's merchant row, not its label ("Verzender / Verkoper"). */
    private static final Pattern AMZ_SELLER = Pattern.compile("(?s)class=\"offer-display-feature-text[^\"]*\"\\s+offer-display-feature-name=\"desktop-merchant-info\"[^>]*>(.{0,600}?)</span>");
    private static final Pattern AMZ_VARIANTS = Pattern.compile("\"dimensionValuesDisplayData\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern AMZ_VARIANT = Pattern.compile("\"([A-Z0-9]{10})\":\\[\"([^\"]*)\",\"([^\"]*)\"\\]");

    /**
     * An Amazon product page read as any visitor gets it. The page must be the
     * watched ASIN itself: when Amazon takes a variant offline it shows another
     * one under the old address (2026-11-15: the white 256 GB showed the 2 TB),
     * and that is "niet aangeboden", never the other variant's stock. "Server
     * Busy" and robot checks are refusals: waited out, never worked around.
     */
    static Observation amazon(Page page, LocalDate today, Item item, String asin) {
        String html = page.html() == null ? "" : page.html();
        String title = pageTitle(html);
        String shown = firstGroup(AMZ_ASIN, html);
        if (shown == null) {
            // No product page: Amazon's gate ("Server Busy", a captcha, or the interstitial titled just "Amazon.de" or
            // "Amazon.nl" that Railway's address gets, seen 2026-11-15). A refusal: waited out, never worked around.
            throw new FetchFailure("Amazon weigert even (" + (title.isBlank() ? "Server Busy" : title) + ")", true);
        }
        if (!containsAny(html, "iPhone Duo")) throw new IllegalStateException("Pagina niet herkend: " + title);
        if (!shown.equals(asin)) {
            // Amazon hides sold-out variants from the list and shows another one under their address (verified Belgian
            // purchases of the blue 256 and 512 GB exist): sold out for now, not "never sold here".
            return new Observation(false, null, "Nu niet te koop: Amazon toont " + amazonVariants(html), null);
        }
        String availability = stripTags(firstGroup(AMZ_AVAILABILITY, html));
        String lower = availability.toLowerCase(Locale.ROOT);
        // Dutch (the store answers in the visitor's language), French on amazon.fr, German and English just in case.
        boolean in = (lower.startsWith("op voorraad") || lower.contains("nog maar") || lower.startsWith("in stock") || lower.startsWith("en stock")
                || lower.contains("plus que") || lower.startsWith("auf lager") || lower.contains("nur noch"))
                && !lower.contains("niet op voorraad") && !lower.contains("rupture");
        // The delivery block can name two dates: the free one and "Of snelste bezorging …"; the earliest counts.
        int block = html.indexOf("mir-layout-DELIVERY_BLOCK");
        String deliveryArea = block < 0 ? html : html.substring(block, Math.min(html.length(), block + 6000));
        String promise = null;
        LocalDate by = null;
        Matcher delivery = AMZ_DELIVERY.matcher(deliveryArea);
        while (delivery.find()) {
            LocalDate date = dated(delivery.group(1), today);
            if (date != null && (by == null || date.isBefore(by))) { by = date; promise = delivery.group(1); }
            if (promise == null) promise = delivery.group(1);
        }
        String seller = stripTags(firstGroup(AMZ_SELLER, html));
        int half = seller.length() / 2;
        if (seller.length() > 2 && seller.length() % 2 == 1 && seller.substring(0, half).equals(seller.substring(half + 1))) seller = seller.substring(0, half);
        StringBuilder label = new StringBuilder(availability.isBlank() ? (in ? "Op voorraad" : "Niet beschikbaar") : availability);
        if (in && promise != null) label.append(" · levering ").append(promise);
        if (in && !seller.isBlank() && !seller.toLowerCase(Locale.ROOT).startsWith("amazon")) label.append(" · verkoper ").append(seller);
        String whole = firstGroup(AMZ_PRICE_WHOLE, html);
        String fraction = firstGroup(AMZ_PRICE_FRACTION, html);
        BigDecimal price = whole == null ? null
                : euro(whole.replaceAll("[.,\\u00a0\\u202f ]", "") + "," + (fraction == null ? "00" : fraction));
        return new Observation(in, in ? by : null, label.toString(), price == null ? item.listPrice() : price);
    }

    /** "2 TB in sterrenwit en nachtelijke hemel": the variants Amazon offers on the page it showed instead. */
    static String amazonVariants(String html) {
        String block = firstGroup(AMZ_VARIANTS, html);
        if (block == null) return "een andere uitvoering";
        Map<String, List<String>> bySize = new java.util.LinkedHashMap<>();
        Matcher variant = AMZ_VARIANT.matcher(block);
        while (variant.find()) bySize.computeIfAbsent(variant.group(3), size -> new ArrayList<>()).add(variant.group(2).toLowerCase(Locale.ROOT));
        if (bySize.isEmpty()) return "een andere uitvoering";
        List<String> parts = new ArrayList<>();
        bySize.forEach((size, colours) -> parts.add(size.replace(" To", " TB").replace(" Go", " GB") + " in " + String.join(" en ", colours)));
        return "alleen " + String.join("; ", parts);
    }

    /** A delivery phrase as a date: "vandaag", "binnen 1 à 2 dagen", "binnen max. 10 dagen", "6 december", "28.11.2026". */
    private static LocalDate dated(String text, LocalDate today) {
        if (text == null) return null;
        LocalDate date = relative(text, today);
        return date != null ? date : dayMonth(text, today);
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    /** "6 dec", the way a status line shows a date. */
    static String day(LocalDate date) {
        return date.getDayOfMonth() + " " + date.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.of("nl", "BE")).replace(".", "");
    }

    private static JsonNode json(String body) {
        try {
            return JSON.readTree(body == null ? "" : body);
        } catch (Exception e) {
            if (containsAny(body, "Page Not Found", "<html")) throw new FetchFailure("Apple gaf een webpagina in plaats van gegevens");
            throw new IllegalStateException("Antwoord is geen JSON");
        }
    }
}
