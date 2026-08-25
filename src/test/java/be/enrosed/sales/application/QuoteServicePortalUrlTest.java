package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class QuoteServicePortalUrlTest {

    @Test
    void returnsConfiguredFullUrlOnlyForActuallySentVisibleQuote() {
        QuoteService service = service();
        service.portalBaseUrl = "https://quotes.enrosed.com/customer///";

        assertEquals("https://quotes.enrosed.com/customer/offerte/sent-token",
                service.activePortalUrl(order(QuoteStatus.VERZONDEN, "sent-token", Instant.now()))
                        .orElseThrow());
        assertTrue(service.activePortalUrl(order(QuoteStatus.CONCEPT, "sent-token", Instant.now()))
                .isEmpty(), "a reopened draft must not advertise its retained token");
        assertTrue(service.activePortalUrl(order(QuoteStatus.VERZONDEN, "stray-token", null))
                .isEmpty(), "a token without evidence of sending is not a customer link");
        assertTrue(service.activePortalUrl(order(QuoteStatus.CONCEPT, null, null)).isEmpty());
    }

    private static QuoteService service() {
        return new QuoteService(
                mock(SalesRepositories.Orders.class),
                mock(SalesRepositories.Revisions.class),
                mock(SalesOrderService.class),
                mock(CustomerService.class),
                mock(QuoteDocumentRenderer.class),
                mock(QuoteMailer.class),
                mock(ProductService.class),
                mock(SalesRepositories.Events.class),
                mock(be.enrosed.shared.company.CompanyProfileService.class));
    }

    private static SalesOrder order(QuoteStatus status, String token, Instant sentAt) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(1L, "ENR-TEST", 2L, "BE", today, today.plusDays(30),
                status, "DAP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, token, sentAt, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null,
                List.of(), List.of());
    }
}
