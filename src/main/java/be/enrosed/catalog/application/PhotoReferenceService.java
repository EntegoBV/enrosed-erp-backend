package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/** Deletes a blob only after every legacy owner and manager version stopped referring to it. */
@ApplicationScoped
public class PhotoReferenceService {
    private final EntityManager entityManager;
    private final PhotoStorage storage;

    public PhotoReferenceService(EntityManager entityManager, PhotoStorage storage) {
        this.entityManager = entityManager;
        this.storage = storage;
    }

    @Transactional
    public void deleteIfUnreferenced(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        // The same row lock is taken by DatabasePhotoStorage.storeKnown(). It turns the
        // count/delete decision into one database-wide critical section, including multi-instance
        // deployments; the media_version FKs remain the final integrity guard.
        boolean exists = !entityManager.createNativeQuery(
                        "select storageKey from photo_blob where storageKey = :key for update")
                .setParameter("key", storageKey)
                .getResultList().isEmpty();
        if (!exists) return;
        entityManager.flush();
        Number references = (Number) entityManager.createNativeQuery("""
                select
                  (select count(*) from product_photo where storageKey = :key) +
                  (select count(*) from product_family_photo where smallStorageKey = :key) +
                  (select count(*) from product_family_photo where largeStorageKey = :key) +
                  (select count(*) from product_supplier_agreement_photo where storage_key = :key) +
                  (select count(*) from purchase_document where storageKey = :key) +
                  (select count(*) from planner_attachment where storageKey = :key) +
                  (select count(*) from media_version where storage_key = :key) +
                  (select count(*) from media_version where thumbnail_storage_key = :key) +
                  (select count(*) from sales_document_media_snapshot where storage_key = :key)
                """)
                .setParameter("key", storageKey)
                .getSingleResult();
        if (references.longValue() == 0 && storage.exists(storageKey)) storage.delete(storageKey);
    }
}
