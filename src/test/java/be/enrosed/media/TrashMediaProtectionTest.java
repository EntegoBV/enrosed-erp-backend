package be.enrosed.media;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TrashMediaProtectionTest {
    @Inject EntityManager entities;
    @Inject MediaService media;
    @Inject MediaRoleResolver roles;

    @Test @TestTransaction
    void purchaseAttachmentsDisappearFromActiveTargetsButRemainRecoverable() {
        var purchase = new PurchaseOrderEntity();
        purchase.number = "trash-media-" + UUID.randomUUID();
        entities.persist(purchase);
        var asset = asset();
        var link = new MediaLinkEntity();
        link.assetId = asset.id; link.targetType = MediaTargetType.PURCHASE_ORDER;
        link.targetId = purchase.id; link.role = MediaRole.INTERNAL;
        link.primarySlot = 1; link.createdAt = Instant.now();
        entities.persist(link); entities.flush();

        assertEquals(1, media.list(asset.name, null, null, null, MediaTargetType.PURCHASE_ORDER,
                purchase.id, false, 0, 50).size());
        assertTrue(roles.primaryImage(MediaTargetType.PURCHASE_ORDER, purchase.id, MediaRole.INTERNAL).isPresent());
        setDeleted("purchase_order", purchase.id, true);
        assertNull(purchase.deletedAt, "the managed parent is deliberately stale");

        assertTrue(media.list(asset.name, null, null, null, MediaTargetType.PURCHASE_ORDER,
                null, false, 0, 50).isEmpty());
        assertTrue(roles.primaryImage(MediaTargetType.PURCHASE_ORDER, purchase.id, MediaRole.INTERNAL).isEmpty());
        assertThrows(NotFoundException.class, () -> media.list(asset.name, null, null, null,
                MediaTargetType.PURCHASE_ORDER, purchase.id, false, 0, 50));
        assertThrows(NotFoundException.class, () -> media.unlink(asset.id, link.id));
        assertThrows(NotFoundException.class, () -> media.link(asset.id,
                new MediaDtos.LinkRequest(MediaTargetType.PURCHASE_ORDER, purchase.id, MediaRole.INTERNAL)));
        assertNotNull(entities.find(MediaLinkEntity.class, link.id));
        assertNotNull(entities.find(MediaVersionEntity.class, asset.currentVersionId));
        assertEquals(1, media.list(asset.name, null, null, null, null, null, false, 0, 50).size(),
                "the library keeps the attachment; trash does not destroy assets");

        setDeleted("purchase_order", purchase.id, false);
        assertEquals(1, media.list(asset.name, null, null, null, MediaTargetType.PURCHASE_ORDER,
                purchase.id, false, 0, 50).size());
        assertTrue(roles.primaryImage(MediaTargetType.PURCHASE_ORDER, purchase.id, MediaRole.INTERNAL).isPresent());
    }

    @Test @TestTransaction
    void cachedTrashedSalesDocumentCannotReadOrReplaceItsPinnedPhoto() {
        var order = new SalesOrderEntity();
        order.number = "trash-snapshot-" + UUID.randomUUID();
        entities.persist(order); entities.flush();
        var original = roles.pinDocumentImage(order.id, 912345L, MediaRole.INVOICE,
                "retained-image.jpg", "image/jpeg", "original.jpg");
        setDeleted("sales_order", order.id, true);
        assertNull(order.deletedAt, "the managed parent is deliberately stale");
        assertThrows(NotFoundException.class,
                () -> roles.documentImage(order.id, 912345L, MediaRole.INVOICE));
        assertThrows(NotFoundException.class, () -> roles.pinDocumentImage(order.id, 912345L,
                MediaRole.INVOICE, "replacement.jpg", "image/jpeg", "replacement.jpg"));
        setDeleted("sales_order", order.id, false);
        assertEquals(original, roles.documentImage(order.id, 912345L, MediaRole.INVOICE).orElseThrow());
    }

    private MediaAssetEntity asset() {
        var asset = new MediaAssetEntity();
        asset.name = "Retained media " + UUID.randomUUID(); asset.kind = MediaKind.IMAGE;
        asset.createdAt = Instant.now(); asset.updatedAt = asset.createdAt;
        entities.persist(asset);
        var version = new MediaVersionEntity();
        version.assetId = asset.id; version.versionNumber = 1; version.storageKey = "retained-image.jpg";
        version.originalFilename = "retained-image.jpg"; version.contentType = "image/jpeg";
        version.sizeBytes = 123; version.sha256 = UUID.randomUUID().toString().replace("-", "").repeat(2);
        version.createdAt = Instant.now(); entities.persist(version);
        asset.currentVersionId = version.id;
        return asset;
    }

    private void setDeleted(String table, long id, boolean deleted) {
        entities.createNativeQuery("update " + table + " set deleted_at = "
                        + (deleted ? "current_timestamp" : "null") + " where id = :id")
                .setParameter("id", id).executeUpdate();
    }
}
