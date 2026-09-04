package be.enrosed.sourcing.adapter.out.market;

import be.enrosed.sourcing.adapter.out.market.NcfiReprint.ProviderAccessException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Exact weekly NCFI Ningbo-Europe route points.
 *
 * The Ningbo Shipping Exchange table names the route Europe (Hamburg and
 * Rotterdam base ports). It is materially better than the all-routes
 * composite for a container that sails Ningbo to Rotterdam. The points come
 * from the official weekly index PDF attached to the Hellenic Shipping News
 * reprint; the Baltic Exchange copy of the same table answers with a bot
 * challenge and is no longer contacted. This is an internal, non-commercial
 * installation reading a public weekly publication once a day. A bounded
 * archive top-up supplies enough recent points for useful charts without
 * crawling the whole reprint history in one run.
 */
@ApplicationScoped
public class NcfiFetcher implements MarketSourceFetcher {

    private static final Logger LOG = Logger.getLogger(NcfiFetcher.class);

    public static final String ROUTE = "NCFI NGB-EUR";
    public static final String LEGACY_COMPOSITE_ROUTE = "NCFI NINGBO";

    public static final MarketSourceDefinition SOURCE = new MarketSourceDefinition(
            ROUTE,
            "NCFI Ningbo → Europa",
            "Exacte route-index: Ningbo-Zhoushan → Europa (Hamburg en Rotterdam)",
            "INDEX_POINTS",
            "EXACT_ROUTE",
            "Ningbo Shipping Exchange · weekly index data via Hellenic Shipping News",
            "https://www.hellenicshippingnews.com/",
            "https://www.hellenicshippingnews.com/terms-of-use/");

    /** Roughly six months is useful for analysis; a full-year crawl is unnecessary. */
    static final int HISTORY_TARGET = 26;
    /** At most six archive reprints in addition to the current publication per day. */
    static final int HISTORY_REQUEST_BUDGET = 6;
    private static final int HISTORY_SCAN_WEEKS = 32;

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
            /* The reprint lands a few days after the Friday release and can
               slide around Chinese holidays. Four candidate Fridays are enough
               for a daily top-up without a history crawl. */
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
            throw new IllegalStateException("No recent NCFI reprint with the Europe route found");
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
                   archive reprint must not turn a healthy cache into a failure. */
                LOG.debugf("NCFI archive reprint %s skipped: %s", candidate, exception.toString());
            }
        }
    }

    /**
     * Newest first, one week at a time. Every reprint carries its week and
     * the one before, and a week already stored is skipped, so the walk
     * never downloads a reprint twice and closes holes left by a missing
     * reprint or a holiday instead of stepping over them.
     */
    static List<Integer> historyCandidateWeeks() {
        ArrayList<Integer> result = new ArrayList<>();
        for (int weeks = 1; weeks <= HISTORY_SCAN_WEEKS; weeks++) result.add(weeks);
        return List.copyOf(result);
    }

    private List<Observation> fetch(LocalDate candidate) throws Exception {
        Optional<WeeklyTable> table = NcfiReprint.fetchWeek(candidate);
        return table.map(NcfiFetcher::europeObservations).orElse(List.of());
    }

    /** Current week first, then the previous one; nothing when the Europe row is missing. */
    static List<Observation> europeObservations(WeeklyTable table) {
        if (table == null || table.europeCurrent() == null) return List.of();
        List<Observation> result = new ArrayList<>(2);
        result.add(new Observation(table.currentOn(), table.europeCurrent()));
        if (table.europePrevious() != null) {
            result.add(new Observation(table.previousOn(), table.europePrevious()));
        }
        return List.copyOf(result);
    }

    static boolean isProviderChallenge(String html) {
        return NcfiReprint.isProviderChallenge(html);
    }

    private void store(List<Observation> observations) {
        for (Observation observation : observations) {
            tracker.store(ROUTE, observation.publishedOn(), observation.value());
        }
    }

    record Observation(LocalDate publishedOn, BigDecimal value) {}
}
