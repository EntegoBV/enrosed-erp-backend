package be.enrosed.media;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Immutable image choice made the first time a quote or invoice is rendered. */
@Entity
@Table(name = "sales_document_media_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_document_media_snapshot",
                columnNames = {"order_id", "product_id", "role"}),
        indexes = @Index(name = "idx_sales_document_media_storage", columnList = "storage_key"))
public class SalesDocumentMediaSnapshotEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "order_id", nullable = false)
    public Long orderId;
    @Column(name = "product_id", nullable = false)
    public Long productId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public MediaRole role;

    @Column(name = "storage_key", nullable = false, length = 80)
    public String storageKey;
    @Column(name = "content_type", nullable = false, length = 120)
    public String contentType;
    @Column(name = "original_filename", nullable = false, length = 255)
    public String originalFilename;
    @Column(name = "media_asset_id")
    public Long mediaAssetId;
    @Column(name = "media_version_id")
    public Long mediaVersionId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
