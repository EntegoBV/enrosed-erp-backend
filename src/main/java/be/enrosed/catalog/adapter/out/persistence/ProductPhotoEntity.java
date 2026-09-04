package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "product_photo")
public class ProductPhotoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    public ProductEntity product;

    /** Name of the file in storage. */
    public String storageKey;
    public String originalFilename;
    public String contentType;
    public long sizeBytes;
    public Integer widthPx;
    public Integer heightPx;
    public int position;
    /** Family-gallery source metadata; null means a product-specific legacy upload. */
    public Long familyPhotoId;
    /** Channels this photo opens, comma-separated (WEBSITE, CATALOGUE); null when none. */
    @jakarta.persistence.Column(name = "lead_roles", length = 60)
    public String leadRoles;
}
