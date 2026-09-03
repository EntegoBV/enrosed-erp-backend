package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderLifecycleActivityTest {

    private static final ActorRef BERAT = new ActorRef("berat", "Berat");

    private SalesRepositories.Orders orders;
    private SalesRepositories.Events history;
    private SalesRepositories.Revisions revisions;
    private ProductService products;
    private ActivityLogService activityLog;
    private Event<SalesActivityPushNotifier.Ready> salesActivityPush;
    private SalesOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = mock(SalesRepositories.Orders.class);
        history = mock(SalesRepositories.Events.class);
        revisions = mock(SalesRepositories.Revisions.class);
        products = mock(ProductService.class);
        CountryService countries = mock(CountryService.class);
        CustomerService customers = mock(CustomerService.class);

        when(orders.save(any(SalesOrder.class))).thenAnswer(call -> call.getArgument(0));
        when(countries.find("BE")).thenReturn(country());
        when(customers.get(7L)).thenReturn(customer());

        service = new SalesOrderService(orders, products, countries,
                mock(DiscountTierService.class), mock(SalesPricingCalculator.class),
                new PalletCalculator(), mock(SalesSettings.class), customers,
                mock(VatCalculator.class), history, revisions,
                mock(be.enrosed.shipping.application.CarrierRepository.class));

        Instance<CurrentActor> actors = mock(Instance.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(actors.isResolvable()).thenReturn(true);
        when(actors.get()).thenReturn(currentActor);
        when(currentActor.current()).thenReturn(BERAT);
        service.actor = actors;

        Instance<ActivityLogService> activities = mock(Instance.class);
        activityLog = mock(ActivityLogService.class);
        when(activities.isResolvable()).thenReturn(true);
        when(activities.get()).thenReturn(activityLog);
        service.activity = activities;

        salesActivityPush = mock(Event.class);
        service.salesActivityPush = salesActivityPush;
    }

    @Test
    void shippingStockUsesServerActorInHistoryAndGlobalActivity() {
        SalesOrder invoice = invoice(70L, QuoteStatus.VERZONDEN, null);
        when(orders.findById(70L)).thenReturn(Optional.of(invoice));

        SalesOrder shipped = service.shipGoods(70L);

        assertNotNull(shipped.goodsShippedAt());
        verify(products).sellStock(9L, 2, "F-2026-0070");
        verify(activityLog).record(SalesOrderService.SALES_ACTION_SHIPPED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "70", "F-2026-0070",
                "Bestelling verzonden en voorraad afgepunt");
        assertHistory("Berat", QuoteEvent.Type.BESTELLING_VERZONDEN,
                "Bestelling verzonden - voorraad afgepunt");
    }

    @Test
    void markingInvoicePaidUsesServerActorAndPrivateSafeActivity() {
        SalesOrder invoice = invoice(71L, QuoteStatus.VERZONDEN, null);
        when(orders.findById(71L)).thenReturn(Optional.of(invoice));

        SalesOrder paid = service.markInvoicePaid(71L);

        assertEquals(QuoteStatus.BETAALD, paid.status());
        verify(activityLog).record(SalesOrderService.SALES_ACTION_PAID,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "71", "F-2026-0071",
                "Factuur als betaald gemarkeerd");
        assertHistory("Berat", QuoteEvent.Type.BETAALD, "Factuur betaald");
    }

    @Test
    void deletingUnusedDraftLeavesAnActorAttributedGlobalTombstone() {
        SalesOrder draft = quote(72L);
        when(orders.findById(72L)).thenReturn(Optional.of(draft));
        when(revisions.findByOrder(72L)).thenReturn(List.of());

        service.delete(72L);

        verify(history).deleteByOrder(72L);
        verify(orders).deleteById(72L);
        verify(activityLog).record(ActivityLogService.ACTION_DELETED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "72", "ENR-2026-0072",
                "Offerte verwijderd");
    }

    @Test
    void deletingUnusedDraftQuoteAlsoRemovesAReservedPortalToken() {
        SalesOrder draft = withPortalToken(quote(76L), "reserved-but-never-sent");
        when(orders.findById(76L)).thenReturn(Optional.of(draft));
        when(revisions.findByOrder(76L)).thenReturn(List.of());

        service.delete(76L);

        verify(orders).lockById(76L);
        verify(history).deleteByOrder(76L);
        verify(orders).deleteById(76L);
        verify(activityLog).record(ActivityLogService.ACTION_DELETED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "76", "ENR-2026-0076",
                "Offerte verwijderd");
    }

    @Test
    void deletingSentQuoteAlsoRemovesItsCustomerRevisions() {
        SalesOrder sent = quote(78L, QuoteStatus.VERZONDEN);
        when(orders.findById(78L)).thenReturn(Optional.of(sent));
        when(revisions.findByOrder(78L)).thenReturn(List.of(new be.enrosed.sales.domain.QuoteRevision(
                1L, 78L, be.enrosed.sales.domain.RevisionStatus.IN_AFWACHTING, Instant.now(),
                "Klant", null, null, null, null, List.of())));

        service.delete(78L);

        verify(revisions).deleteByOrder(78L);
        verify(history).deleteByOrder(78L);
        verify(orders).deleteById(78L);
        verify(activityLog).record(ActivityLogService.ACTION_DELETED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "78", "ENR-2026-0078",
                "Offerte verwijderd");
    }

    @Test
    void deletingUnusedDraftInvoiceKeepsAnInvoiceSpecificTombstone() {
        SalesOrder draft = invoice(74L, QuoteStatus.CONCEPT, null);
        when(orders.findById(74L)).thenReturn(Optional.of(draft));
        when(revisions.findByOrder(74L)).thenReturn(List.of());

        service.delete(74L);

        verify(history).deleteByOrder(74L);
        verify(orders).deleteById(74L);
        verify(activityLog).record(ActivityLogService.ACTION_DELETED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "74", "F-2026-0074",
                "Factuur verwijderd");
    }

    @Test
    void quoteUsedAsInvoiceSourceCannotBeDeleted() {
        SalesOrder draft = quote(75L);
        when(orders.findById(75L)).thenReturn(Optional.of(draft));
        when(revisions.findByOrder(75L)).thenReturn(List.of());
        when(orders.existsBySourceQuoteId(75L)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.delete(75L));

        verify(orders).lockById(75L);
        verify(history, never()).deleteByOrder(75L);
        verify(orders, never()).deleteById(75L);
    }

    @Test
    void sentInvoiceDeletionExplainsTheInvoiceRule() {
        SalesOrder sentInvoice = invoice(77L, QuoteStatus.VERZONDEN, null);
        when(orders.findById(77L)).thenReturn(Optional.of(sentInvoice));
        when(revisions.findByOrder(77L)).thenReturn(List.of());

        BusinessRuleException failure = assertThrows(
                BusinessRuleException.class, () -> service.delete(77L));

        assertEquals("Alleen een conceptfactuur die nog nooit verstuurd of gebruikt is kan verwijderd worden",
                failure.getMessage());
        verify(orders, never()).deleteById(77L);
    }

    @Test
    void manualInvoiceSentUsesTheServerActorInHistoryAuditAndAfterCommitPayload() {
        SalesOrder invoice = invoice(73L, QuoteStatus.CONCEPT, null);
        when(orders.findById(73L)).thenReturn(Optional.of(invoice));

        service.markInvoiceSent(73L);

        assertHistory("Berat", QuoteEvent.Type.VERSTUURD, "Factuur verstuurd");
        verify(activityLog).record(SalesOrderService.SALES_ACTION_SENT,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "73", "F-2026-0073",
                "Factuur verstuurd");
        verify(salesActivityPush).fire(
                SalesActivityPushNotifier.Ready.staffInvoiceSent(73L, "F-2026-0073", BERAT));
    }

    private void assertHistory(String actor, QuoteEvent.Type type, String summary) {
        ArgumentCaptor<QuoteEvent> event = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history).add(event.capture());
        assertEquals(actor, event.getValue().actor());
        assertEquals(type, event.getValue().type());
        assertEquals(summary, event.getValue().summary());
    }

    private static SalesOrder invoice(long id, QuoteStatus status, Instant goodsShippedAt) {
        return invoice(id, status, goodsShippedAt, null);
    }

    private static SalesOrder invoice(long id, QuoteStatus status, Instant goodsShippedAt,
                                      Long sourceQuoteId) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, "F-2026-00" + id, 7L, "BE", today, today.plusDays(30),
                status, "DAP", null, null, MarkupMode.PRODUCT, new BigDecimal("45"),
                null, null, null, status == QuoteStatus.CONCEPT ? null : Instant.now(),
                null, 0, null, null, null, null, DeliveryTermsState.VOLLEDIG,
                FreightState.BEREKEND, null, LoadMode.PALLETS, PalletProfile.EURO_120X80,
                null, FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, today.plusDays(30), null, sourceQuoteId, goodsShippedAt,
                List.of(new SalesOrderLine(1L, 9L, 2, BigDecimal.TEN, null, null)),
                List.of());
    }

    private static SalesOrder quote(long id) {
        return quote(id, QuoteStatus.CONCEPT);
    }

    private static SalesOrder quote(long id, QuoteStatus status) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, "ENR-2026-00" + id, 7L, "BE", today, today.plusDays(30),
                status, "DAP", null, null, MarkupMode.PRODUCT,
                new BigDecimal("45"), null, null, null,
                status == QuoteStatus.CONCEPT ? null : Instant.now(), null, 0,
                null, null, null, null, DeliveryTermsState.VOLLEDIG,
                FreightState.BEREKEND, null, LoadMode.PALLETS, PalletProfile.EURO_120X80,
                null, FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.OFFERTE, null, null, null, null, List.of(), List.of());
    }

    private static SalesOrder withPortalToken(SalesOrder order, String portalToken) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), portalToken,
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(), order.loadMode(),
                order.palletProfile(), order.maxPalletHeightCm(), order.freightPricingStrategy(),
                order.freightRatePerCbmEur(), order.freightCarrierId(),
                order.freightCarrierExtraEur(), order.docType(), order.invoiceDueDate(),
                order.paidAt(), order.sourceQuoteId(), order.goodsShippedAt(), order.lines(),
                order.pallets(), order.pickupLocation());
    }

    private static Country country() {
        return new Country("BE", "België", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("21"), 1, true);
    }

    private static Customer customer() {
        return new Customer(7L, "Private Buyer", "Private Contact", "private@example.test",
                null, "BE0000000000", "BE", Language.NL,
                "Private street 1", "2000", "Antwerpen", "DAP", null, null,
                LocalDate.now());
    }
}
