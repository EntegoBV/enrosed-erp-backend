package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerQuotePreviewTest {

    @Test
    void previewIsAdminOnlyCustomerProjectionAndDoesNotUsePortalLifecycle() throws Exception {
        SalesOrder order = concept();
        SalesOrderService salesOrders = mock(SalesOrderService.class);
        QuoteService quotes = mock(QuoteService.class);
        CustomerService customers = mock(CustomerService.class);
        ProductService products = mock(ProductService.class);
        when(salesOrders.get(7L)).thenReturn(order);
        when(salesOrders.price(order)).thenReturn(emptyPrice());
        when(quotes.revisionsFor(7L)).thenReturn(List.of());

        CustomerQuoteMapper mapper = new CustomerQuoteMapper(
                quotes, salesOrders, customers, products);
        SalesOrderResource resource = new SalesOrderResource(salesOrders, quotes, mapper);

        CustomerQuoteView view = resource.customerPreview(7L, "EN");

        assertTrue(view.preview());
        assertEquals(QuoteStatus.CONCEPT.name(), view.status());
        assertFalse(view.canRespond());
        assertEquals("EN", view.language());
        verify(quotes, never()).openByToken(org.mockito.ArgumentMatchers.anyString());
        verify(quotes, never()).byToken(org.mockito.ArgumentMatchers.anyString());

        RolesAllowed roles = SalesOrderResource.class.getAnnotation(RolesAllowed.class);
        assertTrue(Arrays.asList(roles.value()).contains(AdminIdentityProvider.ADMIN_ROLE));
        Path path = SalesOrderResource.class
                .getMethod("customerPreview", long.class, String.class)
                .getAnnotation(Path.class);
        assertEquals("/{id}/customer-preview", path.value());

        Set<String> fields = Arrays.stream(CustomerQuoteView.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());
        assertFalse(fields.contains("portaltoken"));
        assertFalse(fields.contains("internalnotes"));
        assertFalse(fields.stream().anyMatch(name -> name.contains("margin") || name.contains("cost")));
        Set<String> lineFields = Arrays.stream(CustomerQuoteView.CustomerLine.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());
        assertFalse(lineFields.stream().anyMatch(name -> name.contains("margin") || name.contains("cost")));
    }

    @Test
    void portalCapabilityNeverReturnsRawTokenAndHidesReopenedDraft() {
        SalesOrder order = concept("retained-token", Instant.now());
        SalesOrderService salesOrders = mock(SalesOrderService.class);
        QuoteService quotes = mock(QuoteService.class);
        when(salesOrders.get(7L)).thenReturn(order);
        when(quotes.activePortalUrl(order)).thenReturn(java.util.Optional.empty());

        SalesOrderResource resource = new SalesOrderResource(
                salesOrders, quotes, mock(CustomerQuoteMapper.class));
        SalesOrderResource.PortalLink link = resource.portalLink(7L);

        assertFalse(link.available());
        assertEquals("CONCEPT_IN_BEWERKING", link.status());
        assertNull(link.url());
        Set<String> fields = Arrays.stream(SalesOrderResource.PortalLink.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());
        assertFalse(fields.contains("token"));
    }

    private static SalesOrder concept() {
        return concept(null, null);
    }

    private static SalesOrder concept(String token, Instant sentAt) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(7L, "ENR-0007", null, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, "Customer note", MarkupMode.PRODUCT,
                BigDecimal.ZERO, null, null, token, sentAt, null, 0, null, null, null,
                "private note", DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, List.of(), List.of());
    }

    private static PricedOrder emptyPrice() {
        BigDecimal zero = BigDecimal.ZERO;
        PricedOrder.Totals totals = new PricedOrder.Totals(
                0, 0, 0, 0, 0, 0, zero, zero, zero, zero,
                zero, zero, zero, zero, zero, zero, null, zero, zero,
                zero, false, zero, zero, zero, zero, zero, zero,
                VatTreatment.BINNENLAND, null, null,
                zero, zero, zero, zero);
        PricedOrder.Validation validation = new PricedOrder.Validation(
                zero, true, zero, false, true, List.of(), List.of(), List.of(), null);
        return new PricedOrder(List.of(), totals, validation);
    }
}
