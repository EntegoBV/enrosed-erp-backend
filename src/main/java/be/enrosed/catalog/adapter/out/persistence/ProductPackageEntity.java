package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "product_package",
        uniqueConstraints = @UniqueConstraint(columnNames = {"family_id", "sourceKey"}))
public class ProductPackageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "family_id", nullable = false)
    public ProductFamilyEntity family;
    public Long productId;
    public String sourceKey;
    public String packageType;
    public int position;
    /** Legacy storage names: length=B and width=D in confirmed-axis displays. */
    public BigDecimal lengthValue;
    public BigDecimal widthValue;
    public BigDecimal heightValue;
    public String dimensionUnit;
    public Integer piecesPerPackage;
    public BigDecimal weightValue;
    public String weightUnit;
    @Column(length = 1000) public String rawValue;
    public String variantExternalId;
    public Boolean axisMeaningConfirmed;
    public String sourceType;
    @Column(length = 2000) public String sourceLocation;
    public Boolean operational;
    public String confidence;
}
