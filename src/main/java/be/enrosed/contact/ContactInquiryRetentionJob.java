package be.enrosed.contact;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Bounded deletion of expired general-contact data; quote/customer records are untouched. */
@ApplicationScoped
public class ContactInquiryRetentionJob {
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 20;
    private final int retentionDays;
    private final Clock clock;

    @Inject
    public ContactInquiryRetentionJob(
            @ConfigProperty(name = "enrosed.contact.retention.days",
                    defaultValue = "730") int retentionDays) {
        this(retentionDays, Clock.systemUTC());
    }

    ContactInquiryRetentionJob(int retentionDays, Clock clock) {
        if (retentionDays < 30) {
            throw new IllegalStateException("Contact retention must be at least 30 days");
        }
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    @Scheduled(every = "${enrosed.contact.retention.every:24h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        Instant cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<Long> ids = ContactInquiryEntity.<ContactInquiryEntity>find(
                            "createdAt < ?1 order by id", cutoff)
                    .page(0, BATCH_SIZE).list().stream().map(row -> row.id).toList();
            if (ids.isEmpty()) return;
            /* Delete the child first: this is compatible with both ORM-created H2 and the
               explicit PostgreSQL foreign key, without relying on cascade configuration. */
            ContactNotificationOutboxEntity.delete("inquiryId in ?1", ids);
            ContactInquiryEntity.delete("id in ?1", ids);
        }
    }
}
