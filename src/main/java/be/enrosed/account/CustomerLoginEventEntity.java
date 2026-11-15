package be.enrosed.account;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One moment a logged-in customer used the website: logging in, choosing a password with the
 * link (which logs in too), a page that asked for the session or the customer's prices, the
 * order page, Mijn bestellingen, a PDF, logging out. Visits and their length are worked out
 * from these rows (CustomerLoginActivity); nothing here names a page, a product or an address.
 *
 * Rows with history = true were reconstructed once from what the login tables already held
 * before 2026-11-15 (activity log, last login, session last-seen): a moment, never a length.
 */
@Entity
@Table(name = "customer_login_event", indexes = {
        @Index(name = "idx_customer_login_event_occurred", columnList = "occurred_at"),
        @Index(name = "idx_customer_login_event_account", columnList = "account_id,occurred_at")
})
public class CustomerLoginEventEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "account_id", nullable = false)
    public Long accountId;

    /** The customer the login belonged to at that moment. */
    @Column(name = "customer_id", nullable = false)
    public Long customerId;

    /** The server-side session, when there was one; sessions are deleted later, this id stays. */
    @Column(name = "session_id")
    public Long sessionId;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    /** See CustomerLoginActivity.Kind. */
    @Column(name = "kind", nullable = false, length = 16)
    public String kind;

    /** MOBILE, TABLET or DESKTOP from the browser's own description; null when unknown. */
    @Column(name = "device", length = 12)
    public String device;

    @Column(name = "history", nullable = false)
    public boolean history;
}
