package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.PhotoReferenceService;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Keeps purchase-document rows and their externally stored bytes consistent.
 *
 * <p>Deleting bytes before the owning database transaction commits can leave a
 * document row pointing at a missing file. Uploads have the inverse risk when a
 * future external store cannot participate in the database transaction. Both
 * cleanups therefore run in their own transaction at the matching transaction
 * boundary and remain best effort: a stale blob is recoverable, a broken
 * business transaction is not.</p>
 */
@ApplicationScoped
public class PurchaseDocumentStorageCleanup {

    private final PhotoStorage storage;
    private final PhotoReferenceService references;

    @Inject
    public PurchaseDocumentStorageCleanup(PhotoStorage storage, PhotoReferenceService references) {
        this.storage = storage;
        this.references = references;
    }

    /** Compatibility for isolated cleanup tests without a persistence context. */
    PurchaseDocumentStorageCleanup(PhotoStorage storage) {
        this.storage = storage;
        this.references = null;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterDeleteCommitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) DeleteReady ready) {
        for (String storageKey : ready.storageKeys()) deleteBestEffort(storageKey);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterUploadRolledBack(@Observes(during = TransactionPhase.AFTER_FAILURE) UploadReady ready) {
        deleteBestEffort(ready.storageKey());
    }

    private void deleteBestEffort(String storageKey) {
        try {
            if (references == null) storage.delete(storageKey);
            else references.deleteIfUnreferenced(storageKey);
        } catch (RuntimeException ignored) {
            /* A sweepable orphan is safer than rolling back a valid order change. */
        }
    }

    public record DeleteReady(long orderId, List<String> storageKeys) {
        public DeleteReady {
            storageKeys = storageKeys == null ? List.of() : storageKeys.stream()
                    .map(PurchaseDocumentStorageCleanup::requiredStorageKey)
                    .distinct()
                    .toList();
        }
    }

    public record UploadReady(long orderId, String storageKey) {
        public UploadReady {
            storageKey = requiredStorageKey(storageKey);
        }
    }

    private static String requiredStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        return storageKey.strip();
    }
}
