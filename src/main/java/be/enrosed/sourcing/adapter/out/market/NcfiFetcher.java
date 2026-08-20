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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the weekly NCFI composite for Ningbo departures.
 *
 * The Ningbo Shipping Exchange publishes the NCFI every Friday, but its own
 * site is a splash image and the Baltic Exchange page sits behind a bot
 * wall. Hellenic Shipping News reprints the weekly report at a predictable
 * URL a few days later, with the composite in plain text - so this walks
 * the last few Fridays and takes the first article that answers. Composite
 * points across all 21 routes, not Europe alone: a Ningbo-departure trend
 * gauge, not a price.
 */
@ApplicationScoped
public class NcfiFetcher {

    private static final Logger LOG = Logger.getLogger(NcfiFetcher.class);

    /** Route code the scraped index is stored under. */
    public static final String ROUTE = "NCFI NINGBO";

    private static final String BASE =
            "https://www.hellenicshippingnews.com/ningbo-containerized-freight-index-report-%s/";
    private static final DateTimeFormatter SLUG =
            DateTimeFormatter.ofPattern("d-MMMM-yyyy", Locale.ENGLISH);
    private static final Pattern POINTS = Pattern.compile(
            "NCFI[^0-9]{0,120}?([\\d,]+(?:\\.\\d+)?)\\s*points");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Transactional
    public void refreshIfStale() {
        LocalDate weekAgo = LocalDate.now().minusDays(6);
        long recent = FreightRateEntity.count("route = ?1 and quotedOn >= ?2", ROUTE, weekAgo);
        if (recent > 0) return;

        LocalDate friday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        for (int back = 0; back < 3; back++) {
            LocalDate week = friday.minusWeeks(back);
            if (FreightRateEntity.count("route = ?1 and quotedOn = ?2", ROUTE, week) > 0) return;
            String url = String.format(BASE,
                    SLUG.format(week).toLowerCase(Locale.ENGLISH));
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(4))
                        .header("User-Agent", "Mozilla/5.0 (Enrosed ERP dashboard)")
                        .GET().build();
                HttpResponse<String> response =
                        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) continue;
                Matcher matcher = POINTS.matcher(response.body());
                if (!matcher.find()) continue;
                BigDecimal points = new BigDecimal(matcher.group(1).replace(",", ""));
                FreightRateEntity entity = new FreightRateEntity();
                entity.route = ROUTE;
                entity.quotedOn = week;
                entity.usdPerContainer = points;
                entity.persist();
                LOG.infof("NCFI Ningbo composite: %s points (week of %s)", points, week);
                return;
            } catch (Exception e) {
                LOG.debugf("NCFI fetch for %s skipped: %s", week, e.toString());
            }
        }
    }
}
