package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/** An internal channel price, independent from the public catalogue price. */
@Entity
@Table(name = "product_channel_price", uniqueConstraints =
        @UniqueConstraint(name = "uk_product_channel_price", columnNames = {"product_id", "channel"}))
public class ProductChannelPriceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(nullable = false, length = 30) public String channel;
    @Column(name = "manual_price", precision = 19, scale = 4) public BigDecimal manualPrice;
    @Column(name = "manual_unit", length = 30) public String manualUnit;
    @Column(name = "observed_price", precision = 19, scale = 4) public BigDecimal observedPrice;
    @Column(name = "observed_unit", length = 30) public String observedUnit;
    @Column(name = "observed_at") public LocalDateTime observedAt;
    @Column(name = "observed_sale_id") public Long observedSaleId;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
