package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Objects;

/** Write boundary for canonical family-image-to-SKU links. */
@ApplicationScoped
public class FamilyImageVariantService {
    private final CatalogDaos.Products products;
    private final FamilyPhotoCompatibilityService compatibility;

    public FamilyImageVariantService(
            CatalogDaos.Products products,
            FamilyPhotoCompatibilityService compatibility) {
        this.products = products;
        this.compatibility = compatibility;
    }

    public ProductEntity requireMember(ProductFamilyEntity family, long productId) {
        ProductEntity variant = products.findById(productId);
        if (variant == null || !Objects.equals(variant.familyId, family.id)) {
            throw new BusinessRuleException(
                    "Product " + productId + " behoort niet tot productfamilie " + family.id);
        }
        return variant;
    }

    /** The legacy fields are a derived compatibility projection, never client-owned labels. */
    public void assign(ProductFamilyPhotoEntity photo, ProductEntity variant) {
        photo.variantProduct = variant;
        photo.variantExternalId = variant == null ? null : variant.canonicalVariantKey;
        photo.variantColor = variant == null ? null : variant.colour;
    }

    @Transactional
    public void link(
            ProductFamilyEntity family,
            ProductFamilyPhotoEntity photo,
            Long variantProductId) {
        ProductEntity variant = variantProductId == null
                ? null : requireMember(family, variantProductId);
        assign(photo, variant);
        compatibility.sync(family);
    }
}
