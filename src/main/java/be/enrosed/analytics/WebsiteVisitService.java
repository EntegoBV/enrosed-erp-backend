package be.enrosed.analytics;

import be.enrosed.analytics.WebsiteAnalyticsDtos.CityRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.CountryRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.DayPoint;
import be.enrosed.analytics.WebsiteAnalyticsDtos.DeviceRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.KindRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.LocaleRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.PageRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.Report;
import be.enrosed.analytics.WebsiteAnalyticsDtos.SourceRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.Totals;
import be.enrosed.analytics.WebsiteAnalyticsDtos.VisitInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Records page views and folds them into the report the Analyses page shows. */
@ApplicationScoped
public class WebsiteVisitService {

    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private static final Duration SESSION_GAP = Duration.ofMinutes(30);
    private static final Pattern VISITOR = Pattern.compile("^[a-f0-9]{16,64}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Set<String> SITE_LOCALES = Set.of("nl", "fr", "de", "es", "pl", "pt", "tr");
    private static final Set<String> DEVICES = Set.of("MOBILE", "TABLET", "DESKTOP");

    /** Stores one page view; false when the beacon is not worth keeping. */
    @Transactional
    public boolean record(VisitInput input) {
        if (input == null) return false;
        String path = cleanPath(input.path());
        String visitor = lower(input.visitor());
        if (path == null || visitor == null || !VISITOR.matcher(visitor).matches()) return false;

        WebsiteVisitEntity visit = new WebsiteVisitEntity();
        visit.occurredAt = Instant.now();
        visit.visitor = visitor;
        visit.path = path;
        visit.pageKind = pageKind(path);
        visit.locale = localeOf(path, input.locale());
        String country = upper(input.country());
        visit.country = country != null && COUNTRY.matcher(country).matches() ? country : null;
        visit.city = trim(input.city(), 80);
        visit.referrerHost = referrerHost(input.referrer());
        visit.source = trim(lower(input.utmSource()), 64);
        visit.medium = trim(lower(input.utmMedium()), 64);
        visit.campaign = trim(input.utmCampaign(), 120);
        visit.screenWidth = input.screenWidth() == null || input.screenWidth() <= 0 || input.screenWidth() > 20_000
                ? null : input.screenWidth();
        String device = upper(input.device());
        visit.device = device != null && DEVICES.contains(device) ? device : deviceFor(visit.screenWidth);
        visit.persist();
        return true;
    }

    public Report report(int requestedDays) {
        int days = Math.max(1, Math.min(requestedDays, 365));
        LocalDate today = LocalDate.now(ZONE);
        LocalDate firstDay = today.minusDays(days - 1L);
        Instant from = firstDay.atStartOfDay(ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<WebsiteVisitEntity> rows = WebsiteVisitEntity.list(
                "occurredAt >= ?1 and occurredAt < ?2 order by visitor, occurredAt", from, to);

        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        Map<LocalDate, Set<String>> visitorsPerDay = new HashMap<>();
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            perDay.put(day, new long[1]);
            visitorsPerDay.put(day, new HashSet<>());
        }
        Map<String, Counter> pages = new HashMap<>();
        Map<String, Counter> kinds = new HashMap<>();
        Map<String, Counter> countries = new HashMap<>();
        Map<String, Counter> cities = new HashMap<>();
        Map<String, Counter> sources = new HashMap<>();
        Map<String, Counter> devices = new HashMap<>();
        Map<String, Counter> locales = new HashMap<>();
        Map<String, String> sourceKinds = new HashMap<>();
        Map<String, String> cityCountries = new HashMap<>();
        int[][] hours = new int[7][24];
        Set<String> visitors = new HashSet<>();
        long sessions = 0;
        String lastVisitor = null;
        Instant lastSeen = null;

        for (WebsiteVisitEntity row : rows) {
            ZonedDateTime at = row.occurredAt.atZone(ZONE);
            LocalDate day = at.toLocalDate();
            long[] count = perDay.get(day);
            if (count != null) count[0]++;
            Set<String> dayVisitors = visitorsPerDay.get(day);
            if (dayVisitors != null) dayVisitors.add(row.visitor);
            visitors.add(row.visitor);
            hours[at.getDayOfWeek().getValue() - 1][at.getHour()]++;
            if (!row.visitor.equals(lastVisitor) || lastSeen == null
                    || Duration.between(lastSeen, row.occurredAt).compareTo(SESSION_GAP) > 0) {
                sessions++;
            }
            lastVisitor = row.visitor;
            lastSeen = row.occurredAt;

            pages.computeIfAbsent(row.path, key -> new Counter()).add(row.visitor);
            kinds.computeIfAbsent(row.pageKind, key -> new Counter()).add(row.visitor);
            countries.computeIfAbsent(row.country == null ? "" : row.country, key -> new Counter()).add(row.visitor);
            if (row.city != null && row.country != null) {
                String key = row.city + "|" + row.country;
                cities.computeIfAbsent(key, k -> new Counter()).add(row.visitor);
                cityCountries.put(key, row.country);
            }
            String[] source = sourceOf(row);
            sources.computeIfAbsent(source[0], key -> new Counter()).add(row.visitor);
            sourceKinds.put(source[0], source[1]);
            devices.computeIfAbsent(row.device, key -> new Counter()).add(row.visitor);
            locales.computeIfAbsent(row.locale == null ? "en" : row.locale, key -> new Counter()).add(row.visitor);
        }

        List<DayPoint> series = new ArrayList<>();
        perDay.forEach((day, count) -> series.add(new DayPoint(day.toString(), count[0],
                visitorsPerDay.get(day).size())));
        long visits = rows.size();
        Totals totals = new Totals(visits, visitors.size(), sessions,
                sessions == 0 ? 0 : Math.round(visits * 10.0 / sessions) / 10.0,
                countries.keySet().stream().filter(code -> !code.isEmpty()).count());
        return new Report(days, firstDay.toString(), today.toString(), totals, series,
                top(pages, 40, (key, counter) -> new PageRow(key, pageKind(key), counter.visits, counter.visitors.size())),
                top(kinds, 10, (key, counter) -> new KindRow(key, counter.visits)),
                top(countries, 40, (key, counter) -> new CountryRow(key.isEmpty() ? null : key, counter.visits, counter.visitors.size())),
                top(cities, 12, (key, counter) -> new CityRow(key.substring(0, key.indexOf('|')), cityCountries.get(key), counter.visits)),
                top(sources, 15, (key, counter) -> new SourceRow(key, sourceKinds.get(key), counter.visits)),
                hours,
                top(devices, 3, (key, counter) -> new DeviceRow(key, counter.visits)),
                top(locales, 8, (key, counter) -> new LocaleRow(key, counter.visits)));
    }

    private static final class Counter {
        long visits;
        final Set<String> visitors = new HashSet<>();

        void add(String visitor) {
            visits++;
            visitors.add(visitor);
        }
    }

    private interface RowMaker<T> {
        T make(String key, Counter counter);
    }

    private static <T> List<T> top(Map<String, Counter> counters, int limit, RowMaker<T> maker) {
        return counters.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Counter> entry) -> entry.getValue().visits).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> maker.make(entry.getKey(), entry.getValue()))
                .toList();
    }

    /* ---- normalisation ------------------------------------------------- */

    static String cleanPath(String raw) {
        if (raw == null) return null;
        String path = raw.strip();
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        if (!path.startsWith("/") || path.length() > 255 || path.chars().anyMatch(Character::isISOControl)) return null;
        return path;
    }

    /** The locale is in the first path segment; English lives at the root. */
    static String localeOf(String path, String hint) {
        String[] parts = path.split("/");
        if (parts.length > 1 && SITE_LOCALES.contains(parts[1])) return parts[1];
        String value = lower(hint);
        if (value != null && value.length() >= 2) {
            String code = value.substring(0, 2);
            if (SITE_LOCALES.contains(code)) return code;
        }
        return "en";
    }

    static String pageKind(String path) {
        String[] parts = path.split("/");
        int start = parts.length > 1 && SITE_LOCALES.contains(parts[1]) ? 2 : 1;
        if (parts.length <= start) return "HOME";
        String head = parts[start];
        boolean deeper = parts.length > start + 1 && !parts[start + 1].isBlank();
        return switch (head) {
            case "products" -> deeper ? "PRODUCT" : "PRODUCTS";
            case "collections" -> "COLLECTION";
            case "quote" -> "QUOTE";
            case "contact" -> "CONTACT";
            case "legal" -> "LEGAL";
            default -> "OTHER";
        };
    }

    static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) return null;
        try {
            String host = URI.create(referrer.strip()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.equals("enrosed.com") || host.endsWith(".enrosed.com") || host.endsWith(".vercel.app")) return null;
            return trim(host, 120);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** A label and its kind: campaigns first, then the search engine or social network behind the host. */
    static String[] sourceOf(WebsiteVisitEntity row) {
        if (row.source != null && !row.source.isBlank()) {
            return new String[] {"Campagne · " + row.source + (row.campaign == null ? "" : " · " + row.campaign), "CAMPAIGN"};
        }
        String host = row.referrerHost;
        if (host == null) return new String[] {"Rechtstreeks", "DIRECT"};
        if (host.contains("google.")) return new String[] {"Google", "SEARCH"};
        if (host.contains("bing.com")) return new String[] {"Bing", "SEARCH"};
        if (host.contains("duckduckgo")) return new String[] {"DuckDuckGo", "SEARCH"};
        if (host.contains("yahoo.")) return new String[] {"Yahoo", "SEARCH"};
        if (host.contains("ecosia")) return new String[] {"Ecosia", "SEARCH"};
        if (host.contains("instagram")) return new String[] {"Instagram", "SOCIAL"};
        if (host.contains("facebook") || host.equals("fb.com") || host.contains("l.facebook")) return new String[] {"Facebook", "SOCIAL"};
        if (host.contains("linkedin") || host.equals("lnkd.in")) return new String[] {"LinkedIn", "SOCIAL"};
        if (host.contains("pinterest")) return new String[] {"Pinterest", "SOCIAL"};
        if (host.contains("tiktok")) return new String[] {"TikTok", "SOCIAL"};
        if (host.contains("youtube") || host.equals("youtu.be")) return new String[] {"YouTube", "SOCIAL"};
        if (host.equals("t.co") || host.contains("twitter") || host.equals("x.com")) return new String[] {"X", "SOCIAL"};
        return new String[] {host, "SITE"};
    }

    static String deviceFor(Integer width) {
        if (width == null) return "DESKTOP";
        if (width < 768) return "MOBILE";
        if (width < 1100) return "TABLET";
        return "DESKTOP";
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String cleaned = value.strip().replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
