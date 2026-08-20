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
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact weekly NCFI Ningbo-Europe route points.
 *
 * The Baltic Exchange weekly page identifies the route as Ningbo to Hamburg
 * and Rotterdam and attributes compilation to Ningbo Shipping Exchange. It
 * is materially better than the old all-routes NCFI composite. Baltic's data
 * policy requires licensed automated/non-display use, so the connector is
 * fail-closed until the operator explicitly confirms that permission.
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
                    defaultValue = "false") boolean authorized) {
        this.tracker = tracker;
        this.authorized = authorized;
    }

    @Override
    public void refreshIfDue() {
        if (!authorized || !tracker.beginDailyCheck(ROUTE)) return;

        LocalDate friday = LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        try {
            /* Publication can slide around Chinese holidays. Four candidate
               Fridays are enough for a daily top-up without a history crawl. */
            for (int back = 0; back < 4; back++) {
                LocalDate candidate = friday.minusWeeks(back);
                URI page = URI.create(String.format(Locale.ROOT, BASE,
                        candidate.getYear(), SLUG.format(candidate)));
                HttpRequest request = HttpRequest.newBuilder(page)
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                        .GET().build();
                HttpResponse<String> response = HTTP.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) continue;
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                List<Observation> observations = parseEurope(response.body());
                if (observations.isEmpty()) continue;
                for (Observation observation : observations) {
                    tracker.store(ROUTE, observation.publishedOn(), observation.value());
                }
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

    record Observation(LocalDate publishedOn, BigDecimal value) {}
}
