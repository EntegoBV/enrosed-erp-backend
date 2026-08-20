package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "catalog_import_batch")
public class CatalogImportBatchEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true) public String importKey;
    @Column(nullable = false, length = 64) public String sourceDigest;
    @Column(length = 64) public String payloadSha256;
    /** Server-canonical digest used in addition to the generator's verified raw-payload digest. */
    @Column(length = 64) public String contentDigest;
    public String transformVersion;
    public String status;
    public Instant generatedAt;
    public Instant appliedAt;
    public int familyCount;
    public int variantCount;
    public int imageCount;
    public int conflictCount;
}
