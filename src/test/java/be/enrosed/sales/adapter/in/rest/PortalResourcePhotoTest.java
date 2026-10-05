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
                quotes, mock(SalesOrderService.class), mock(CustomerService.class), products,
                mock(CustomerQuoteMapper.class));

        try (Response response = resource.photo("valid-token", 41L)) {
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }
        verify(quotes).byToken("valid-token");
        verify(products, never()).photoData(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void catalogueItemsNameTheirUnitInThePortalLanguage() {
        QuoteService quotes = mock(QuoteService.class);
        ProductService products = mock(ProductService.class);
        Product base = new Product(
                42L, "ENR-BOWL", "Bowl", Dimensions.empty(), null,
                null, null, null, true, Barcodes.none(), null, Carton.empty(),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO, BigDecimal.ZERO, null,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of(), List.of());
        Product bowl = new Product(base.id(), base.sku(), base.name(), base.dimensions(),
                new be.enrosed.catalog.domain.Packaging(be.enrosed.catalog.domain.PackagingKind.DISPLAY,
                        Dimensions.empty(), null, 8, be.enrosed.catalog.domain.SalesUnit.PIECE, "bowl"),
                base.colour(), base.variantSize(), base.colourHex(), base.description(), base.categoryId(),
                base.supplierId(), base.supplierNote(), base.active(), base.familyId(), base.canonicalVariantKey(),
                base.canonicalBarcode(), base.variantPosition(), base.inventoryKnown(), base.familyKey(),
                base.publicHandle(), base.websiteStatus(), base.orderAppStatus(), base.barcodes(), base.hsCode(),
                base.carton(), base.exwPrice(), base.exwCurrency(), base.extraUnitCost(), base.landedCostEur(),
                base.landedCostSource(), base.markupPct(), base.fixedSalesPriceEur(), base.stockQuantity(),
                base.photos(), base.texts(), base.demo());
        when(quotes.byToken("valid-token")).thenReturn(order());
        when(products.list()).thenReturn(List.of(bowl));
        PortalResource resource = new PortalResource(
                quotes, mock(SalesOrderService.class), mock(CustomerService.class), products,
                mock(CustomerQuoteMapper.class));

        PortalResource.CatalogItem item = resource.catalog("valid-token", "pl").getFirst();

        assertEquals("PIECE", item.salesUnit());
        assertEquals(8, item.piecesPerDisplay());
        assertEquals("bowl", item.unit().key());
        assertEquals("za miseczkę", item.unit().per());
        assertEquals("miseczka", item.unit().one());
        assertEquals("miseczki", item.unit().few());
        assertEquals("miseczek", item.unit().many());
    }

    private static be.enrosed.sales.domain.SalesOrder order() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 9, 22);
        return new be.enrosed.sales.domain.SalesOrder(9L, "Q-2026-0009", null, "PL", today, today.plusDays(30),
                be.enrosed.sales.domain.QuoteStatus.VERZONDEN, "DAP", null, null,
                be.enrosed.sales.domain.MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, null, null, null, 0, null, null, null, null,
                be.enrosed.sales.domain.DeliveryTermsState.VOLLEDIG, be.enrosed.sales.domain.FreightState.BEREKEND,
                null, be.enrosed.sales.domain.LoadMode.LOOSE_CARTONS,
                be.enrosed.sales.domain.PalletProfile.EURO_120X80, null,
                be.enrosed.sales.domain.FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                be.enrosed.sales.domain.DocumentType.OFFERTE, null, null, null, null,
                List.of(), List.of());
    }
}
