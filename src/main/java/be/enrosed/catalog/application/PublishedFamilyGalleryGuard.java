package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;

/** Transactional invariant used after every canonical family-gallery mutation. */
@ApplicationScoped
public class PublishedFamilyGalleryGuard {
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final FamilyPhotoPublicationPolicy photoPublication;
    private final FeaturedProductSelectionService featuredProducts;

    public PublishedFamilyGalleryGuard(
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            FamilyPhotoPublicationPolicy photoPublication,
            FeaturedProductSelectionService featuredProducts) {
        this.products = products;
        this.categories = categories;
        this.photoPublication = photoPublication;
        this.featuredProducts = featuredProducts;
    }

    public void validate(ProductFamilyEntity family) {
        if (!published(family)) return;
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        if (family.photos.stream().noneMatch(photo -> photoPublication.isPublic(photo, members))) {
            throw new BusinessRuleException(
                    "Een gepubliceerde productfamilie moet minstens één publiceerbare foto "
                            + "met afmetingen en alt-tekst houden");
        }
        if (family.cardFeaturedProductId != null) {
            featuredProducts.requireFamilyMember(family, family.cardFeaturedProductId);
        }
        if (family.websiteStatus != PublicationState.PUBLISHED) return;
        for (ProductFamilyCollectionEntity membership : family.collections) {
            ProductCollectionEntity collection = membership.collection;
            if (collection == null || collection.featuredProductId == null) continue;
            ProductEntity featured = products.findById(collection.featuredProductId);
            if (featured != null && Objects.equals(featured.familyId, family.id)) {
                featuredProducts.requireCollectionMember(collection, collection.featuredProductId);
            }
        }
        for (CategoryEntity category : categories.list("featuredProductId is not null")) {
            ProductEntity featured = products.findById(category.featuredProductId);
            if (featured != null && Objects.equals(featured.familyId, family.id)) {
                featuredProducts.requireCategoryMember(
                        category.id, category.code, category.featuredProductId);
            }
        }
    }

    private static boolean published(ProductFamilyEntity family) {
        return family.websiteStatus == PublicationState.PUBLISHED
                || family.orderAppStatus == PublicationState.PUBLISHED
                || family.catalogueStatus == PublicationState.PUBLISHED;
    }
}
