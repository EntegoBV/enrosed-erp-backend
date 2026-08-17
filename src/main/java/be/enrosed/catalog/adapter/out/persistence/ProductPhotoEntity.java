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

    /** Naam van het bestand in de opslag. */
    public String storageKey;
    public String originalFilename;
    public String contentType;
    public long sizeBytes;
    public Integer widthPx;
    public Integer heightPx;
    public int position;
}
