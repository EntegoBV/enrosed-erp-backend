package be.enrosed.stockradar;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

/** The radar's heartbeat; each shop keeps its own, longer rhythm inside the service. */
@ApplicationScoped
public class StockRadarJob {

    private static final Logger LOG = Logger.getLogger(StockRadarJob.class);
    private final StockRadarService service;

    public StockRadarJob(StockRadarService service) { this.service = service; }

    @Scheduled(identity = "stock-radar", every = "${enrosed.stockradar.every:10s}", delayed = "25s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try { service.tick(Instant.now()); }
        catch (RuntimeException failure) { LOG.warnf("Voorraadradar-ronde mislukt (%s)", failure.getClass().getSimpleName()); }
    }
}
