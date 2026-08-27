package be.enrosed.planning;

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

class PlannerAttachmentCleanupTest {

    @Test
    void deletionIsDeduplicatedBestEffortAndOnlyRunsAfterCommit() throws Exception {
        PhotoStorage storage = mock(PhotoStorage.class);
        PlannerAttachmentCleanup cleanup = new PlannerAttachmentCleanup(storage);
        PlannerAttachmentCleanup.DeleteReady ready = new PlannerAttachmentCleanup.DeleteReady(
                List.of(" attachment-1 ", "attachment-1", "attachment-2"));

        cleanup.afterDeleteCommitted(ready);
        assertEquals(List.of("attachment-1", "attachment-2"), ready.storageKeys());
        verify(storage).delete("attachment-1");
        verify(storage).delete("attachment-2");

        doThrow(new IllegalStateException("storage unavailable")).when(storage)
                .delete("attachment-1");
        assertDoesNotThrow(() -> cleanup.afterDeleteCommitted(ready));

        assertObserverBoundary("afterDeleteCommitted", PlannerAttachmentCleanup.DeleteReady.class,
                TransactionPhase.AFTER_SUCCESS);
    }

    @Test
    void failedUploadGetsBoundedBestEffortCompensation() throws Exception {
        PhotoStorage storage = mock(PhotoStorage.class);
        PlannerAttachmentCleanup cleanup = new PlannerAttachmentCleanup(storage);
        PlannerAttachmentCleanup.UploadReady ready =
                new PlannerAttachmentCleanup.UploadReady(14L, " upload-14 ");

        cleanup.afterUploadRolledBack(ready);
        assertEquals("upload-14", ready.storageKey());
        verify(storage).delete("upload-14");

        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("upload-14");
        assertDoesNotThrow(() -> cleanup.afterUploadRolledBack(ready));

        assertObserverBoundary("afterUploadRolledBack", PlannerAttachmentCleanup.UploadReady.class,
                TransactionPhase.AFTER_FAILURE);
    }

    private static void assertObserverBoundary(String methodName, Class<?> eventType,
                                               TransactionPhase phase) throws Exception {
        Method method = PlannerAttachmentCleanup.class.getDeclaredMethod(methodName, eventType);
        Observes observes = method.getParameters()[0].getAnnotation(Observes.class);
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertNotNull(observes);
        assertEquals(phase, observes.during());
        assertNotNull(transaction);
        assertEquals(Transactional.TxType.REQUIRES_NEW, transaction.value());
    }
}
