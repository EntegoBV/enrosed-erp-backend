package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One batch of a product: the pieces of one received container line (or an opening / count batch) that are still on
 * the shelf, and their cost per selling unit. Created by {@code docs/migrations/2026-10-25/stock-batches-postgresql.sql};
 * every column named, all nullable, plain text instead of enums, no relations.
 */
@Entity
@Table(name = "stock_batch")
public class StockBatchEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "product_id") public Long productId;
    /** RECEIPT, OPENING or COUNT (StockBatch.Kind). */
    @Column(name = "kind", length = 16) public String kind;
    /** OWN or PARTNER (StockBatch.Owner). */
    @Column(name = "owner", length = 16) public String owner;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "purchase_order_line_id") public Long purchaseOrderLineId;
    @Column(name = "order_number", length = 120) public String orderNumber;
    @Column(name = "supplier_id") public Long supplierId;
    @Column(name = "product_supplier_id") public Long productSupplierId;
    @Column(name = "received_on") public LocalDate receivedOn;
    @Column(name = "booked_at") public Instant bookedAt;
    /** Physical pieces per selling unit when the batch was born (display factor, else 1). */
    @Column(name = "unit_pieces") public Integer unitPieces;
    @Column(name = "pieces_in") public Integer piecesIn;
    @Column(name = "pieces_left") public Integer piecesLeft;
    /** APPLIED, CALCULATED, CARD or NONE (StockBatch.CostState). */
    @Column(name = "cost_state", length = 16) public String costState;
    @Column(name = "unit_cost_eur", precision = 19, scale = 4) public BigDecimal unitCostEur;
    @Column(name = "unit_cost_excl_enrosed_eur", precision = 19, scale = 4) public BigDecimal unitCostExclEnrosedEur;
    /* One selling unit of the cost, part by part, in euro. */
    @Column(name = "goods_eur", precision = 19, scale = 4) public BigDecimal goodsEur;
    @Column(name = "origin_eur", precision = 19, scale = 4) public BigDecimal originEur;
    @Column(name = "freight_eur", precision = 19, scale = 4) public BigDecimal freightEur;
    @Column(name = "duty_eur", precision = 19, scale = 4) public BigDecimal dutyEur;
    @Column(name = "destination_eur", precision = 19, scale = 4) public BigDecimal destinationEur;
    /** The Enrosed kost. */
    @Column(name = "enrosed_eur", precision = 19, scale = 4) public BigDecimal enrosedEur;
    @Column(name = "separate_eur", precision = 19, scale = 4) public BigDecimal separateEur;
    @Column(name = "cost_applied_at") public Instant costAppliedAt;
    @Column(name = "cost_applied_by", length = 80) public String costAppliedBy;
    @Column(name = "note", length = 300) public String note;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "created_by", length = 80) public String createdBy;
}
