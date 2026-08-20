package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.FreightRateEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Reads the weekly CCFI Europe route from the Shanghai Shipping Exchange.
 *
 * The CCFI (China Containerized Freight Index) covers the whole Chinese
 * coast per destination - the closest public thing to a Ningbo/Guangzhou/
 * Shenzhen -> Europe number, since no per-port index is published openly.
 * The exchange serves it as plain JSON, keyless; route-level SCFI data sits
 * behind a login, but CCFI routes are open. Index points, not dollars: the
 * trend is the signal, the forwarder quote stays the price.
 *
 * Same contract as the Drewry fetcher: lazy, one short attempt per week,
 * every failure swallowed so the dashboard never breaks on a scrape.
 */
@ApplicationScoped
public class CcfiFetcher {

    private static final Logger LOG = Logger.getLogger(CcfiFetcher.class);

    /** Route code the scraped index is stored under. */
    public static final String ROUTE = "CCFI CN-EUR";

    private static final URI ENDPOINT =
            URI.create("https://en.sse.net.cn/currentIndex?indexName=ccfi");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Transactional
    public void refreshIfStale() {
        LocalDate weekAgo = LocalDate.now().minusDays(6);
        long recent = FreightRateEntity.count("route = ?1 and quotedOn >= ?2", ROUTE, weekAgo);
        if (recent > 0) return;

        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                    .header("Referer", "https://en.sse.net.cn/indices/ccfinew.jsp")
                    .GET().build();
            String body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
            JsonNode data = JSON.readTree(body).path("data");
            LocalDate quotedOn = LocalDate.parse(data.path("currentDate").asText());

            /* The published date is the index date; a re-run within the same
               week must not duplicate the row. */
            if (FreightRateEntity.count("route = ?1 and quotedOn = ?2", ROUTE, quotedOn) > 0) {
                return;
            }
            for (JsonNode line : data.path("lineDataList")) {
                String name = line.path("properties").path("lineName_EN").asText();
                if (!"EUROPE".equalsIgnoreCase(name.trim())) continue;
                if (line.path("currentContent").isNull()) return;
                BigDecimal points = line.path("currentContent").decimalValue();
                FreightRateEntity entity = new FreightRateEntity();
                entity.route = ROUTE;
                entity.quotedOn = quotedOn;
                entity.usdPerContainer = points;
                entity.persist();
                LOG.infof("CCFI China-Europe: %s points (%s)", points, quotedOn);
                return;
            }
            LOG.warn("CCFI JSON fetched but no EUROPE line found; format may have changed");
        } catch (Exception e) {
            LOG.debugf("CCFI fetch skipped: %s", e.toString());
        }
    }
}
