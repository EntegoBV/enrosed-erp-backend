package be.enrosed.media;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/** Resolves the one selected image for a document role, ignoring archived assets safely. */
@ApplicationScoped
public class MediaRoleResolver {
    private final EntityManager entities;
    private final PhotoStorage storage;

    public MediaRoleResolver(EntityManager entities, PhotoStorage storage) {
        this.entities = entities;
        this.storage = storage;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<ResolvedMedia> primaryImage(
            MediaTargetType targetType, long targetId, MediaRole role) {
        return primaryImageUnpinned(targetType, targetId, role);
    }

    /** Pins the first generated quote/invoice image while new orders use the latest version. */
    @Transactional
    public Optional<ResolvedMedia> documentImage(long orderId, long productId,
                                                  MediaRole documentRole,
                                                  MediaRole... resolutionOrder) {
        lockOrder(orderId);
        SalesDocumentMediaSnapshotEntity snapshot = snapshot(orderId, productId, documentRole);
        if (snapshot != null) return Optional.of(resolved(snapshot));
        MediaRole[] roles = resolutionOrder == null || resolutionOrder.length == 0
                ? new MediaRole[] { documentRole } : resolutionOrder;
        Optional<ResolvedMedia> selected = Arrays.stream(roles)
                .map(role -> primaryImageUnpinned(MediaTargetType.PRODUCT, productId, role))
                .flatMap(Optional::stream).findFirst();
        selected.ifPresent(media -> persistSnapshot(orderId, productId, documentRole, media));
        return selected;
    }

    /** Pins the existing product-photo fallback under the same immutable document slot. */
    @Transactional
    public ResolvedMedia pinDocumentImage(long orderId, long productId, MediaRole documentRole,
                                           String storageKey, String contentType,
                                           String originalFilename) {
        lockOrder(orderId);
        SalesDocumentMediaSnapshotEntity existing = snapshot(orderId, productId, documentRole);
        if (existing != null) return resolved(existing);
        if (!safeRaster(contentType)) {
            throw new IllegalArgumentException("Alleen veilige rasterfoto's kunnen worden vastgezet");
        }
        ResolvedMedia media = new ResolvedMedia(0, 0, storageKey, contentType,
                MediaUploadPolicy.safeFilename(originalFilename));
        persistSnapshot(orderId, productId, documentRole, media);
        return media;
    }

    private Optional<ResolvedMedia> primaryImageUnpinned(
            MediaTargetType targetType, long targetId, MediaRole role) {
        MediaLinkEntity link = entities.createQuery("select l from MediaLinkEntity l, "
                        + "MediaAssetEntity a where l.assetId = a.id and a.archived = false "
                        + "and l.targetType = :type and l.targetId = :target "
                        + "and l.role = :role and l.primarySlot = 1", MediaLinkEntity.class)
                .setParameter("type", targetType).setParameter("target", targetId)
                .setParameter("role", role).setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
        if (link == null) return Optional.empty();
        MediaAssetEntity asset = entities.find(MediaAssetEntity.class, link.assetId);
        if (asset == null || asset.archived) return Optional.empty();
        Long versionId = link.pinnedVersionId == null ? asset.currentVersionId : link.pinnedVersionId;
        MediaVersionEntity version = versionId == null
                ? null : entities.find(MediaVersionEntity.class, versionId);
        if (version == null || !version.assetId.equals(asset.id)
                || !safeRaster(version.contentType)) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedMedia(asset.id, version.id, version.storageKey,
                version.contentType, version.originalFilename));
    }

    private void lockOrder(long orderId) {
        if (entities.find(SalesEntities.SalesOrderEntity.class, orderId,
                LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new IllegalArgumentException("Onbekend verkoopdocument " + orderId);
        }
    }

    private SalesDocumentMediaSnapshotEntity snapshot(long orderId, long productId,
                                                        MediaRole role) {
        return entities.createQuery("select s from SalesDocumentMediaSnapshotEntity s "
                        + "where s.orderId = :order and s.productId = :product and s.role = :role",
                        SalesDocumentMediaSnapshotEntity.class)
                .setParameter("order", orderId).setParameter("product", productId)
                .setParameter("role", role).getResultStream().findFirst().orElse(null);
    }

    private void persistSnapshot(long orderId, long productId, MediaRole role,
                                 ResolvedMedia media) {
        SalesDocumentMediaSnapshotEntity snapshot = new SalesDocumentMediaSnapshotEntity();
        snapshot.orderId = orderId;
        snapshot.productId = productId;
        snapshot.role = role;
        snapshot.storageKey = media.storageKey();
        snapshot.contentType = media.contentType();
        snapshot.originalFilename = MediaUploadPolicy.safeFilename(media.originalFilename());
        snapshot.mediaAssetId = media.assetId() == 0 ? null : media.assetId();
        snapshot.mediaVersionId = media.versionId() == 0 ? null : media.versionId();
        snapshot.createdAt = Instant.now();
        entities.persist(snapshot);
    }

    private static ResolvedMedia resolved(SalesDocumentMediaSnapshotEntity snapshot) {
        return new ResolvedMedia(
                snapshot.mediaAssetId == null ? 0 : snapshot.mediaAssetId,
                snapshot.mediaVersionId == null ? 0 : snapshot.mediaVersionId,
                snapshot.storageKey, snapshot.contentType, snapshot.originalFilename);
    }

    private static boolean safeRaster(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg", "image/png", "image/gif", "image/webp" -> true;
            default -> false;
        };
    }

    public InputStream read(ResolvedMedia media) {
        return storage.read(media.storageKey());
    }

    public record ResolvedMedia(long assetId, long versionId, String storageKey,
                                String contentType, String originalFilename) {}
}
