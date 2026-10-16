package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One moment a product's landed cost was set from a container. Never
 * deleted: containers come in cheaper and dearer, and the line of costs
 * over time is what the product page shows under its cost.
 */
@Entity
@Table(name = "product_cost_history")
public class ProductCostHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(name = "landed_unit_eur", nullable = false, precision = 19, scale = 4) public BigDecimal landedUnitEur;
    @Column(name = "previous_landed_unit_eur", precision = 19, scale = 4) public BigDecimal previousLandedUnitEur;
    /** The container the cost came from, by number; free text for older sources. */
    @Column(length = 120) public String source;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    public Integer quantity;
    @Column(name = "exw_price", precision = 19, scale = 4) public BigDecimal exwPrice;
    @Column(name = "exw_currency", length = 3) public String exwCurrency;
    @Column(name = "applied_at", nullable = false) public Instant appliedAt;
    @Column(name = "applied_by", length = 80) public String appliedBy;

    /* Since 2026-10-16, all nullable (docs/migrations/2026-10-16/product-cost-history-basis-postgresql.sql).
       Plain text instead of enums: no generated check constraint has to learn a later value. */
    /** APPLY, REAPPLY, SEED or COPY (ProductCostKind). */
    @Column(name = "kind", length = 16) public String kind;
    /** The basis the cost was applied on: SalesUnit name, display pieces, unit key. */
    @Column(name = "sales_unit", length = 16) public String salesUnit;
    @Column(name = "pieces_per_unit") public Integer piecesPerUnit;
    @Column(name = "unit_key", length = 40) public String unitKey;
    /** In the EXW currency, like the factory price next to it. */
    @Column(name = "extra_unit_cost", precision = 19, scale = 4) public BigDecimal extraUnitCost;
    /* One unit of the cost, part by part, in euro. */
    @Column(name = "goods_eur", precision = 19, scale = 4) public BigDecimal goodsEur;
    @Column(name = "origin_eur", precision = 19, scale = 4) public BigDecimal originEur;
    @Column(name = "freight_eur", precision = 19, scale = 4) public BigDecimal freightEur;
    @Column(name = "duty_eur", precision = 19, scale = 4) public BigDecimal dutyEur;
    @Column(name = "destination_eur", precision = 19, scale = 4) public BigDecimal destinationEur;
    /** The Enrosed kost. */
    @Column(name = "enrosed_eur", precision = 19, scale = 4) public BigDecimal enrosedEur;
    /** Inspection and other named costs, when a key spread them into the piece price. */
    @Column(name = "separate_eur", precision = 19, scale = 4) public BigDecimal separateEur;
}
