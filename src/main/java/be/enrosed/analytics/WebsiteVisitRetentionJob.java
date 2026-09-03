package be.enrosed.analytics;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Page views older than the retention window are dropped in bounded batches. */
@ApplicationScoped
public class WebsiteVisitRetentionJob {

    private final int retentionDays;
    private final WebsiteVisitService visits;

    @Inject
    public WebsiteVisitRetentionJob(
            @ConfigProperty(name = "enrosed.analytics.retention.days", defaultValue = "400") int retentionDays,
            WebsiteVisitService visits) {
        this.retentionDays = Math.max(30, retentionDays);
        this.visits = visits;
    }

    @Scheduled(every = "${enrosed.analytics.retention.every:24h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        WebsiteVisitEntity.delete("occurredAt < ?1", cutoff);
        /* Own visits stored before a town joined the list leave the table too. */
        List<WebsiteVisitEntity> belgian = WebsiteVisitEntity.list("country = ?1 and city is not null", "BE");
        for (WebsiteVisitEntity row : belgian) {
            if (visits.ownVisit(row.country, row.city)) row.delete();
        }
    }
}
