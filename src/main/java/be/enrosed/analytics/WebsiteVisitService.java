package be.enrosed.analytics;

import be.enrosed.analytics.WebsiteAnalyticsDtos.CityRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.Compare;
import be.enrosed.analytics.WebsiteAnalyticsDtos.CountryRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.DayPoint;
import be.enrosed.analytics.WebsiteAnalyticsDtos.DeviceRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.Funnel;
import be.enrosed.analytics.WebsiteAnalyticsDtos.HourPoint;
import be.enrosed.analytics.WebsiteAnalyticsDtos.KindRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.LocaleRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.PageRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.Report;
import be.enrosed.analytics.WebsiteAnalyticsDtos.SourceRow;
import be.enrosed.analytics.WebsiteAnalyticsDtos.Totals;
import be.enrosed.analytics.WebsiteAnalyticsDtos.VisitInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.text.Normalizer;
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
    private static final Duration ACTIVE_WINDOW = Duration.ofMinutes(30);
    private static final Pattern VISITOR = Pattern.compile("^[a-f0-9]{16,64}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Set<String> SITE_LOCALES = Set.of("nl", "fr", "de", "es", "pl", "pt", "tr");
    private static final Set<String> DEVICES = Set.of("MOBILE", "TABLET", "DESKTOP");

    /**
     * Our own corner of the Kempen. Visits from these Belgian towns are the
     * team, the warehouse and the family checking the site, not customers,
     * so they are neither stored nor counted.
     */
    private final Set<String> excludedCities;
    private final List<String> excludedCityLabels;
    /**
     * Where the team comes from when it is not at home: the ERP itself and
     * preview deployments. A page opened from there is us checking our work.
     */
    private final Set<String> internalReferrerHosts;

    @Inject
    public WebsiteVisitService(
            @ConfigProperty(name = "enrosed.analytics.excluded-cities",
                    defaultValue = "Tessenderlo,Mol,Balen,Geel,Arendonk,Dessel,Retie") String excludedCities,
            @ConfigProperty(name = "enrosed.analytics.internal-referrer-hosts",
                    defaultValue = "erp.enrosed.com,app.enrosed.com,localhost,127.0.0.1") String internalReferrerHosts) {
        List<String> labels = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (String city : excludedCities.split(",")) {
            String label = city.strip();
            if (label.isEmpty()) continue;
            labels.add(label);
            keys.add(cityKey(label));
        }
        this.excludedCityLabels = List.copyOf(labels);
        this.excludedCities = Set.copyOf(keys);
        Set<String> hosts = new HashSet<>();
        for (String host : internalReferrerHosts.split(",")) {
            String value = host.strip().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) hosts.add(value);
        }
        this.internalReferrerHosts = Set.copyOf(hosts);
    }

    /** Whether the page was reached from the ERP, a preview build or a developer machine. */
    boolean internalReferrer(String referrer) {
        if (referrer == null || referrer.isBlank()) return false;
        try {
            String host = URI.create(referrer.strip()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return internalReferrerHosts.contains(host) || host.endsWith(".vercel.app");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** A Belgian visit from one of our own towns. */
    boolean ownVisit(String country, String city) {
        return "BE".equals(country) && city != null && excludedCities.contains(cityKey(city));
    }

    public List<String> excludedCityLabels() {
        return excludedCityLabels;
    }

    /** "Mol", "MOL" and "Mól" are the same town. */
    static String cityKey(String city) {
        String flat = Normalizer.normalize(city.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return flat.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

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
        /* Accepted, but not kept: the beacon did its job, the number stays honest. */
        if (ownVisit(visit.country, visit.city)) return true;
        if (Boolean.TRUE.equals(input.internal()) || internalReferrer(input.referrer())) return true;
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
        Fold current = fold(firstDay, today);
        Fold previous = fold(firstDay.minusDays(days), firstDay.minusDays(1));
        long activeNow = activeVisitors(Instant.now().minus(ACTIVE_WINDOW));

        List<DayPoint> series = new ArrayList<>();
        current.perDay.forEach((day, count) -> series.add(new DayPoint(day.toString(), count[0],
                current.visitorsPerDay.get(day).size())));
        List<HourPoint> perHour = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            perHour.add(new HourPoint(hour, current.perHourVisits[hour], current.perHourVisitors.get(hour).size()));
        }
        long visits = current.visits;
        long sessions = current.sessions;
        Totals totals = new Totals(visits, current.visitors.size(), sessions,
                sessions == 0 ? 0 : Math.round(visits * 10.0 / sessions) / 10.0,
                current.countries.keySet().stream().filter(code -> !code.isEmpty()).count(),
                sessions == 0 ? 0 : Math.round(current.bounces * 1000.0 / sessions) / 10.0,
                current.timedSessions == 0 ? 0 : current.timedSeconds / current.timedSessions,
                activeNow);
        Compare compare = new Compare(previous.visits, previous.visitors.size(), previous.sessions,
                previous.quoteSessions);
        return new Report(days, firstDay.toString(), today.toString(), totals, compare, series, perHour,
                top(current.pages, 40, (key, counter) -> new PageRow(key, pageKind(key), counter.visits, counter.visitors.size())),
                top(current.kinds, 10, (key, counter) -> new KindRow(key, counter.visits)),
                top(current.countries, 40, (key, counter) -> new CountryRow(key.isEmpty() ? null : key, counter.visits, counter.visitors.size())),
                top(current.cities, 12, (key, counter) -> new CityRow(key.substring(0, key.indexOf('|')), current.cityCountries.get(key), counter.visits)),
                top(current.sources, 15, (key, counter) -> new SourceRow(key, current.sourceKinds.get(key), counter.visits)),
                current.hours,
                top(current.devices, 3, (key, counter) -> new DeviceRow(key, counter.visits)),
                top(current.locales, 8, (key, counter) -> new LocaleRow(key, counter.visits)),
                top(current.entries, 8, (key, counter) -> new PageRow(key, pageKind(key), counter.visits, counter.visitors.size())),
                top(current.exits, 8, (key, counter) -> new PageRow(key, pageKind(key), counter.visits, counter.visitors.size())),
                new Funnel(sessions, current.productSessions, current.quoteSessions, current.contactSessions),
                excludedCityLabels,
                Instant.now().toString());
    }

    /** Every stored view between the two days, folded; own visits stored before the town list never count. */
    private Fold fold(LocalDate firstDay, LocalDate lastDay) {
        Instant from = firstDay.atStartOfDay(ZONE).toInstant();
        Instant to = lastDay.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<WebsiteVisitEntity> rows = WebsiteVisitEntity.list(
                "occurredAt >= ?1 and occurredAt < ?2 order by visitor, occurredAt", from, to);
        Fold fold = new Fold(firstDay, lastDay);
        for (WebsiteVisitEntity row : rows) {
            if (ownVisit(row.country, row.city)) continue;
            fold.add(row);
        }
        fold.finish();
        return fold;
    }

    /** Distinct visitors seen since the given moment: the "right now" of the report. */
    private long activeVisitors(Instant since) {
        List<WebsiteVisitEntity> rows = WebsiteVisitEntity.list("occurredAt >= ?1", since);
        Set<String> active = new HashSet<>();
        for (WebsiteVisitEntity row : rows) {
            if (!ownVisit(row.country, row.city)) active.add(row.visitor);
        }
        return active.size();
    }

    private static final class Counter {
        long visits;
        final Set<String> visitors = new HashSet<>();

        void add(String visitor) {
            visits++;
            visitors.add(visitor);
        }
    }

    /**
     * One pass over the views of a period, sorted by visitor and time. Views
     * of one visitor less than half an hour apart form a session; when the
     * session ends its first and last page, its length and how far it got
     * are booked.
     */
    private static final class Fold {
        final Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        final Map<LocalDate, Set<String>> visitorsPerDay = new HashMap<>();
        final long[] perHourVisits = new long[24];
        final List<Set<String>> perHourVisitors = new ArrayList<>();
        final Map<String, Counter> pages = new HashMap<>();
        final Map<String, Counter> kinds = new HashMap<>();
        final Map<String, Counter> countries = new HashMap<>();
        final Map<String, Counter> cities = new HashMap<>();
        final Map<String, Counter> sources = new HashMap<>();
        final Map<String, Counter> devices = new HashMap<>();
        final Map<String, Counter> locales = new HashMap<>();
        final Map<String, Counter> entries = new HashMap<>();
        final Map<String, Counter> exits = new HashMap<>();
        final Map<String, String> sourceKinds = new HashMap<>();
        final Map<String, String> cityCountries = new HashMap<>();
        final int[][] hours = new int[7][24];
        final Set<String> visitors = new HashSet<>();
        long visits;
        long sessions;
        long bounces;
        long timedSessions;
        long timedSeconds;
        long productSessions;
        long quoteSessions;
        long contactSessions;

        private String sessionVisitor;
        private Instant sessionStart;
        private Instant lastSeen;
        private String entryPath;
        private String lastPath;
        private int sessionPages;
        private boolean sawProduct;
        private boolean sawQuote;
        private boolean sawContact;

        Fold(LocalDate firstDay, LocalDate lastDay) {
            for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
                perDay.put(day, new long[1]);
                visitorsPerDay.put(day, new HashSet<>());
            }
            for (int hour = 0; hour < 24; hour++) perHourVisitors.add(new HashSet<>());
        }

        void add(WebsiteVisitEntity row) {
            ZonedDateTime at = row.occurredAt.atZone(ZONE);
            LocalDate day = at.toLocalDate();
            long[] count = perDay.get(day);
            if (count != null) count[0]++;
            Set<String> dayVisitors = visitorsPerDay.get(day);
            if (dayVisitors != null) dayVisitors.add(row.visitor);
            visits++;
            visitors.add(row.visitor);
            hours[at.getDayOfWeek().getValue() - 1][at.getHour()]++;
            perHourVisits[at.getHour()]++;
            perHourVisitors.get(at.getHour()).add(row.visitor);

            boolean newSession = !row.visitor.equals(sessionVisitor) || lastSeen == null
                    || Duration.between(lastSeen, row.occurredAt).compareTo(SESSION_GAP) > 0;
            if (newSession) {
                closeSession();
                sessionVisitor = row.visitor;
                sessionStart = row.occurredAt;
                entryPath = row.path;
                sessionPages = 0;
                sawProduct = false;
                sawQuote = false;
                sawContact = false;
                sessions++;
            }
            sessionPages++;
            lastSeen = row.occurredAt;
            lastPath = row.path;
            switch (row.pageKind) {
                case "PRODUCT" -> sawProduct = true;
                case "QUOTE" -> sawQuote = true;
                case "CONTACT" -> sawContact = true;
                default -> { }
            }

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

        void finish() {
            closeSession();
        }

        private void closeSession() {
            if (sessionVisitor == null) return;
            entries.computeIfAbsent(entryPath, key -> new Counter()).add(sessionVisitor);
            exits.computeIfAbsent(lastPath, key -> new Counter()).add(sessionVisitor);
            if (sessionPages <= 1) {
                bounces++;
            } else {
                timedSessions++;
                timedSeconds += Math.max(0, Duration.between(sessionStart, lastSeen).getSeconds());
            }
            if (sawProduct) productSessions++;
            if (sawQuote) quoteSessions++;
            if (sawContact) contactSessions++;
            sessionVisitor = null;
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
