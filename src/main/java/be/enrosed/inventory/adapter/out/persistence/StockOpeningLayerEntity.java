package be.enrosed.inventory.adapter.out.persistence;

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
 * A documented opening value for stock from before the containers in the ERP: quantity, value
 * per piece, date and source. A row is never edited or deleted: it is retired and replaced.
 */
@Entity
@Table(name = "stock_opening_layer")
public class StockOpeningLayerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "quantity") public Integer quantity;
    @Column(name = "unit_value_eur", precision = 19, scale = 4) public BigDecimal unitValueEur;
    @Column(name = "as_of_date") public LocalDate asOfDate;
    @Column(name = "source", length = 500) public String source;
    @Column(name = "note", length = 1000) public String note;
    @Column(name = "created_by", length = 64) public String createdBy;
    @Column(name = "created_by_name", length = 120) public String createdByName;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "retired_by", length = 64) public String retiredBy;
    @Column(name = "retired_at") public Instant retiredAt;
}
