package be.enrosed.media;

import be.enrosed.catalog.application.PhotoReferenceService;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.planning.PlannerAttachmentEntity;
import be.enrosed.planning.PlannerItemEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MediaLegacyIndexerPersistenceTest {
    @Inject EntityManager entities;
    @Inject PhotoStorage storage;
    @Inject MediaLegacyIndexer indexer;
    @Inject MediaService media;
    @Inject PhotoReferenceService references;

    @Test
    void missingLegacyBlobDoesNotStopNextSourceAndIndexedBlobSurvivesLegacyDelete() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/seed-images/P05.jpg")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        PhotoStorage.Stored stored = storage.store(
                "planner-photo.jpg", "image/jpeg", bytes);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            PlannerItemEntity item = new PlannerItemEntity();
            item.title = "Legacy index " + System.nanoTime();
            item.createdAt = Instant.now();
            entities.persist(item);
            entities.flush();

            PlannerAttachmentEntity missing = attachment(item.id, "missing.jpg", "missing-key");
            PlannerAttachmentEntity valid = attachment(item.id, "valid.jpg", stored.storageKey());
            entities.persist(missing);
            entities.persist(valid);
            entities.flush();
            return new long[] { item.id, missing.id, valid.id };
        });

        MediaLegacyIndexer.ScanResult result = indexer.scanOnce();
        assertTrue(result.failed() >= 1);
        assertTrue(result.indexed() >= 1);
        Long assetId = QuarkusTransaction.requiringNew().call(() -> entities.createQuery(
                        "select s.assetId from MediaLegacySourceEntity s where "
                                + "s.sourceType = :type and s.sourceId = :id", Long.class)
                .setParameter("type", MediaLegacySourceType.PLANNER_ATTACHMENT)
                .setParameter("id", ids[2]).getSingleResult());
        assertNotNull(assetId);

        QuarkusTransaction.requiringNew().run(() -> {
            media.unlinkLegacy(MediaLegacySourceType.PLANNER_ATTACHMENT, ids[2]);
            PlannerAttachmentEntity valid = entities.find(PlannerAttachmentEntity.class, ids[2]);
            entities.remove(valid);
        });
        references.deleteIfUnreferenced(stored.storageKey());
        assertTrue(storage.exists(stored.storageKey()),
                "media_version remains the owner after the old detail row is deleted");
        assertEquals(assetId, media.get(assetId).id());
    }

    private PlannerAttachmentEntity attachment(long itemId, String filename, String key) {
        PlannerAttachmentEntity attachment = new PlannerAttachmentEntity();
        attachment.itemId = itemId;
        attachment.filename = filename;
        attachment.contentType = "image/jpeg";
        attachment.sizeBytes = 10;
        attachment.storageKey = key;
        attachment.addedAt = Instant.now();
        return attachment;
    }
}
