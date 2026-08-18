package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.FreightRateEntity;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the weekly Drewry World Container Index from their public page.
 *
 * Drewry publishes the Shanghai -> Rotterdam spot rate in plain HTML every
 * week - the one public, keyless source for exactly the lane this business
 * ships on. Licensed APIs (FBX, WCI feed) exist but need paid keys; this
 * stays within what the public page already tells any visitor.
 *
 * Fetched lazily: when the log is read and this week's number is not in
 * yet, one four-second attempt runs; any failure is swallowed and the
 * cached history serves. A broken scrape must never break the dashboard -
 * the page layout WILL change some day, and the log simply stops growing
 * until the regex is updated.
 */
@ApplicationScoped
public class DrewryWciFetcher {

    private static final Logger LOG = Logger.getLogger(DrewryWciFetcher.class);

    /** Route code the scraped index is stored under. */
    public static final String ROUTE = "WCI SHA-RTM";

    private static final URI PAGE = URI.create(
            "https://www.drewry.co.uk/supply-chain-advisors/supply-chain-expertise/"
            + "world-container-index-assessed-by-drewry");
    private static final Pattern RATE = Pattern.compile(
            "Shanghai to Rotterdam[^$]{0,80}\\$([\\d,]+)");

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
            HttpRequest request = HttpRequest.newBuilder(PAGE)
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                    .GET().build();
            String html = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
            Matcher matcher = RATE.matcher(html);
            if (!matcher.find()) {
                LOG.warn("Drewry page fetched but the Shanghai-Rotterdam rate was not found;"
                        + " the page layout may have changed");
                return;
            }
            BigDecimal rate = new BigDecimal(matcher.group(1).replace(",", ""));
            FreightRateEntity entity = new FreightRateEntity();
            entity.route = ROUTE;
            entity.quotedOn = LocalDate.now();
            entity.usdPerContainer = rate;
            entity.persist();
            LOG.infof("Drewry WCI Shanghai-Rotterdam: $%s per 40ft", rate);
        } catch (Exception e) {
            LOG.debugf("Drewry fetch skipped: %s", e.toString());
        }
    }
}
