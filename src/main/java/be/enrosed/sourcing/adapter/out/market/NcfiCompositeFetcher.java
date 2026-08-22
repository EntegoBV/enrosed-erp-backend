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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NCFI composite (all 21 routes out of Ningbo, index points), read from the
 * weekly reprint Hellenic Shipping News publishes a few days after each
 * Friday release. The Ningbo exchange's own site is a splash page and the
 * Baltic Exchange pages sit behind a bot challenge; the reprint is the one
 * free, dated, parseable publication - and it reaches back for years, so a
 * thin log backfills itself to a full year of Fridays.
 *
 * Composite, not the Europe route: the reprint carries only the headline
 * number in text (the route table is a PDF). As a trend indicator for
 * Ningbo departures that is exactly what the dashboard shows it as.
 */
@ApplicationScoped
public class NcfiCompositeFetcher implements MarketSourceFetcher {

    private static final Logger LOG = Logger.getLogger(NcfiCompositeFetcher.class);

    /** The dashboard's Ningbo index row; matches the history already logged. */
    public static final String ROUTE = "NCFI NINGBO";

    public static final MarketSourceDefinition SOURCE = new MarketSourceDefinition(
            ROUTE,
            "NCFI Ningbo composiet",
            "Ningbo Containerized Freight Index, composiet over alle routes, indexpunten",
            "INDEX_POINTS",
            "COMPOSITE",
            "Ningbo Shipping Exchange via Hellenic Shipping News",
            "https://www.hellenicshippingnews.com/",
            "https://www.hellenicshippingnews.com/terms-of-use/");

    private static final String BASE =
            "https://www.hellenicshippingnews.com/ningbo-containerized-freight-index-report-%d-%s-%d/";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);
    private static final Pattern POINTS = Pattern.compile(
            "\\(NCFI\\)[^.]{0,120}?quotes\\s+([\\d,]+(?:\\.\\d+)?)\\s+points", Pattern.CASE_INSENSITIVE);

    /** A full year of Fridays is worth having once; afterwards a weekly top-up. */
    static final int BACKFILL_WEEKS = 55;
    static final int SETTLED_COUNT = 20;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final MarketSourceTracker tracker;
    private final boolean authorized;

    public NcfiCompositeFetcher(
            MarketSourceTracker tracker,
            @ConfigProperty(
                    name = "enrosed.market.ncfi-composite.automated-access-authorized",
                    defaultValue = "true") boolean authorized) {
        this.tracker = tracker;
        this.authorized = authorized;
    }

    @Override
    public void refreshIfDue() {
        if (!authorized || !tracker.beginDailyCheck(ROUTE)) return;

        LocalDate friday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        int weeks = tracker.observationCount(ROUTE) >= SETTLED_COUNT ? 3 : BACKFILL_WEEKS;
        int found = 0;
        try {
            for (int back = 0; back < weeks; back++) {
                LocalDate candidate = friday.minusWeeks(back);
                if (tracker.hasObservation(ROUTE, candidate)) continue;
                BigDecimal points = fetch(candidate);
                if (points == null) continue;
                tracker.store(ROUTE, candidate, points);
                found++;
            }
            tracker.success(ROUTE);
            if (found > 0) LOG.infof("NCFI composite: %d week(s) added", found);
        } catch (Exception e) {
            tracker.failure(ROUTE, e);
            LOG.debugf("NCFI composite fetch skipped: %s", e.toString());
        }
    }

    @Override
    public MarketSourceStatus status() {
        return tracker.status(SOURCE, authorized);
    }

    /** The reprint for one Friday, or null when that week has none. */
    private static BigDecimal fetch(LocalDate friday) throws Exception {
        String url = String.format(BASE, friday.getDayOfMonth(),
                friday.format(MONTH).toLowerCase(Locale.ENGLISH), friday.getYear());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        return parsePoints(response.body());
    }

    static BigDecimal parsePoints(String html) {
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        Matcher matcher = POINTS.matcher(text);
        if (!matcher.find()) return null;
        return new BigDecimal(matcher.group(1).replace(",", ""));
    }
}
