package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.adapter.out.market.NcfiReprint.WeeklyTable;
import be.enrosed.sourcing.domain.MarketSourceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NCFI composite (all 21 routes out of Ningbo, index points), read from the
 * weekly reprint Hellenic Shipping News publishes a few days after each
 * Friday release. The reprint reaches back for months, so a thin log
 * backfills itself to a year of Fridays.
 *
 * Older reprints quote the headline number in their text; since August 2026
 * the text says nothing and only the attached index PDF carries the table.
 * The text is tried first because it is one cheap request, the PDF second.
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

    private static final Pattern POINTS = Pattern.compile(
            "\\(NCFI\\)[^.]{0,120}?quotes\\s+([\\d,]+(?:\\.\\d+)?)\\s+points", Pattern.CASE_INSENSITIVE);

    /** A full year of Fridays is worth having once; afterwards a weekly top-up. */
    static final int BACKFILL_WEEKS = 55;
    static final int SETTLED_COUNT = 20;

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

    /** The composite for one Friday, or null when that week has no reprint. */
    private static BigDecimal fetch(LocalDate friday) throws Exception {
        Optional<String> article = NcfiReprint.fetchArticle(friday);
        if (article.isEmpty()) return null;
        BigDecimal points = parsePoints(article.get());
        if (points != null) return points;
        String link = NcfiReprint.dataPdfLink(article.get());
        if (link == null) return null;
        WeeklyTable table = NcfiReprint.parseTable(NcfiReprint.extractText(NcfiReprint.fetchPdf(link)));
        return compositeFor(table, friday);
    }

    /** The PDF dates its columns itself; a reprint filed under another Friday never mislabels a week. */
    static BigDecimal compositeFor(WeeklyTable table, LocalDate friday) {
        return table == null ? null : table.compositeOn(friday);
    }

    static BigDecimal parsePoints(String html) {
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        Matcher matcher = POINTS.matcher(text);
        if (!matcher.find()) return null;
        return new BigDecimal(matcher.group(1).replace(",", ""));
    }
}
