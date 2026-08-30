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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderCreationActivityTest {

    private static final ActorRef EMRE = new ActorRef("emre", "Emre");

    private SalesRepositories.Orders orders;
    private SalesRepositories.Events history;
    private ActivityLogService activityLog;
    private Event<SalesCreationPushNotifier.Ready> creationPush;
    private SalesOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = mock(SalesRepositories.Orders.class);
        history = mock(SalesRepositories.Events.class);
        ProductService products = mock(ProductService.class);
        CountryService countries = mock(CountryService.class);
        CustomerService customers = mock(CustomerService.class);
        be.enrosed.shipping.application.CarrierRepository carriers =
                mock(be.enrosed.shipping.application.CarrierRepository.class);

        when(orders.findAll()).thenReturn(List.of());
        when(products.list()).thenReturn(List.of());
        when(countries.find("BE")).thenReturn(country());
        when(customers.get(7L)).thenReturn(customer());
        when(carriers.findAll()).thenReturn(List.of());

        SalesSettings settings = new SalesSettings();
        settings.defaultMarkupPct = new BigDecimal("45");
        settings.palletLengthCm = new BigDecimal("120");
        settings.palletWidthCm = new BigDecimal("80");
        settings.palletBaseHeightCm = new BigDecimal("14.4");
        settings.palletMaxHeightCm = new BigDecimal("260");
        settings.palletMaxWeightKg = new BigDecimal("700");

        service = new SalesOrderService(orders, products, countries,
                mock(DiscountTierService.class), mock(SalesPricingCalculator.class),
                new PalletCalculator(), settings, customers, mock(VatCalculator.class),
                history, mock(SalesRepositories.Revisions.class), carriers);

        Instance<CurrentActor> actors = mock(Instance.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(actors.isResolvable()).thenReturn(true);
        when(actors.get()).thenReturn(currentActor);
        when(currentActor.current()).thenReturn(EMRE);
        service.actor = actors;

        Instance<ActivityLogService> activities = mock(Instance.class);
        activityLog = mock(ActivityLogService.class);
        when(activities.isResolvable()).thenReturn(true);
        when(activities.get()).thenReturn(activityLog);
        service.activity = activities;

        creationPush = mock(Event.class);
        service.salesCreationPush = creationPush;
    }

    @Test
    void staffQuoteCreationUsesServerActorForHistoryAuditAndAfterCommitPayload() {
        when(orders.save(any(SalesOrder.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 51L));

        SalesOrder result = service.create(7L, "BE", "DAP");

        assertEquals(51L, result.id());
        verify(activityLog).record(ActivityLogService.ACTION_CREATED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "51", "ENR-2026-0001",
                "Offerte aangemaakt");
        verify(creationPush).fire(new SalesCreationPushNotifier.Ready(
                SalesCreationPushNotifier.Kind.QUOTE_CREATED, 51L, "ENR-2026-0001", null, EMRE));

        ArgumentCaptor<QuoteEvent> event = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history).add(event.capture());
        assertEquals("Emre", event.getValue().actor());
        assertEquals("Offerte opgemaakt", event.getValue().summary());
    }

    @Test
    void websiteRequestKeepsItsDedicatedNotifierAndDoesNotPretendToBeAStaffAction() {
        when(orders.save(any(SalesOrder.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 52L));

        service.createWebsiteRequest(7L, "BE", "DAP");

        verify(activityLog, never()).record(any(), any(), any(), any(), any());
        verify(creationPush, never()).fire(any());
        ArgumentCaptor<QuoteEvent> event = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history).add(event.capture());
        assertNull(event.getValue().actor());
    }

    @Test
    void invoiceFromQuoteRecordsOnePrivateSafeActivityAndQueuesActorAwarePush() {
        SalesOrder source = quote(41L, "ENR-2026-0041");
        when(orders.findById(41L)).thenReturn(Optional.of(source));
        when(orders.findAll()).thenReturn(List.of(source));
        when(orders.save(any(SalesOrder.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 61L));

        service.createInvoiceFrom(41L);

        verify(orders).lockById(41L);
        verify(activityLog).record(ActivityLogService.ACTION_CREATED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "61", "F-2026-0001",
                "Factuur aangemaakt vanuit offerte");
        verify(creationPush).fire(new SalesCreationPushNotifier.Ready(
                SalesCreationPushNotifier.Kind.INVOICE_FROM_QUOTE_CREATED,
                61L, "F-2026-0001", "ENR-2026-0041", EMRE));

        ArgumentCaptor<QuoteEvent> events = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history, org.mockito.Mockito.times(2)).add(events.capture());
        assertEquals(List.of("Emre", "Emre"),
                events.getAllValues().stream().map(QuoteEvent::actor).toList());
    }

    @Test
    void duplicateIsAStaffCreationWithActorHistoryAuditAndAfterCommitPush() {
        SalesOrder source = quote(41L, "ENR-2026-0041");
        when(orders.findById(41L)).thenReturn(Optional.of(source));
        when(orders.findAll()).thenReturn(List.of(source));
        when(orders.save(any(SalesOrder.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 62L));

        SalesOrder duplicate = service.duplicate(41L);

        assertEquals("ENR-2026-0042", duplicate.number());
        verify(activityLog).record(ActivityLogService.ACTION_DUPLICATED,
                SalesOrderService.SALES_ORDER_ACTIVITY_TYPE, "62", "ENR-2026-0042",
                "Offerte gedupliceerd");
        verify(creationPush).fire(new SalesCreationPushNotifier.Ready(
                SalesCreationPushNotifier.Kind.QUOTE_DUPLICATED,
                62L, "ENR-2026-0042", "ENR-2026-0041", EMRE));

        ArgumentCaptor<QuoteEvent> event = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history).add(event.capture());
        assertEquals("Emre", event.getValue().actor());
        assertEquals("Offerte gekopieerd vanuit ENR-2026-0041", event.getValue().summary());
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

    private static SalesOrder quote(long id, String number) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, number, 7L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, null, MarkupMode.PRODUCT,
                new BigDecimal("45"), null, null, null, null, null, 0,
                null, null, null, null, DeliveryTermsState.VOLLEDIG,
                FreightState.BEREKEND, null, LoadMode.PALLETS,
                PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.OFFERTE, null, null, null, null, List.of(), List.of());
    }

    private static SalesOrder withId(SalesOrder order, long id) {
        return new SalesOrder(id, order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(),
                order.freightCarrierId(), order.freightCarrierExtraEur(), order.docType(),
                order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(), order.lines(), order.pallets());
    }
}
