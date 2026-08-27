package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PurchaseDocumentStorageCleanupTest {

    @Test
    void deletionRunsAfterCommitInANewTransactionAndStorageFailureNeverEscapes() throws Exception {
        PhotoStorage storage = mock(PhotoStorage.class);
        PurchaseDocumentStorageCleanup cleanup = new PurchaseDocumentStorageCleanup(storage);
        PurchaseDocumentStorageCleanup.DeleteReady ready =
                new PurchaseDocumentStorageCleanup.DeleteReady(41L,
                        List.of(" blob-8 ", "blob-8", "blob-9"));

        cleanup.afterDeleteCommitted(ready);
        assertEquals(List.of("blob-8", "blob-9"), ready.storageKeys());
        verify(storage).delete("blob-8");
        verify(storage).delete("blob-9");

        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("blob-8");
        assertDoesNotThrow(() -> cleanup.afterDeleteCommitted(ready),
                "external storage must never turn a committed document deletion into an error");

        assertObserverBoundary("afterDeleteCommitted", PurchaseDocumentStorageCleanup.DeleteReady.class,
                TransactionPhase.AFTER_SUCCESS);
    }

    @Test
    void failedUploadTransactionGetsBoundedBestEffortCompensation() throws Exception {
        PhotoStorage storage = mock(PhotoStorage.class);
        PurchaseDocumentStorageCleanup cleanup = new PurchaseDocumentStorageCleanup(storage);
        PurchaseDocumentStorageCleanup.UploadReady ready =
                new PurchaseDocumentStorageCleanup.UploadReady(41L, " upload-9 ");

        cleanup.afterUploadRolledBack(ready);
        assertEquals("upload-9", ready.storageKey());
        verify(storage).delete("upload-9");

        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("upload-9");
        assertDoesNotThrow(() -> cleanup.afterUploadRolledBack(ready));

        assertObserverBoundary("afterUploadRolledBack", PurchaseDocumentStorageCleanup.UploadReady.class,
                TransactionPhase.AFTER_FAILURE);
    }

    private static void assertObserverBoundary(String methodName, Class<?> eventType,
                                               TransactionPhase phase) throws Exception {
        Method method = PurchaseDocumentStorageCleanup.class.getDeclaredMethod(methodName, eventType);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(observes);
        assertEquals(phase, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
