package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Purchasing evidence agreed with one supplier for one product.
 *
 * <p>The supplier id is deliberately snapshotted instead of following the product relation.
 * When a product moves to another supplier, the old agreement photos remain available if the
 * product is ever assigned back, but they are never exposed to the new supplier.</p>
 */
@Entity
@Table(
        name = "product_supplier_agreement_photo",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_product_supplier_agreement_photo_position",
                columnNames = {"product_id", "supplier_id", "position"}),
        indexes = {
                @Index(name = "ix_product_supplier_agreement_photo_scope",
                        columnList = "product_id,supplier_id"),
                @Index(name = "ix_product_supplier_agreement_photo_storage",
                        columnList = "storage_key")
        })
public class ProductSupplierAgreementPhotoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "product_id", nullable = false)
    public long productId;

    /** Supplier at upload time; intentionally not a mutable relationship. */
    @Column(name = "supplier_id", nullable = false)
    public long supplierId;

    @Column(name = "storage_key", nullable = false, length = 80)
    public String storageKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    public String originalFilename;

    @Column(name = "content_type", nullable = false, length = 120)
    public String contentType;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(name = "width_px")
    public Integer widthPx;

    @Column(name = "height_px")
    public Integer heightPx;

    @Column(nullable = false)
    public int position;

    /** English caption printed next to the evidence in the supplier agreement. */
    @Column(name = "caption_en", length = 500)
    public String captionEn;
}
