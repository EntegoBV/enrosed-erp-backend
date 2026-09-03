package be.enrosed.media;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "media_version",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_media_version_asset_number",
                        columnNames = {"asset_id", "version_number"}),
                @UniqueConstraint(name = "uk_media_version_sha256", columnNames = "sha256")
        },
        indexes = @Index(name = "idx_media_version_storage_key", columnList = "storage_key"))
public class MediaVersionEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "asset_id", nullable = false)
    public Long assetId;
    @Column(name = "version_number", nullable = false)
    public int versionNumber;

    @Column(name = "storage_key", nullable = false, length = 80)
    public String storageKey;
    @Column(name = "original_filename", nullable = false, length = 255)
    public String originalFilename;
    @Column(name = "content_type", nullable = false, length = 120)
    public String contentType;
    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;
    @Column(nullable = false, length = 64)
    public String sha256;
    @Column(name = "width_px")
    public Integer widthPx;
    @Column(name = "height_px")
    public Integer heightPx;
    @Column(name = "thumbnail_storage_key", length = 80)
    public String thumbnailStorageKey;
    @Column(name = "thumbnail_content_type", length = 120)
    public String thumbnailContentType;
    @Column(name = "thumbnail_size_bytes")
    public Long thumbnailSizeBytes;
    @Column(name = "thumbnail_width_px")
    public Integer thumbnailWidthPx;
    @Column(name = "thumbnail_height_px")
    public Integer thumbnailHeightPx;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "created_by", length = 64)
    public String createdBy;
}
