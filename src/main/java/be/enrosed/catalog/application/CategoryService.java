package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;

/** Manages the fixed list of product categories. */
@ApplicationScoped
public class CategoryService {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final FeaturedProductSelectionService featuredProducts;
    private final CanonicalCatalogDaos.Families familyRows;
    private final ProductFamilyWriteGuard familyWrites;

    public CategoryService(
            CategoryRepository categories,
            ProductRepository products,
            FeaturedProductSelectionService featuredProducts,
            CanonicalCatalogDaos.Families familyRows,
            ProductFamilyWriteGuard familyWrites) {
        this.categories = categories;
        this.products = products;
        this.featuredProducts = featuredProducts;
        this.familyRows = familyRows;
        this.familyWrites = familyWrites;
    }

    public List<Category> list() {
        return categories.findAll().stream()
                .sorted(Comparator.comparingInt(Category::position).thenComparing(Category::name))
                .toList();
    }

    public Category get(long id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Categorie", id));
    }

    @Transactional
    public Category create(Category category) {
        validateIdentityAndPosition(category, null);
        Category created = categories.save(new Category(
                null, category.code(), category.name(), category.description(), category.eyebrow(),
                category.position(), category.mobileName(), null));
        if (category.featuredProductId() == null) return created;
        lockFeaturedProduct(category.featuredProductId(), List.of());
        featuredProducts.requireCategoryMember(
                created.id(), created.code(), category.featuredProductId());
        return categories.save(new Category(
                created.id(), created.code(), created.name(), created.description(),
                created.eyebrow(), created.position(), created.mobileName(),
                category.featuredProductId()));
    }

    @Transactional
    public Category update(long id, Category changes) {
        Category current = get(id);
        Category updated = new Category(
                current.id(),
                changes.code() == null ? current.code() : changes.code(),
                changes.name() == null ? current.name() : changes.name(),
                changes.description(),
                changes.eyebrow(),
                changes.position(),
                changes.mobileName(),
                changes.featuredProductId());
        List<Long> linkedFamilyIds = familyRows.list("categoryId", id).stream()
                .map(family -> family.id).toList();
        lockFeaturedProduct(updated.featuredProductId(), linkedFamilyIds);
        validateIdentityAndPosition(updated, id);
        if (updated.featuredProductId() != null) {
            featuredProducts.requireCategoryMember(
                    updated.id(), updated.code(), updated.featuredProductId());
        }
        Category saved = categories.save(updated);
        familyWrites.validateFamilies(linkedFamilyIds);
        return saved;
    }

    /* Lock order is family -> product -> category persistence, identical to ProductService.
       Taking category locks first here would deadlock with product inactivation clearing a
       category feature while holding the same family/product locks. */
    private void lockFeaturedProduct(Long productId, List<Long> linkedFamilyIds) {
        LinkedHashSet<Long> familyIds = new LinkedHashSet<>(linkedFamilyIds);
        Product observed = productId == null ? null : products.findById(productId).orElse(null);
        if (observed != null && observed.familyId() != null) familyIds.add(observed.familyId());
        familyWrites.lockFamilies(familyIds);
        if (observed != null) {
            Long lockedFamilyId = familyWrites.lockProduct(observed.id());
            if (!Objects.equals(lockedFamilyId, observed.familyId())) {
                throw new BusinessRuleException(
                        "Uitgelicht product is gelijktijdig naar een andere familie verplaatst; "
                                + "laad de categorie opnieuw");
            }
        }
    }

    private void validateIdentityAndPosition(Category candidate, Long currentId) {
        if (candidate == null) throw new BusinessRuleException("Geen categorie meegestuurd");
        if (candidate.position() < 0) {
            throw new BusinessRuleException("Categoriepositie mag niet negatief zijn");
        }
        String publicKey = CategoryPublicKey.from(candidate.code());
        for (Category existing : categories.findAll()) {
            if (Objects.equals(existing.id(), currentId)) continue;
            if (existing.code().equalsIgnoreCase(candidate.code())) {
                throw new BusinessRuleException(
                        "Categoriecode " + candidate.code() + " bestaat al");
            }
            if (CategoryPublicKey.from(existing.code()).equals(publicKey)) {
                throw new BusinessRuleException(
                        "Publieke categoriecode " + publicKey + " bestaat al");
            }
            if (existing.position() == candidate.position()) {
                throw new BusinessRuleException(
                        "Categoriepositie " + candidate.position() + " is al in gebruik");
            }
        }
    }

    @Transactional
    public void delete(long id) {
        Category category = get(id);
        long inUse = products.countByCategory(category.id());
        if (inUse > 0) {
            throw new BusinessRuleException(
                    "Categorie " + category.name() + " staat nog op " + inUse + " product(en)");
        }
        String publicKey = CategoryPublicKey.from(category.code());
        long familyUse = familyRows.listAll().stream().filter(family ->
                Objects.equals(family.categoryId, category.id())
                        || matchesPublicKey(family.categoryKey, publicKey)
                        || family.collections.stream().anyMatch(membership ->
                                membership.collection != null
                                        && matchesPublicKey(
                                                membership.collection.collectionKey, publicKey)))
                .map(family -> family.id).filter(Objects::nonNull).distinct().count();
        if (familyUse > 0) {
            throw new BusinessRuleException(
                    "Categorie " + category.name() + " staat nog op " + familyUse
                            + " productfamilie(ën)");
        }
        categories.deleteById(id);
    }

    private static boolean matchesPublicKey(String candidate, String publicKey) {
        return candidate != null && !candidate.isBlank()
                && publicKey.equals(CategoryPublicKey.from(candidate));
    }
}
