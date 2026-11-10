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
 */
@Entity
@Table(name = "partner_sale_report",
        uniqueConstraints = @UniqueConstraint(name = "uq_partner_sale_report_client_ref",
                columnNames = {"customer_id", "client_ref"}),
        indexes = {
                @Index(name = "idx_partner_sale_report_customer", columnList = "customer_id, id"),
                @Index(name = "idx_partner_sale_report_sold_on", columnList = "sold_on")
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

    public boolean isVoided() {
        return voidedAt != null;
    }
}
