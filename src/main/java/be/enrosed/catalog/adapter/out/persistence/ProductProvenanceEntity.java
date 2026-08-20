package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "product_provenance")
public class ProductProvenanceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String ownerType;
    public String ownerKey;
    public Long familyId;
    public Long productId;
    public String fieldName;
    public String source;
    public String sourceRecordKey;
    @Column(length = 10000) public String rawValue;
    public String confidence;
    public String status;
    public Instant observedAt;
    public String importKey;
}
