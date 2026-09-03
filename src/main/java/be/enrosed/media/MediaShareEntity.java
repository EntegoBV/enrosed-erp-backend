package be.enrosed.media;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A public link to an asset: whoever holds the token can fetch the current
 * version without logging in. Revoking keeps the row for the audit trail.
 */
@Entity
@Table(name = "media_share", indexes = {
        @Index(name = "idx_media_share_asset", columnList = "asset_id"),
        @Index(name = "idx_media_share_token", columnList = "token", unique = true)
})
public class MediaShareEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "asset_id", nullable = false)
    public Long assetId;
    @Column(nullable = false, length = 64)
    public String token;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "created_by", length = 64)
    public String createdBy;
    @Column(name = "revoked_at")
    public Instant revokedAt;
    @Column(nullable = false)
    public long downloads;
}
