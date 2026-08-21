package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

/** Reusable merchandising collection; unlike an operational category, families may join many. */
@Entity
@Table(name = "product_collection")
public class ProductCollectionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true) public String collectionKey;
    public String name;
    public String eyebrow;
    @Column(length = 4000) public String description;
    public int position;
    public String mobileName;
    /** Runtime FK identity; portable manifests use featuredCanonicalVariantKey. */
    public Long featuredProductId;
}
