package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.CategoryText;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;

/** Manages the fixed list of product categories. */
@ApplicationScoped
public class CategoryService {

    private static final int MAX_SHORT = 255;
    private static final int MAX_DESCRIPTION = 4_000;

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final FeaturedProductSelectionService featuredProducts;
    private final CanonicalCatalogDaos.Families familyRows;
    private final ProductFamilyWriteGuard familyWrites;

    @Inject
    WebsiteRebuildService websiteRebuild;

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
        Category candidate = normalized(category);
        validateIdentityAndPosition(candidate, null);
        Category created = categories.save(new Category(
                null, candidate.code(), candidate.name(), candidate.description(),
                candidate.eyebrow(), candidate.position(), candidate.mobileName(),
                candidate.navigationName(), candidate.footerName(), null,
                candidate.texts()));
        if (candidate.featuredProductId() == null) {
            queueWebsite();
            return created;
        }
        lockFeaturedProduct(candidate.featuredProductId(), List.of());
        featuredProducts.requireCategoryMember(
                created.id(), created.code(), candidate.featuredProductId());
        Category saved = categories.save(new Category(
                created.id(), created.code(), created.name(), created.description(),
                created.eyebrow(), created.position(), created.mobileName(),
                created.navigationName(), created.footerName(), candidate.featuredProductId(),
                created.texts()));
        queueWebsite();
        return saved;
    }

    @Transactional
    public Category update(long id, Category changes) {
        if (changes == null) throw new BusinessRuleException("Geen categorie meegestuurd");
        if (changes.revision() == null) {
            throw new BusinessRuleException("Categorie-revision is verplicht bij bewaren");
        }
        Category observed = categories.findById(id)
                .orElseThrow(() -> new NotFoundException("Categorie", id));
        if (!Objects.equals(changes.revision(), observed.revision())) {
            throw new BusinessRuleException(
                    "Categorie is intussen gewijzigd; herlaad voor je opnieuw bewaart");
        }
        Category candidate = normalized(merge(observed, changes));
        List<Long> linkedFamilyIds = linkedFamilyIds(id);
        /* Global lock order: family -> selected product -> category. Product writes follow the
           same order before clearing invalid category feature references. */
        lockFeaturedProduct(candidate.featuredProductId(), linkedFamilyIds);
        Category current = categories.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Categorie", id));
        if (!Objects.equals(changes.revision(), current.revision())
                || !new LinkedHashSet<>(linkedFamilyIds)
                        .equals(new LinkedHashSet<>(linkedFamilyIds(id)))) {
            throw new BusinessRuleException(
                    "Categorie of productfamiliekoppeling is intussen gewijzigd; herlaad voor je bewaart");
        }
        Category updated = normalized(merge(current, changes));
        validateIdentityAndPosition(updated, id);
        if (updated.featuredProductId() != null) {
            featuredProducts.requireCategoryMember(
                    updated.id(), updated.code(), updated.featuredProductId());
        }
        if (updated.equals(current)) return current;
        Category saved = categories.save(updated);
        familyWrites.validateFamilies(linkedFamilyIds);
        queueWebsite();
        return saved;
    }

    private static Category merge(Category current, Category changes) {
        return new Category(
                current.id(),
                changes.code() == null ? current.code() : changes.code(),
                changes.name() == null ? current.name() : changes.name(),
                changes.description(), changes.eyebrow(), changes.position(),
                changes.mobileName(), changes.navigationName(), changes.footerName(),
                changes.featuredProductId(),
                changes.texts().isEmpty() ? current.texts() : changes.texts(),
                current.revision());
    }

    private List<Long> linkedFamilyIds(long categoryId) {
        return familyRows.list("categoryId", categoryId).stream()
                .map(family -> family.id).filter(Objects::nonNull).sorted().toList();
    }

    /* Called before the caller acquires the category row: family -> product -> category. */
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

    private static Category normalized(Category category) {
        if (category == null) throw new BusinessRuleException("Geen categorie meegestuurd");
        String code = required(category.code(), MAX_SHORT, "Categoriecode");
        String name = required(category.name(), MAX_SHORT, "Categorienaam");
        String description = optional(category.description(), MAX_DESCRIPTION,
                "Categoriebeschrijving");
        String eyebrow = optional(category.eyebrow(), MAX_SHORT, "Categorie-eyebrow");
        String mobileName = optional(category.mobileName(), MAX_SHORT, "Mobiele categorienaam");
        String navigationName = optional(
                category.navigationName(), MAX_SHORT, "Navigatiecategorienaam");
        String footerName = optional(category.footerName(), MAX_SHORT, "Footercategorienaam");
        java.util.EnumSet<be.enrosed.shared.Language> seen =
                java.util.EnumSet.noneOf(be.enrosed.shared.Language.class);
        java.util.ArrayList<CategoryText> texts = new java.util.ArrayList<>();
        for (CategoryText text : category.texts()) {
            if (text == null || text.language() == null || !seen.add(text.language())) {
                throw new BusinessRuleException("Elke categorietaal mag exact één keer voorkomen");
            }
            CategoryText value = new CategoryText(
                    text.language(),
                    optional(text.name(), MAX_SHORT,
                            "Categorienaam " + text.language().code()),
                    optional(text.description(), MAX_DESCRIPTION,
                            "Categoriebeschrijving " + text.language().code()),
                    optional(text.eyebrow(), MAX_SHORT,
                            "Categorie-eyebrow " + text.language().code()),
                    optional(text.mobileName(), MAX_SHORT,
                            "Mobiele categorienaam " + text.language().code()),
                    optional(text.navigationName(), MAX_SHORT,
                            "Navigatiecategorienaam " + text.language().code()),
                    optional(text.footerName(), MAX_SHORT,
                            "Footercategorienaam " + text.language().code()));
            if (!value.isEmpty()) texts.add(value);
        }
        return new Category(category.id(), code, name, description, eyebrow,
                category.position(), mobileName, navigationName, footerName,
                category.featuredProductId(), List.copyOf(texts), category.revision());
    }

    private static String required(String value, int max, String field) {
        String result = optional(value, max, field);
        if (result == null) throw new BusinessRuleException(field + " is verplicht");
        return result;
    }

    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > max) {
            throw new BusinessRuleException(field + " is langer dan " + max + " tekens");
        }
        return result;
    }

    @Transactional
    public void delete(long id) {
        Category category = categories.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Categorie", id));
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
        queueWebsite();
    }

    private static boolean matchesPublicKey(String candidate, String publicKey) {
        return candidate != null && !candidate.isBlank()
                && publicKey.equals(CategoryPublicKey.from(candidate));
    }

    private void queueWebsite() {
        if (websiteRebuild != null) websiteRebuild.queue();
    }
}
