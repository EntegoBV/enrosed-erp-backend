package be.enrosed.media;

import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductPhotoEntity;
import be.enrosed.planning.PlannerAttachmentEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Incrementally incorporates files from the existing detail screens.
 *
 * <p>It runs after startup in bounded batches. Every source gets its own transaction and failure
 * boundary, so a corrupt supplier attachment neither blocks the health check nor the remaining
 * files.</p>
 */
@ApplicationScoped
public class MediaLegacyIndexer {
    private static final Logger LOG = Logger.getLogger(MediaLegacyIndexer.class);
    private static final int PER_SOURCE_BATCH = 50;

    private final EntityManager entities;
    private final MediaService media;

    public MediaLegacyIndexer(EntityManager entities, MediaService media) {
        this.entities = entities;
        this.media = media;
    }

    @Scheduled(every = "${enrosed.media.legacy-index.every:1m}", delayed = "5s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduled() {
        scanOnce();
    }

    /** Package-visible for deterministic persistence tests. */
    public ScanResult scanOnce() {
        List<MediaService.LegacyFile> batch;
        try {
            batch = QuarkusTransaction.requiringNew().call(this::loadBatch);
        } catch (RuntimeException unavailableSchema) {
            LOG.warnf("Media-index kon de legacybronnen niet oplijsten: %s",
                    unavailableSchema.getMessage());
            return new ScanResult(0, 0);
        }
        int indexed = 0;
        int failed = 0;
        for (MediaService.LegacyFile source : batch) {
            try {
                QuarkusTransaction.requiringNew().run(() -> media.indexLegacy(source));
                indexed++;
            } catch (RuntimeException badSource) {
                failed++;
                LOG.warnf("Media-index sloeg %s %d over: %s", source.sourceType(),
                        source.sourceId(), badSource.getMessage());
            }
        }
        if (!batch.isEmpty()) {
            LOG.infof("Media-index: %d verwerkt, %d overgeslagen", indexed, failed);
        }
        return new ScanResult(indexed, failed);
    }

    private List<MediaService.LegacyFile> loadBatch() {
        List<MediaService.LegacyFile> files = new ArrayList<>();
        files.addAll(productPhotos());
        files.addAll(familyPhotos());
        files.addAll(purchaseDocuments());
        files.addAll(plannerAttachments());
        return List.copyOf(files);
    }

    private List<MediaService.LegacyFile> productPhotos() {
        return entities.createQuery("select p from ProductPhotoEntity p "
                        + "where p.familyPhotoId is null and not exists ("
                        + "select s.id from MediaLegacySourceEntity s where "
                        + "s.sourceType = :sourceType and s.sourceId = p.id) "
                        + "order by p.product.id, p.position, p.id", ProductPhotoEntity.class)
                .setParameter("sourceType", MediaLegacySourceType.PRODUCT_PHOTO)
                .setMaxResults(PER_SOURCE_BATCH).getResultList().stream()
                .map(photo -> new MediaService.LegacyFile(
                        MediaLegacySourceType.PRODUCT_PHOTO, photo.id,
                        MediaTargetType.PRODUCT, photo.product.id, MediaRole.INTERNAL,
                        photo.originalFilename, photo.originalFilename, photo.contentType,
                        MediaKind.IMAGE, photo.storageKey, photo.widthPx, photo.heightPx,
                        null, null, null, null, null,
                        Instant.now(), "system", photo.position == 0))
                .toList();
    }

    private List<MediaService.LegacyFile> familyPhotos() {
        return entities.createQuery("select p from ProductFamilyPhotoEntity p where not exists ("
                        + "select s.id from MediaLegacySourceEntity s where "
                        + "s.sourceType = :sourceType and s.sourceId = p.id) "
                        + "order by p.family.id, p.position, p.id", ProductFamilyPhotoEntity.class)
                .setParameter("sourceType", MediaLegacySourceType.FAMILY_PHOTO)
                .setMaxResults(PER_SOURCE_BATCH).getResultList().stream()
                .map(photo -> new MediaService.LegacyFile(
                        MediaLegacySourceType.FAMILY_PHOTO, photo.id,
                        MediaTargetType.PRODUCT_FAMILY, photo.family.id, MediaRole.CATALOGUE,
                        photo.originalFilename, photo.originalFilename, photo.largeContentType,
                        MediaKind.IMAGE, photo.largeStorageKey,
                        photo.originalWidthPx, photo.originalHeightPx,
                        photo.smallStorageKey, photo.smallContentType, photo.smallSizeBytes,
                        photo.smallWidthPx, photo.smallHeightPx,
                        Instant.now(), "system", photo.position == 0))
                .toList();
    }

    private List<MediaService.LegacyFile> purchaseDocuments() {
        /* A nested entity class is not reachable by its simple name in HQL;
           ask the metamodel how Hibernate registered it. */
        String documentEntity = entities.getMetamodel()
                .entity(SourcingEntities.PurchaseDocumentEntity.class).getName();
        return entities.createQuery("select d from " + documentEntity + " d where not exists ("
                        + "select s.id from MediaLegacySourceEntity s where "
                        + "s.sourceType = :sourceType and s.sourceId = d.id) "
                        + "order by d.orderId, d.addedAt, d.id",
                        SourcingEntities.PurchaseDocumentEntity.class)
                .setParameter("sourceType", MediaLegacySourceType.PURCHASE_DOCUMENT)
                .setMaxResults(PER_SOURCE_BATCH).getResultList().stream()
                .map(document -> new MediaService.LegacyFile(
                        MediaLegacySourceType.PURCHASE_DOCUMENT, document.id,
                        MediaTargetType.PURCHASE_ORDER, document.orderId, MediaRole.INTERNAL,
                        document.label, document.originalFilename, document.contentType,
                        kind(document.contentType), document.storageKey, null, null,
                        null, null, null, null, null,
                        document.addedAt, document.actor, true))
                .toList();
    }

    private List<MediaService.LegacyFile> plannerAttachments() {
        return entities.createQuery("select a from PlannerAttachmentEntity a where not exists ("
                        + "select s.id from MediaLegacySourceEntity s where "
                        + "s.sourceType = :sourceType and s.sourceId = a.id) "
                        + "order by a.itemId, a.addedAt, a.id", PlannerAttachmentEntity.class)
                .setParameter("sourceType", MediaLegacySourceType.PLANNER_ATTACHMENT)
                .setMaxResults(PER_SOURCE_BATCH).getResultList().stream()
                .map(attachment -> new MediaService.LegacyFile(
                        MediaLegacySourceType.PLANNER_ATTACHMENT, attachment.id,
                        MediaTargetType.PLANNER_ITEM, attachment.itemId, MediaRole.INTERNAL,
                        attachment.filename, attachment.filename, attachment.contentType,
                        kind(attachment.contentType), attachment.storageKey, null, null,
                        null, null, null, null, null,
                        attachment.addedAt, "system", true))
                .toList();
    }

    private static MediaKind kind(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("image/") ? MediaKind.IMAGE : MediaKind.DOCUMENT;
    }

    public record ScanResult(int indexed, int failed) {}
}
