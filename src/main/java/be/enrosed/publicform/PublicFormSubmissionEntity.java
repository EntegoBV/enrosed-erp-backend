package be.enrosed.publicform;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Durable idempotency result; contains no raw idempotency key or request payload. */
@Entity
@Table(name = "public_form_submission", indexes = {
        @Index(name = "idx_public_form_submission_expiry", columnList = "expires_at")
})
public class PublicFormSubmissionEntity extends PanacheEntityBase {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 16)
    public String purpose;

    @Column(name = "payload_hash", nullable = false, length = 64)
    public String payloadHash;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    public String responseJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
