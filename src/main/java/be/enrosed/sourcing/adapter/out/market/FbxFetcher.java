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
 * Freightos Baltic Index, lane FBX11: China/East Asia to North Europe in
 * USD per 40ft. The public index page embeds the current lane values as a
 * small JSON fragment, so one regex reads the number - no login, no key.
 *
 * Second dollar benchmark next to Drewry: Drewry is Shanghai to Rotterdam
 * specifically, FBX11 is the whole China/East Asia to North Europe trade.
 * When the two disagree, the difference is the Rotterdam premium.
 */
@ApplicationScoped
public class FbxFetcher implements MarketSourceFetcher {

    private static final Logger LOG = Logger.getLogger(FbxFetcher.class);

    public static final String ROUTE = "FBX11 CN-NEUR";

    public static final MarketSourceDefinition SOURCE = new MarketSourceDefinition(
            ROUTE,
            "Freightos FBX11 China → Noord-Europa",
            "Lanebenchmark China/Oost-Azië → Noord-Europa, USD per 40ft-container",
            "USD_PER_40FT",
            "LANE",
            "Freightos Baltic Index (FBX)",
            "https://fbx.freightos.com/",
            "https://www.freightos.com/terms-of-service/");

    private static final URI PAGE = URI.create("https://fbx.freightos.com/");
    private static final Pattern RATE = Pattern.compile(
            "\"label\":\"FBX11\",\"value\":\"\\$([\\d,]+)\"");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final MarketSourceTracker tracker;
    private final boolean authorized;

    public FbxFetcher(
            MarketSourceTracker tracker,
            @ConfigProperty(
                    name = "enrosed.market.fbx.automated-access-authorized",
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
            BigDecimal rate = parseFbx11(response.body());
            if (rate == null) throw new IllegalStateException("FBX11 value missing");
            tracker.storeAtMostWeekly(ROUTE, LocalDate.now(), rate);
            tracker.success(ROUTE);
            LOG.infof("Freightos FBX11 China-North Europe: $%s per 40ft", rate);
        } catch (Exception e) {
            tracker.failure(ROUTE, e);
            LOG.debugf("FBX fetch skipped: %s", e.toString());
        }
    }

    @Override
    public MarketSourceStatus status() {
        return tracker.status(SOURCE, authorized);
    }

    static BigDecimal parseFbx11(String html) {
        Matcher matcher = RATE.matcher(html);
        if (!matcher.find()) return null;
        return new BigDecimal(matcher.group(1).replace(",", ""));
    }
}
