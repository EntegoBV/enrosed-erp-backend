package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Append-only history of a customer's prices: every set, change, end date and removal, with
 * where it came from (CUSTOMER_SCREEN, CATALOG_EXPORT or CUSTOMER_DELETED). A null price is a
 * removal; the previous price says what it replaced.
 */
@Entity
@Table(name = "customer_product_price_history",
        indexes = @Index(name = "idx_customer_price_history", columnList = "customer_id,product_id,changed_at"))
public class CustomerProductPriceHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "customer_id", nullable = false) public Long customerId;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(name = "price_excl_vat", precision = 19, scale = 2) public BigDecimal priceExclVat;
    @Column(name = "previous_price_excl_vat", precision = 19, scale = 2) public BigDecimal previousPriceExclVat;
    @Column(name = "sales_unit", length = 16) public String salesUnit;
    @Column(name = "pieces_per_unit") public Integer piecesPerUnit;
    @Column(name = "unit_key", length = 40) public String unitKey;
    @Column(name = "valid_until") public LocalDate validUntil;
    @Column(name = "source", nullable = false, length = 30) public String source;
    @Column(name = "changed_at", nullable = false) public Instant changedAt;
    @Column(name = "changed_by", nullable = false, length = 80) public String changedBy;
}
