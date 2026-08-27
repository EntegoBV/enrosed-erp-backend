package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.Currency;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderDocumentLifecycleTest {

    @Test
    @SuppressWarnings("unchecked")
    void deleteRemovesTheRowAndEmitsStructuredCleanupWithoutTouchingStorageInline() {
        SourcingRepositories.PurchaseOrders orders = mock(SourcingRepositories.PurchaseOrders.class);
        PurchaseOrder order = order();
        when(orders.findById(41L)).thenReturn(Optional.of(order));

        SourcingRepositories.Documents repository = mock(SourcingRepositories.Documents.class);
        PurchaseDocument document = document();
        when(repository.find(41L, 8L)).thenReturn(Optional.of(document));
        Instance<SourcingRepositories.Documents> repositories = mock(Instance.class);
        when(repositories.get()).thenReturn(repository);

        Instance<PhotoStorage> storage = mock(Instance.class);
        Event<PurchaseDocumentStorageCleanup.DeleteReady> cleanup = mock(Event.class);
        PurchaseOrderService service = service(orders);
        service.documents = repositories;
        service.photoStorage = storage;
        service.documentDeleteCleanup = cleanup;

        service.deleteDocument(41L, 8L);

        verify(repository).delete(41L, 8L);
        verify(cleanup).fire(new PurchaseDocumentStorageCleanup.DeleteReady(41L, List.of("blob-8")));
        verify(storage, never()).get();

        /* Services constructed directly by pure unit tests still work without CDI events. */
        PurchaseOrderService direct = service(orders);
        direct.documents = repositories;
        assertDoesNotThrow(() -> direct.deleteDocument(41L, 8L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingAnOrderRemovesDependentRowsAndEmitsOnePostCommitBlobCleanup() {
        SourcingRepositories.PurchaseOrders orders = mock(SourcingRepositories.PurchaseOrders.class);
        PurchaseOrder order = order();
        when(orders.findByIdForUpdate(41L)).thenReturn(Optional.of(order));

        SourcingRepositories.Documents documentRepository = mock(SourcingRepositories.Documents.class);
        PurchaseDocument second = new PurchaseDocument(9L, 41L, PurchaseDocument.Kind.PACKING_LIST,
                null, "packing.pdf", "application/pdf", 4L, "blob-9",
                null, "Berat", Instant.parse("2026-08-27T11:15:30Z"));
        when(documentRepository.forOrder(41L)).thenReturn(List.of(document(), second));
        Instance<SourcingRepositories.Documents> documentRepositories = mock(Instance.class);
        when(documentRepositories.isResolvable()).thenReturn(true);
        when(documentRepositories.get()).thenReturn(documentRepository);

        SourcingRepositories.Payments paymentRepository = mock(SourcingRepositories.Payments.class);
        Instance<SourcingRepositories.Payments> paymentRepositories = mock(Instance.class);
        when(paymentRepositories.isResolvable()).thenReturn(true);
        when(paymentRepositories.get()).thenReturn(paymentRepository);

        ActivityLogService activityLog = mock(ActivityLogService.class);
        Instance<ActivityLogService> activities = mock(Instance.class);
        when(activities.isResolvable()).thenReturn(true);
        when(activities.get()).thenReturn(activityLog);

        Event<PurchaseDocumentStorageCleanup.DeleteReady> cleanup = mock(Event.class);
        Instance<PhotoStorage> storage = mock(Instance.class);
        PurchaseOrderService service = service(orders);
        service.documents = documentRepositories;
        service.payments = paymentRepositories;
        service.activity = activities;
        service.documentDeleteCleanup = cleanup;
        service.photoStorage = storage;

        service.delete(41L);

        InOrder sequence = inOrder(documentRepository, paymentRepository, orders, activityLog, cleanup);
        sequence.verify(documentRepository).deleteForOrder(41L);
        sequence.verify(paymentRepository).deleteForOrder(41L);
        sequence.verify(orders).deleteById(41L);
        sequence.verify(activityLog).record(ActivityLogService.ACTION_DELETED,
                ActivityLogService.ENTITY_PURCHASE_ORDER, "41", "PO-2026-041", "Inkooporder verwijderd");
        sequence.verify(cleanup).fire(new PurchaseDocumentStorageCleanup.DeleteReady(
                41L, List.of("blob-8", "blob-9")));
        verify(storage, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadRegistersRollbackCompensationBeforeTheDocumentRowIsSaved() {
        SourcingRepositories.PurchaseOrders orders = mock(SourcingRepositories.PurchaseOrders.class);
        PurchaseOrder order = order();
        when(orders.findById(41L)).thenReturn(Optional.of(order));

        SourcingRepositories.Documents repository = mock(SourcingRepositories.Documents.class);
        when(repository.forOrder(41L)).thenReturn(List.of());
        when(repository.save(any())).thenThrow(new IllegalStateException("database write failed"));
        Instance<SourcingRepositories.Documents> repositories = mock(Instance.class);
        when(repositories.get()).thenReturn(repository);

        PhotoStorage blobStore = mock(PhotoStorage.class);
        when(blobStore.store("invoice.pdf", "application/pdf", new byte[] { 1, 2, 3 }))
                .thenReturn(new PhotoStorage.Stored("blob-new", 3L, null, null));
        Instance<PhotoStorage> storage = mock(Instance.class);
        when(storage.get()).thenReturn(blobStore);

        Event<PurchaseDocumentStorageCleanup.UploadReady> cleanup = mock(Event.class);
        PurchaseOrderService service = service(orders);
        service.documents = repositories;
        service.photoStorage = storage;
        service.documentUploadCleanup = cleanup;

        assertThrows(IllegalStateException.class, () -> service.addDocument(41L,
                PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, null,
                "invoice.pdf", "application/pdf", new byte[] { 1, 2, 3 }));

        InOrder sequence = inOrder(blobStore, cleanup, repository);
        sequence.verify(blobStore).store("invoice.pdf", "application/pdf", new byte[] { 1, 2, 3 });
        sequence.verify(cleanup).fire(new PurchaseDocumentStorageCleanup.UploadReady(41L, "blob-new"));
        sequence.verify(repository).save(any(PurchaseDocument.class));
    }

    private static PurchaseOrderService service(SourcingRepositories.PurchaseOrders orders) {
        return new PurchaseOrderService(orders, mock(SourcingRepositories.Suppliers.class),
                mock(ProductService.class), null);
    }

    private static PurchaseDocument document() {
        return new PurchaseDocument(8L, 41L, PurchaseDocument.Kind.COMMERCIAL_INVOICE,
                null, "invoice.pdf", "application/pdf", 3L, "blob-8",
                null, "Emre", Instant.parse("2026-08-27T10:15:30Z"));
    }

    private static PurchaseOrder order() {
        return new PurchaseOrder(41L, "PO-2026-041", null, 7L,
                LocalDate.of(2026, 8, 27), PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.90"), new BigDecimal("0.90"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                BigDecimal.TEN, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
    }
}
