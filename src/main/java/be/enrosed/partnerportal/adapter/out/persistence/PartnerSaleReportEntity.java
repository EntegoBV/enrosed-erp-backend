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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One melding (VV-&lt;id&gt;) of a partner: what sold on one day through one channel, and what was
 * bought back on the clock. The cost percents are copied from the settings when it is made and
 * never re-read. login_id is null when staff entered it.
 *
 * <p>Round 2: confirmed_* is set when staff confirm it (Factureren takes only confirmed meldingen),
 * updated_* by an edit, buyer_customer_id names the buyer of a direct sale. An <b>archive row</b>
 * (revision_of = the edited melding, voided "Gewijzigd") holds the old lines of an edit that rebooked;
 * it is never listed, only read by id for the audit trail. extra_cost_mode / extra_cost_value are
 * round-1 only: newer meldingen keep their extra cost lines in partner_sale_report_cost.
 */
@Entity
@Table(name = "partner_sale_report",
        uniqueConstraints = @UniqueConstraint(name = "uq_partner_sale_report_client_ref",
                columnNames = {"customer_id", "client_ref"}),
        indexes = {
                @Index(name = "idx_partner_sale_report_customer", columnList = "customer_id, id"),
                @Index(name = "idx_partner_sale_report_sold_on", columnList = "sold_on"),
                @Index(name = "idx_partner_sale_report_revision_of", columnList = "revision_of"),
                @Index(name = "idx_partner_sale_report_buyer", columnList = "buyer_customer_id")
        })
public class PartnerSaleReportEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "login_id")
    public Long loginId;

    /** AUCTION or DIRECT. */
    @Column(name = "channel", length = 20)
    public String channel;

    @Column(name = "sold_on")
    public LocalDate soldOn;

    @Column(name = "commission_pct", precision = 7, scale = 4)
    public BigDecimal commissionPct;

    @Column(name = "transport_pct", precision = 7, scale = 4)
    public BigDecimal transportPct;

    /** AMOUNT, PCT or null. */
    @Column(name = "extra_cost_mode", length = 10)
    public String extraCostMode;

    @Column(name = "extra_cost_value", precision = 19, scale = 4)
    public BigDecimal extraCostValue;

    @Column(name = "note", length = 500)
    public String note;

    @Column(name = "location_id")
    public Long locationId;

    @Column(name = "client_ref", length = 64)
    public String clientRef;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "created_by_name", length = 100)
    public String createdByName;

    @Column(name = "voided_at")
    public Instant voidedAt;

    @Column(name = "voided_by", length = 64)
    public String voidedBy;

    @Column(name = "void_reason", length = 200)
    public String voidReason;

    @Column(name = "confirmed_at")
    public Instant confirmedAt;

    @Column(name = "confirmed_by", length = 64)
    public String confirmedBy;

    @Column(name = "confirmed_by_name", length = 100)
    public String confirmedByName;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    public String updatedBy;

    @Column(name = "updated_by_name", length = 100)
    public String updatedByName;

    /** Set on an archive row only: the melding whose old lines it holds. */
    @Column(name = "revision_of")
    public Long revisionOf;

    /** The buyer of a direct sale (an ERP customer, never a partner); null otherwise. */
    @Column(name = "buyer_customer_id")
    public Long buyerCustomerId;

    public boolean isVoided() {
        return voidedAt != null;
    }

    /** An archive row of an edit (never listed, never editable). */
    public boolean isArchive() {
        return revisionOf != null;
    }

    /** The optimistic edit token: the last edit, else the creation, as an ISO instant. */
    public String editToken() {
        Instant at = updatedAt != null ? updatedAt : createdAt;
        return at == null ? null : at.toString();
    }
}
