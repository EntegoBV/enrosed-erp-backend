package be.enrosed.contact;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "contact_notification_outbox", indexes = {
        @Index(name = "idx_contact_outbox_due", columnList = "status,next_attempt_at")
})
public class ContactNotificationOutboxEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "inquiry_id", nullable = false, unique = true)
    public Long inquiryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public ContactOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    public Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    public Instant lastAttemptAt;

    @Column(name = "sent_at")
    public Instant sentAt;

    @Column(name = "last_error", length = 300)
    public String lastError;
}
