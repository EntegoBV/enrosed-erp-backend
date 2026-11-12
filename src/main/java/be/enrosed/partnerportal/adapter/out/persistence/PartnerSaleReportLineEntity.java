package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One part of a melding entry: the entry (entry_no) of one product, split over its sources.
 * purchase_order_id is the partner container the pieces came from, null for own stock. A SALE
 * part carries the money of its share of the entry (net = gross - costs, never allocated on its
 * own); a BUYBACK part carries the clock price total as gross and -gross as net, and points with
 * mirrors_line_id at the sale part whose stock takes it puts back. Quantities are sales units.
 */
@Entity
@Table(name = "partner_sale_report_line", indexes = {
        @Index(name = "idx_partner_sale_line_report", columnList = "report_id"),
        @Index(name = "idx_partner_sale_line_customer_invoice", columnList = "customer_id, invoice_id"),
        @Index(name = "idx_partner_sale_line_container", columnList = "purchase_order_id, product_id"),
        @Index(name = "idx_partner_sale_line_invoice", columnList = "invoice_id"),
        @Index(name = "idx_partner_sale_line_mirrors", columnList = "mirrors_line_id")
})
public class PartnerSaleReportLineEntity extends PanacheEntityBase {
    public static final String SALE = "SALE";
    public static final String BUYBACK = "BUYBACK";
    /**
     * Written when a melding is withdrawn: one row per part it reversed (mirrors_line_id = that
     * part, stock_movement_id = the reversing movement, no money). Never a part: every reader
     * only counts SALE and BUYBACK.
     */
    public static final String REVERSAL = "REVERSAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "report_id")
    public Long reportId;

    @Column(name = "customer_id")
    public Long customerId;

    @Column(name = "product_id")
    public Long productId;

    @Column(name = "entry_no")
    public Integer entryNo;

    /** SALE or BUYBACK. */
    @Column(name = "line_type", length = 10)
    public String lineType;

    @Column(name = "purchase_order_id")
    public Long purchaseOrderId;

    @Column(name = "quantity")
    public Integer quantity;

    @Column(name = "unit_price_eur", precision = 19, scale = 4)
    public BigDecimal unitPriceEur;

    @Column(name = "gross_eur", precision = 19, scale = 2)
    public BigDecimal grossEur;

    @Column(name = "commission_eur", precision = 19, scale = 2)
    public BigDecimal commissionEur;

    @Column(name = "transport_eur", precision = 19, scale = 2)
    public BigDecimal transportEur;

    @Column(name = "extra_cost_eur", precision = 19, scale = 2)
    public BigDecimal extraCostEur;

    @Column(name = "net_eur", precision = 19, scale = 2)
    public BigDecimal netEur;

    /** Own-stock parts only: external cost per sales unit at report time. */
    @Column(name = "unit_cost_eur", precision = 19, scale = 4)
    public BigDecimal unitCostEur;

    /** True when some pieces of this own part had no known cost and count at the average of the known ones. */
    @Column(name = "unit_cost_estimated")
    public Boolean unitCostEstimated;

    @Column(name = "mirrors_line_id")
    public Long mirrorsLineId;

    @Column(name = "stock_movement_id")
    public Long stockMovementId;

    @Column(name = "invoice_id")
    public Long invoiceId;

    @Column(name = "invoiced_at")
    public Instant invoicedAt;

    public boolean isSale() {
        return SALE.equals(lineType);
    }

    public boolean isBuyback() {
        return BUYBACK.equals(lineType);
    }

    public int quantityOrZero() {
        return quantity == null ? 0 : quantity;
    }
}
