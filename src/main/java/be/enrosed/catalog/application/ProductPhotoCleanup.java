package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

import java.util.List;

/** Keeps product-photo references and externally stored bytes aligned at transaction boundaries. */
@ApplicationScoped
public class ProductPhotoCleanup {

    private final PhotoReferenceService references;
    private final PhotoStorage storage;

    public ProductPhotoCleanup(PhotoReferenceService references, PhotoStorage storage) {
        this.references = references;
        this.storage = storage;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterDeleteCommitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) DeleteReady ready) {
        for (String storageKey : ready.storageKeys()) {
            try {
                references.deleteIfUnreferenced(storageKey);
            } catch (RuntimeException ignored) {
                /* A stale blob is safer than restoring a database row whose photo disappeared. */
            }
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterUploadRolledBack(@Observes(during = TransactionPhase.AFTER_FAILURE) UploadReady ready) {
        try {
            storage.delete(ready.storageKey());
        } catch (RuntimeException ignored) {
            /* A sweepable orphan is safer than changing the failed product transaction. */
        }
    }

    public record DeleteReady(List<String> storageKeys) {
        public DeleteReady {
            storageKeys = storageKeys == null ? List.of() : storageKeys.stream()
                    .map(ProductPhotoCleanup::requiredStorageKey)
                    .distinct()
                    .toList();
        }
    }

    public record UploadReady(long productId, String storageKey) {
        public UploadReady {
            if (productId <= 0) throw new IllegalArgumentException("productId is required");
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
