package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The price agreed with one customer for one product (klantprijs): EUR excl. VAT on the product's
 * own sales unit, like the catalogue price, with the basis it was set on and the catalogue price
 * of that moment. Plain strings and no foreign keys (docs/migrations/2026-11-06/
 * customer-prices-and-catalog-exports-postgresql.sql); the service keeps the integrity.
 */
@Entity
@Table(name = "customer_product_price",
        uniqueConstraints = @UniqueConstraint(name = "uk_customer_product_price",
                columnNames = {"customer_id", "product_id"}),
        indexes = @Index(name = "idx_customer_product_price_product", columnList = "product_id"))
public class CustomerProductPriceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "customer_id", nullable = false) public Long customerId;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(name = "price_excl_vat", nullable = false, precision = 19, scale = 2) public BigDecimal priceExclVat;
    /** The basis the price was set on: SalesUnit name, pieces per display (null per piece), unit key. */
    @Column(name = "sales_unit", nullable = false, length = 16) public String salesUnit;
    @Column(name = "pieces_per_unit") public Integer piecesPerUnit;
    @Column(name = "unit_key", length = 40) public String unitKey;
    /** The catalogue price when this price was set, so drift against the catalogue stays visible. */
    @Column(name = "catalogue_price_eur", precision = 19, scale = 2) public BigDecimal cataloguePriceEur;
    /** Null: until changed. An expired price is kept but no longer applied. */
    @Column(name = "valid_until") public LocalDate validUntil;
    @Column(name = "note", length = 300) public String note;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "created_by", nullable = false, length = 80) public String createdBy;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 80) public String updatedBy;
}
