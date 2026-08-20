package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "product_family_photo",
        uniqueConstraints = @UniqueConstraint(columnNames = {"family_id", "sourceKey"}))
public class ProductFamilyPhotoEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "family_id", nullable = false)
    public ProductFamilyEntity family;
    public String sourceKey;
    public String sourceAssetId;
    @Column(length = 2000) public String sourceUrl;
    public String originalFilename;
    public Integer originalWidthPx;
    public Integer originalHeightPx;

    @Column(nullable = false, length = 80) public String smallStorageKey;
    public String smallContentType;
    @Column(length = 64) public String smallSha256;
    public long smallSizeBytes;
    public Integer smallWidthPx;
    public Integer smallHeightPx;

    @Column(nullable = false, length = 80) public String largeStorageKey;
    public String largeContentType;
    @Column(length = 64) public String largeSha256;
    public long largeSizeBytes;
    public Integer largeWidthPx;
    public Integer largeHeightPx;
    public int position;
    public String variantExternalId;
    public String variantColor;
    public String altTextSource;
    @Column(length = 10000) public String altTextsJson;
}
