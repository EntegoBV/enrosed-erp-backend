package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.BarcodeValidator;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductVariantLinkService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductResourceSharedFieldsTest {

    @Test
    void mapsTheSmallRequestToTheTransactionalProductCommand() {
        ProductService products = mock(ProductService.class);
        ProductService.SharedFieldsResult expected =
                new ProductService.SharedFieldsResult(List.of(22L, 33L), 2);
        Set<ProductService.SharedField> fields = Set.of(
                ProductService.SharedField.CARTON,
                ProductService.SharedField.SALES_PRICE);
        when(products.applySharedFields(11L, 71L, List.of(22L, 33L), fields))
                .thenReturn(expected);
        ProductResource resource = resource(products);

        ProductService.SharedFieldsResult result = resource.applySharedFields(
                11L,
                new ProductResource.ApplySharedFieldsRequest(
                        71L, List.of(22L, 33L), fields));

        assertSame(expected, result);
        assertEquals(2, result.updatedProducts());
        verify(products).applySharedFields(11L, 71L, List.of(22L, 33L), fields);
    }

    @Test
    void rejectsAMissingBodyBeforeCallingTheService() {
        ProductService products = mock(ProductService.class);
        ProductResource resource = resource(products);

        BusinessRuleException error = assertThrows(
                BusinessRuleException.class,
                () -> resource.applySharedFields(11L, null));

        assertEquals("Geen gedeelde productgegevens meegestuurd", error.getMessage());
        verifyNoInteractions(products);
    }

    private static ProductResource resource(ProductService products) {
        return new ProductResource(
                products,
                mock(BarcodeValidator.class),
                mock(ProductVariantLinkService.class),
                mock(ProductFamilyDtoFactory.class),
                mock(StockService.class));
    }
}
