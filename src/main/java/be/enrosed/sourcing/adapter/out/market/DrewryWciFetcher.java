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
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the weekly Drewry Shanghai -> Rotterdam WCI value for ENROSED's
 * authorized internal installation. The connector is enabled by default,
 * can still be disabled explicitly, and preserves its last valid cache when
 * a lookup fails.
 */
@ApplicationScoped
public class DrewryWciFetcher implements MarketSourceFetcher {

    private static final Logger LOG = Logger.getLogger(DrewryWciFetcher.class);

    /** Route code the scraped index is stored under. */
    public static final String ROUTE = "WCI SHA-RTM";

    public static final MarketSourceDefinition SOURCE = new MarketSourceDefinition(
            ROUTE,
            "Drewry Shanghai → Rotterdam",
            "Routebenchmark Shanghai → Rotterdam, USD per 40ft-container",
            "USD_PER_40FT",
            "EXACT_ROUTE",
            "Drewry World Container Index",
            "https://www.drewry.co.uk/free-market-insights",
            "https://www.drewry.co.uk/maritime-research/maritime-research-related-content/standard-licence-terms");

    private static final URI PAGE = URI.create(
            "https://www.drewry.co.uk/supply-chain-advisors/supply-chain-expertise/"
            + "world-container-index-assessed-by-drewry");
    private static final Pattern RATE = Pattern.compile(
            "Shanghai to Rotterdam[^$]{0,80}\\$([\\d,]+)");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final MarketSourceTracker tracker;
    private final boolean authorized;

    public DrewryWciFetcher(
            MarketSourceTracker tracker,
            @ConfigProperty(
                    name = "enrosed.market.drewry.automated-access-authorized",
                    defaultValue = "true") boolean authorized) {
        this.tracker = tracker;
        this.authorized = authorized;
    }

    @Override
    public void refreshIfDue() {
        if (!authorized || !tracker.beginDailyCheck(ROUTE)) return;

        try {
            HttpRequest request = HttpRequest.newBuilder(PAGE)
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                    .GET().build();
            HttpResponse<String> response = HTTP.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            BigDecimal rate = parseShanghaiRotterdam(response.body());
            if (rate == null) throw new IllegalStateException("Route value missing");
            tracker.storeAtMostWeekly(ROUTE, LocalDate.now(), rate);
            tracker.success(ROUTE);
            LOG.infof("Drewry WCI Shanghai-Rotterdam: $%s per 40ft", rate);
        } catch (Exception e) {
            tracker.failure(ROUTE, e);
            LOG.debugf("Drewry fetch skipped: %s", e.toString());
        }
    }

    @Override
    public MarketSourceStatus status() {
        return tracker.status(SOURCE, authorized);
    }

    static BigDecimal parseShanghaiRotterdam(String html) {
        Matcher matcher = RATE.matcher(html);
        return matcher.find()
                ? new BigDecimal(matcher.group(1).replace(",", ""))
                : null;
    }
}
