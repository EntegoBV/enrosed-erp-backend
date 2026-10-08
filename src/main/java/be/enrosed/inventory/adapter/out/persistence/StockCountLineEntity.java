package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One product of a count session. The product facts are frozen at the start; expected is the
 * live level at the moment the count was saved, and the booked figures are written once at booking.
 */
@Entity
@Table(name = "stock_count_line")
public class StockCountLineEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "count_id") public Long countId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "category_name", length = 255) public String categoryName;
    @Column(name = "family_id") public Long familyId;
    @Column(name = "unit_key", length = 32) public String unitKey;
    @Column(name = "sales_unit", length = 16) public String salesUnit;
    @Column(name = "pieces_per_unit") public Integer piecesPerUnit;
    @Column(name = "added_by_hand") public Boolean addedByHand;
    @Column(name = "expected_quantity") public Integer expectedQuantity;
    @Column(name = "expected_at") public Instant expectedAt;
    @Column(name = "counted_quantity") public Integer countedQuantity;
    @Column(name = "difference") public Integer difference;
    @Column(name = "reason_code", length = 32) public String reasonCode;
    @Column(name = "reason_note", length = 500) public String reasonNote;
    @Column(name = "counted_by", length = 64) public String countedBy;
    @Column(name = "counted_by_name", length = 120) public String countedByName;
    @Column(name = "counted_at") public Instant countedAt;
    /** Raised by every write; a save with a stale value is refused. */
    @Column(name = "revision") public Integer revision;
    /** The counter states that the difference does not come from the open invoice or container shown. */
    @Column(name = "documents_confirmed") public Boolean documentsConfirmed;
    @Column(name = "live_at_booking") public Integer liveAtBooking;
    @Column(name = "booked_delta") public Integer bookedDelta;
    @Column(name = "booked_quantity") public Integer bookedQuantity;
    @Column(name = "booked_at") public Instant bookedAt;
}
