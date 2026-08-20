package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "catalog_import_conflict")
public class CatalogImportConflictEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long importBatchId;
    public String familyKey;
    public String canonicalVariantKey;
    public String fieldName;
    public String code;
    public String severity;
    @Column(length = 4000) public String reason;
    public String confidence;
    public String status;
    @Column(length = 10000) public String sourceValuesJson;
}
