package be.enrosed.media;

import be.enrosed.catalog.application.PhotoReferenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.transaction.Transactional;

import java.util.List;

/** Deletes bytes only after the owning transaction and only when globally unreferenced. */
@ApplicationScoped
public class MediaBlobCleanup {
    private final PhotoReferenceService references;

    public MediaBlobCleanup(PhotoReferenceService references) {
        this.references = references;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterDeleteCommitted(@Observes(during = TransactionPhase.AFTER_SUCCESS) DeleteReady ready) {
        ready.storageKeys().forEach(this::deleteBestEffort);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void afterUploadRolledBack(@Observes(during = TransactionPhase.AFTER_FAILURE) UploadReady ready) {
        deleteBestEffort(ready.storageKey());
    }

    private void deleteBestEffort(String key) {
        try {
            references.deleteIfUnreferenced(key);
        } catch (RuntimeException ignored) {
            /* A stale blob is recoverable; a dangling reference is not. */
        }
    }

    public record DeleteReady(List<String> storageKeys) {
        public DeleteReady {
            storageKeys = storageKeys == null ? List.of() : storageKeys.stream()
                    .map(MediaBlobCleanup::required).distinct().toList();
        }
    }

    public record UploadReady(String storageKey) {
        public UploadReady { storageKey = required(storageKey); }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("storageKey is required");
        return value.strip();
    }
}
