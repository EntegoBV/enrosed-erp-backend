package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "product_price_observation",
        uniqueConstraints = @UniqueConstraint(columnNames = {"productId", "sourceKey"}))
public class ProductPriceObservationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long familyId;
    public Long productId;
    public String ownerType;
    @Column(nullable = false) public String ownerKey;
    @Column(nullable = false) public String sourceKey;
    public String source;
    public String context;
    @Column(precision = 19, scale = 6) public BigDecimal amount;
    public String currency;
    public String taxTreatment;
    public String incoterm;
    public String market;
    public boolean publicPrice;
    /** Source-agnostic public meaning after one-time ingestion: RETAIL or COMPARE_AT. */
    public String publicRole;
    @Column(length = 2000) public String rawValue;
    @Column(length = 2000) public String sourceLocation;
    public Instant observedAt;
}
