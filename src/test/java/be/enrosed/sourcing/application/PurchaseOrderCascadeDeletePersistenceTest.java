package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.trash.DeletedItemsService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class PurchaseOrderCascadeDeletePersistenceTest {
    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService supplierService;
    @Inject SourcingRepositories.PurchaseOrders orders;
    @Inject SourcingRepositories.Payments payments;
    @Inject SourcingRepositories.Documents documents;
    @Inject DeletedItemsService trash;
    @Inject PhotoStorage storage;
    @Inject EntityManager entities;

    @Test @TestTransaction
    void deletingKeepsTheDossierAndBlobAndRestoresTheOriginalIdentity() {
        var order = order();
        var document = purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.COMMERCIAL_INVOICE,
                null, null, "invoice.pdf", "application/pdf", new byte[]{1, 2, 3});
        purchaseOrders.delete(order.id());
        entities.clear();
        assertThrows(NotFoundException.class, () -> purchaseOrders.get(order.id()));
        assertTrue(orders.findAll().stream().noneMatch(row -> row.id().equals(order.id())));
        assertEquals(1, documents.forOrder(order.id()).size());
        assertTrue(storage.exists(document.storageKey()));
        assertTrue(orders.numbersIncludingDeleted().contains(order.number()));
        assertThrows(NotFoundException.class, () -> orders.save(order), "a deleted ID must not silently create another order");
        assertEquals(1L, ((Number) entities.createNativeQuery("select count(*) from purchase_order where id=:id and deleted_at is not null")
                .setParameter("id", order.id()).getSingleResult()).longValue());
        var entry = trash.list().items().stream().filter(row -> row.sourceId() == order.id()
                && row.type() == be.enrosed.shared.trash.DeletedItemDtos.Type.PURCHASE_ORDER).findFirst().orElseThrow();
        assertEquals("invoice.pdf", trash.detail(entry.id()).attachments().getFirst().name());
        assertEquals(1L, ((Number) entities.createNativeQuery("select count(*) from activity_log where entity_type='PURCHASE_ORDER' and entity_id=:id and action='DELETED'")
                .setParameter("id", Long.toString(order.id())).getSingleResult()).longValue());
        trash.restore(entry.id());
        var restored = purchaseOrders.get(order.id());
        assertEquals(order.number(), restored.number());
        assertEquals(order.status(), restored.status());
        assertEquals(order.notes(), restored.notes());
        assertFalse(restored.isStockBooked());
        assertEquals(document.id(), documents.forOrder(order.id()).getFirst().id());
        assertArrayEquals(new byte[]{1, 2, 3}, read(document.storageKey()));
    }

    @Test @TestTransaction
    void paymentHistoryAndLegacyPaidAmountBothBlockDeletion() {
        var registered = order();
        payments.save(new PurchasePayment(null, registered.id(), LocalDate.now(), BigDecimal.ONE,
                Currency.EUR, BigDecimal.ONE, "Betaling", "Emre", Instant.now()));
        assertThrows(BusinessRuleException.class, () -> purchaseOrders.delete(registered.id()));
        assertEquals(1, payments.forOrder(registered.id()).size());
        assertNotNull(purchaseOrders.get(registered.id()));
        var legacy = order();
        entities.find(PurchaseOrderEntity.class, legacy.id()).paidTotalEur = new BigDecimal("12.34");
        entities.flush(); entities.clear();
        assertThrows(BusinessRuleException.class, () -> purchaseOrders.delete(legacy.id()));
        assertEquals(0, payments.forOrder(legacy.id()).size());
        assertEquals(0, new BigDecimal("12.34").compareTo(purchaseOrders.get(legacy.id()).paidTotalEur()));
    }

    @Test @TestTransaction
    void deletedNumbersRemainReservedForCreationAndManualRenaming() {
        var first = order();
        purchaseOrders.delete(first.id());
        var next = purchaseOrders.create(first.supplierId(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.TEN);
        assertNotEquals(first.number(), next.number());
        var renamed = new PurchaseOrder(next.id(), first.number(), next.alias(), next.supplierId(), next.orderDate(),
                next.status(), next.containerType(), next.cnyToUsd(), next.usdToEurGoods(), next.usdToEurTransport(),
                next.freightUsd(), next.originCosts(), next.originCurrency(), next.destinationCostsEur(),
                next.defaultDutyRatePct(), next.extraRevenueEur(), next.allocFreight(), next.allocOrigin(),
                next.allocDestination(), next.allocExtra(), next.departurePort(), next.destinationPort(), next.notes(), next.lines());
        assertThrows(BusinessRuleException.class, () -> purchaseOrders.update(next.id(), renamed));
        assertTrue(orders.referencesSupplierIncludingDeleted(first.supplierId()));
    }

    @Test @TestTransaction
    void lifecycleLockReloadsAChangedCachedParentAndRejectsATombstone() {
        var order = order();
        var cached = entities.find(PurchaseOrderEntity.class, order.id());
        assertEquals(PurchaseOrderStatus.CONCEPT, cached.status);
        entities.createNativeQuery("update purchase_order set status='ONTVANGEN', stockBooked=true where id=:id")
                .setParameter("id", order.id()).executeUpdate();
        assertEquals(PurchaseOrderStatus.CONCEPT, cached.status);
        assertThrows(BusinessRuleException.class, () -> purchaseOrders.delete(order.id()), "authoritative status wins after waiting for the row lock");
        assertEquals(PurchaseOrderStatus.ONTVANGEN, purchaseOrders.get(order.id()).status());
        entities.createNativeQuery("update purchase_order set deleted_at=CURRENT_TIMESTAMP where id=:id")
                .setParameter("id", order.id()).executeUpdate();
        assertTrue(orders.findByIdForUpdate(order.id()).isEmpty());
        assertThrows(NotFoundException.class, () -> purchaseOrders.addPayment(order.id(), LocalDate.now(),
                BigDecimal.ONE, Currency.EUR, "Late betaling"));
        assertTrue(payments.forOrder(order.id()).isEmpty());
        assertThrows(NotFoundException.class, () -> purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.OTHER,
                null, null, "late.pdf", "application/pdf", new byte[]{1}));
        assertTrue(documents.forOrder(order.id()).isEmpty());
    }

    @Test @TestTransaction
    void trashAttachmentReturnsOriginalBytesOnlyForItsOwnHiddenPurchase() throws Exception {
        var hidden = order();
        byte[] original = new byte[]{37, 80, 68, 70, 10, 42};
        var own = purchaseOrders.addDocument(hidden.id(), PurchaseDocument.Kind.COMMERCIAL_INVOICE,
                null, null, "original.pdf", "application/pdf", original);
        var unrelated = order();
        var other = purchaseOrders.addDocument(unrelated.id(), PurchaseDocument.Kind.OTHER,
                null, null, "other.pdf", "application/pdf", new byte[]{3, 2, 1});
        purchaseOrders.delete(hidden.id());
        long entryId = trashEntry(hidden.id());
        assertThrows(NotFoundException.class, () -> purchaseOrders.document(hidden.id(), own.id()));
        var download = trash.attachment(entryId, own.id());
        assertEquals("original.pdf", download.name());
        assertEquals("application/pdf", download.contentType());
        try (var input = download.content()) { assertArrayEquals(original, input.readAllBytes()); }
        assertThrows(NotFoundException.class, () -> trash.attachment(entryId, other.id()));
        assertEquals(other.id(), purchaseOrders.document(unrelated.id(), other.id()).id());
    }

    @Test @TestTransaction
    void expiredAndRestoredTrashEntriesCannotDownloadPurchaseAttachments() {
        var expired = order();
        var expiredFile = purchaseOrders.addDocument(expired.id(), PurchaseDocument.Kind.OTHER,
                null, null, "expired.pdf", "application/pdf", new byte[]{1, 2});
        purchaseOrders.delete(expired.id());
        long expiredEntry = trashEntry(expired.id());
        entities.find(be.enrosed.shared.trash.DeletedItemEntity.class, expiredEntry).expiresAt = Instant.now().minusSeconds(1);
        entities.flush(); entities.clear();
        assertThrows(NotFoundException.class, () -> trash.attachment(expiredEntry, expiredFile.id()));
        assertTrue(storage.exists(expiredFile.storageKey()), "expiry closes access and does not erase retained bytes");

        var restored = order();
        var restoredFile = purchaseOrders.addDocument(restored.id(), PurchaseDocument.Kind.OTHER,
                null, null, "restored.pdf", "application/pdf", new byte[]{4, 5});
        purchaseOrders.delete(restored.id());
        long restoredEntry = trashEntry(restored.id());
        trash.restore(restoredEntry);
        assertThrows(NotFoundException.class, () -> trash.attachment(restoredEntry, restoredFile.id()));
        assertEquals(restoredFile.id(), purchaseOrders.document(restored.id(), restoredFile.id()).id());
    }

    private long trashEntry(long sourceId) {
        return trash.list().items().stream().filter(row -> row.sourceId() == sourceId
                && row.type() == be.enrosed.shared.trash.DeletedItemDtos.Type.PURCHASE_ORDER)
                .findFirst().orElseThrow().id();
    }

    private PurchaseOrder order() {
        var supplier = supplierService.save(new Supplier(null, "Recovery supplier " + System.nanoTime(), "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        return purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.TEN);
    }

    private byte[] read(String key) {
        try (var input = storage.read(key)) { return input.readAllBytes(); }
        catch (java.io.IOException problem) { throw new java.io.UncheckedIOException(problem); }
    }
}
