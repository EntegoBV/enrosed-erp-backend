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

import java.time.Instant;

@Entity
@Table(name = "media_asset", indexes = {
        @Index(name = "idx_media_asset_updated", columnList = "updated_at"),
        @Index(name = "idx_media_asset_archived", columnList = "archived")
})
public class MediaAssetEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 255)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public MediaKind kind;

    @Column(name = "current_version_id")
    public Long currentVersionId;

    @Column(nullable = false)
    public boolean archived;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    @Column(name = "created_by", length = 64)
    public String createdBy;
}
