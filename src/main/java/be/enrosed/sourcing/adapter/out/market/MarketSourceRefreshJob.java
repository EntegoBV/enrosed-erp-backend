package be.enrosed.sourcing.adapter.out.market;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/** Daily background refresh; the REST endpoint repeats this as a safe fallback. */
@ApplicationScoped
public class MarketSourceRefreshJob {

    private static final Logger LOG = Logger.getLogger(MarketSourceRefreshJob.class);

    private final List<MarketSourceFetcher> sources;

    @Inject
    public MarketSourceRefreshJob(DrewryWciFetcher drewry, NcfiFetcher ncfi, CcfiFetcher ccfi) {
        this(List.of(drewry, ncfi, ccfi));
    }

    MarketSourceRefreshJob(List<MarketSourceFetcher> sources) {
        this.sources = List.copyOf(sources);
    }

    @Scheduled(
            identity = "freight-market-daily-refresh",
            cron = "{enrosed.market.refresh.cron}",
            timeZone = "UTC",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refreshDaily() {
        for (MarketSourceFetcher source : sources) {
            try {
                source.refreshIfDue();
            } catch (RuntimeException exception) {
                /* One connector must never prevent the other sources from
                   refreshing. Connectors retain their last valid cache. */
                LOG.warnf("Scheduled market source refresh failed: %s", exception.toString());
            }
        }
    }
}
