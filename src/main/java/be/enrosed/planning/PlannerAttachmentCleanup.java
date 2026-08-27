package be.enrosed.planning;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

import java.util.List;

/** Keeps planner attachment rows and externally stored bytes aligned at transaction boundaries. */
@ApplicationScoped
public class PlannerAttachmentCleanup {

    private final PhotoStorage storage;

    public PlannerAttachmentCleanup(PhotoStorage storage) {
        this.storage = storage;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterDeleteCommitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) DeleteReady ready) {
        for (String storageKey : ready.storageKeys()) {
            deleteBestEffort(storageKey);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterUploadRolledBack(@Observes(during = TransactionPhase.AFTER_FAILURE) UploadReady ready) {
        deleteBestEffort(ready.storageKey());
    }

    private void deleteBestEffort(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException ignored) {
            /* A sweepable orphan is safer than changing the planner transaction. */
        }
    }

    public record DeleteReady(List<String> storageKeys) {
        public DeleteReady {
            storageKeys = storageKeys == null ? List.of() : storageKeys.stream()
                    .map(PlannerAttachmentCleanup::requiredStorageKey)
                    .distinct()
                    .toList();
        }
    }

    public record UploadReady(long itemId, String storageKey) {
        public UploadReady {
            if (itemId <= 0) throw new IllegalArgumentException("itemId is required");
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
