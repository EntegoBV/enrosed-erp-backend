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
 * A request for a website login, waiting for or carrying a staff decision. Status
 * (PENDING, APPROVED, REJECTED), source (QUOTE, ORDER_SCREEN, NEW_LINK) and language
 * are plain strings, never enum columns. PostgreSQL also holds a partial unique
 * index (one PENDING row per e-mail) that is deliberately not declared here.
 */
@Entity
@Table(name = "customer_login_request", indexes = {
        @Index(name = "idx_customer_login_request_status", columnList = "status,created_at"),
        @Index(name = "idx_customer_login_request_email", columnList = "email"),
        @Index(name = "idx_customer_login_request_customer", columnList = "customer_id")
}, uniqueConstraints = @UniqueConstraint(
        name = "uq_customer_login_request_reference", columnNames = "reference"))
public class CustomerLoginRequestEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "reference", length = 32)
    public String reference;

    @Column(name = "status", length = 16)
    public String status;

    @Column(name = "source", length = 16)
    public String source;

    @Column(name = "language", length = 4)
    public String language;

    @Column(name = "company_name", length = 160)
    public String companyName;

    @Column(name = "company_country_code", length = 2)
    public String companyCountryCode;

    @Column(name = "vat_number", length = 32)
    public String vatNumber;

    @Column(name = "contact_name", length = 120)
    public String contactName;

    /** Always the output of AccountEmails.normalize. */
    @Column(name = "email", length = 254)
    public String email;

    @Column(name = "phone", length = 50)
    public String phone;

    @Column(name = "message", length = 1_000)
    public String message;

    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "sales_order_id")
    public Long salesOrderId;

    @Column(name = "sales_order_number", length = 40)
    public String salesOrderNumber;

    @Column(name = "account_id")
    public Long accountId;

    @Column(name = "repeat_count")
    public int repeatCount;

    /** JSON array of the later, differing submissions for the same open request. */
    @Column(name = "later_submissions", length = 8_000)
    public String laterSubmissions;

    @Column(name = "privacy_accepted_at")
    public Instant privacyAcceptedAt;

    @Column(name = "privacy_policy_version", length = 32)
    public String privacyPolicyVersion;

    @Column(name = "decided_at")
    public Instant decidedAt;

    @Column(name = "decided_by", length = 64)
    public String decidedBy;

    @Column(name = "decision_note", length = 500)
    public String decisionNote;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;
}
