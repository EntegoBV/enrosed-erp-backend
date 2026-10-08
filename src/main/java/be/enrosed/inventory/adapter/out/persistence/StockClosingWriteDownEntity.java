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
 * Output: the write-down of one decision on one layer: pieces, the layer value and the market
 * value per piece, and the amount. The acquisition value stays on the layer.
 */
@Entity
@Table(name = "stock_closing_write_down")
public class StockClosingWriteDownEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "decision_id") public Long decisionId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "layer_position") public Integer layerPosition;
    @Column(name = "layer_label", length = 255) public String layerLabel;
    @Column(name = "quantity") public Integer quantity;
    @Column(name = "layer_unit_eur", precision = 19, scale = 4) public BigDecimal layerUnitEur;
    @Column(name = "market_unit_eur", precision = 19, scale = 4) public BigDecimal marketUnitEur;
    @Column(name = "amount_eur", precision = 19, scale = 2) public BigDecimal amountEur;
    @Column(name = "reason_code", length = 32) public String reasonCode;
    @Column(name = "reason", length = 1000) public String reason;
    @Column(name = "decided_by_name", length = 120) public String decidedByName;
    @Column(name = "decided_at") public Instant decidedAt;
}
