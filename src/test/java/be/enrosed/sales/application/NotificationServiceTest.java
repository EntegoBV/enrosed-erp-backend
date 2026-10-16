package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void anAdoptedProposalIsOneResendItemWaitingSinceTheLatestAdoption() {
        SalesRepositories.Orders orders = mock(SalesRepositories.Orders.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);
        CustomerService customers = mock(CustomerService.class);
        Instant sent = Instant.parse("2026-09-01T08:00:00Z");
        SalesOrder adopted = order(41L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent);
        SalesOrder resentSince = order(42L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null,
                Instant.parse("2026-09-05T08:00:00Z"));
        SalesOrder outAgain = order(43L, QuoteStatus.VERZONDEN, null, DocumentType.OFFERTE, null, sent);
        SalesOrder unhandled = order(44L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent);
        SalesOrder archived = order(45L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent)
                .withArchivedAt(Instant.now());
        SalesOrder invoiced = order(46L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent);
        SalesOrder invoice = order(47L, QuoteStatus.CONCEPT, null, DocumentType.FACTUUR, 46L, null);
        List<SalesOrder> documents = List.of(adopted, resentSince, outAgain, unhandled, archived, invoiced, invoice);
        when(orders.findAll()).thenReturn(documents);
        when(revisions.findApproved()).thenReturn(List.of(
                approved(1L, 41L, Instant.parse("2026-09-03T10:00:00Z")),
                approved(2L, 41L, Instant.parse("2026-09-02T10:00:00Z")),
                approved(3L, 42L, Instant.parse("2026-09-04T10:00:00Z")),
                approved(4L, 43L, Instant.parse("2026-09-02T10:00:00Z")),
                approved(5L, 44L, null),
                approved(6L, 45L, Instant.parse("2026-09-02T10:00:00Z")),
                approved(7L, 46L, Instant.parse("2026-09-02T10:00:00Z")),
                approved(8L, 99L, Instant.parse("2026-09-02T10:00:00Z"))));
        when(customers.get(9L)).thenReturn(new Customer(
                9L, "Buyer BV", "Ana", "ana@example.com", null, null,
                "BE", be.enrosed.shared.Language.EN, null, null, null,
                "DAP", null, null, LocalDate.now()));

        NotificationService.Feed feed = new NotificationService(orders, revisions,
                mock(SalesRepositories.Events.class), customers).feed();

        assertEquals(1, feed.items().size(), "only the quote adopted after its last send: " + feed.items());
        NotificationService.Notification item = feed.items().getFirst();
        assertEquals(NotificationService.Kind.OPNIEUW_VERSTUREN, item.kind());
        assertEquals(41L, item.orderId());
        assertEquals("ENR-2026-0041", item.orderNumber());
        assertEquals("Buyer BV", item.customer());
        assertEquals("Wijziging nog versturen", item.title());
        assertEquals("Je nam de wijziging van de klant over; de klant wacht op de nieuwe versie.", item.detail());
        assertTrue(item.actionNeeded());
        assertEquals(Instant.parse("2026-09-03T10:00:00Z"), item.at(), "waiting since the latest adoption");
        assertEquals(1, feed.actionCount());
        /* The sales list's "opnieuw versturen" reads the same rule: one set of documents for the list and the bell. */
        Set<Long> listed = new QuoteService(orders, revisions, null, null,
                null, null, null, null, null, null).awaitsResendIds(documents);
        assertEquals(Set.of(41L, 45L, 46L), listed,
                "the list marks archived and invoiced quotes too and hides them itself; the bell leaves closed quotes out");
    }

    @Test
    void theResendItemReplacesTheWebsiteItemOfTheSameDocument() {
        SalesRepositories.Orders orders = mock(SalesRepositories.Orders.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);
        Instant sent = Instant.parse("2026-09-01T08:00:00Z");
        Instant adoptedAt = Instant.parse("2026-09-02T10:00:00Z");
        SalesOrder legacy = order(51L, QuoteStatus.CONCEPT,
                SalesOrderService.WEBSITE_REQUEST_MARKER + " ENR-2026-0051", DocumentType.OFFERTE, null, sent);
        SalesOrder inProcessing = order(52L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent);
        SalesOrder untaken = order(53L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent);
        SalesOrder cancelledByCustomer = order(54L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, sent);
        SalesOrder freshOrder = order(55L, QuoteStatus.CONCEPT, null, DocumentType.OFFERTE, null, null);
        when(orders.findAll()).thenReturn(List.of(legacy, inProcessing, untaken, cancelledByCustomer, freshOrder));
        when(revisions.findApproved()).thenReturn(List.of(
                approved(1L, 51L, adoptedAt), approved(2L, 52L, adoptedAt),
                approved(3L, 53L, adoptedAt), approved(4L, 54L, adoptedAt)));
        Instant placed = Instant.parse("2026-08-30T08:00:00Z");
        Instant cancelledAt = Instant.now().minus(java.time.Duration.ofDays(1));
        WebOrders web = mock(WebOrders.class);
        when(web.indexStates()).thenReturn(Map.of(
                52L, webOrder(52L, placed, null, Instant.parse("2026-08-31T08:00:00Z")),
                53L, webOrder(53L, placed, null, null),
                54L, webOrder(54L, placed, cancelledAt, null),
                55L, webOrder(55L, placed, null, null)));
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<WebOrders> webOrders = mock(jakarta.enterprise.inject.Instance.class);
        when(webOrders.isResolvable()).thenReturn(true);
        when(webOrders.get()).thenReturn(web);
        NotificationService service = new NotificationService(orders, revisions,
                mock(SalesRepositories.Events.class), mock(CustomerService.class));
        service.webOrders = webOrders;

        NotificationService.Feed feed = service.feed();

        for (long id : List.of(51L, 52L, 53L, 54L, 55L))
            assertEquals(1, feed.items().stream().filter(item -> item.orderId() == id).count(),
                    "one item per document: " + feed.items());
        for (long id : List.of(51L, 52L, 53L)) {
            NotificationService.Notification item = feed.items().stream()
                    .filter(candidate -> candidate.orderId() == id).findFirst().orElseThrow();
            assertEquals(NotificationService.Kind.OPNIEUW_VERSTUREN, item.kind(), "document " + id);
            assertEquals(adoptedAt, item.at());
        }
        NotificationService.Notification cancelled = feed.items().stream()
                .filter(item -> item.orderId() == 54L).findFirst().orElseThrow();
        assertEquals(NotificationService.Kind.AFGEWEZEN, cancelled.kind(), "a withdrawn order stays news, nothing to resend");
        NotificationService.Notification fresh = feed.items().stream()
                .filter(item -> item.orderId() == 55L).findFirst().orElseThrow();
        assertEquals(NotificationService.Kind.WEBSITE_AANVRAAG, fresh.kind());
        assertEquals("Nieuwe websitebestelling", fresh.title());
        assertEquals(4, feed.actionCount());
    }

    private static WebOrders.Row webOrder(long id, Instant placedAt, Instant customerCancelledAt,
                                          Instant processingStartedAt) {
        return new WebOrders.Row(id, 9L, 70L, "buyer@login.example", "nl", 1, placedAt, null, null,
                customerCancelledAt, processingStartedAt, processingStartedAt == null ? null : "emre",
                processingStartedAt == null ? null : "KNOP", null, null, null, null, null, null, null);
    }

    private static QuoteRevision approved(long id, long orderId, Instant handledAt) {
        return new QuoteRevision(id, orderId, RevisionStatus.GOEDGEKEURD,
                Instant.parse("2026-09-01T12:00:00Z"), "Buyer", "Andere aantallen",
                handledAt, "emre", null, List.of());
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
        return order(id, status, internalNotes, docType, sourceQuoteId, null);
    }

    private static SalesOrder order(long id, QuoteStatus status, String internalNotes,
                                    DocumentType docType, Long sourceQuoteId, Instant sentAt) {
        LocalDate today = LocalDate.of(2026, 8, 27);
        return new SalesOrder(id, "ENR-2026-00" + id, 9L, "BE",
                today, today.plusDays(30), status, "DAP", null, null,
                MarkupMode.PRODUCT, BigDecimal.valueOf(45), null, null,
                null, sentAt, null, 0, null, null, null, internalNotes,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                docType, null, null, sourceQuoteId, null, List.of(), List.of());
    }
}
