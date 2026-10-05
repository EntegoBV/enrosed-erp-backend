package be.enrosed.account;

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

/** Revocable server-side session of a website login; only the SHA-256 of the token is stored. */
@Entity
@Table(name = "customer_session", indexes = {
        @Index(name = "idx_customer_session_account", columnList = "account_id"),
        @Index(name = "idx_customer_session_expiry", columnList = "expires_at")
}, uniqueConstraints = @UniqueConstraint(
        name = "uq_customer_session_token_hash", columnNames = "token_hash"))
public class CustomerSessionEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "account_id")
    public Long accountId;

    @Column(name = "token_hash", length = 64)
    public String tokenHash;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    @Column(name = "expires_at")
    public Instant expiresAt;
}
