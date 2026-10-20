package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.StockMovement;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity
@Table(name = "stock_movement", indexes = @Index(columnList = "productId, at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_tica_revision_target",
                columnNames = {"tica_revision_id", "productId", "locationId"}))
public class StockMovementEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public long productId;
    /** Where it happened; null on lines booked before locations existed. */
    public Long locationId;
    @Column(nullable = false) public Instant at;
    @Column(nullable = false) public int delta;
    @Column(nullable = false) public int quantityAfter;
    /*
     * Stored as plain text on purpose. An enum column gets a CHECK constraint
     * listing the values known at creation, and "schema update" never widens
     * it - the first new kind would then be refused in production. Text it
     * is, and the code keeps the list.
     */
    @Column(nullable = false, length = 40) public String kind;

    public StockMovement.Kind kind() {
        return StockMovement.Kind.valueOf(kind);
    }
    public String reference;
    public String actor;
    /** The container a later damage or shortage report points at; null otherwise. */
    @Column(name = "purchase_order_id")
    public Long purchaseOrderId;
    @Column(name = "tica_sale_id") public Long ticaSaleId;
    @Column(name = "tica_revision_id") public Long ticaRevisionId;
    @Column(name = "source_sold_at") public LocalDateTime sourceSoldAt;
    @Column(name = "loose_delta") public Integer looseDelta;
    @Column(name = "loose_pieces_after") public Integer loosePiecesAfter;
    @Column(name = "unit_pieces") public Integer unitPieces;
    @Column(name = "source_quantity", precision = 15, scale = 3) public BigDecimal sourceQuantity;
    @Column(name = "source_unit", length = 30) public String sourceUnit;
}
