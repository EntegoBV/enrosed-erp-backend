package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "product_family_photo",
        uniqueConstraints = @UniqueConstraint(columnNames = {"family_id", "sourceKey"}),
        indexes = @Index(name = "idx_family_photo_variant_product", columnList = "variant_product_id"))
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
    /** Rendition policy that processed this row; null is legacy/imported data. */
    @Column(name = "small_rendition_policy", length = 40)
    public String smallRenditionVersion;

    @Column(nullable = false, length = 80) public String largeStorageKey;
    public String largeContentType;
    @Column(length = 64) public String largeSha256;
    public long largeSizeBytes;
    public Integer largeWidthPx;
    public Integer largeHeightPx;
    public int position;
    /** Canonical variant link. Legacy text fields below remain fallback/import evidence only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_product_id",
            foreignKey = @ForeignKey(name = "fk_family_photo_variant_product"))
    public ProductEntity variantProduct;
    public String variantExternalId;
    public String variantColor;
    public String altTextSource;
    @Column(length = 10000) public String altTextsJson;
    /**
     * Explicit public destinations for this image. A null value is the additive-migration
     * compatibility state: images that already existed before this field remain available on
     * every catalogue channel. Administrator uploads always persist an explicit empty array and
     * therefore stay internal until a user publishes them.
     */
    @Column(name = "published_channels_json", length = 255)
    public String publishedChannelsJson;
}
