package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "product_external_identifier",
        uniqueConstraints = @UniqueConstraint(columnNames = {
                "ownerType", "ownerKey", "source", "identifierType", "externalValue"}))
public class ProductExternalIdentifierEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String ownerType;
    @Column(nullable = false) public String ownerKey;
    public Long familyId;
    public Long productId;
    @Column(nullable = false) public String source;
    @Column(nullable = false) public String identifierType;
    @Column(nullable = false, length = 1000) public String externalValue;
    public Boolean confirmed;
}
