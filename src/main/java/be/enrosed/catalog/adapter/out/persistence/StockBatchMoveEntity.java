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
 * One signed change of a batch; {@code batchId} null = pieces no batch could give ("zonder batch").
 * {@code linkMoveId} points at the take a return restores, or at the unassigned take a later batch settles.
 * Created by {@code docs/migrations/2026-10-25/stock-batches-postgresql.sql}; all nullable, no relations.
 */
@Entity
@Table(name = "stock_batch_move")
public class StockBatchMoveEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "batch_id") public Long batchId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "stock_movement_id") public Long stockMovementId;
    @Column(name = "link_move_id") public Long linkMoveId;
    /** A StockMovement.Kind name, or OPENING, COUNT_IN, BACKFILL, RECEIPT_CORRECTION, REPAIR. */
    @Column(name = "kind", length = 40) public String kind;
    @Column(name = "pieces") public Integer pieces;
    @Column(name = "unit_pieces") public Integer unitPieces;
    @Column(name = "unit_cost_eur", precision = 19, scale = 4) public BigDecimal unitCostEur;
    @Column(name = "unit_cost_excl_enrosed_eur", precision = 19, scale = 4) public BigDecimal unitCostExclEnrosedEur;
    @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "tica_sale_id") public Long ticaSaleId;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "pinned") public Boolean pinned;
    @Column(name = "reference", length = 160) public String reference;
    @Column(name = "moved_at") public Instant movedAt;
    @Column(name = "actor", length = 80) public String actor;
}
