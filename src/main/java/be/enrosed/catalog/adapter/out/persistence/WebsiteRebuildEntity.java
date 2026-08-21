package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Single-row, durable deploy-hook outbox and synchronization status. */
@Entity
@Table(name = "website_rebuild")
public class WebsiteRebuildEntity {
    @Id
    public Long id = 1L;

    @Version
    public long rowRevision;

    @Enumerated(EnumType.STRING)
    public WebsiteRebuildStatus status = WebsiteRebuildStatus.QUEUED;
    public Instant queuedAt;
    public Instant lastAttemptAt;
    public Instant hookAcceptedAt;
    public Instant liveAt;
    public Instant nextAttemptAt;
    public String currentRevision;
    public String liveRevision;
    public String lastError;
    public int attemptCount;
}
