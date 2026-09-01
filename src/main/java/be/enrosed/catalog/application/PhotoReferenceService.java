package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/** Deletes a blob only after every product and family rendition stopped referring to it. */
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
        entityManager.flush();
        Number references = (Number) entityManager.createNativeQuery("""
                select
                  (select count(*) from product_photo where storageKey = :key) +
                  (select count(*) from product_family_photo where smallStorageKey = :key) +
                  (select count(*) from product_family_photo where largeStorageKey = :key) +
                  (select count(*) from product_supplier_agreement_photo where storage_key = :key)
                """)
                .setParameter("key", storageKey)
                .getSingleResult();
        if (references.longValue() == 0 && storage.exists(storageKey)) storage.delete(storageKey);
    }
}
