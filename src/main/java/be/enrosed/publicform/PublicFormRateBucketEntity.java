package be.enrosed.publicform;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "public_form_rate_bucket", indexes = {
        @Index(name = "idx_public_form_rate_expiry", columnList = "expires_at")
})
public class PublicFormRateBucketEntity extends PanacheEntityBase {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 32)
    public String action;

    @Column(name = "key_type", nullable = false, length = 16)
    public String keyType;

    /** HMAC only; no raw IP address or e-mail is retained. */
    @Column(name = "key_hash", nullable = false, length = 64)
    public String keyHash;

    @Column(name = "window_started_at", nullable = false)
    public Instant windowStartedAt;

    @Column(name = "request_count", nullable = false)
    public int requestCount;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
