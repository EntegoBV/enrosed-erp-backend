package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;

/** Validates that merchandising preview choices remain stable, active catalogue members. */
@ApplicationScoped
public class FeaturedProductSelectionService {
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final CanonicalCatalogDaos.Families families;
    private final CanonicalCatalogDaos.Collections collections;
    private final FamilyPhotoPublicationPolicy photoPublication;

    public FeaturedProductSelectionService(
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            CanonicalCatalogDaos.Families families,
            CanonicalCatalogDaos.Collections collections,
            FamilyPhotoPublicationPolicy photoPublication) {
        this.products = products;
        this.categories = categories;
        this.families = families;
        this.collections = collections;
        this.photoPublication = photoPublication;
    }

    public ProductEntity requireFamilyMember(ProductFamilyEntity family, Long productId) {
        ProductEntity product = requireActive(productId);
        if (!family.active || family.id == null || !Objects.equals(product.familyId, family.id)) {
            throw new BusinessRuleException(
                    "Uitgelicht product " + productId + " behoort niet tot deze productfamilie");
        }
        requirePublicPhoto(family, product);
        return product;
    }

    public ProductEntity requireCategoryMember(
            Long categoryId, String categoryCode, Long productId) {
        ProductEntity product = requireActive(productId);
        ProductFamilyEntity family = product.familyId == null ? null : families.findById(product.familyId);
        if (family == null || !family.active || !belongsToCategory(
                family, product, categoryId, categoryCode)) {
            throw new BusinessRuleException(
                    "Uitgelicht product " + productId + " behoort niet tot deze categorie of collectie");
        }
        requireWebsitePublished(family, product.id);
        requirePublicPhoto(family, product);
        return product;
    }

    public ProductEntity requireCollectionMember(
            ProductCollectionEntity collection, Long productId) {
        ProductEntity product = requireActive(productId);
        ProductFamilyEntity family = product.familyId == null ? null : families.findById(product.familyId);
        boolean member = family != null && family.active && family.collections.stream()
                .anyMatch(item -> item.collection != null
                        && (item.collection == collection
                        || collection.id != null && Objects.equals(item.collection.id, collection.id)
                        || Objects.equals(item.collection.collectionKey, collection.collectionKey)));
        if (!member) {
            throw new BusinessRuleException(
                    "Uitgelicht product " + productId + " behoort niet tot collectie "
                            + collection.collectionKey);
        }
        requireWebsitePublished(family, product.id);
        requirePublicPhoto(family, product);
        return product;
    }

    /** Clears persisted merchandising pointers made invalid by a family lifecycle/category edit. */
    public void clearInvalidReferencesForFamily(ProductFamilyEntity family) {
        if (family == null || family.id == null) return;
        if (family.cardFeaturedProductId != null
                && !valid(() -> requireFamilyMember(family, family.cardFeaturedProductId))) {
            family.cardFeaturedProductId = null;
        }
        for (ProductCollectionEntity collection : collections.list(
                "featuredProductId is not null")) {
            ProductEntity selected = products.findById(collection.featuredProductId);
            if (selected != null && Objects.equals(selected.familyId, family.id)
                    && !valid(() -> requireCollectionMember(
                            collection, collection.featuredProductId))) {
                collection.featuredProductId = null;
            }
        }
        for (CategoryEntity category : categories.list("featuredProductId is not null")) {
            ProductEntity selected = products.findById(category.featuredProductId);
            if (selected != null && Objects.equals(selected.familyId, family.id)
                    && !valid(() -> requireCategoryMember(
                            category.id, category.code, category.featuredProductId))) {
                category.featuredProductId = null;
            }
        }
    }

    private void requirePublicPhoto(ProductFamilyEntity family, ProductEntity product) {
        var familyMembers = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        boolean found = family.photos.stream().anyMatch(photo ->
                photoPublication.isUsableBy(photo, product, familyMembers));
        if (!found) {
            throw new BusinessRuleException(
                    "Uitgelicht product " + product.id
                            + " heeft geen eigen of familiebrede publieke foto");
        }
    }

    private static void requireWebsitePublished(ProductFamilyEntity family, long productId) {
        if (family.websiteStatus != PublicationState.PUBLISHED) {
            throw new BusinessRuleException(
                    "Uitgelicht product " + productId
                            + " behoort niet tot een gepubliceerde websitefamilie");
        }
    }

    private ProductEntity requireActive(Long productId) {
        if (productId == null) return null;
        ProductEntity product = products.findById(productId);
        if (product == null) {
            throw new BusinessRuleException("Onbekend uitgelicht product " + productId);
        }
        if (!product.active) {
            throw new BusinessRuleException("Uitgelicht product " + productId + " is niet actief");
        }
        return product;
    }

    private static boolean belongsToCategory(
            ProductFamilyEntity family, ProductEntity product,
            Long categoryId, String categoryCode) {
        if (categoryId != null && Objects.equals(family.categoryId, categoryId)) {
            return true;
        }
        if (categoryCode == null) return false;
        String publicKey = CategoryPublicKey.from(categoryCode);
        return publicKey.equals(family.categoryKey);
    }

    private static boolean valid(Runnable validation) {
        try {
            validation.run();
            return true;
        } catch (BusinessRuleException ignored) {
            return false;
        }
    }
}
