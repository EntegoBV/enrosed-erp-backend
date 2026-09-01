package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The Panache repositories.
 *
 * Deliberately separate from the port adapters: Panache ships its own
 * findAll() and findById(), with return types different from our ports.
 * Composition instead of inheritance keeps both sides readable.
 */
public final class CatalogDaos {

    private CatalogDaos() {}

    @ApplicationScoped
    public static class Products implements PanacheRepository<ProductEntity> {}

    @ApplicationScoped
    public static class Categories implements PanacheRepository<CategoryEntity> {}

    @ApplicationScoped
    public static class CategoryTexts implements PanacheRepository<CategoryTextEntity> {}

    @ApplicationScoped
    public static class HsCodes implements PanacheRepository<HsCodeEntity> {}

    @ApplicationScoped
    public static class SupplierAgreementPhotos
            implements PanacheRepository<ProductSupplierAgreementPhotoEntity> {}
}
