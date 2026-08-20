package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

/** Ordered family-to-collection membership with an explicit primary collection. */
@Entity
@Table(name = "product_family_collection",
        uniqueConstraints = @UniqueConstraint(columnNames = {"family_id", "collection_id"}))
public class ProductFamilyCollectionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "family_id", nullable = false)
    public ProductFamilyEntity family;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "collection_id", nullable = false)
    public ProductCollectionEntity collection;
    public int position;
    public boolean primaryCollection;
}
