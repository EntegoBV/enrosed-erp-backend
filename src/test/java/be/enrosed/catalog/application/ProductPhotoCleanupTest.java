package be.enrosed.catalog.application;

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

class ProductPhotoCleanupTest {

    @Test
    void deletionIsDeduplicatedBestEffortAndOnlyRunsAfterCommit() throws Exception {
        PhotoReferenceService references = mock(PhotoReferenceService.class);
        ProductPhotoCleanup cleanup = new ProductPhotoCleanup(references, mock(PhotoStorage.class));
        ProductPhotoCleanup.DeleteReady ready = new ProductPhotoCleanup.DeleteReady(
                List.of(" photo-1 ", "photo-1", "photo-2"));

        cleanup.afterDeleteCommitted(ready);
        assertEquals(List.of("photo-1", "photo-2"), ready.storageKeys());
        verify(references).deleteIfUnreferenced("photo-1");
        verify(references).deleteIfUnreferenced("photo-2");

        doThrow(new IllegalStateException("storage unavailable")).when(references)
                .deleteIfUnreferenced("photo-1");
        assertDoesNotThrow(() -> cleanup.afterDeleteCommitted(ready));

        assertObserverBoundary("afterDeleteCommitted", ProductPhotoCleanup.DeleteReady.class,
                TransactionPhase.AFTER_SUCCESS);
    }

    @Test
    void failedUploadGetsBoundedBestEffortCompensation() throws Exception {
        PhotoStorage storage = mock(PhotoStorage.class);
        ProductPhotoCleanup cleanup = new ProductPhotoCleanup(
                mock(PhotoReferenceService.class), storage);
        ProductPhotoCleanup.UploadReady ready =
                new ProductPhotoCleanup.UploadReady(82L, " upload-82 ");

        cleanup.afterUploadRolledBack(ready);
        assertEquals("upload-82", ready.storageKey());
        verify(storage).delete("upload-82");

        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("upload-82");
        assertDoesNotThrow(() -> cleanup.afterUploadRolledBack(ready));

        assertObserverBoundary("afterUploadRolledBack", ProductPhotoCleanup.UploadReady.class,
                TransactionPhase.AFTER_FAILURE);
    }

    private static void assertObserverBoundary(String methodName, Class<?> eventType,
                                               TransactionPhase phase) throws Exception {
        Method method = ProductPhotoCleanup.class.getDeclaredMethod(methodName, eventType);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(observes);
        assertEquals(phase, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
