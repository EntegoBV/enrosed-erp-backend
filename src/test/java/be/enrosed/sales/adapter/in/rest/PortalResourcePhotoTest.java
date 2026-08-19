package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.shared.Currency;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalResourcePhotoTest {

    @Test
    void validTokenStillCannotExposeAnInactiveProductPhoto() {
        QuoteService quotes = mock(QuoteService.class);
        ProductService products = mock(ProductService.class);
        Product inactive = new Product(
                41L, "ENR-P41", "Verborgen product", Dimensions.empty(), null,
                null, null, null, false, Barcodes.none(), null, Carton.empty(),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO, BigDecimal.ZERO, null,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of(), List.of());
        when(products.get(41L)).thenReturn(inactive);
        PortalResource resource = new PortalResource(
                quotes, mock(SalesOrderService.class), mock(CustomerService.class), products);

        try (Response response = resource.photo("valid-token", 41L)) {
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }
        verify(quotes).byToken("valid-token");
        verify(products, never()).photoData(org.mockito.ArgumentMatchers.anyString());
    }
}
