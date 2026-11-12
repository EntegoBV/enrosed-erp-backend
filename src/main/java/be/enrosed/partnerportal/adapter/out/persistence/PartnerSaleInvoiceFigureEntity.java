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
 * What Factureren invoiced per document and product, written in the same transaction as the
 * document (container settlement and own-stock invoice alike), so the preview, the document note
 * and the margin popup show the same numbers later, whatever the settings say by then. ERP only:
 * never in a partner response. Quantities in sales units, sale_pieces in pieces; report_ids is the
 * comma-separated melding ids. Documents from before round 2 have no rows.
 */
@Entity
@Table(name = "partner_sale_invoice_figure", indexes = {
        @Index(name = "idx_partner_sale_invoice_figure_order", columnList = "sales_order_id")
})
public class PartnerSaleInvoiceFigureEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "sales_order_id")
    public Long salesOrderId;

    @Column(name = "product_id")
    public Long productId;

    @Column(name = "sold_quantity")
    public Integer soldQuantity;

    @Column(name = "bought_back_quantity")
    public Integer boughtBackQuantity;

    @Column(name = "quantity")
    public Integer quantity;

    @Column(name = "sale_pieces")
    public Integer salePieces;

    @Column(name = "gross_eur", precision = 19, scale = 2)
    public BigDecimal grossEur;

    @Column(name = "commission_eur", precision = 19, scale = 2)
    public BigDecimal commissionEur;

    @Column(name = "transport_eur", precision = 19, scale = 2)
    public BigDecimal transportEur;

    @Column(name = "extra_eur", precision = 19, scale = 2)
    public BigDecimal extraEur;

    @Column(name = "buyback_cost_eur", precision = 19, scale = 2)
    public BigDecimal buybackCostEur;

    @Column(name = "proceeds_eur", precision = 19, scale = 2)
    public BigDecimal proceedsEur;

    @Column(name = "absorbed_eur", precision = 19, scale = 2)
    public BigDecimal absorbedEur;

    @Column(name = "cost_eur", precision = 19, scale = 2)
    public BigDecimal costEur;

    @Column(name = "value_eur", precision = 19, scale = 2)
    public BigDecimal valueEur;

    @Column(name = "advance_eur", precision = 19, scale = 2)
    public BigDecimal advanceEur;

    @Column(name = "correction_eur", precision = 19, scale = 2)
    public BigDecimal correctionEur;

    @Column(name = "profit_share_pct", precision = 7, scale = 4)
    public BigDecimal profitSharePct;

    @Column(name = "cost_share_pct", precision = 7, scale = 4)
    public BigDecimal costSharePct;

    @Column(name = "report_ids", length = 500)
    public String reportIds;

    @Column(name = "created_at")
    public Instant createdAt;
}
