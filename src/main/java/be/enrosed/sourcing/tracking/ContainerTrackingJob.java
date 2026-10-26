package be.enrosed.sourcing.tracking;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ContainerTrackingJob {
    private static final Logger LOG = Logger.getLogger(ContainerTrackingJob.class);
    @Inject ContainerTrackingService service;

    /** Frequent due scan makes restarts harmless; each reference has a durable six-hour deadline. */
    @Scheduled(identity = "container-tracking", every = "5m", delayed = "30s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try { service.syncDue(); }
        catch (RuntimeException failure) { LOG.warn("Container tracking scan failed; saved observations are retained."); }
    }
}
