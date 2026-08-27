package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CatalogExportServiceTest {

    @Test
    void omittedBuilderFieldsKeepLayoutDefaultsAndLeaveCopyToTheContentStore() {
        CatalogExportService.Request request = new CatalogExportService.Request(
                null, false, true, null, null, null, null);

        assertEquals(CatalogExportService.Layout.SIMPLE, request.resolvedLayout());
        assertEquals(4, request.resolvedPhotosPerProduct());
        assertTrue(request.resolvedBrochure().includeOverview());
        assertTrue(request.resolvedBrochure().includeCategoryIntros());
        assertEquals(null, request.resolvedBrochure().coverTitle());
    }

    @Test
    void jsonWireContractUsesTheDesktopBuilderFieldNames() throws Exception {
        CatalogExportService.Request request = new ObjectMapper().readValue("""
                {
                  "productIds": [3, 1],
                  "includePrices": true,
                  "includePhotos": true,
                  "layout": "BROCHURE",
                  "brochure": {
                    "includeOverview": false,
                    "includeCategoryIntros": true,
                    "includeCustomisation": false,
                    "includeOrdering": true,
                    "includeBackCover": false,
                    "coverTitle": "Trade collection",
                    "coverSubtitle": "Selected for you"
                  }
                }
                """, CatalogExportService.Request.class);

        assertEquals(CatalogExportService.Layout.BROCHURE, request.resolvedLayout());
        assertEquals(List.of(3L, 1L), request.productIds());
        assertEquals(false, request.resolvedBrochure().includeOverview());
        assertEquals(true, request.resolvedBrochure().includeCategoryIntros());
        assertEquals("Trade collection", request.resolvedBrochure().coverTitle());
        assertEquals("Selected for you", request.resolvedBrochure().coverSubtitle());
    }

    @Test
    void explicitEmptySelectionIsRejectedButNullStillMeansAll() {
        ProductService products = mock(ProductService.class);
        CategoryService categories = mock(CategoryService.class);
        CatalogFamilyReader families = mock(CatalogFamilyReader.class);
        CatalogDocumentRenderer renderer = mock(CatalogDocumentRenderer.class);
        CatalogExportService service = new CatalogExportService(
                products, categories, families, renderer);

        assertThrows(BusinessRuleException.class, () -> service.export(new CatalogExportService.Request(
                List.of(), false, false, null, null, null, "nl")));

        Product product = product(1L, "P-01", null, 1L, 0);
        when(products.list()).thenReturn(List.of(product));
        when(categories.list()).thenReturn(List.of(category()));
        when(families.findByIds(Set.of())).thenReturn(List.of());
        when(renderer.render(any())).thenReturn(document());

        service.export(new CatalogExportService.Request(
                null, false, false, null, null, null, "nl"));

        ArgumentCaptor<CatalogExportService.Model> model =
                ArgumentCaptor.forClass(CatalogExportService.Model.class);
        verify(renderer).render(model.capture());
        assertEquals(List.of(product), model.getValue().products());
    }

    @Test
    void groupsOnlySelectedVariantsAndPreservesBuilderOrder() {
        Product familyRed = product(1L, "RED", 10L, 1L, 0);
        Product standalone = product(2L, "ONE", null, 1L, 0);
        Product familyBlue = product(3L, "BLUE", 10L, 1L, 1);
        CatalogFamilyReader.Family family = family(10L);

        ProductService products = mock(ProductService.class);
        CategoryService categories = mock(CategoryService.class);
        CatalogFamilyReader families = mock(CatalogFamilyReader.class);
        CatalogDocumentRenderer renderer = mock(CatalogDocumentRenderer.class);
        when(products.list()).thenReturn(List.of(familyRed, standalone, familyBlue));
        when(categories.list()).thenReturn(List.of(category()));
        when(families.findByIds(Set.of(10L))).thenReturn(List.of(family));
        when(renderer.render(any())).thenReturn(document());

        CatalogExportService service = new CatalogExportService(
                products, categories, families, renderer);
        service.export(new CatalogExportService.Request(
                List.of(3L, 2L, 1L, 3L), false, false, null,
                null, null, "en", CatalogExportService.Layout.BROCHURE, null));

        ArgumentCaptor<CatalogExportService.Model> model =
                ArgumentCaptor.forClass(CatalogExportService.Model.class);
        verify(renderer).render(model.capture());
        assertEquals(List.of(familyBlue, standalone, familyRed), model.getValue().products());
        assertEquals(2, model.getValue().families().size());
        assertEquals(List.of(familyBlue, familyRed), model.getValue().families().getFirst().variants());
        assertEquals(family, model.getValue().families().getFirst().content());
        assertTrue(model.getValue().families().get(1).synthetic());
    }

    @Test
    void internalAssessmentProductsNeverEnterCustomerCataloguesFromSavedSelections() {
        Product accepted = product(1L, "ACCEPTED", null, 1L, 0);
        Product assessment = product(2L, "ASSESSMENT", null, 1L, 1).withDemo(true);

        ProductService products = mock(ProductService.class);
        CategoryService categories = mock(CategoryService.class);
        CatalogFamilyReader families = mock(CatalogFamilyReader.class);
        CatalogDocumentRenderer renderer = mock(CatalogDocumentRenderer.class);
        when(products.list()).thenReturn(List.of(accepted, assessment));
        when(categories.list()).thenReturn(List.of(category()));
        when(families.findByIds(Set.of())).thenReturn(List.of());
        when(renderer.render(any())).thenReturn(document());

        CatalogExportService service = new CatalogExportService(
                products, categories, families, renderer);
        service.export(new CatalogExportService.Request(
                List.of(assessment.id(), accepted.id()), false, false, null,
                null, null, "nl", CatalogExportService.Layout.BROCHURE, null));

        ArgumentCaptor<CatalogExportService.Model> model =
                ArgumentCaptor.forClass(CatalogExportService.Model.class);
        verify(renderer).render(model.capture());
        assertEquals(List.of(accepted), model.getValue().products());
        assertEquals(1, model.getValue().families().size());
        assertTrue(model.getValue().families().getFirst().synthetic());
    }

    private static CatalogDocumentRenderer.Document document() {
        return new CatalogDocumentRenderer.Document("catalog.pdf", new byte[] {1}, "application/pdf");
    }

    private static Category category() {
        return new Category(1L, "counter", "Counter Displays", "Retail ready", 0);
    }

    private static CatalogFamilyReader.Family family(long id) {
        return new CatalogFamilyReader.Family(
                id, "family", "family", 1L, "counter", "Counter Displays", 0, 0,
                "Display Rose", "Summary", "Description", "Counter display", List.of(),
                new CatalogFamilyReader.Dimensions(
                        new BigDecimal("10"), new BigDecimal("8"), new BigDecimal("22"), "cm"),
                List.of(), List.of(), List.of());
    }

    public static Product product(long id, String sku, Long familyId, Long categoryId, int position) {
        Product base = new Product(
                id, sku, sku, new Dimensions(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                "Rood", "Beschrijving", categoryId, null, true,
                Barcodes.none(), null, new Carton(
                        new Dimensions(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN),
                        6, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO,
                BigDecimal.ONE, "calculated", BigDecimal.ZERO, new BigDecimal("4.95"),
                0, List.of(), List.of());
        return base.withCanonicalIdentity(familyId, sku.toLowerCase(), "5400000000000",
                position, true);
    }
}
