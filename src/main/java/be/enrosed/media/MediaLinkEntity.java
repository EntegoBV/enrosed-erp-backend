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

@Entity
@Table(name = "media_link",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_media_link_asset_target_role",
                        columnNames = {"asset_id", "target_type", "target_id", "role"})
        },
        indexes = {
                @Index(name = "idx_media_link_target_role",
                        columnList = "target_type,target_id,role"),
                @Index(name = "idx_media_link_asset", columnList = "asset_id")
        })
public class MediaLinkEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "asset_id", nullable = false)
    public Long assetId;
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    public MediaTargetType targetType;
    @Column(name = "target_id", nullable = false)
    public Long targetId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public MediaRole role;

    /** 1 marks the selected link; null permits any number of non-primary links. */
    @Column(name = "primary_slot")
    public Integer primarySlot;
    /** Historical targets pin the bytes that were linked at that moment. */
    @Column(name = "pinned_version_id")
    public Long pinnedVersionId;

    /** True until a staff member explicitly manages this otherwise auto-created relation. */
    @Column(name = "legacy_only", nullable = false)
    public boolean legacyOnly;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "created_by", length = 64)
    public String createdBy;

    public boolean primary() {
        return Integer.valueOf(1).equals(primarySlot);
    }
}
