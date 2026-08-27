package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    @Test
    void conceptWebsiteRequestIsOneActionableBellItemWithItsCreationTime() {
        SalesRepositories.Orders orders = mock(SalesRepositories.Orders.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);
        SalesRepositories.Events events = mock(SalesRepositories.Events.class);
        CustomerService customers = mock(CustomerService.class);
        Instant createdAt = Instant.parse("2026-08-27T08:15:00Z");
        SalesOrder website = order(41L, QuoteStatus.CONCEPT,
                SalesOrderService.WEBSITE_REQUEST_MARKER + " ENR-2026-0041");
        SalesOrder ordinary = order(42L, QuoteStatus.CONCEPT, null);
        SalesOrder alreadySent = order(43L, QuoteStatus.VERZONDEN,
                SalesOrderService.WEBSITE_REQUEST_MARKER + " ENR-2026-0043");
        when(orders.findAll()).thenReturn(List.of(ordinary, alreadySent, website));
        when(revisions.findPending()).thenReturn(List.of());
        when(events.findByOrder(41L)).thenReturn(List.of(
                new QuoteEvent(1L, 41L, QuoteEvent.Type.OPGEMAAKT, createdAt,
                        null, false, "Offerte opgemaakt", null)));
        when(customers.get(9L)).thenReturn(new Customer(
                9L, "Buyer BV", "Ana", "ana@example.com", null, null,
                "BE", be.enrosed.shared.Language.EN, null, null, null,
                "DAP", null, null, LocalDate.now()));

        NotificationService.Feed feed = new NotificationService(
                orders, revisions, events, customers).feed();

        assertEquals(1, feed.actionCount());
        assertEquals(1, feed.items().size());
        NotificationService.Notification item = feed.items().getFirst();
        assertEquals(NotificationService.Kind.WEBSITE_AANVRAAG, item.kind());
        assertEquals(41L, item.orderId());
        assertEquals("ENR-2026-0041", item.orderNumber());
        assertEquals("Buyer BV", item.customer());
        assertEquals("Nieuwe websiteaanvraag", item.title());
        assertEquals("Controleer aantallen, prijzen, btw en levering en stuur daarna de offerte.",
                item.detail());
        assertEquals(createdAt, item.at());
    }

    private static SalesOrder order(long id, QuoteStatus status, String internalNotes) {
        LocalDate today = LocalDate.of(2026, 8, 27);
        return new SalesOrder(id, "ENR-2026-00" + id, 9L, "BE",
                today, today.plusDays(30), status, "DAP", null, null,
                MarkupMode.PRODUCT, BigDecimal.valueOf(45), null, null,
                null, null, null, 0, null, null, null, internalNotes,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.OFFERTE, null, null, null, null, List.of(), List.of());
    }
}
