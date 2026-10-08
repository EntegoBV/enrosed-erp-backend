package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Output: one product at one location: the count facts behind the anchor, the roll to the
 * closing date and the product value spread over the locations by quantity, for information.
 */
@Entity
@Table(name = "stock_closing_line")
public class StockClosingLineEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "location_id") public Long locationId;
    @Column(name = "location_name", length = 255) public String locationName;
    @Column(name = "anchor", length = 16) public String anchor;
    /** The base session of the location; count_line_id is the anchor line of the product. */
    @Column(name = "count_id") public Long countId;
    @Column(name = "count_line_id") public Long countLineId;
    @Column(name = "anchored_at") public Instant anchoredAt;
    @Column(name = "expected_quantity") public Integer expectedQuantity;
    @Column(name = "counted_quantity") public Integer countedQuantity;
    @Column(name = "count_difference") public Integer countDifference;
    @Column(name = "count_reason_code", length = 32) public String countReasonCode;
    @Column(name = "count_reason_note", length = 500) public String countReasonNote;
    @Column(name = "counted_by_name", length = 120) public String countedByName;
    @Column(name = "counted_at") public Instant countedAt;
    @Column(name = "anchor_quantity") public Integer anchorQuantity;
    @Column(name = "roll_delta") public Integer rollDelta;
    @Column(name = "closing_quantity") public Integer closingQuantity;
    @Column(name = "cost_value_eur", precision = 19, scale = 2) public BigDecimal costValueEur;
    @Column(name = "goods_eur", precision = 19, scale = 2) public BigDecimal goodsEur;
    @Column(name = "transport_eur", precision = 19, scale = 2) public BigDecimal transportEur;
    @Column(name = "logistics_eur", precision = 19, scale = 2) public BigDecimal logisticsEur;
    @Column(name = "separate_eur", precision = 19, scale = 2) public BigDecimal separateEur;
    @Column(name = "opening_eur", precision = 19, scale = 2) public BigDecimal openingEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "write_down_eur", precision = 19, scale = 2) public BigDecimal writeDownEur;
    @Column(name = "own_value_eur", precision = 19, scale = 2) public BigDecimal ownValueEur;
}
