package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import jakarta.enterprise.context.ApplicationScoped;

/** Updates only true family-owned denormalized member fields; SKU copy remains product-owned. */
@ApplicationScoped
public class FamilyMemberCacheService {
    private final CatalogDaos.Products products;

    public FamilyMemberCacheService(CatalogDaos.Products products) {
        this.products = products;
    }

    public void sync(ProductFamilyEntity family) {
        for (ProductEntity product : products.list("familyId", family.id)) {
            product.familyKey = family.familyKey;
            product.categoryId = family.categoryId;
        }
    }
}
