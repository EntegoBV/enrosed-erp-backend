package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Audit marker for a versioned, source-bounded localization backfill. */
@Entity
@Table(name = "catalog_localization_backfill")
public class CatalogLocalizationBackfillEntity {
    @Id
    public String version;
    public String payloadSha256;
    public Instant appliedAt;
    public int insertedRows;
    public int correctedKnownFields;
}
