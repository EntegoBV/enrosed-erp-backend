package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Links two stock-bearing products as colour and/or size variants.
 *
 * The command deliberately hides canonical family administration from its caller. It either uses
 * the one existing family or creates a private model group and performs both assignments inside the
 * same transaction.
 */
@ApplicationScoped
public class ProductVariantLinkService {
    private final ProductRepository products;
    private final ProductService productService;
    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Categories categories;
    private final ProductFamilyWriteGuard familyWrites;
    private final FamilyCollectionAlignmentService familyCollections;
    private final FamilyMemberCacheService familyMembers;

    public ProductVariantLinkService(
            ProductRepository products,
            ProductService productService,
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Categories categories,
            ProductFamilyWriteGuard familyWrites,
            FamilyCollectionAlignmentService familyCollections,
            FamilyMemberCacheService familyMembers) {
        this.products = products;
        this.productService = productService;
        this.families = families;
        this.categories = categories;
        this.familyWrites = familyWrites;
        this.familyCollections = familyCollections;
        this.familyMembers = familyMembers;
    }

    public record Result(ProductFamilyEntity family, boolean familyCreated) {}

    @Transactional
    public Result link(long sourceProductId, long variantProductId) {
        if (sourceProductId == variantProductId) {
            throw new BusinessRuleException("Een product kan niet als variant van zichzelf worden gekoppeld");
        }

        Product observedSource = product(sourceProductId);
        Product observedVariant = product(variantProductId);
        familyWrites.lockFamilies(Arrays.asList(observedSource.familyId(), observedVariant.familyId()));
        familyWrites.lockProducts(List.of(sourceProductId, variantProductId));

        Product source = product(sourceProductId);
        Product variant = product(variantProductId);
        if (!Objects.equals(source.familyId(), observedSource.familyId())
                || !Objects.equals(variant.familyId(), observedVariant.familyId())) {
            throw new BusinessRuleException(
                    "Een van de producten is gelijktijdig naar een ander model verplaatst; laad opnieuw");
        }

        if (source.familyId() != null && variant.familyId() != null
                && !Objects.equals(source.familyId(), variant.familyId())) {
            throw new BusinessRuleException(
                    "De producten horen al bij verschillende modellen; die worden niet automatisch samengevoegd");
        }

        /* Existing membership is a true idempotent result. Legacy review families may still
           contain incomplete or duplicate option tuples; this command must not turn a no-op
           into a validation failure or mutate that family. */
        if (source.familyId() != null && Objects.equals(source.familyId(), variant.familyId())) {
            return new Result(family(source.familyId()), false);
        }

        requireVariantOption(source);
        requireVariantOption(variant);
        if (FamilyVariantRules.sameOption(source, variant)) {
            throw new BusinessRuleException(
                    "De producten hebben dezelfde combinatie van kleur en maat; kies een echte variant");
        }

        ProductFamilyEntity existingFamily = source.familyId() != null
                ? family(source.familyId())
                : variant.familyId() != null ? family(variant.familyId()) : null;
        if (existingFamily != null) {
            Product standalone = source.familyId() == null ? source : variant;
            Long canonicalCategoryId = canonicalExistingCategory(existingFamily, standalone.categoryId());
            requireCompatibleCategory(canonicalCategoryId, standalone.categoryId());
            requireNoActiveOptionCollision(existingFamily.id, standalone);
            productService.assignFamily(standalone.id(), existingFamily.id);
            families.flush();
            return new Result(existingFamily, false);
        }

        Long categoryId = compatibleNewFamilyCategory(source.categoryId(), variant.categoryId());
        CategoryEntity category = lockCategory(categoryId);
        ProductFamilyEntity created = newFamily(source, variant, category);
        families.persist(created);
        familyCollections.alignPrimary(created);
        families.flush();

        productService.assignFamily(source.id(), created.id);
        productService.assignFamily(variant.id(), created.id);
        families.flush();
        return new Result(created, true);
    }

    private ProductFamilyEntity newFamily(
            Product source, Product variant, CategoryEntity category) {
        if (source.name() == null || source.name().isBlank()) {
            throw new BusinessRuleException("Het bronproduct heeft geen naam voor de nieuwe modelgroep");
        }
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = nextFamilyKey(source.id(), variant.id());
        family.publicHandle = null;
        family.active = true;
        family.name = source.name();
        family.highlightsJson = "[]";
        family.tagsJson = "[]";
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        family.productPosition = nextFamilyPosition(category == null ? null : category.id);
        family.createdAt = Instant.now();
        family.updatedAt = family.createdAt;
        if (category != null) {
            family.categoryId = category.id;
            family.categoryKey = CategoryPublicKey.from(category.code);
            family.categoryName = category.name;
            family.categoryPosition = category.position;
        }
        return family;
    }

    /** Resolves legacy key-only families before ProductService synchronizes the member category. */
    private Long canonicalExistingCategory(
            ProductFamilyEntity family, Long candidateCategoryId) {
        if (family.categoryId != null) return family.categoryId;
        String familyCategoryKey = optional(family.categoryKey);
        if (familyCategoryKey != null) {
            CategoryEntity resolved = categories.listAll().stream()
                    .filter(item -> item.code != null
                            && familyCategoryKey.equals(CategoryPublicKey.from(item.code)))
                    .findFirst().orElse(null);
            if (resolved == null) {
                if (candidateCategoryId != null) {
                    throw new BusinessRuleException(
                            "Het bestaande model heeft een onbekende categorie; herstel die eerst");
                }
                return null;
            }
            CategoryEntity category = lockCategory(resolved.id);
            family.categoryId = category.id;
            family.categoryKey = CategoryPublicKey.from(category.code);
            family.categoryName = category.name;
            family.categoryPosition = category.position;
            familyCollections.alignPrimary(family);
            familyMembers.sync(family);
            family.updatedAt = Instant.now();
            families.flush();
            return category.id;
        }
        if (candidateCategoryId != null) {
            throw new BusinessRuleException(
                    "Het bestaande model heeft geen categorie; stel die eerst in");
        }
        return null;
    }

    private void requireNoActiveOptionCollision(long familyId, Product candidate) {
        if (!candidate.active()) return;
        Product collision = products.findByFamily(familyId).stream()
                .filter(Product::active)
                .filter(member -> !Objects.equals(member.id(), candidate.id()))
                .filter(member -> FamilyVariantRules.sameOption(member, candidate))
                .findFirst().orElse(null);
        if (collision != null) {
            throw new BusinessRuleException(
                    FamilyVariantRules.OPTION_ISSUE + " (botsing met SKU " + collision.sku() + ")");
        }
    }

    private static void requireVariantOption(Product product) {
        if (optional(product.colour()) == null && optional(product.variantSize()) == null) {
            String identity = optional(product.sku()) == null
                    ? String.valueOf(product.id()) : product.sku();
            throw new BusinessRuleException(
                    "Product " + identity + " heeft geen kleur of maat en kan niet als variant worden gekoppeld");
        }
    }

    private static Long compatibleNewFamilyCategory(Long sourceCategoryId, Long variantCategoryId) {
        requireCompatibleCategory(sourceCategoryId, variantCategoryId);
        return sourceCategoryId != null ? sourceCategoryId : variantCategoryId;
    }

    private static void requireCompatibleCategory(Long canonicalCategoryId, Long candidateCategoryId) {
        if (canonicalCategoryId != null && candidateCategoryId != null
                && !Objects.equals(canonicalCategoryId, candidateCategoryId)) {
            throw new BusinessRuleException(
                    "Producten uit verschillende categorieën kunnen niet als varianten worden gekoppeld");
        }
    }

    private int nextFamilyPosition(Long categoryId) {
        if (categoryId == null) return 0;
        return families.list("categoryId", categoryId).stream()
                .mapToInt(family -> family.productPosition).max().orElse(-1) + 1;
    }

    /** Serializes max+1 position allocation and first collection alignment per category. */
    private CategoryEntity lockCategory(Long categoryId) {
        if (categoryId == null) return null;
        CategoryEntity category = categories.findById(categoryId, LockModeType.PESSIMISTIC_WRITE);
        if (category == null) throw new BusinessRuleException("Onbekende categorie " + categoryId);
        /* The category may already be managed because legacy-key resolution listed it first.
           Refresh only after taking its row lock so copied family metadata cannot be stale. */
        categories.getEntityManager().refresh(category, LockModeType.PESSIMISTIC_WRITE);
        return category;
    }

    private String nextFamilyKey(long sourceProductId, long variantProductId) {
        long first = Math.min(sourceProductId, variantProductId);
        long second = Math.max(sourceProductId, variantProductId);
        String base = "model-" + first + "-" + second;
        String candidate = base;
        for (int suffix = 2; families.count("familyKey", candidate) > 0; suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    private Product product(long id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
    }

    private ProductFamilyEntity family(long id) {
        ProductFamilyEntity family = families.findById(id);
        if (family == null) throw new NotFoundException("Model", id);
        return family;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
