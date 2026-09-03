package be.enrosed.media;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.planning.PlannerItemEntity;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MediaServicePersistenceTest {
    @Inject MediaService media;
    @Inject MediaRoleResolver roles;
    @Inject EntityManager entities;
    @Inject PhotoStorage storage;

    @Test
    void identicalUploadReusesOneAssetVersionAndBlobEvenWhenArchived() throws Exception {
        byte[] bytes = uniqueResource("/seed-images/P06.jpg", "dedupe");
        var file = image("beer-rood.jpg", bytes);
        MediaDtos.UploadResult first = media.upload("Rode beer", file);
        MediaDtos.UploadResult second = media.upload("Niet stil overschrijven", file);

        assertFalse(first.reused());
        assertTrue(second.reused());
        assertEquals(first.asset().id(), second.asset().id());
        assertEquals("Rode beer", second.asset().name());
        assertEquals(1, second.asset().versionCount());
        assertTrue(storage.exists("sha256-" + first.asset().sha256() + ".jpg"));
        try (InputStream thumbnail = media.thumbnail(first.asset().id()).data()) {
            assertTrue(thumbnail.readAllBytes().length > 0,
                    "the grid thumbnail must be independently readable");
        }

        media.archive(first.asset().id());
        MediaDtos.UploadResult archived = media.upload("Nogmaals", file);
        assertTrue(archived.reused());
        assertTrue(archived.asset().archived());
        assertEquals("Rode beer", archived.asset().name());
    }

    @Test
    void onePrimaryPerTargetAndRoleIsSwitchedAtomically() throws Exception {
        long productId = product("MEDIA-PRIMARY-" + System.nanoTime());
        var first = media.upload("Eerste", image("first.jpg",
                uniqueResource("/seed-images/P07.jpg", "primary-first"))).asset();
        var second = media.upload("Tweede", image("second.jpg",
                uniqueResource("/seed-images/P08.jpg", "primary-second"))).asset();

        media.link(first.id(), new MediaDtos.LinkRequest(
                MediaTargetType.PRODUCT, productId, MediaRole.CATALOGUE));
        media.link(second.id(), new MediaDtos.LinkRequest(
                MediaTargetType.PRODUCT, productId, MediaRole.CATALOGUE));

        assertFalse(media.get(first.id()).links().getFirst().primary());
        assertTrue(media.get(second.id()).links().getFirst().primary());
        var selected = roles.primaryImage(
                MediaTargetType.PRODUCT, productId, MediaRole.CATALOGUE).orElseThrow();
        assertEquals(second.id(), selected.assetId());
    }

    @Test
    void historicalLinkPinsOldVersionAndSafeDeleteRequiresArchiveAndNoLinks() throws Exception {
        long plannerId = planner("Media pinned " + System.nanoTime());
        var uploaded = media.upload("Plannerbeeld",
                image("old.jpg", uniqueResource("/seed-images/P03.jpg", "pinned-old"))).asset();
        MediaDtos.Detail linked = media.link(uploaded.id(), new MediaDtos.LinkRequest(
                MediaTargetType.PLANNER_ITEM, plannerId, MediaRole.INTERNAL));
        Long pinned = linked.links().getFirst().pinnedVersionId();
        assertNotNull(pinned);

        MediaDtos.Detail replaced = media.replace(uploaded.id(),
                document("new.pdf", ("%PDF-1.4\nmedia-test-" + System.nanoTime())
                        .getBytes(StandardCharsets.US_ASCII)));
        assertNotEquals(pinned, replaced.currentVersionId());
        assertEquals(MediaKind.DOCUMENT, replaced.kind());
        assertEquals(pinned, replaced.links().getFirst().pinnedVersionId());
        assertEquals(pinned, roles.primaryImage(
                MediaTargetType.PLANNER_ITEM, plannerId, MediaRole.INTERNAL)
                .orElseThrow().versionId());

        assertThrows(BusinessRuleException.class, () -> media.delete(uploaded.id()));
        media.archive(uploaded.id());
        assertThrows(BusinessRuleException.class, () -> media.delete(uploaded.id()));
        media.unlink(uploaded.id(), replaced.links().getFirst().id());
        media.delete(uploaded.id());
        assertThrows(RuntimeException.class, () -> media.get(uploaded.id()));
    }

    @Test
    void includeArchivedListBuildsValidQueryAndReturnsBothStates() throws Exception {
        var active = media.upload("Lijst actief " + System.nanoTime(),
                image("active.jpg", uniqueResource("/seed-images/P01.jpg", "list-active"))).asset();
        var archived = media.upload("Lijst archief " + System.nanoTime(),
                image("archived.jpg", uniqueResource("/seed-images/P10.jpg", "list-archived"))).asset();
        media.archive(archived.id());

        var all = media.list(null, null, null, null, null, null, true, 0, 200);
        assertTrue(all.stream().anyMatch(item -> item.id().equals(active.id())));
        assertTrue(all.stream().anyMatch(item -> item.id().equals(archived.id())));
        var archivedOnly = media.list("Lijst", null, null, true,
                null, null, true, 0, 200);
        assertTrue(archivedOnly.stream().anyMatch(item -> item.id().equals(archived.id())));
        assertFalse(archivedOnly.stream().anyMatch(item -> item.id().equals(active.id())));
        var firstPage = media.list("Lijst", null, null, null,
                null, null, true, 0, 1);
        var secondPage = media.list("Lijst", null, null, null,
                null, null, true, 1, 1);
        assertEquals(1, firstPage.size());
        assertEquals(1, secondPage.size());
        assertNotEquals(firstPage.getFirst().id(), secondPage.getFirst().id());
    }

    @Test
    void rejectedCrossAssetReplacementNeverDeletesSharedContentAddressedBlob() throws Exception {
        byte[] sharedBytes = uniqueResource("/seed-images/P09.jpg", "shared-rollback");
        var shared = media.upload("Gedeeld", image("shared.jpg", sharedBytes)).asset();
        var other = media.upload("Andere asset", image(
                "other.jpg", uniqueResource("/seed-images/P01.jpg", "shared-other"))).asset();
        assertThrows(BusinessRuleException.class, () -> media.replace(other.id(), image(
                "duplicate.jpg", sharedBytes)));
        assertTrue(storage.exists("sha256-" + shared.sha256() + ".jpg"));
        assertEquals(shared.id(), media.upload("Nog eens", image(
                "duplicate.jpg", sharedBytes)).asset().id());
    }

    @Test
    void deletingATargetUnlinksMediaWithoutDeletingTheReusableAsset() throws Exception {
        long productId = product("MEDIA-TARGET-" + System.nanoTime());
        var asset = media.upload("Blijvend beeld", image("target.jpg",
                uniqueResource("/seed-images/P04.jpg", "target-delete"))).asset();
        media.link(asset.id(), new MediaDtos.LinkRequest(
                MediaTargetType.PRODUCT, productId, MediaRole.INTERNAL));

        media.unlinkTarget(MediaTargetType.PRODUCT, productId);

        assertTrue(media.get(asset.id()).links().isEmpty());
        assertTrue(storage.exists("sha256-" + asset.sha256() + ".jpg"));
    }

    private long product(String sku) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProductEntity entity = new ProductEntity();
            entity.sku = sku;
            entity.name = "Mediatestproduct";
            entities.persist(entity);
            entities.flush();
            return entity.id;
        });
    }

    private long planner(String title) {
        return QuarkusTransaction.requiringNew().call(() -> {
            PlannerItemEntity entity = new PlannerItemEntity();
            entity.title = title;
            entity.createdAt = Instant.now();
            entities.persist(entity);
            entities.flush();
            return entity.id;
        });
    }

    private static MediaUploadPolicy.ValidatedFile image(String name, byte[] bytes) {
        return new MediaUploadPolicy.ValidatedFile(name, "image/jpeg", MediaKind.IMAGE, bytes);
    }

    private static MediaUploadPolicy.ValidatedFile document(String name, byte[] bytes) {
        return new MediaUploadPolicy.ValidatedFile(
                name, "application/pdf", MediaKind.DOCUMENT, bytes);
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream input = MediaServicePersistenceTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return input.readAllBytes();
        }
    }

    /** Keeps content-addressed test uploads isolated while retaining a valid JPEG stream. */
    private static byte[] uniqueResource(String path, String marker) throws Exception {
        byte[] image = resource(path);
        byte[] suffix = ("media-test-" + marker + "-" + System.nanoTime())
                .getBytes(StandardCharsets.UTF_8);
        byte[] unique = Arrays.copyOf(image, image.length + suffix.length);
        System.arraycopy(suffix, 0, unique, image.length, suffix.length);
        return unique;
    }
}
