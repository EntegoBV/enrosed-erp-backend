package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Output: one product in a closing: the closing quantity, what is set apart, the own quantity
 * and its acquisition value by component, before and after write-down.
 */
@Entity
@Table(name = "stock_closing_article")
public class StockClosingArticleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "category_name", length = 255) public String categoryName;
    @Column(name = "unit_key", length = 32) public String unitKey;
    @Column(name = "sales_unit", length = 16) public String salesUnit;
    @Column(name = "pieces_per_unit") public Integer piecesPerUnit;
    @Column(name = "demo") public Boolean demo;
    @Column(name = "active") public Boolean active;
    @Column(name = "counted_quantity") public Integer countedQuantity;
    @Column(name = "roll_delta") public Integer rollDelta;
    @Column(name = "closing_quantity") public Integer closingQuantity;
    @Column(name = "third_party_quantity") public Integer thirdPartyQuantity;
    @Column(name = "partner_quantity") public Integer partnerQuantity;
    @Column(name = "invoiced_out_quantity") public Integer invoicedOutQuantity;
    @Column(name = "own_quantity") public Integer ownQuantity;
    @Column(name = "unvalued_quantity") public Integer unvaluedQuantity;
    @Column(name = "cost_value_eur", precision = 19, scale = 2) public BigDecimal costValueEur;
    @Column(name = "goods_eur", precision = 19, scale = 2) public BigDecimal goodsEur;
    @Column(name = "transport_eur", precision = 19, scale = 2) public BigDecimal transportEur;
    @Column(name = "logistics_eur", precision = 19, scale = 2) public BigDecimal logisticsEur;
    @Column(name = "separate_eur", precision = 19, scale = 2) public BigDecimal separateEur;
    @Column(name = "opening_eur", precision = 19, scale = 2) public BigDecimal openingEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "average_unit_eur", precision = 19, scale = 4) public BigDecimal averageUnitEur;
    @Column(name = "write_down_eur", precision = 19, scale = 2) public BigDecimal writeDownEur;
    @Column(name = "own_value_eur", precision = 19, scale = 2) public BigDecimal ownValueEur;
    @Column(name = "previous_write_down_eur", precision = 19, scale = 2) public BigDecimal previousWriteDownEur;
    @Column(name = "status", length = 24) public String status;
}
