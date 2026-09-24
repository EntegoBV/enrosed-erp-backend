package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Shared catalogue ordering, independent of export selections and product merchandising. */
@Entity
@Table(name = "catalog_export_order")
public class CatalogExportOrderEntity {
    @Id
    public Long id = 1L;

    @Version
    @Column(nullable = false)
    public long revision;

    @Column(name = "ordered_ids_json", nullable = false, length = 240000)
    public String orderedIdsJson = "[]";

    @Column(name = "updated_at")
    public Instant updatedAt;
}
