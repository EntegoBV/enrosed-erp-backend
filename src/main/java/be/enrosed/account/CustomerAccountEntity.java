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

/**
 * A website login: one per e-mail address, several per customer allowed. The status
 * (INVITED, ACTIVE, DISABLED) and the language are plain strings; customerId is a
 * plain id without a foreign key.
 */
@Entity
@Table(name = "customer_account", indexes = {
        @Index(name = "idx_customer_account_customer", columnList = "customer_id")
}, uniqueConstraints = @UniqueConstraint(
        name = "uq_customer_account_email", columnNames = "email"))
public class CustomerAccountEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "customer_id")
    public Long customerId;

    /** Always the output of AccountEmails.normalize. */
    @Column(name = "email", length = 254)
    public String email;

    @Column(name = "contact_name", length = 120)
    public String contactName;

    @Column(name = "language", length = 4)
    public String language;

    @Column(name = "status", length = 16)
    public String status;

    /** bcrypt; null until the customer has chosen a password and again after a withdrawal. */
    @Column(name = "password_hash", length = 100)
    public String passwordHash;

    @Column(name = "password_set_at")
    public Instant passwordSetAt;

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    /** An invitation mail has left for this login since it was (re)given. */
    @Column(name = "last_link_sent_at")
    public Instant lastLinkSentAt;

    @Column(name = "last_link_error", length = 300)
    public String lastLinkError;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "disabled_at")
    public Instant disabledAt;

    @Column(name = "disabled_by", length = 64)
    public String disabledBy;
}
