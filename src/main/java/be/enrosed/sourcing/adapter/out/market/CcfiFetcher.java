package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.domain.MarketSourceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the weekly CCFI Europe route from the Shanghai Shipping Exchange.
 *
 * The CCFI (China Containerized Freight Index) covers the whole Chinese
 * coast per destination. Source research did not identify a reusable exact
 * Guangzhou- or Shenzhen-Europe series, so this broad CCFI route is the
 * honest context for those ports. Index points, not dollars: the trend is
 * the signal, the forwarder quote stays the price.
 *
 * ENROSED confirmed permission for this internal installation. The connector
 * is therefore enabled by default, while an explicit false configuration
 * still prevents every request.
 */
@ApplicationScoped
public class CcfiFetcher implements MarketSourceFetcher {

    private static final Logger LOG = Logger.getLogger(CcfiFetcher.class);

    /** Route code the scraped index is stored under. */
    public static final String ROUTE = "CCFI CN-EUR";

    public static final MarketSourceDefinition SOURCE = new MarketSourceDefinition(
            ROUTE,
            "CCFI China → Europa",
            "Brede referentie: tien Chinese vertrekhavens, waaronder Guangzhou en Shenzhen",
            "INDEX_POINTS",
            "BROAD_REFERENCE",
            "Shanghai Shipping Exchange · CCFI",
            "https://en.sse.net.cn/indices/ccfinew.jsp",
            "https://en.sse.net.cn/indices/agreetext.htm");

    private static final URI ENDPOINT =
            URI.create("https://en.sse.net.cn/currentIndex?indexName=ccfi");
    private static final ObjectMapper JSON = new ObjectMapper();

    /* The exchange answers from China; four seconds proved too tight. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final MarketSourceTracker tracker;
    private final boolean authorized;

    public CcfiFetcher(
            MarketSourceTracker tracker,
            @ConfigProperty(
                    name = "enrosed.market.ccfi.automated-access-authorized",
                    defaultValue = "true") boolean authorized) {
        this.tracker = tracker;
        this.authorized = authorized;
    }

    @Override
    public void refreshIfDue() {
        if (!authorized || !tracker.beginDailyCheck(ROUTE)) return;

        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                    .header("Referer", "https://en.sse.net.cn/indices/ccfinew.jsp")
                    .GET().build();
            HttpResponse<String> response = HTTP.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            List<Observation> observations = parseEurope(response.body());
            if (observations.isEmpty()) {
                throw new IllegalStateException("EUROPE line missing");
            }
            for (Observation observation : observations) {
                tracker.store(ROUTE, observation.publishedOn(), observation.value());
            }
            tracker.success(ROUTE);
            LOG.infof("CCFI China-Europe refreshed: %s", observations);
        } catch (Exception e) {
            tracker.failure(ROUTE, e);
            LOG.debugf("CCFI fetch skipped: %s", e.toString());
        }
    }

    @Override
    public MarketSourceStatus status() {
        return tracker.status(SOURCE, authorized);
    }

    static List<Observation> parseEurope(String body) throws Exception {
        JsonNode data = JSON.readTree(body).path("data");
        String currentDate = data.path("currentDate").asText();
        String lastDate = data.path("lastDate").asText();
        if (currentDate.isBlank() || lastDate.isBlank()) return List.of();

        for (JsonNode line : data.path("lineDataList")) {
            String name = line.path("properties").path("lineName_EN").asText();
            if (!"EUROPE".equalsIgnoreCase(name.trim())) continue;
            List<Observation> result = new ArrayList<>(2);
            if (line.path("currentContent").isNumber()) {
                result.add(new Observation(LocalDate.parse(currentDate),
                        line.path("currentContent").decimalValue()));
            }
            if (line.path("lastContent").isNumber()) {
                result.add(new Observation(LocalDate.parse(lastDate),
                        line.path("lastContent").decimalValue()));
            }
            return result;
        }
        return List.of();
    }

    record Observation(LocalDate publishedOn, BigDecimal value) {}
}
