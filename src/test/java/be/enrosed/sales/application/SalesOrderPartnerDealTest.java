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
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesExtraLine;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.sales.domain.VatTreatment;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A partner co-orders a container at our landed cost, sells the goods on
 * and we settle our share of the profit afterwards. The deal must survive
 * every status or freight change and the settlement invoice must carry
 * exactly our share.
 */
class SalesOrderPartnerDealTest {

    private static final ActorRef EMRE = new ActorRef("emre", "Emre");

    private SalesRepositories.Orders orders;
    private SalesRepositories.Events history;
    private SalesPricingCalculator pricing;
    private SalesOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = mock(SalesRepositories.Orders.class);
        history = mock(SalesRepositories.Events.class);
        pricing = mock(SalesPricingCalculator.class);
        CountryService countries = mock(CountryService.class);
        CustomerService customers = mock(CustomerService.class);
        ProductService products = mock(ProductService.class);

        when(orders.save(any(SalesOrder.class))).thenAnswer(call -> call.getArgument(0));
        when(countries.find("BE")).thenReturn(country());
        when(customers.get(7L)).thenReturn(customer());
        when(products.list()).thenReturn(List.of());

        service = new SalesOrderService(orders, products, countries,
                mock(DiscountTierService.class), pricing,
                new PalletCalculator(), mock(SalesSettings.class), customers,
                mock(VatCalculator.class), history, mock(SalesRepositories.Revisions.class),
                mock(be.enrosed.shipping.application.CarrierRepository.class));

        Instance<CurrentActor> actors = mock(Instance.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(actors.isResolvable()).thenReturn(true);
        when(actors.get()).thenReturn(currentActor);
        when(currentActor.current()).thenReturn(EMRE);
        service.actor = actors;

        Instance<ActivityLogService> activities = mock(Instance.class);
        when(activities.isResolvable()).thenReturn(true);
        when(activities.get()).thenReturn(mock(ActivityLogService.class));
        service.activity = activities;
        service.salesActivityPush = mock(Event.class);
    }

    @Test
    void settlementInvoiceCarriesOurShareOfTheProfitAboveTheCostBasis() {
        SalesOrder invoice = partnerInvoice(65L);
        when(orders.findById(65L)).thenReturn(Optional.of(invoice));
        /* Goods 3 000 plus inspection and fumigation 230: the partner paid 3 230. */
        when(pricing.price(any(), any(), any())).thenReturn(priced("3000.00", "230.00"));

        SalesOrder settlement = service.createSettlement(65L, new SalesOrderService.SettlementRequest(
                new BigDecimal("5230.00"), null, "Veiling 12 sept", "Afgerekend na de veiling"));

        assertEquals(DocumentType.FACTUUR, settlement.docType());
        assertEquals(QuoteStatus.CONCEPT, settlement.status());
        assertEquals(7L, settlement.customerId());
        assertTrue(settlement.lines().isEmpty(), "no goods on the settlement invoice");
        assertEquals(1, settlement.extraLines().size());
        SalesExtraLine share = settlement.extraLines().get(0);
        assertEquals(new BigDecimal("1000.00"), share.unitPriceEur());
        assertEquals("Winstdeling veiling · Veiling 12 sept · 50 % van € 2.000,00", share.description());
        assertEquals(FreightState.AANGEVULD, settlement.freight());
        assertEquals(BigDecimal.ZERO, settlement.manualFreightEur());
        assertEquals(13L, settlement.partnerPurchaseOrderId());
        assertEquals(new BigDecimal("50"), settlement.partnerSharePct());
        assertTrue(settlement.notes().contains("kostbasis € 3.230,00"), settlement.notes());
        assertTrue(settlement.notes().endsWith("Afgerekend na de veiling"), settlement.notes());

        ArgumentCaptor<QuoteEvent> events = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history, times(2)).add(events.capture());
        assertEquals(65L, events.getAllValues().get(1).salesOrderId());
        assertEquals(QuoteEvent.Type.GEFACTUREERD, events.getAllValues().get(1).type());
    }

    @Test
    void settlementRefusesALossAndAnEmptyProceeds() {
        SalesOrder invoice = partnerInvoice(66L);
        when(orders.findById(66L)).thenReturn(Optional.of(invoice));
        when(pricing.price(any(), any(), any())).thenReturn(priced("3000.00", "230.00"));

        assertThrows(BusinessRuleException.class, () -> service.createSettlement(66L,
                new SalesOrderService.SettlementRequest(new BigDecimal("3000"), null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.createSettlement(66L,
                new SalesOrderService.SettlementRequest(null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.createSettlement(66L,
                new SalesOrderService.SettlementRequest(new BigDecimal("9000"), new BigDecimal("140"), null, null)));
    }

    @Test
    void partnerDealAndFreeLinesSurviveStatusAndFreightChanges() {
        SalesOrder invoice = partnerInvoice(67L);
        when(orders.findById(67L)).thenReturn(Optional.of(invoice));

        SalesOrder sent = service.markInvoiceSent(67L);
        assertEquals(QuoteStatus.VERZONDEN, sent.status());
        assertEquals(13L, sent.partnerPurchaseOrderId());
        assertEquals(new BigDecimal("50"), sent.partnerSharePct());
        assertEquals(2, sent.extraLines().size());

        when(orders.findById(67L)).thenReturn(Optional.of(sent));
        SalesOrder paid = service.markInvoicePaid(67L);
        assertEquals(13L, paid.partnerPurchaseOrderId());
        assertEquals(2, paid.extraLines().size());
    }

    @Test
    void carryingOnlyFillsWhatThePositionalConstructorLeftEmpty() {
        SalesOrder deal = partnerInvoice(68L);
        SalesOrder rebuilt = new SalesOrder(deal.id(), deal.number(), deal.customerId(), deal.countryCode(),
                deal.orderDate(), deal.validUntil(), QuoteStatus.BETAALD, deal.incoterm(),
                deal.paymentTerms(), deal.notes(), deal.markupMode(), deal.orderMarkupPct(),
                deal.extraDiscountPct(), deal.extraDiscountLabel(), deal.portalToken(),
                deal.sentAt(), deal.viewedAt(), deal.viewCount(), deal.decidedAt(),
                deal.signedByName(), deal.customerMessage(), deal.internalNotes(),
                deal.deliveryTerms(), deal.freight(), deal.manualFreightEur(),
                deal.loadMode(), deal.palletProfile(), deal.maxPalletHeightCm(),
                deal.freightPricingStrategy(), deal.freightRatePerCbmEur(),
                deal.freightCarrierId(), deal.freightCarrierExtraEur(),
                deal.docType(), deal.invoiceDueDate(), Instant.now(), deal.sourceQuoteId(),
                deal.goodsShippedAt(), deal.lines(), deal.pallets());
        assertNull(rebuilt.partnerPurchaseOrderId());
        assertTrue(rebuilt.extraLines().isEmpty());

        SalesOrder carried = rebuilt.carrying(deal);
        assertEquals(QuoteStatus.BETAALD, carried.status());
        assertEquals(13L, carried.partnerPurchaseOrderId());
        assertEquals(new BigDecimal("50"), carried.partnerSharePct());
        assertEquals(deal.extraLines(), carried.extraLines());

        SalesOrder cleared = rebuilt.withPartnerDeal(null, null).carrying(deal.withPartnerDeal(null, null));
        assertNull(cleared.partnerPurchaseOrderId());
    }

    @Test
    void linkingAndUnlinkingAPartnerContainerIsRecordedOnTheDocument() {
        SalesOrder plain = partnerInvoice(69L).withPartnerDeal(null, null);
        when(orders.findById(69L)).thenReturn(Optional.of(plain));

        SalesOrder linked = service.setPartnerDeal(69L,
                new SalesOrderService.PartnerDealRequest(21L, null, "PO-2026-021"));
        assertEquals(21L, linked.partnerPurchaseOrderId());
        assertEquals(new BigDecimal("50"), linked.partnerSharePct(), "half is the default share");

        when(orders.findById(69L)).thenReturn(Optional.of(linked));
        SalesOrder changed = service.setPartnerDeal(69L,
                new SalesOrderService.PartnerDealRequest(21L, new BigDecimal("40"), "PO-2026-021"));
        assertEquals(new BigDecimal("40"), changed.partnerSharePct());

        when(orders.findById(69L)).thenReturn(Optional.of(changed));
        SalesOrder cleared = service.setPartnerDeal(69L, new SalesOrderService.PartnerDealRequest(null, null, null));
        assertNull(cleared.partnerPurchaseOrderId());
        assertNull(cleared.partnerSharePct());

        assertThrows(BusinessRuleException.class, () -> service.setPartnerDeal(69L,
                new SalesOrderService.PartnerDealRequest(21L, new BigDecimal("120"), null)));

        ArgumentCaptor<QuoteEvent> events = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history, times(3)).add(events.capture());
        assertEquals("Gekoppeld aan partnercontainer PO-2026-021 · 50 % winstdeling", events.getAllValues().get(0).summary());
        assertEquals(QuoteEvent.Type.PARTNER_GEKOPPELD, events.getAllValues().get(0).type());
        assertEquals("Losgekoppeld van de partnercontainer", events.getAllValues().get(2).summary());
    }

    private static PricedOrder priced(String goods, String extra) {
        BigDecimal goodsTotal = new BigDecimal(goods);
        BigDecimal extraTotal = new BigDecimal(extra);
        PricedOrder.Totals totals = new PricedOrder.Totals(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, goodsTotal, BigDecimal.ZERO, goodsTotal, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, goodsTotal, BigDecimal.ZERO, false,
                BigDecimal.ZERO, BigDecimal.ZERO, goodsTotal.add(extraTotal), BigDecimal.ZERO, BigDecimal.ZERO,
                goodsTotal.add(extraTotal), VatTreatment.BINNENLAND, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, extraTotal);
        return new PricedOrder(List.of(), totals, new PricedOrder.Validation(BigDecimal.ZERO, true, BigDecimal.ZERO, true, true,
                List.of(), List.of(), List.of(), null), List.of());
    }

    private static SalesOrder partnerInvoice(long id) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, "F-2026-00" + id, 7L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, null, null, null, 0, null, null, null, null, DeliveryTermsState.VOLLEDIG,
                FreightState.AANGEVULD, BigDecimal.ZERO, LoadMode.PALLETS, PalletProfile.EURO_120X80,
                null, FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, today.plusDays(30), null, null, null,
                List.of(new SalesOrderLine(1L, 9L, 40, new BigDecimal("75.00"), null, null)),
                List.of())
                .withExtraLines(List.of(new SalesExtraLine("Inspectie", BigDecimal.ONE, new BigDecimal("150.00")),
                        new SalesExtraLine("Fumigatie", BigDecimal.ONE, new BigDecimal("80.00"))))
                .withPartnerDeal(13L, new BigDecimal("50"));
    }

    private static Country country() {
        return new Country("BE", "België", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("21"), 1, true);
    }

    private static Customer customer() {
        return new Customer(7L, "Frans Verhoeven", "Frans", "frans@example.test",
                null, "BE0000000000", "BE", Language.NL,
                "Veilingstraat 1", "2000", "Antwerpen", "DAP", null, null,
                LocalDate.now());
    }
}
