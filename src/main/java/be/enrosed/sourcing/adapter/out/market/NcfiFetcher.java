package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.domain.MarketSourceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact weekly NCFI Ningbo-Europe route points.
 *
 * The Baltic Exchange weekly page identifies the route as Ningbo to Hamburg
 * and Rotterdam and attributes compilation to Ningbo Shipping Exchange. It
 * is materially better than the old all-routes NCFI composite. ENROSED
 * confirmed permission for this internal installation. The connector
 * is enabled by default, while an explicit false configuration still blocks
 * every request. A bounded archive top-up supplies enough recent points for
 * useful charts without crawling the full provider history in one run.
 */
@ApplicationScoped
public class NcfiFetcher implements MarketSourceFetcher {

    private static final Logger LOG = Logger.getLogger(NcfiFetcher.class);

    public static final String ROUTE = "NCFI NGB-EUR";
    public static final String LEGACY_COMPOSITE_ROUTE = "NCFI NINGBO";

    public static final MarketSourceDefinition SOURCE = new MarketSourceDefinition(
            ROUTE,
            "NCFI Ningbo → Europa",
            "Exacte route-index: Ningbo-Zhoushan → Hamburg en Rotterdam",
            "INDEX_POINTS",
            "EXACT_ROUTE",
            "Ningbo Shipping Exchange · via Baltic Exchange",
            "https://www.balticexchange.com/en/data-services/WeeklyRoundup.html",
            "https://www.balticexchange.com/en/site-services/data-policy.html");

    private static final String BASE =
            "https://www.balticexchange.com/en/data-services/WeeklyRoundup/ningbo/news/%d/"
            + "ningbo-containerised-freight-index-%s.html";
    private static final DateTimeFormatter SLUG = DateTimeFormatter.ofPattern("ddMMyy");
    private static final DateTimeFormatter TABLE_DATE = DateTimeFormatter.ofPattern("d-M-uuuu");
    private static final String DATE = "(\\d{1,2}-\\d{1,2}-\\d{4})";
    private static final Pattern HEADER = Pattern.compile(
            "Route\\s+" + DATE + "\\s+" + DATE + "\\s+Weekly\\s+change",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EUROPE = Pattern.compile(
            "Ningbo\\s*[-–—]\\s*Europe\\s+([\\d,]+(?:\\.\\d+)?)\\s+"
            + "([\\d,]+(?:\\.\\d+)?)\\s+[-+]?\\d+(?:\\.\\d+)?",
            Pattern.CASE_INSENSITIVE);

    /** Roughly six months is useful for analysis; a full-year crawl is unnecessary. */
    static final int HISTORY_TARGET = 26;
    /** At most six archive pages in addition to the current publication per day. */
    static final int HISTORY_REQUEST_BUDGET = 6;
    private static final int HISTORY_SCAN_WEEKS = 32;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final MarketSourceTracker tracker;
    private final boolean authorized;

    public NcfiFetcher(
            MarketSourceTracker tracker,
            @ConfigProperty(
                    name = "enrosed.market.ncfi.automated-access-authorized",
                    defaultValue = "true") boolean authorized) {
        this.tracker = tracker;
        this.authorized = authorized;
    }

    @Override
    public void refreshIfDue() {
        if (!authorized || !tracker.beginDailyCheck(ROUTE)) return;

        LocalDate friday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        try {
            /* Publication can slide around Chinese holidays. Four candidate
               Fridays are enough for a daily top-up without a history crawl. */
            for (int back = 0; back < 4; back++) {
                LocalDate candidate = friday.minusWeeks(back);
                List<Observation> observations = fetch(candidate);
                if (observations.isEmpty()) continue;
                store(observations);
                topUpRecentHistory(friday);
                tracker.success(ROUTE);
                LOG.infof("NCFI Ningbo-Europe refreshed: %s", observations);
                return;
            }
            throw new IllegalStateException("No recent Ningbo-Europe publication found");
        } catch (Exception e) {
            tracker.failure(ROUTE, e);
            LOG.debugf("NCFI Ningbo-Europe fetch skipped: %s", e.toString());
        }
    }

    @Override
    public MarketSourceStatus status() {
        return tracker.status(SOURCE, authorized);
    }

    private void topUpRecentHistory(LocalDate latestFriday) {
        long stored = tracker.observationCount(ROUTE);
        if (stored >= HISTORY_TARGET) return;

        int requests = 0;
        for (int weeksBack : historyCandidateWeeks()) {
            if (requests >= HISTORY_REQUEST_BUDGET || stored >= HISTORY_TARGET) return;
            LocalDate candidate = latestFriday.minusWeeks(weeksBack);
            if (tracker.hasObservation(ROUTE, candidate)) continue;
            requests++;
            try {
                List<Observation> observations = fetch(candidate);
                if (observations.isEmpty()) continue;
                store(observations);
                stored = tracker.observationCount(ROUTE);
            } catch (ProviderAccessException exception) {
                /* Current data may already have been stored. Surface this as
                   a cache-after-access-block state instead of pretending the
                   historical top-up completed successfully. */
                throw exception;
            } catch (Exception exception) {
                /* The current publication already succeeded. An unavailable
                   archive page must not turn a healthy cache into a failure. */
                LOG.debugf("NCFI archive page %s skipped: %s", candidate, exception.toString());
            }
        }
    }

    /**
     * Pages contain both the selected week and its predecessor. Even weeks
     * therefore fill history without overlap; odd weeks are fallback slots
     * for holiday gaps and missing archive pages.
     */
    static List<Integer> historyCandidateWeeks() {
        ArrayList<Integer> result = new ArrayList<>();
        for (int weeks = 2; weeks <= HISTORY_SCAN_WEEKS; weeks += 2) result.add(weeks);
        for (int weeks = 3; weeks <= HISTORY_SCAN_WEEKS; weeks += 2) result.add(weeks);
        return List.copyOf(result);
    }

    private List<Observation> fetch(LocalDate candidate) throws Exception {
        URI page = URI.create(String.format(Locale.ROOT, BASE,
                candidate.getYear(), SLUG.format(candidate)));
        HttpRequest request = HttpRequest.newBuilder(page)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return List.of();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        if (isProviderChallenge(response.body())) {
            throw new ProviderAccessException(
                    "Provider challenge received; configure the authorized NCFI feed, "
                    + "credentials or IP allowlist");
        }
        return parseEurope(response.body());
    }

    static boolean isProviderChallenge(String html) {
        if (html == null || html.isBlank()) return false;
        String normalized = html.toLowerCase(Locale.ROOT);
        return normalized.contains("<title>challenge validation</title>")
                || normalized.contains("akamai bot manager");
    }

    private void store(List<Observation> observations) {
        for (Observation observation : observations) {
            tracker.store(ROUTE, observation.publishedOn(), observation.value());
        }
    }

    static List<Observation> parseEurope(String html) {
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&ndash;", "–")
                .replaceAll("\\s+", " ")
                .trim();
        Matcher header = HEADER.matcher(text);
        Matcher europe = EUROPE.matcher(text);
        if (!header.find() || !europe.find()) return List.of();

        LocalDate current = LocalDate.parse(header.group(1), TABLE_DATE);
        LocalDate previous = LocalDate.parse(header.group(2), TABLE_DATE);
        BigDecimal currentValue = decimal(europe.group(1));
        BigDecimal previousValue = decimal(europe.group(2));
        return List.of(
                new Observation(current, currentValue),
                new Observation(previous, previousValue));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value.replace(",", ""));
    }

    private static final class ProviderAccessException extends IllegalStateException {
        private ProviderAccessException(String message) {
            super(message);
        }
    }

    record Observation(LocalDate publishedOn, BigDecimal value) {}
}
