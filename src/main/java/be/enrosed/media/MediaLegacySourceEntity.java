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

/** One legacy row indexed by the manager, separate from its functional role link. */
@Entity
@Table(name = "media_legacy_source",
        uniqueConstraints = @UniqueConstraint(name = "uk_media_legacy_source_identity",
                columnNames = {"source_type", "source_id"}),
        indexes = {
                @Index(name = "idx_media_legacy_source_asset", columnList = "asset_id"),
                @Index(name = "idx_media_legacy_source_target", columnList = "target_type,target_id")
        })
public class MediaLegacySourceEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    public MediaLegacySourceType sourceType;
    @Column(name = "source_id", nullable = false)
    public Long sourceId;
    @Column(name = "asset_id", nullable = false)
    public Long assetId;
    @Column(name = "version_id", nullable = false)
    public Long versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    public MediaTargetType targetType;
    @Column(name = "target_id", nullable = false)
    public Long targetId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public MediaRole role;
    @Column(length = 255)
    public String label;
    @Column(name = "indexed_at", nullable = false)
    public Instant indexedAt;
}
