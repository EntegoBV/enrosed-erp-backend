package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Append-only history of effective channel prices, including their unit and origin. */
@Entity
@Table(name = "product_channel_price_history", indexes =
        @Index(name = "idx_channel_price_history", columnList = "product_id,channel,changed_at"))
public class ProductChannelPriceHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(nullable = false, length = 30) public String channel;
    @Column(name = "price_excl_vat", precision = 19, scale = 4) public BigDecimal priceExclVat;
    @Column(length = 30) public String unit;
    @Column(length = 30) public String source;
    @Column(name = "source_sale_id") public Long sourceSaleId;
    @Column(name = "changed_at", nullable = false) public Instant changedAt;
    @Column(name = "changed_by", nullable = false, length = 80) public String changedBy;
}
