package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Output: one receipt lot, a product on a container, with its quantities, the allocation key
 * behind each cost share and the acquisition value per piece built from four rounded components.
 */
@Entity
@Table(name = "stock_closing_lot")
public class StockClosingLotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "container_id") public Long containerId;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "role", length = 16) public String role;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "ordered_quantity") public Integer orderedQuantity;
    @Column(name = "received_quantity") public Integer receivedQuantity;
    @Column(name = "damaged_quantity") public Integer damagedQuantity;
    @Column(name = "later_lost_quantity") public Integer laterLostQuantity;
    @Column(name = "billed_quantity") public Integer billedQuantity;
    @Column(name = "goods_divisor") public Integer goodsDivisor;
    @Column(name = "cost_divisor") public Integer costDivisor;
    @Column(name = "capacity") public Integer capacity;
    @Column(name = "unit_price_eur", precision = 19, scale = 4) public BigDecimal unitPriceEur;
    /** The weight used for each of the four shares; the transport key is null when the container is not CIF. */
    @Column(name = "goods_key_eur", precision = 19, scale = 2) public BigDecimal goodsKeyEur;
    @Column(name = "transport_key_eur", precision = 19, scale = 2) public BigDecimal transportKeyEur;
    @Column(name = "logistics_key_eur", precision = 19, scale = 2) public BigDecimal logisticsKeyEur;
    @Column(name = "separate_key_eur", precision = 19, scale = 2) public BigDecimal separateKeyEur;
    @Column(name = "goods_eur", precision = 19, scale = 2) public BigDecimal goodsEur;
    @Column(name = "price_credit_eur", precision = 19, scale = 2) public BigDecimal priceCreditEur;
    @Column(name = "transport_eur", precision = 19, scale = 2) public BigDecimal transportEur;
    @Column(name = "logistics_eur", precision = 19, scale = 2) public BigDecimal logisticsEur;
    @Column(name = "separate_eur", precision = 19, scale = 2) public BigDecimal separateEur;
    @Column(name = "lot_cost_eur", precision = 19, scale = 2) public BigDecimal lotCostEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "unit_goods_eur", precision = 19, scale = 4) public BigDecimal unitGoodsEur;
    @Column(name = "unit_transport_eur", precision = 19, scale = 4) public BigDecimal unitTransportEur;
    @Column(name = "unit_logistics_eur", precision = 19, scale = 4) public BigDecimal unitLogisticsEur;
    @Column(name = "unit_separate_eur", precision = 19, scale = 4) public BigDecimal unitSeparateEur;
    @Column(name = "unit_value_eur", precision = 19, scale = 4) public BigDecimal unitValueEur;
    @Column(name = "unit_estimated_eur", precision = 19, scale = 4) public BigDecimal unitEstimatedEur;
    /** Split of the logistics share according to the calculation, for information only. */
    @Column(name = "calc_origin_eur", precision = 19, scale = 2) public BigDecimal calcOriginEur;
    @Column(name = "calc_freight_eur", precision = 19, scale = 2) public BigDecimal calcFreightEur;
    @Column(name = "calc_duty_eur", precision = 19, scale = 2) public BigDecimal calcDutyEur;
    @Column(name = "calc_destination_eur", precision = 19, scale = 2) public BigDecimal calcDestinationEur;
    @Column(name = "calc_duty_rate_pct", precision = 19, scale = 4) public BigDecimal calcDutyRatePct;
    @Column(name = "previous_unit_value_eur", precision = 19, scale = 4) public BigDecimal previousUnitValueEur;
    @Column(name = "previous_closing_id") public Long previousClosingId;
    @Column(name = "status", length = 24) public String status;
}
