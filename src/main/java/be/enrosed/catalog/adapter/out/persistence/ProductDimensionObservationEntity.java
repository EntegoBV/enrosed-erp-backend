package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

/**
 * A source-accurate dimension observation. Values stay ordered and JSON encoded
 * because a source does not always prove its axes. Where axes are confirmed,
 * the display convention is Breedte × Diepte × Hoogte (B × D × H).
 */
@Entity
@Table(name = "product_dimension_observation",
        uniqueConstraints = @UniqueConstraint(columnNames = {"familyId", "sourceKey"}))
public class ProductDimensionObservationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long familyId;
    public Long productId;
    @Column(nullable = false) public String sourceKey;
    public int position;
    public String dimensionType;
    @Column(length = 2000) public String valuesJson;
    public String unit;
    @Column(length = 2000) public String rawValue;
    public Boolean axisMeaningConfirmed;
    public String sourceType;
    @Column(length = 2000) public String sourceLocation;
    public Boolean operational;
    public String confidence;
}
