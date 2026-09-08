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
        when(products.list()).thenReturn(List.of(product(9L, "Rood"), product(10L, "Wit")));

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
    void auctionSettlementRecoversOurFinancedCostAndProfitSharePerProduct() {
        SalesOrder costInvoice = partnerInvoice(65L);
        when(orders.findById(65L)).thenReturn(Optional.of(costInvoice));

        /* The partner paid the whole landed cost up front, so only the profit share is left:
           red fetched 5 000 on a cost of 3 000, white fetched 400 on a cost of 500. */
        SalesOrder settlement = service.createAuctionSettlement(new SalesOrderService.AuctionSettlementRequest(
                null, null, "PO-2026-008", 65L, BigDecimal.ZERO, new BigDecimal("50"),
                List.of(new SalesOrderService.AuctionLine(9L, 40, new BigDecimal("5000.00"), new BigDecimal("75.00")),
                        new SalesOrderService.AuctionLine(10L, 10, new BigDecimal("400.00"), new BigDecimal("50.00"))),
                "Veiling Aalsmeer week 38"));

        assertEquals(DocumentType.FACTUUR, settlement.docType());
        assertTrue(settlement.partnerSettlement());
        assertEquals(7L, settlement.customerId());
        assertEquals(13L, settlement.partnerPurchaseOrderId(), "the container comes from the source document");
        assertEquals(new BigDecimal("50"), settlement.partnerSharePct());
        assertEquals(2, settlement.lines().size());
        assertEquals(new BigDecimal("25.0000"), settlement.lines().get(0).unitPriceEur(), "half of 2 000 profit over 40 pieces");
        assertEquals(new BigDecimal("0.0000"), settlement.lines().get(1).unitPriceEur(), "a loss is never invoiced");
        assertEquals(FreightState.AANGEVULD, settlement.freight());
        assertTrue(settlement.notes().contains("veiling € 5.000,00 − kost € 3.000,00 = winst € 2.000,00 · ons deel € 1.000,00"), settlement.notes());
        assertTrue(settlement.notes().contains("verlies € 100,00 · ons deel € 0,00"), settlement.notes());
        assertEquals("Veiling Aalsmeer week 38", settlement.internalNotes());

        ArgumentCaptor<QuoteEvent> events = ArgumentCaptor.forClass(QuoteEvent.class);
        verify(history, times(2)).add(events.capture());
        assertEquals(65L, events.getAllValues().get(1).salesOrderId());
        assertEquals(QuoteEvent.Type.GEFACTUREERD, events.getAllValues().get(1).type());
    }

    @Test
    void auctionSettlementWithoutACostDocumentRecoversWhatWeFinanced() {
        /* We financed the whole container: the partner pays the cost back plus half the profit. */
        SalesOrder settlement = service.createAuctionSettlement(new SalesOrderService.AuctionSettlementRequest(
                7L, 21L, "PO-2026-021", null, new BigDecimal("100"), new BigDecimal("50"),
                List.of(new SalesOrderService.AuctionLine(9L, 40, new BigDecimal("5000.00"), new BigDecimal("75.00"))),
                null));

        assertEquals(21L, settlement.partnerPurchaseOrderId());
        assertEquals(new BigDecimal("100.0000"), settlement.lines().get(0).unitPriceEur(), "75 cost plus 25 profit share per piece");
        assertTrue(settlement.notes().startsWith("Veilingafrekening PO-2026-021 · 100 % van de gelande kost terug + 50 % van de winst"), settlement.notes());
        assertNull(settlement.internalNotes());
        verify(history, times(1)).add(any(QuoteEvent.class));
    }

    @Test
    void auctionSettlementRefusesEmptyStatementsAndBadPercentages() {
        assertThrows(BusinessRuleException.class, () -> service.createAuctionSettlement(
                new SalesOrderService.AuctionSettlementRequest(7L, 21L, null, null, BigDecimal.ZERO, new BigDecimal("50"), List.of(), null)));
        assertThrows(BusinessRuleException.class, () -> service.createAuctionSettlement(
                new SalesOrderService.AuctionSettlementRequest(7L, 21L, null, null, new BigDecimal("120"), new BigDecimal("50"),
                        List.of(new SalesOrderService.AuctionLine(9L, 1, BigDecimal.TEN, BigDecimal.ONE)), null)));
        assertThrows(BusinessRuleException.class, () -> service.createAuctionSettlement(
                new SalesOrderService.AuctionSettlementRequest(null, 21L, null, null, BigDecimal.ZERO, new BigDecimal("50"),
                        List.of(new SalesOrderService.AuctionLine(9L, 1, BigDecimal.TEN, BigDecimal.ONE)), null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aContainerBecomesAPartnerQuoteInOneGo() {
        be.enrosed.sourcing.application.PurchaseOrderService sourcing = mock(be.enrosed.sourcing.application.PurchaseOrderService.class);
        be.enrosed.sourcing.domain.PurchaseOrder container = new be.enrosed.sourcing.domain.PurchaseOrder(
                13L, "PO-2026-008", null, 1L,
                LocalDate.of(2026, 8, 19), be.enrosed.sourcing.domain.PurchaseOrderStatus.CONCEPT,
                be.enrosed.sourcing.domain.ContainerType.FORTY_HQ,
                new BigDecimal("0.1400"), new BigDecimal("0.8900"), new BigDecimal("0.8900"),
                new BigDecimal("3800.00"), new BigDecimal("450.00"), be.enrosed.shared.Currency.USD,
                new BigDecimal("1250.00"), new BigDecimal("5.0"), new BigDecimal("2500.00"),
                be.enrosed.sourcing.domain.Allocation.CBM, be.enrosed.sourcing.domain.Allocation.VALUE,
                be.enrosed.sourcing.domain.Allocation.CBM, be.enrosed.sourcing.domain.Allocation.VALUE,
                "Ningbo", "Rotterdam", null, true,
                null, null, null, null,
                be.enrosed.sourcing.domain.PaymentTerms.DEPOSIT_30_40_30, null, null, "",
                List.of(new be.enrosed.sourcing.domain.PurchaseOrderLine(1L, 9L, 40, new BigDecimal("19.20"), null, null, null)))
                .withInspectionCost(new BigDecimal("150"))
                .withOtherCosts(List.of(new be.enrosed.sourcing.domain.OtherCost("Fumigatie", new BigDecimal("80"))));
        be.enrosed.sourcing.domain.LandedCost.Line costLine = new be.enrosed.sourcing.domain.LandedCost.Line(
                9L, "Rood", 40, 10, new BigDecimal("1.36"),
                new BigDecimal("768.00"), new BigDecimal("683.52"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "test", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("771.45"),
                new BigDecimal("19.2863"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        be.enrosed.sourcing.domain.LandedCost costing = new be.enrosed.sourcing.domain.LandedCost(List.of(costLine), null, null);
        when(sourcing.get(13L)).thenReturn(container);
        when(sourcing.calculate(container)).thenReturn(costing);
        Instance<be.enrosed.sourcing.application.PurchaseOrderService> sourcingInstance = mock(Instance.class);
        when(sourcingInstance.isResolvable()).thenReturn(true);
        when(sourcingInstance.get()).thenReturn(sourcing);
        service.purchaseOrders = sourcingInstance;
        when(orders.save(any(SalesOrder.class))).thenAnswer(call -> withId(call.getArgument(0), 70L));

        SalesOrder quote = service.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(
                13L, 7L, "COST", BigDecimal.ZERO, true, new BigDecimal("50"), new BigDecimal("100"), true, List.of(0), null));

        assertEquals(DocumentType.OFFERTE, quote.docType());
        assertEquals(7L, quote.customerId());
        assertEquals(new BigDecimal("19.2863"), quote.lines().get(0).unitPriceEur(), "the container's landed cost to the cent");
        assertEquals(40, quote.lines().get(0).quantity());
        assertTrue(quote.extraLines().isEmpty(), "the inspection and other costs sit inside the landed piece price");
        assertEquals(new BigDecimal("19.2863"), quote.lines().get(0).unitCostEur(), "the line remembers what the container cost us");
        assertEquals(FreightState.AANGEVULD, quote.freight());
        assertEquals(FreightPricingStrategy.FIXED, quote.freightPricingStrategy(), "no carrier tariff on top of the landed cost");
        assertEquals(BigDecimal.ZERO, quote.manualFreightEur());
        assertEquals(13L, quote.partnerPurchaseOrderId());
        assertEquals(new BigDecimal("50"), quote.partnerSharePct());
        assertEquals("PARTNER", quote.salesChannel());
        assertTrue(quote.internalNotes().startsWith("Partnercontainer PO-2026-008: goederen aan 100 % van onze gelande kostprijs"), quote.internalNotes());
        assertNull(quote.notes() == null || quote.notes().isBlank() ? null : quote.notes(), "nothing customer-facing is written");

        /* Half the cost up front: the lines and the separate costs follow. */
        SalesOrder half = service.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(
                13L, 7L, "COST", BigDecimal.ZERO, true, new BigDecimal("50"), new BigDecimal("50"), true, List.of(), null));
        assertEquals(new BigDecimal("9.6432"), half.lines().get(0).unitPriceEur());
        assertEquals(new BigDecimal("19.2863"), half.lines().get(0).unitCostEur(), "half the price, the whole cost");
        assertTrue(half.extraLines().isEmpty());

        /* Customer prices: no landed cost needed, no partner deal, ordinary freight. */
        SalesOrder plain = service.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(
                13L, 7L, "CUSTOMER", null, true, null, null, false, List.of(), null));
        assertNull(plain.lines().get(0).unitPriceEur());
        assertEquals(new BigDecimal("19.2863"), plain.lines().get(0).unitCostEur(), "customer prices, still the container's cost");
        assertNull(plain.partnerPurchaseOrderId());
        assertEquals(FreightState.BEREKEND, plain.freight());
        assertEquals("DIRECT", plain.salesChannel());

        assertThrows(BusinessRuleException.class, () -> service.createFromPurchaseOrder(
                new SalesOrderService.FromPurchaseOrderRequest(13L, null, "COST", null, false, null, null, false, List.of(), null)));
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

        SalesOrder carried = rebuilt.carrying(deal.asPartnerSettlement());
        assertTrue(carried.partnerSettlement(), "the settlement flag travels along");
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

    private static be.enrosed.catalog.domain.Product product(long id, String name) {
        return new be.enrosed.catalog.domain.Product(id, "SKU-" + id, name,
                be.enrosed.catalog.domain.Dimensions.empty(), null, null, 1L, 1L, true,
                be.enrosed.catalog.domain.Barcodes.none(), null,
                new be.enrosed.catalog.domain.Carton(new be.enrosed.catalog.domain.Dimensions(
                        new BigDecimal("40"), new BigDecimal("40"), new BigDecimal("20")), 10, new BigDecimal("5")),
                BigDecimal.ZERO, be.enrosed.shared.Currency.USD, BigDecimal.ZERO,
                BigDecimal.ONE, "test", new BigDecimal("45"), null, 100, List.of(), List.of());
    }

    /** A saved copy with an id, keeping the free lines, the deal and the channel. */
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
                order.goodsShippedAt(), order.lines(), order.pallets()).carrying(order);
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
