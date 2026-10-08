package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Output: one stock ledger row around the closing date, with the question whether it counts
 * towards the closing quantity. A row that vanished from the ledger is kept with removed set.
 */
@Entity
@Table(name = "stock_closing_movement")
public class StockClosingMovementEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "movement_id") public Long movementId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "location_id") public Long locationId;
    @Column(name = "location_name", length = 255) public String locationName;
    @Column(name = "count_id") public Long countId;
    @Column(name = "booked_at") public Instant bookedAt;
    @Column(name = "kind", length = 40) public String kind;
    @Column(name = "kind_label", length = 80) public String kindLabel;
    @Column(name = "ref_text", length = 255) public String refText;
    @Column(name = "actor", length = 64) public String actor;
    @Column(name = "delta") public Integer delta;
    @Column(name = "quantity_after") public Integer quantityAfter;
    @Column(name = "effective_delta") public Integer effectiveDelta;
    @Column(name = "no_anchor") public Boolean noAnchor;
    @Column(name = "business_date") public LocalDate businessDate;
    @Column(name = "business_date_source", length = 80) public String businessDateSource;
    @Column(name = "default_applied") public Boolean defaultApplied;
    @Column(name = "default_note", length = 255) public String defaultNote;
    @Column(name = "applied") public Boolean applied;
    @Column(name = "applied_reason", length = 1000) public String appliedReason;
    @Column(name = "review") public Boolean review;
    @Column(name = "removed") public Boolean removed;
}
