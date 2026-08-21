package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDtoFactory;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogDto;
import be.enrosed.catalog.adapter.in.rest.PublicFamilyCatalogResource;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductCollectionEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProductVariantLinkPersistenceTest {
    @Inject EntityManager entityManager;
    @Inject ProductVariantLinkService links;
    @Inject ProductFamilyDtoFactory familyDtos;
    @Inject PublicFamilyCatalogResource publicFamilies;

    @Test
    @TestTransaction
    void twoStandaloneProductsCreateOnePrivateModelGroupWithAFullFamilyResponse() {
        CategoryEntity category = category("LINK CREATE");
        entityManager.persist(category);
        entityManager.flush();
        ProductEntity source = standalone(
                category.id, "SKU-LINK-CREATE-A", "Exact model title", "Red", null, "#A91F32");
        ProductEntity variant = standalone(
                null, "SKU-LINK-CREATE-B", "Other internal SKU name", null, "Large", null);
        entityManager.persist(source);
        entityManager.persist(variant);
        entityManager.flush();

        ProductVariantLinkService.Result result = links.link(source.id, variant.id);
        ProductFamilyDto dto = familyDtos.from(result.family());

        assertTrue(result.familyCreated());
        assertEquals("Exact model title", dto.name());
        assertEquals("model-" + source.id + "-" + variant.id, dto.familyKey());
        assertNull(dto.publicHandle());
        assertTrue(dto.active());
        assertEquals(PublicationState.DRAFT, dto.websiteStatus());
        assertEquals(PublicationState.DRAFT, dto.orderAppStatus());
        assertEquals(PublicationState.DRAFT, dto.catalogueStatus());
        assertEquals(category.id, dto.categoryId());
        assertEquals(CategoryPublicKey.from(category.code), dto.categoryKey());
        assertEquals(1, dto.collections().size());
        assertTrue(dto.collections().getFirst().primary());
        assertEquals(2, dto.variantCount());
        assertEquals(List.of(source.id, variant.id),
                dto.members().stream().map(ProductFamilyDto.MemberDto::productId).toList());
        assertEquals(List.of(0, 1),
                dto.members().stream().map(ProductFamilyDto.MemberDto::position).toList());

        ProductEntity linkedSource = entityManager.find(ProductEntity.class, source.id);
        ProductEntity linkedVariant = entityManager.find(ProductEntity.class, variant.id);
        assertEquals(result.family().id, linkedSource.familyId);
        assertEquals(result.family().id, linkedVariant.familyId);
        assertEquals(category.id, linkedSource.categoryId);
        assertEquals(category.id, linkedVariant.categoryId);
        assertEquals(PublicationState.DRAFT, linkedSource.websiteStatus);
        assertEquals(PublicationState.DRAFT, linkedVariant.websiteStatus);

        Response publicResponse = publicFamilies.catalog(CatalogChannel.WEBSITE, "EN", null);
        PublicFamilyCatalogDto publicCatalog = (PublicFamilyCatalogDto) publicResponse.getEntity();
        assertTrue(publicCatalog.families().stream()
                .noneMatch(item -> Objects.equals(item.id(), result.family().id)),
                "a server-created DRAFT model group must stay out of the public website contract");
    }

    @Test
    @TestTransaction
    void addsTheStandaloneProductToTheExistingFamilyAndThenBecomesIdempotent() {
        CategoryEntity category = category("LINK EXISTING");
        entityManager.persist(category);
        entityManager.flush();
        ProductFamilyEntity family = draftFamily("link-existing-family", category);
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity inactiveTail = member(
                family, "SKU-LINK-INACTIVE", "White", null, "#EEE8DD", 7, false);
        ProductEntity anchor = member(
                family, "SKU-LINK-ANCHOR", "Blue", null, "#6C8FC4", 2, true);
        ProductEntity standalone = standalone(
                category.id, "SKU-LINK-STANDALONE", "Existing model", "Red", null, "#A91F32");
        entityManager.persist(inactiveTail);
        entityManager.persist(anchor);
        entityManager.persist(standalone);
        entityManager.flush();

        ProductVariantLinkService.Result added = links.link(standalone.id, anchor.id);
        ProductEntity linked = entityManager.find(ProductEntity.class, standalone.id);
        int linkedPosition = linked.variantPosition;
        Instant familyTimestamp = family.updatedAt;
        ProductVariantLinkService.Result repeated = links.link(anchor.id, standalone.id);

        assertFalse(added.familyCreated());
        assertFalse(repeated.familyCreated());
        assertEquals(family.id, added.family().id);
        assertEquals(family.id, linked.familyId);
        assertEquals(8, linkedPosition,
                "new members append after active and inactive family members");
        assertEquals(linkedPosition,
                entityManager.find(ProductEntity.class, standalone.id).variantPosition);
        assertEquals(familyTimestamp, family.updatedAt,
                "the same-family no-op must not edit family metadata");
    }

    @Test
    @TestTransaction
    void sameFamilyIsANoOpEvenForLegacyIncompleteOrDuplicateOptions() {
        CategoryEntity category = category("LINK LEGACY NOOP");
        entityManager.persist(category);
        entityManager.flush();
        ProductFamilyEntity family = draftFamily("link-legacy-noop-family", category);
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity first = member(
                family, "SKU-LINK-LEGACY-NOOP-A", null, null, null, 0, true);
        ProductEntity second = member(
                family, "SKU-LINK-LEGACY-NOOP-B", null, null, null, 1, true);
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        Instant familyTimestamp = family.updatedAt;

        ProductVariantLinkService.Result result = links.link(first.id, second.id);

        assertFalse(result.familyCreated());
        assertEquals(family.id, result.family().id);
        assertEquals(familyTimestamp, result.family().updatedAt);
        assertEquals(List.of(0, 1), List.of(
                entityManager.find(ProductEntity.class, first.id).variantPosition,
                entityManager.find(ProductEntity.class, second.id).variantPosition));
    }

    @Test
    @TestTransaction
    void rejectsProductsThatAlreadyBelongToDifferentFamilies() {
        CategoryEntity category = category("LINK TWO FAMILIES");
        entityManager.persist(category);
        entityManager.flush();
        ProductFamilyEntity first = draftFamily("link-family-one", category);
        ProductFamilyEntity second = draftFamily("link-family-two", category);
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        ProductEntity red = member(first, "SKU-LINK-FAMILY-ONE", "Red", null, "#A91F32", 0, true);
        ProductEntity blue = member(second, "SKU-LINK-FAMILY-TWO", "Blue", null, "#6C8FC4", 0, true);
        entityManager.persist(red);
        entityManager.persist(blue);
        entityManager.flush();

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(red.id, blue.id));

        assertTrue(error.getMessage().contains("niet automatisch samengevoegd"), error.getMessage());
        assertEquals(first.id, entityManager.find(ProductEntity.class, red.id).familyId);
        assertEquals(second.id, entityManager.find(ProductEntity.class, blue.id).familyId);
    }

    @Test
    void rejectsLinkingAProductToItself() {
        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(91L, 91L));

        assertTrue(error.getMessage().contains("zichzelf"), error.getMessage());
    }

    @Test
    @TestTransaction
    void requiresAnExplicitOptionOnEachProduct() {
        ProductEntity source = standalone(
                null, "SKU-LINK-NO-OPTION", "Optionless", null, null, null);
        ProductEntity variant = standalone(
                null, "SKU-LINK-HAS-OPTION", "Optioned", "Blue", null, "#6C8FC4");
        entityManager.persist(source);
        entityManager.persist(variant);
        entityManager.flush();
        long familyCount = countFamilies();

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(source.id, variant.id));

        assertTrue(error.getMessage().contains("geen kleur of maat"), error.getMessage());
        assertEquals(familyCount, countFamilies());
        assertNull(entityManager.find(ProductEntity.class, source.id).familyId);
        assertNull(entityManager.find(ProductEntity.class, variant.id).familyId);
    }

    @Test
    @TestTransaction
    void rejectsTheSameNormalizedColourAndSizeTuple() {
        ProductEntity source = standalone(
                null, "SKU-LINK-SAME-A", "Same model", " Cherry   Pink ", " Large ", "#D9577E");
        ProductEntity variant = standalone(
                null, "SKU-LINK-SAME-B", "Same model", "cherry pink", "large", "#D9577E");
        entityManager.persist(source);
        entityManager.persist(variant);
        entityManager.flush();

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(source.id, variant.id));

        assertTrue(error.getMessage().contains("dezelfde combinatie"), error.getMessage());
        assertNull(entityManager.find(ProductEntity.class, source.id).familyId);
        assertNull(entityManager.find(ProductEntity.class, variant.id).familyId);
    }

    @Test
    @TestTransaction
    void rejectsAnActiveCollisionWithAnotherExistingFamilyMember() {
        CategoryEntity category = category("LINK COLLISION");
        entityManager.persist(category);
        entityManager.flush();
        ProductFamilyEntity family = draftFamily("link-collision-family", category);
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity red = member(
                family, "SKU-LINK-COLLISION-RED", "Red", "Small", "#A91F32", 0, true);
        ProductEntity blue = member(
                family, "SKU-LINK-COLLISION-BLUE", "Blue", "Small", "#6C8FC4", 1, true);
        ProductEntity candidate = standalone(
                category.id, "SKU-LINK-COLLISION-CANDIDATE", "Model", " red ", " small ", "#A91F32");
        entityManager.persist(red);
        entityManager.persist(blue);
        entityManager.persist(candidate);
        entityManager.flush();

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(blue.id, candidate.id));

        assertTrue(error.getMessage().contains(FamilyVariantRules.OPTION_ISSUE), error.getMessage());
        assertNull(entityManager.find(ProductEntity.class, candidate.id).familyId);
    }

    @Test
    @TestTransaction
    void rejectsDifferentKnownCategoriesButUsesTheKnownOneWhenTheOtherIsNull() {
        CategoryEntity first = category("LINK CATEGORY A");
        CategoryEntity second = category("LINK CATEGORY B");
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        ProductEntity red = standalone(
                first.id, "SKU-LINK-CATEGORY-A", "Category model", "Red", null, "#A91F32");
        ProductEntity blue = standalone(
                second.id, "SKU-LINK-CATEGORY-B", "Category model", "Blue", null, "#6C8FC4");
        entityManager.persist(red);
        entityManager.persist(blue);
        entityManager.flush();

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(red.id, blue.id));

        assertTrue(error.getMessage().contains("verschillende categorieën"), error.getMessage());
        assertNull(entityManager.find(ProductEntity.class, red.id).familyId);
        assertNull(entityManager.find(ProductEntity.class, blue.id).familyId);
    }

    @Test
    @TestTransaction
    void rejectsAStandaloneProductFromAnotherKnownCategoryForAnExistingFamily() {
        CategoryEntity first = category("LINK EXISTING CATEGORY A");
        CategoryEntity second = category("LINK EXISTING CATEGORY B");
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        ProductFamilyEntity family = draftFamily("link-existing-category-family", first);
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity blue = member(
                family, "SKU-LINK-EXISTING-CATEGORY", "Blue", null, "#6C8FC4", 0, true);
        ProductEntity red = standalone(
                second.id, "SKU-LINK-OTHER-CATEGORY", "Category model", "Red", null, "#A91F32");
        entityManager.persist(blue);
        entityManager.persist(red);
        entityManager.flush();

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class, () -> links.link(blue.id, red.id));

        assertTrue(error.getMessage().contains("verschillende categorieën"), error.getMessage());
        assertNull(entityManager.find(ProductEntity.class, red.id).familyId);
    }

    @Test
    @TestTransaction
    void resolvesALegacyKeyOnlyFamilyBeforeSynchronizingTheStandaloneCategory() {
        CategoryEntity category = category("LINK LEGACY CATEGORY");
        entityManager.persist(category);
        entityManager.flush();
        ProductFamilyEntity family = draftFamily("link-legacy-category-family", category);
        family.categoryId = null;
        entityManager.persist(family);
        entityManager.flush();
        ProductEntity blue = member(
                family, "SKU-LINK-LEGACY-CATEGORY", "Blue", null, "#6C8FC4", 0, true);
        ProductEntity red = standalone(
                category.id, "SKU-LINK-LEGACY-STANDALONE", "Legacy category model",
                "Red", null, "#A91F32");
        entityManager.persist(blue);
        entityManager.persist(red);
        entityManager.flush();

        ProductVariantLinkService.Result result = links.link(blue.id, red.id);

        assertEquals(category.id, result.family().categoryId);
        assertEquals(CategoryPublicKey.from(category.code), result.family().categoryKey);
        assertEquals(category.id, entityManager.find(ProductEntity.class, blue.id).categoryId,
                "legacy members must receive the newly resolved canonical family category");
        assertEquals(category.id, entityManager.find(ProductEntity.class, red.id).categoryId);
    }

    @Test
    void aFailureOnTheSecondAssignmentRollsBackTheFamilyAndFirstAssignment() {
        RollbackSetup setup = QuarkusTransaction.requiringNew().call(() -> {
            CategoryEntity category = category("LINK ATOMIC ROLLBACK");
            entityManager.persist(category);
            entityManager.flush();
            ProductEntity source = standalone(
                    category.id, "SKU-LINK-ROLLBACK-A", "Rollback model", "Red", null, "#A91F32");
            ProductEntity invalidVariant = standalone(
                    category.id, "SKU-LINK-ROLLBACK-B", "Rollback model", "Blue", null, "#bad");
            entityManager.persist(source);
            entityManager.persist(invalidVariant);
            entityManager.flush();
            String familyKey = "model-" + Math.min(source.id, invalidVariant.id)
                    + "-" + Math.max(source.id, invalidVariant.id);
            return new RollbackSetup(category.id, source.id, invalidVariant.id,
                    familyKey, CategoryPublicKey.from(category.code));
        });

        try {
            BusinessRuleException error = assertThrows(BusinessRuleException.class,
                    () -> QuarkusTransaction.requiringNew().run(
                            () -> links.link(setup.sourceId(), setup.variantId())));
            assertTrue(error.getMessage().contains("#RRGGBB"), error.getMessage());

            QuarkusTransaction.requiringNew().run(() -> {
                assertNull(entityManager.find(ProductEntity.class, setup.sourceId()).familyId);
                assertNull(entityManager.find(ProductEntity.class, setup.variantId()).familyId);
                assertEquals(0L, entityManager.createQuery(
                                "select count(item) from ProductFamilyEntity item where item.familyKey = :key",
                                Long.class)
                        .setParameter("key", setup.familyKey()).getSingleResult());
            });
        } finally {
            QuarkusTransaction.requiringNew().run(() -> cleanup(setup));
        }
    }

    private long countFamilies() {
        return entityManager.createQuery("select count(item) from ProductFamilyEntity item", Long.class)
                .getSingleResult();
    }

    private void cleanup(RollbackSetup setup) {
        ProductEntity source = entityManager.find(ProductEntity.class, setup.sourceId());
        ProductEntity variant = entityManager.find(ProductEntity.class, setup.variantId());
        if (source != null) entityManager.remove(source);
        if (variant != null) entityManager.remove(variant);
        entityManager.createQuery(
                        "from ProductFamilyEntity item where item.familyKey = :key",
                        ProductFamilyEntity.class)
                .setParameter("key", setup.familyKey()).getResultList()
                .forEach(entityManager::remove);
        entityManager.flush();
        entityManager.createQuery(
                        "from ProductCollectionEntity item where item.collectionKey = :key",
                        ProductCollectionEntity.class)
                .setParameter("key", setup.collectionKey()).getResultList()
                .forEach(entityManager::remove);
        CategoryEntity category = entityManager.find(CategoryEntity.class, setup.categoryId());
        if (category != null) entityManager.remove(category);
    }

    private record RollbackSetup(
            long categoryId, long sourceId, long variantId,
            String familyKey, String collectionKey) {}

    private static CategoryEntity category(String code) {
        CategoryEntity category = new CategoryEntity();
        category.code = code;
        category.name = code + " name";
        category.description = "Category description";
        category.eyebrow = "Category eyebrow";
        category.position = Math.abs(code.hashCode() % 10_000) + 100;
        return category;
    }

    private static ProductFamilyEntity draftFamily(String key, CategoryEntity category) {
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.familyKey = key;
        family.active = true;
        family.name = "Existing model family";
        family.categoryId = category.id;
        family.categoryKey = CategoryPublicKey.from(category.code);
        family.categoryName = category.name;
        family.categoryPosition = category.position;
        family.websiteStatus = PublicationState.DRAFT;
        family.orderAppStatus = PublicationState.DRAFT;
        family.catalogueStatus = PublicationState.DRAFT;
        family.createdAt = Instant.now();
        family.updatedAt = family.createdAt;
        return family;
    }

    private static ProductEntity member(
            ProductFamilyEntity family, String sku, String colour, String size,
            String colourHex, int position, boolean active) {
        ProductEntity product = standalone(
                family.categoryId, sku, "Existing model", colour, size, colourHex);
        product.familyId = family.id;
        product.familyKey = family.familyKey;
        product.variantPosition = position;
        product.active = active;
        return product;
    }

    private static ProductEntity standalone(
            Long categoryId, String sku, String name,
            String colour, String size, String colourHex) {
        ProductEntity product = new ProductEntity();
        product.sku = sku;
        product.name = name;
        product.description = "Internal product description";
        product.categoryId = categoryId;
        product.colour = colour;
        product.variantSize = size;
        product.colourHex = colourHex;
        product.active = true;
        product.inventoryKnown = true;
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        product.piecesPerCarton = 1;
        product.exwCurrency = Currency.USD;
        product.fixedSalesPriceEur = BigDecimal.TEN;
        product.websiteStatus = PublicationState.DRAFT;
        product.orderAppStatus = PublicationState.DRAFT;
        return product;
    }
}
