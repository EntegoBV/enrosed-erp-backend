package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The state of an order a logged-in customer placed on the website: a side
 * row with the id of its sales_order. Its existence is the only definition
 * of a "websitebestelling"; the channel and the note marker are never the
 * key. Every status-like value is a plain string and every column nullable,
 * so the dev schema update and the production migration stay alike.
 */
@Entity
@Table(name = "sales_web_order", indexes = @Index(name = "idx_sales_web_order_customer", columnList = "customer_id"))
public class SalesWebOrderEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    /** The customer the order was placed for; a document re-linked to another customer is nobody's order. */
    @Column(name = "customer_id") public Long customerId;
    @Column(name = "account_id") public Long accountId;
    @Column(name = "account_email", length = 254) public String accountEmail;
    @Column(name = "language", length = 4) public String language;
    /** Moves only through customer actions: 1 at placement, + 1 for every change and for the cancellation. */
    @Column(name = "revision") public Integer revision;
    @Column(name = "placed_at") public Instant placedAt;
    @Column(name = "customer_changed_at") public Instant customerChangedAt;
    @Column(name = "customer_change_summary", length = 1000) public String customerChangeSummary;
    @Column(name = "customer_cancelled_at") public Instant customerCancelledAt;
    /** The processing marker: set once by the first staff mutation, never cleared. */
    @Column(name = "processing_started_at") public Instant processingStartedAt;
    @Column(name = "processing_started_by", length = 120) public String processingStartedBy;
    /** KNOP or AUTOMATISCH. */
    @Column(name = "processing_trigger", length = 16) public String processingTrigger;
    /** SHA-256 of price, quantity and freight as ordered; null when something was still open. */
    @Column(name = "ordered_terms", length = 64) public String orderedTerms;
    @Column(name = "sent_terms", length = 64) public String sentTerms;
    @Column(name = "accepted_terms", length = 64) public String acceptedTerms;
    @Column(name = "order_snapshot", columnDefinition = "TEXT") public String orderSnapshot;
    @Column(name = "received_mail_sent_at") public Instant receivedMailSentAt;
    @Column(name = "processing_mail_sent_at") public Instant processingMailSentAt;
    @Column(name = "mail_error", length = 300) public String mailError;
}
