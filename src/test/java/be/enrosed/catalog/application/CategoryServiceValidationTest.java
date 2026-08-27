package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.CategoryText;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.adapter.in.rest.BusinessRuleMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryServiceValidationTest {

    @Test
    void acceptsActualDatabaseColumnBoundariesAndNormalizesBeforeSaving() {
        Fixture fixture = fixture();
        String shortValue = "x".repeat(255);
        String description = "d".repeat(4_000);
        CategoryText english = new CategoryText(
                Language.EN, shortValue, description, shortValue,
                shortValue, shortValue, shortValue);

        Category saved = fixture.service().create(new Category(
                null, "  category-code  ", shortValue, description, shortValue,
                0, shortValue, shortValue, shortValue, null, List.of(english)));

        assertEquals("category-code", saved.code());
        assertEquals(255, saved.name().length());
        assertEquals(4_000, saved.description().length());
        assertEquals(4_000, saved.texts().getFirst().description().length());
        verify(fixture.categories()).save(any(Category.class));
    }

    @Test
    void rejectsShortFieldsAt256BeforePersistence() {
        Fixture fixture = fixture();
        Category invalidBase = category("x".repeat(256), "Description", List.of());
        assertThrows(BusinessRuleException.class, () -> fixture.service().create(invalidBase));

        Category invalidText = category("Valid", "Description", List.of(new CategoryText(
                Language.EN, "x".repeat(256), null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> fixture.service().create(invalidText));
        verify(fixture.categories(), never()).save(any(Category.class));
    }

    @Test
    void rejectsDescriptionsAt4001BeforePersistence() {
        Fixture fixture = fixture();
        Category invalidBase = category("Valid", "d".repeat(4_001), List.of());
        assertThrows(BusinessRuleException.class, () -> fixture.service().create(invalidBase));

        Category invalidText = category("Valid", "Description", List.of(new CategoryText(
                Language.EN, "English", "d".repeat(4_001), null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> fixture.service().create(invalidText));
        verify(fixture.categories(), never()).save(any(Category.class));
    }

    @Test
    void updateRequiresAnExplicitRevisionEvenForRevisionZeroRows() {
        Fixture fixture = fixture();
        Category current = new Category(
                17L, "category-code", "Existing", "Existing description",
                null, 0, null, null, null, null, List.of(), 0L);
        when(fixture.categories().findByIdForUpdate(17L))
                .thenReturn(java.util.Optional.of(current));
        Category missingRevision = new Category(
                17L, "category-code", "Overwritten", "Overwritten description",
                null, 0, null, null, null, null, List.of(), null);

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> fixture.service().update(17L, missingRevision));

        assertTrue(error.getMessage().contains("revision"));
        verify(fixture.categories(), never()).save(any(Category.class));
        assertEquals("Existing", current.name());
    }

    @Test
    void updateLocksFamiliesBeforeTheCategoryRow() {
        Fixture fixture = fixture();
        Category current = new Category(
                17L, "category-code", "Existing", "Existing description",
                null, 0, null, null, null, null, List.of(), 3L);
        when(fixture.categories().findById(17L)).thenReturn(java.util.Optional.of(current));
        when(fixture.categories().findByIdForUpdate(17L))
                .thenReturn(java.util.Optional.of(current));
        ProductFamilyEntity family = new ProductFamilyEntity();
        family.id = 44L;
        when(fixture.families().list("categoryId", 17L)).thenReturn(List.of(family));

        fixture.service().update(17L, new Category(
                17L, current.code(), "Updated", current.description(),
                null, 0, null, null, null, null, List.of(), current.revision()));

        org.mockito.InOrder order = inOrder(fixture.guard(), fixture.categories());
        order.verify(fixture.guard()).lockFamilies(anyCollection());
        order.verify(fixture.categories()).findByIdForUpdate(17L);
    }

    @Test
    void existingCategoryCodeIsImmutableWith422WhileTheVisibleNameRemainsEditable() {
        Fixture fixture = fixture();
        Category current = new Category(
                17L, "category-code", "Existing", "Existing description",
                null, 0, null, null, null, null, List.of(), 3L);
        when(fixture.categories().findById(17L)).thenReturn(java.util.Optional.of(current));
        when(fixture.categories().findByIdForUpdate(17L))
                .thenReturn(java.util.Optional.of(current));

        Category renamed = fixture.service().update(17L, new Category(
                17L, current.code(), "Visible customer title", current.description(),
                null, 0, null, null, null, null, List.of(), current.revision()));
        assertEquals("Visible customer title", renamed.name());
        assertEquals("category-code", renamed.code());

        UnprocessableBusinessRuleException blocked = assertThrows(
                UnprocessableBusinessRuleException.class,
                () -> fixture.service().update(17L, new Category(
                        17L, "renamed-technical-code", "Another visible title",
                        current.description(), null, 0, null, null, null, null,
                        List.of(), current.revision())));
        assertTrue(blocked.getMessage().contains("vaste technische sleutel"));
        assertEquals(422, new BusinessRuleMapper().toResponse(blocked).getStatus());
    }

    private static Category category(
            String name, String description, List<CategoryText> texts) {
        return new Category(null, "category-code", name, description,
                null, 0, null, null, null, null, texts);
    }

    private static Fixture fixture() {
        CategoryRepository categories = mock(CategoryRepository.class);
        when(categories.findAll()).thenReturn(List.of());
        when(categories.save(any(Category.class))).thenAnswer(call -> call.getArgument(0));
        CanonicalCatalogDaos.Families families = mock(CanonicalCatalogDaos.Families.class);
        ProductFamilyWriteGuard guard = mock(ProductFamilyWriteGuard.class);
        CategoryService service = new CategoryService(
                categories, mock(ProductRepository.class),
                mock(FeaturedProductSelectionService.class),
                families, guard);
        return new Fixture(service, categories, families, guard);
    }

    private record Fixture(
            CategoryService service,
            CategoryRepository categories,
            CanonicalCatalogDaos.Families families,
            ProductFamilyWriteGuard guard) {}
}
