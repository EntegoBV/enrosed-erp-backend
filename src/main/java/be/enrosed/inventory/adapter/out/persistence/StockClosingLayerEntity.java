package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output: one FIFO layer of a product, newest first: a lot of the period, a layer carried from
 * the previous closing or an opening value. Block GEFACTUREERD holds pieces carved out for an invoice.
 */
@Entity
@Table(name = "stock_closing_layer")
public class StockClosingLayerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "product_id") public Long productId;
    @Column(name = "block", length = 16) public String block;
    @Column(name = "position") public Integer position;
    @Column(name = "source", length = 16) public String source;
    @Column(name = "origin_source", length = 16) public String originSource;
    /** The closing in which the layer was first valued. */
    @Column(name = "origin_closing_id") public Long originClosingId;
    @Column(name = "lot_id") public Long lotId;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "order_number", length = 120) public String orderNumber;
    @Column(name = "display_name", length = 255) public String displayName;
    @Column(name = "received_on") public LocalDate receivedOn;
    @Column(name = "opening_layer_id") public Long openingLayerId;
    @Column(name = "opening_source", length = 500) public String openingSource;
    @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "capacity") public Integer capacity;
    @Column(name = "quantity") public Integer quantity;
    @Column(name = "unit_value_eur", precision = 19, scale = 4) public BigDecimal unitValueEur;
    @Column(name = "unit_goods_eur", precision = 19, scale = 4) public BigDecimal unitGoodsEur;
    @Column(name = "unit_transport_eur", precision = 19, scale = 4) public BigDecimal unitTransportEur;
    @Column(name = "unit_logistics_eur", precision = 19, scale = 4) public BigDecimal unitLogisticsEur;
    @Column(name = "unit_separate_eur", precision = 19, scale = 4) public BigDecimal unitSeparateEur;
    @Column(name = "unit_estimated_eur", precision = 19, scale = 4) public BigDecimal unitEstimatedEur;
    @Column(name = "value_eur", precision = 19, scale = 2) public BigDecimal valueEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "write_down_quantity") public Integer writeDownQuantity;
    @Column(name = "write_down_eur", precision = 19, scale = 2) public BigDecimal writeDownEur;
}
