package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void archivedAndInvoicedWebsiteRequestsNoLongerCountAsOpenWork() {
        SalesRepositories.Orders orders = mock(SalesRepositories.Orders.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);
        SalesOrder archived = order(41L, QuoteStatus.CONCEPT,
                SalesOrderService.WEBSITE_REQUEST_MARKER).withArchivedAt(Instant.now());
        SalesOrder invoiced = order(42L, QuoteStatus.CONCEPT, SalesOrderService.WEBSITE_REQUEST_MARKER);
        SalesOrder invoice = order(43L, QuoteStatus.CONCEPT, null, DocumentType.FACTUUR, 42L);
        when(orders.findAll()).thenReturn(List.of(archived, invoiced, invoice));

        NotificationService.Feed feed = new NotificationService(orders, revisions,
                mock(SalesRepositories.Events.class), mock(CustomerService.class)).feed();

        assertEquals(0, feed.actionCount());
        assertEquals(List.of(), feed.items());
    }

    @Test
    void pendingProposalListAndBadgeExcludeClosedQuotesButKeepAdvanceAgreements() {
        SalesRepositories.Orders orders = mock(SalesRepositories.Orders.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);
        SalesOrder active = order(41L, QuoteStatus.WIJZIGING_GEVRAAGD, null);
        SalesOrder archived = order(42L, QuoteStatus.WIJZIGING_GEVRAAGD, null)
                .withArchivedAt(Instant.now());
        SalesOrder invoiced = order(43L, QuoteStatus.WIJZIGING_GEVRAAGD, null);
        SalesOrder invoice = order(44L, QuoteStatus.CONCEPT, null, DocumentType.FACTUUR, 43L);
        SalesOrder agreement = order(45L, QuoteStatus.WIJZIGING_GEVRAAGD, null)
                .withPartnerDeal(50L, BigDecimal.valueOf(50));
        SalesOrder termInvoice = order(46L, QuoteStatus.CONCEPT, null, DocumentType.FACTUUR, 45L)
                .withPartnerDeal(50L, BigDecimal.valueOf(50));
        List<SalesOrder> documents = List.of(active, archived, invoiced, invoice, agreement, termInvoice);
        when(orders.findAll()).thenReturn(documents);
        documents.forEach(order -> when(orders.findById(order.id())).thenReturn(Optional.of(order)));
        when(orders.findById(99L)).thenReturn(Optional.empty());
        when(revisions.findPending()).thenReturn(List.of(
                revision(41L), revision(42L), revision(43L), revision(45L), revision(99L)));

        NotificationService.Feed feed = new NotificationService(orders, revisions,
                mock(SalesRepositories.Events.class), mock(CustomerService.class)).feed();
        List<QuoteRevision> pending = new QuoteService(orders, revisions, null, null,
                null, null, null, null, null, null).pendingRevisions();

        assertEquals(List.of(41L, 45L), pending.stream().map(QuoteRevision::salesOrderId).toList());
        assertEquals(List.of(41L, 45L), feed.items().stream()
                .filter(item -> item.kind() == NotificationService.Kind.VOORSTEL)
                .map(NotificationService.Notification::orderId).toList());
        assertEquals(pending.size(), feed.actionCount());
    }

    @Test
    void cancelledInvoiceDoesNotHideAnUnarchivedWebsiteRequestOrItsProposal() {
        SalesRepositories.Orders orders = mock(SalesRepositories.Orders.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);
        SalesOrder website = order(41L, QuoteStatus.CONCEPT, SalesOrderService.WEBSITE_REQUEST_MARKER);
        SalesOrder cancelledInvoice = order(42L, QuoteStatus.GEANNULEERD, null, DocumentType.FACTUUR, 41L);
        List<SalesOrder> documents = List.of(website, cancelledInvoice);
        when(orders.findAll()).thenReturn(documents);
        when(orders.findById(41L)).thenReturn(Optional.of(website));
        when(revisions.findPending()).thenReturn(List.of(revision(41L)));

        NotificationService.Feed feed = new NotificationService(orders, revisions,
                mock(SalesRepositories.Events.class), mock(CustomerService.class)).feed();
        List<QuoteRevision> pending = new QuoteService(orders, revisions, null, null,
                null, null, null, null, null, null).pendingRevisions();
        assertEquals(2, feed.actionCount());
        assertEquals(1, pending.size());
        assertEquals(1, feed.items().stream()
                .filter(item -> item.kind() == NotificationService.Kind.WEBSITE_AANVRAAG).count());

        SalesOrderService sales = mock(SalesOrderService.class);
        when(sales.list()).thenReturn(documents);
        var resource = new be.enrosed.sales.adapter.in.rest.SalesOrderResource(sales, mock(QuoteService.class));
        var views = resource.list();
        assertNull(views.getFirst().invoicedAsId(), "the app must allow reviewing and invoicing the quote again");
        assertEquals(website.number(), views.get(1).sourceQuoteNumber(), "the cancelled invoice retains its history link");
    }

    private static QuoteRevision revision(long orderId) {
        return new QuoteRevision(orderId, orderId, RevisionStatus.IN_AFWACHTING,
                Instant.parse("2026-09-10T08:00:00Z"), "Buyer", "Andere aantallen",
                null, null, null, List.of());
    }

    private static SalesOrder order(long id, QuoteStatus status, String internalNotes) {
        return order(id, status, internalNotes, DocumentType.OFFERTE, null);
    }

    private static SalesOrder order(long id, QuoteStatus status, String internalNotes,
                                    DocumentType docType, Long sourceQuoteId) {
        LocalDate today = LocalDate.of(2026, 8, 27);
        return new SalesOrder(id, "ENR-2026-00" + id, 9L, "BE",
                today, today.plusDays(30), status, "DAP", null, null,
                MarkupMode.PRODUCT, BigDecimal.valueOf(45), null, null,
                null, null, null, 0, null, null, null, internalNotes,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                docType, null, null, sourceQuoteId, null, List.of(), List.of());
    }
}
