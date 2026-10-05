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

/** One-time invitation link of a website login; only the SHA-256 of the token is stored. */
@Entity
@Table(name = "customer_account_token", indexes = {
        @Index(name = "idx_customer_account_token_account", columnList = "account_id")
}, uniqueConstraints = @UniqueConstraint(
        name = "uq_customer_account_token_hash", columnNames = "token_hash"))
public class CustomerAccountTokenEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "account_id")
    public Long accountId;

    @Column(name = "token_hash", length = 64)
    public String tokenHash;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "used_at")
    public Instant usedAt;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;
}
