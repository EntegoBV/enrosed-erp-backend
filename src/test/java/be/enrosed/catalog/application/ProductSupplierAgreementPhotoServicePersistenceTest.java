package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductSupplierAgreementPhotoEntity;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhoto;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProductSupplierAgreementPhotoServicePersistenceTest {

    private static final byte[] FIRST_PNG = pngBytes((byte) 1);
    private static final byte[] SECOND_PNG = pngBytes((byte) 2);

    @Inject EntityManager entityManager;
    @Inject ProductSupplierAgreementPhotoService photos;
    @Inject PhotoStorage storage;
    @Inject PhotoReferenceService photoReferences;

    @Test
    @TestTransaction
    void retainsOldSupplierHistoryButNeverReturnsItForTheCurrentSupplier() throws Exception {
        ProductEntity product = product("AGREEMENT-SCOPE", 101L);

        AgreementPhoto front = photos.upload(product.id, "front.png",
                new ByteArrayInputStream(FIRST_PNG), "  Approved front view  ");
        AgreementPhoto packaging = photos.upload(product.id, "packaging.png",
                new ByteArrayInputStream(SECOND_PNG), "Approved retail packaging");

        assertEquals(0, front.position());
        assertEquals(1, packaging.position());
        assertEquals("Approved front view", front.caption());
        assertArrayEquals(FIRST_PNG, photos.open(product.id, front.id()).data().readAllBytes());

        List<AgreementPhoto> reordered = photos.reorder(
                product.id, List.of(packaging.id(), front.id()));
        assertEquals(List.of(packaging.id(), front.id()),
                reordered.stream().map(AgreementPhoto::id).toList());
        assertEquals(List.of(0, 1), reordered.stream().map(AgreementPhoto::position).toList());

        AgreementPhoto renamed = photos.updateCaption(
                product.id, front.id(), "  Approved close-up  ");
        assertEquals("Approved close-up", renamed.caption());

        product.supplierId = 202L;
        entityManager.flush();

        assertTrue(photos.list(product.id).isEmpty());
        assertNotNull(entityManager.find(ProductSupplierAgreementPhotoEntity.class, front.id()),
                "changing supplier must keep the old agreement evidence");
        assertThrows(NotFoundException.class, () -> photos.get(product.id, front.id()));
        assertThrows(NotFoundException.class, () -> photos.open(product.id, front.id()));
        assertThrows(NotFoundException.class,
                () -> photos.updateCaption(product.id, front.id(), "Must stay hidden"));
        assertThrows(NotFoundException.class, () -> photos.delete(product.id, front.id()));

        AgreementPhoto replacement = photos.upload(product.id, "new-supplier.png",
                new ByteArrayInputStream(FIRST_PNG), "Approved by replacement supplier");
        assertEquals(202L, replacement.supplierId());
        assertEquals(0, replacement.position(), "each supplier has its own ordered series");
        assertEquals(List.of(replacement.id()),
                photos.list(product.id).stream().map(AgreementPhoto::id).toList());

        product.supplierId = 101L;
        entityManager.flush();
        assertEquals(List.of(packaging.id(), front.id()),
                photos.list(product.id).stream().map(AgreementPhoto::id).toList(),
                "reassigning the old supplier restores its untouched agreement history");

        photos.delete(product.id, packaging.id());
        List<AgreementPhoto> afterDelete = photos.list(product.id);
        assertEquals(List.of(front.id()), afterDelete.stream().map(AgreementPhoto::id).toList());
        assertEquals(0, afterDelete.getFirst().position());
    }

    @Test
    @TestTransaction
    void validatesEnglishCaptionLengthAndRequiresAnExactReorderSet() {
        ProductEntity product = product("AGREEMENT-VALIDATION", 303L);

        BusinessRuleException captionError = assertThrows(BusinessRuleException.class,
                () -> photos.upload(product.id, "long.png", new ByteArrayInputStream(FIRST_PNG),
                        "x".repeat(501)));
        assertEquals("Het Engelse bijschrift mag maximaal 500 tekens bevatten",
                captionError.getMessage());

        AgreementPhoto one = photos.upload(product.id, "one.png",
                new ByteArrayInputStream(FIRST_PNG), "Front");
        AgreementPhoto two = photos.upload(product.id, "two.png",
                new ByteArrayInputStream(SECOND_PNG), "Back");

        assertThrows(BusinessRuleException.class,
                () -> photos.reorder(product.id, List.of(one.id(), one.id())));
        assertThrows(BusinessRuleException.class,
                () -> photos.reorder(product.id, List.of(one.id())));
        assertThrows(BusinessRuleException.class,
                () -> photos.reorder(product.id, Arrays.asList(one.id(), null)));
        assertEquals(List.of(one.id(), two.id()),
                photos.list(product.id).stream().map(AgreementPhoto::id).toList());

        assertNull(photos.updateCaption(product.id, one.id(), "   ").caption(),
                "a blank caption is stored as null");
    }

    @Test
    @TestTransaction
    void productWithoutSupplierHasNoVisibleScopeAndCannotAcceptUploads() {
        ProductEntity product = product("AGREEMENT-NO-SUPPLIER", null);

        assertTrue(photos.list(product.id).isEmpty());
        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> photos.upload(product.id, "front.png",
                        new ByteArrayInputStream(FIRST_PNG), "Front"));
        assertTrue(error.getMessage().contains("Koppel eerst een leverancier"), error.getMessage());
    }

    @Test
    @TestTransaction
    void sharedBlobCleanupCountsSupplierAgreementReferences() {
        ProductEntity product = product("AGREEMENT-REFERENCE", 404L);
        AgreementPhoto photo = photos.upload(product.id, "reference.png",
                new ByteArrayInputStream(FIRST_PNG), "Reference");
        ProductSupplierAgreementPhotoEntity stored = entityManager.find(
                ProductSupplierAgreementPhotoEntity.class, photo.id());

        photoReferences.deleteIfUnreferenced(stored.storageKey);
        assertTrue(storage.exists(stored.storageKey),
                "an agreement row must protect its blob from shared cleanup");

        entityManager.remove(stored);
        entityManager.flush();
        photoReferences.deleteIfUnreferenced(stored.storageKey);
        assertTrue(!storage.exists(stored.storageKey));
    }

    private ProductEntity product(String sku, Long supplierId) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = sku;
        product.active = true;
        product.piecesPerCarton = 1;
        product.supplierId = supplierId;
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }

    private static byte[] pngBytes(byte marker) {
        return new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                marker, 0, 0, 0
        };
    }
}
