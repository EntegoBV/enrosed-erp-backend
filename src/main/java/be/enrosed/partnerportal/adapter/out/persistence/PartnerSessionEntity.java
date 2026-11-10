package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Revocable server-side session of a partner login; only the SHA-256 of the epp1_ token is stored. */
@Entity
@Table(name = "partner_session",
        indexes = @Index(name = "idx_partner_session_login", columnList = "login_id"),
        uniqueConstraints = @UniqueConstraint(name = "uq_partner_session_token_hash", columnNames = "token_hash"))
public class PartnerSessionEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "login_id")
    public Long loginId;

    @Column(name = "token_hash", length = 64)
    public String tokenHash;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Override
    public String toString() {
        return "PartnerSession[id=" + id + ", loginId=" + loginId + ", expiresAt=" + expiresAt + "]";
    }
}
