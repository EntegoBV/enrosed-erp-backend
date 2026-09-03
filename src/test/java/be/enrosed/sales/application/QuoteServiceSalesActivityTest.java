package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.push.WebPushNotifier;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.sales.domain.VatTreatment;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.company.CompanyProfile;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuoteServiceSalesActivityTest {

    private SalesRepositories.Orders orders;
    private SalesRepositories.Revisions revisions;
    private SalesRepositories.Events history;
    private SalesOrderService salesOrders;
    private CustomerService customers;
    private QuoteDocumentRenderer renderer;
    private QuoteMailer mailer;
    private CompanyProfileService company;
    private CurrentActor currentActor;
    private ActivityLogService activityLog;
    private Event<SalesActivityPushNotifier.Ready> salesPushReady;
    private QuoteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = mock(SalesRepositories.Orders.class);
        revisions = mock(SalesRepositories.Revisions.class);
        history = mock(SalesRepositories.Events.class);
        salesOrders = mock(SalesOrderService.class);
        customers = mock(CustomerService.class);
        renderer = mock(QuoteDocumentRenderer.class);
        mailer = mock(QuoteMailer.class);
        company = mock(CompanyProfileService.class);
        currentActor = mock(CurrentActor.class);
        activityLog = mock(ActivityLogService.class);
        salesPushReady = mock(Event.class);

        service = new QuoteService(orders, revisions, salesOrders, customers, renderer, mailer,
                mock(ProductService.class), history, company, mock(WebPushNotifier.class));
        service.currentActor = currentActor;
        service.activityLog = activityLog;
        service.salesPushReady = salesPushReady;
        service.portalBaseUrl = "https://quotes.enrosed.test";

        when(customers.get(2L)).thenReturn(customer());
        when(renderer.render(any(), any(), any(), any())).thenReturn(
                new QuoteDocumentRenderer.Document("document.pdf", new byte[]{1}, "application/pdf"));
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(company.get()).thenReturn(CompanyProfile.empty());
    }

    @Test
    void quotePushCarriesActorAndInvoiceDelegatesItsSingleAuditAndPushToSalesOrderService() {
        SalesOrder quote = order(DocumentType.OFFERTE, QuoteStatus.CONCEPT, null);
        when(salesOrders.get(42L)).thenReturn(quote);
        when(salesOrders.price(any())).thenReturn(pricedOrder());
        when(currentActor.current()).thenReturn(new ActorRef("emre", "Emre"));

        service.send(42L, "Persoonlijk bericht voor de klant");

        SalesOrder invoice = order(DocumentType.FACTUUR, QuoteStatus.CONCEPT, null);
        when(salesOrders.get(42L)).thenReturn(invoice);
        when(salesOrders.markInvoiceSent(42L)).thenReturn(invoice);
        when(currentActor.current()).thenReturn(new ActorRef("berat", "Berat"));

        service.send(42L, "Privé factuurbericht");

        ArgumentCaptor<SalesActivityPushNotifier.Ready> events =
                ArgumentCaptor.forClass(SalesActivityPushNotifier.Ready.class);
        verify(salesPushReady).fire(events.capture());

        SalesActivityPushNotifier.Ready quoteReady = events.getValue();
        assertEquals(SalesActivityPushNotifier.Activity.STAFF_QUOTE_SENT, quoteReady.activity());
        assertEquals("Emre", quoteReady.actorDisplayName());

        String devicePayload = events.getAllValues().toString();
        assertFalse(devicePayload.contains("private-buyer@example.test"));
        assertFalse(devicePayload.contains("Private Buyer BV"));
        assertFalse(devicePayload.contains("Persoonlijk bericht"));
        assertFalse(devicePayload.contains("Privé factuurbericht"));

        verify(activityLog).record(
                "SENT", "SALES_ORDER", "42", "ENR-2026-0042", "Offerte verstuurd");
        verify(salesOrders).markInvoiceSent(42L);
    }

    @Test
    void customerImportantEventsUseStructuredNonPiiAfterCommitPayloads() {
        SalesOrder sent = order(DocumentType.OFFERTE, QuoteStatus.VERZONDEN, "portal-token");
        when(orders.findByPortalToken(any())).thenReturn(Optional.of(sent));
        when(revisions.findByOrder(42L)).thenReturn(List.of());

        service.openByToken("portal-token");
        service.acceptByCustomer("portal-token", "Private Signer", "private acceptance message");
        service.rejectByCustomer("portal-token", "private rejection reason");
        service.proposeRevision("portal-token", List.of(), "Private Buyer",
                "private requested changes");

        ArgumentCaptor<SalesActivityPushNotifier.Ready> events =
                ArgumentCaptor.forClass(SalesActivityPushNotifier.Ready.class);
        verify(salesPushReady, times(4)).fire(events.capture());

        assertEquals(List.of(
                        SalesActivityPushNotifier.Activity.CUSTOMER_OPENED,
                        SalesActivityPushNotifier.Activity.CUSTOMER_ACCEPTED,
                        SalesActivityPushNotifier.Activity.CUSTOMER_REJECTED,
                        SalesActivityPushNotifier.Activity.CUSTOMER_CHANGE_REQUESTED),
                events.getAllValues().stream()
                        .map(SalesActivityPushNotifier.Ready::activity)
                        .toList());
        events.getAllValues().forEach(event -> assertNull(event.actorDisplayName()));

        String devicePayload = events.getAllValues().toString();
        assertFalse(devicePayload.contains("private-buyer@example.test"));
        assertFalse(devicePayload.contains("Private Buyer BV"));
        assertFalse(devicePayload.contains("Private Signer"));
        assertFalse(devicePayload.contains("acceptance message"));
        assertFalse(devicePayload.contains("rejection reason"));
        assertFalse(devicePayload.contains("requested changes"));
    }

    @Test
    void cancellingAnOpenQuoteTellsTheCustomerWithThePortalLinkAndLeavesItReopenable() {
        SalesOrder sent = order(DocumentType.OFFERTE, QuoteStatus.VERZONDEN, "portal-token");
        when(salesOrders.get(42L)).thenReturn(sent);
        when(currentActor.current()).thenReturn(new ActorRef("emre", "Emre"));

        SalesOrder cancelled = service.cancel(42L, "De collectie is uitverkocht", true);

        assertEquals(QuoteStatus.GEANNULEERD, cancelled.status());
        assertTrue(cancelled.status().canReopen(), "a cancelled quote can be reopened by us");
        assertFalse(cancelled.status().isOpenForCustomer());
        ArgumentCaptor<String> portalUrl = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendCancellation(eq(sent), eq(customer()), portalUrl.capture(),
                eq("De collectie is uitverkocht"));
        assertTrue(portalUrl.getValue().contains("portal-token"), "the mail carries the customer's own link");
        ArgumentCaptor<be.enrosed.sales.domain.QuoteEvent> event =
                ArgumentCaptor.forClass(be.enrosed.sales.domain.QuoteEvent.class);
        verify(history).add(event.capture());
        assertEquals(be.enrosed.sales.domain.QuoteEvent.Type.GEANNULEERD, event.getValue().type());
        assertTrue(event.getValue().summary().contains("private-buyer@example.test"));
        verify(activityLog).record("CANCELLED", "SALES_ORDER", "42", "ENR-2026-0042", "Offerte geannuleerd");
    }

    @Test
    void cancellingWithoutNoticeSendsNoMailAndAConceptCancelsQuietly() {
        SalesOrder concept = order(DocumentType.OFFERTE, QuoteStatus.CONCEPT, null);
        when(salesOrders.get(42L)).thenReturn(concept);
        when(currentActor.current()).thenReturn(new ActorRef("emre", "Emre"));

        SalesOrder cancelled = service.cancel(42L, null, true);

        assertEquals(QuoteStatus.GEANNULEERD, cancelled.status());
        verify(mailer, org.mockito.Mockito.never()).sendCancellation(any(), any(), any(), any());
    }

    @Test
    void anAcceptedQuoteOrAnInvoiceCannotBeCancelled() {
        when(salesOrders.get(42L)).thenReturn(order(DocumentType.OFFERTE, QuoteStatus.GEACCEPTEERD, "t"));
        org.junit.jupiter.api.Assertions.assertThrows(be.enrosed.shared.BusinessRuleException.class,
                () -> service.cancel(42L, null, false));
        when(salesOrders.get(42L)).thenReturn(order(DocumentType.FACTUUR, QuoteStatus.CONCEPT, null));
        org.junit.jupiter.api.Assertions.assertThrows(be.enrosed.shared.BusinessRuleException.class,
                () -> service.cancel(42L, null, false));
        verify(mailer, org.mockito.Mockito.never()).sendCancellation(any(), any(), any(), any());
    }

    private static PricedOrder pricedOrder() {
        BigDecimal zero = BigDecimal.ZERO;
        PricedOrder.Line line = new PricedOrder.Line(
                7L, "BOWL-XL", "Glass bowl roses", "Glass bowl roses", null,
                12, 1, 1, 1, 1, 1, zero, zero, zero,
                BigDecimal.TEN, new BigDecimal("120.00"), zero, zero, zero, zero,
                new BigDecimal("120.00"), BigDecimal.TEN,
                zero, zero, zero, zero, null, null,
                12, true, true, 0, null, null, null);
        PricedOrder.Totals totals = new PricedOrder.Totals(
                12, 1, 1, 1, 0, 0, zero, zero, zero, zero,
                new BigDecimal("120.00"), zero, new BigDecimal("120.00"), zero, zero,
                zero, null, zero, new BigDecimal("120.00"), zero, false, zero, zero,
                new BigDecimal("100.00"), new BigDecimal("21.00"), new BigDecimal("21.00"),
                new BigDecimal("121.00"), VatTreatment.BINNENLAND, null, null,
                zero, zero, zero, zero);
        PricedOrder.Validation validation = new PricedOrder.Validation(
                zero, true, zero, true, true, List.of(), List.of(), List.of(), null);
        return new PricedOrder(List.of(line), totals, validation);
    }

    private static Customer customer() {
        return new Customer(2L, "Private Buyer BV", "Private Contact",
                "private-buyer@example.test", "+32 400 00 00 00", "BE0000000000", "BE",
                Language.NL, "Private street 1", "2400", "Mol", "DAP", "Vooruitbetaling",
                null, LocalDate.now());
    }

    private static SalesOrder order(DocumentType type, QuoteStatus status, String token) {
        LocalDate today = LocalDate.now();
        boolean invoice = type == DocumentType.FACTUUR;
        return new SalesOrder(42L, "ENR-2026-0042", 2L, "BE", today, today.plusDays(30),
                status, "DAP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, token, token == null ? null : java.time.Instant.now(), null, 0,
                null, null, null, null, DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND,
                null, LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                type, invoice ? today.plusDays(30) : null, null, null, null,
                List.of(new SalesOrderLine(1L, 7L, 12, new BigDecimal("10.00"), null, null)),
                List.of());
    }
}
