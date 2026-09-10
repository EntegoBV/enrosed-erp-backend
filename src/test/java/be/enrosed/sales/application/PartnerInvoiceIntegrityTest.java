package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartnerInvoiceIntegrityTest {
    private final Map<Long, SalesOrder> stored = new HashMap<>();
    private SalesOrderService service;
    private SalesRepositories.Orders orders;
    private SalesRepositories.Events events;
    private PartnerSettlements settlements;
    private CustomerService customers;
    private ProductService products;

    @BeforeEach
    void setUp() {
        orders = mock(SalesRepositories.Orders.class);
        events = mock(SalesRepositories.Events.class);
        when(orders.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(orders.findAll()).thenAnswer(call -> List.copyOf(stored.values()));
        when(orders.save(any())).thenAnswer(call -> {
            SalesOrder order = call.getArgument(0);
            if (order.id() == null) order = withId(order, 100L + stored.size());
            stored.put(order.id(), order); return order;
        });
        customers = mock(CustomerService.class);
        when(customers.get(7L)).thenReturn(new Customer(7L, "Partner", "Partner", "partner@example.test",
                null, "BE0000000000", "BE", Language.NL, "Straat 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var countries = mock(CountryService.class);
        when(countries.find("BE")).thenReturn(country());
        products = mock(ProductService.class);
        when(products.list()).thenReturn(List.of(product()));
        var settings = mock(SalesSettings.class);
        when(settings.pallet(any(), any())).thenReturn(PalletSpec.euro());
        service = new SalesOrderService(orders, products, countries, mock(DiscountTierService.class),
                mock(SalesPricingCalculator.class), new PalletCalculator(), settings, customers,
                mock(VatCalculator.class), events, mock(SalesRepositories.Revisions.class),
                mock(be.enrosed.shipping.application.CarrierRepository.class));
        settlements = mock(PartnerSettlements.class);
        service.partnerSettlements = instance(settlements);
    }

    @Test
    void issuedInvoiceCanBeSentLaterAndPaidInvoiceKeepsItsReceiptTime() {
        var draft = put(invoice(1));
        var issued = service.issueInvoice(draft.id());
        assertEquals(QuoteStatus.UITGEREIKT, issued.status());
        assertNull(issued.sentAt());
        var sent = service.markInvoiceSent(issued.id());
        assertEquals(QuoteStatus.VERZONDEN, sent.status());
        assertNotNull(sent.sentAt());
        assertEquals(sent, service.markInvoiceSent(sent.id()), "repeated mark-sent does not change time or audit");

        Instant paidAt = Instant.parse("2026-08-10T12:24:00Z");
        var paid = put(invoice(2).withPaymentState(QuoteStatus.BETAALD, paidAt));
        var paidSent = service.markInvoiceSent(paid.id());
        assertEquals(QuoteStatus.BETAALD, paidSent.status());
        assertEquals(paidAt, paidSent.paidAt());
        assertNotNull(paidSent.sentAt());
        verify(events, times(3)).add(any());
    }

    @Test
    void issuingAnotherAdvanceAfterTheSettlementIsBlockedButExistingIssuedAdvanceCanBeSent() {
        var draft = put(invoice(1));
        put(invoice(2).asPartnerSettlement());
        assertThrows(BusinessRuleException.class, () -> service.issueInvoice(draft.id()));
        assertThrows(BusinessRuleException.class, () -> service.markInvoiceSent(draft.id()));
        put(draft.withPaymentState(QuoteStatus.UITGEREIKT, null));
        assertEquals(QuoteStatus.VERZONDEN, service.markInvoiceSent(draft.id()).status(),
                "an advance already credited by the settlement may still be delivered to the partner");
    }

    @Test
    void anIssuedAdvanceCannotMoveItsReceiptsToAnotherContainerOrChangeProfitSharing() {
        var invoice = put(invoice(1).withPaymentState(QuoteStatus.UITGEREIKT, null));
        assertThrows(BusinessRuleException.class, () -> service.setPartnerDeal(invoice.id(),
                new SalesOrderService.PartnerDealRequest(14L, new BigDecimal("50"), null)));
        assertThrows(BusinessRuleException.class, () -> service.setPartnerDeal(invoice.id(),
                new SalesOrderService.PartnerDealRequest(13L, new BigDecimal("40"), null)));
        assertEquals(13L, service.setPartnerDeal(invoice.id(),
                new SalesOrderService.PartnerDealRequest(13L, new BigDecimal("50.00"), null)).linkedPurchaseOrderId());
    }

    @Test
    void aSettlementSnapshotProtectsItsFreightAndItsPurposeEvenWhileDraft() {
        var settlement = put(invoice(1).asPartnerSettlement());
        when(settlements.find(settlement.id())).thenReturn(new PartnerSettlements.Snapshot(
                new BigDecimal("150"), new BigDecimal("100"), new BigDecimal("100")));
        assertThrows(BusinessRuleException.class, () -> service.updateFreight(settlement.id(),
                FreightState.AANGEVULD, BigDecimal.TEN, FreightPricingStrategy.FIXED, null, null));
        assertThrows(BusinessRuleException.class, () -> service.setPartnerDeal(settlement.id(),
                new SalesOrderService.PartnerDealRequest(null, null, null, SalesPurpose.STANDARD, null)));
    }

    @Test
    void sentInvoicesCannotChangeTheirCashClaimThroughTheFreightShortcut() {
        var invoice = put(invoice(1).withPurpose(SalesPurpose.STANDARD, null, SalesPaymentPlan.FULL)
                .withPaymentState(QuoteStatus.VERZONDEN, null));
        assertThrows(BusinessRuleException.class, () -> service.updateFreight(invoice.id(),
                FreightState.AANGEVULD, BigDecimal.TEN, FreightPricingStrategy.FIXED, null, null));
    }

    @Test
    void fullUpdateCannotSplitThePartnerAndPurchaseSourceIdentifiers() {
        var draft = put(invoice(1));
        assertThrows(BusinessRuleException.class, () -> service.update(draft.id(),
                draft.withPartnerDeal(14L, new BigDecimal("50"))));
    }

    @Test
    void advanceSettlementConflictIsRejectedBeforeAnyEmailLeaves() {
        var draft = put(invoice(1));
        put(invoice(2).asPartnerSettlement());
        var mailer = mock(be.enrosed.sales.application.port.out.QuoteMailer.class);
        var renderer = mock(be.enrosed.sales.application.port.out.QuoteDocumentRenderer.class);
        var quotes = new QuoteService(orders, mock(SalesRepositories.Revisions.class), service, customers,
                renderer, mailer, mock(ProductService.class), events,
                mock(be.enrosed.shared.company.CompanyProfileService.class), mock(be.enrosed.push.WebPushNotifier.class));
        assertThrows(BusinessRuleException.class, () -> quotes.send(draft.id(), null));
        verifyNoInteractions(mailer, renderer);
    }

    @Test
    void invoiceEmailAsksOnlyForTheOpenAmountAndExplainsSettledOrCreditBalances() {
        var company = mock(be.enrosed.shared.company.CompanyProfileService.class);
        when(company.get()).thenReturn(be.enrosed.shared.company.CompanyProfile.empty());
        var quotes = new QuoteService(orders, mock(SalesRepositories.Revisions.class), service, customers,
                mock(be.enrosed.sales.application.port.out.QuoteDocumentRenderer.class),
                mock(be.enrosed.sales.application.port.out.QuoteMailer.class), mock(ProductService.class), events,
                company, mock(be.enrosed.push.WebPushNotifier.class));
        var incoming = mock(IncomingPaymentService.class);
        quotes.incomingPayments = instance(incoming);
        var invoice = invoice(1);
        var priced = new PricedOrder(List.of(), null, null, List.of());
        when(incoming.summary(invoice, priced)).thenReturn(summary("80.67", "0", "0"));
        String partial = quotes.invoicePaymentSentence(invoice, priced, customers.get(7L));
        assertTrue(partial.contains("80,67"), partial);
        assertFalse(partial.contains("121,00"), partial);
        assertTrue(partial.contains("1/3"), partial);
        when(incoming.summary(invoice, priced)).thenReturn(summary("0", "0", "5"));
        String overpaid = quotes.invoicePaymentSentence(invoice, priced, customers.get(7L));
        assertTrue(overpaid.contains("Geen betaling meer nodig"), overpaid);
        assertTrue(overpaid.contains("5,00"), overpaid);
        when(incoming.summary(invoice, priced)).thenReturn(summary("0", "25", "0"));
        String credit = quotes.invoicePaymentSentence(invoice, priced, customers.get(7L));
        assertTrue(credit.contains("Tegoed"), credit);
        assertTrue(credit.contains("25,00"), credit);
    }

    private static SalesPaymentSummary summary(String remaining, String credit, String overpaid) {
        return new SalesPaymentSummary(new BigDecimal("121"), new BigDecimal("40.33"), new BigDecimal(remaining),
                new BigDecimal(overpaid), new BigDecimal(credit), SalesPaymentSummary.Status.PARTIAL, List.of(), List.of(), false);
    }

    @Test
    void ordinaryCopiesKeepThePurchaseSourceButPartnerClaimsCannotBeCopied() {
        var standard = put(invoice(1).withPurpose(SalesPurpose.STANDARD, 14L, SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION));
        var copy = service.duplicate(standard.id());
        assertEquals(SalesPurpose.STANDARD, copy.purpose());
        assertEquals(14L, copy.linkedPurchaseOrderId());
        assertEquals(SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION, copy.paymentPlan());
        var advance = put(invoice(2).withPurpose(SalesPurpose.PARTNER_ADVANCE, 13L, SalesPaymentPlan.FULL));
        int before = stored.size();
        assertThrows(BusinessRuleException.class, () -> service.duplicate(advance.id()));
        assertEquals(before, stored.size(), "copying must not reserve a second claim even when financing capacity remains");
        var settlement = put(invoice(3).asPartnerSettlement());
        assertThrows(BusinessRuleException.class, () -> service.duplicate(settlement.id()));
    }

    @Test
    void finalInvoiceRetainsLegacyShipmentTimeWithoutDeductingStockAgain() {
        Instant oldShipment = Instant.parse("2026-07-01T13:45:00Z");
        put(invoice(1, oldShipment));
        var finalInvoice = put(invoice(2).asPartnerSettlement().withPaymentState(QuoteStatus.UITGEREIKT, null));
        var shipped = service.shipGoods(finalInvoice.id());
        assertEquals(oldShipment, shipped.goodsShippedAt());
        verify(products, never()).sellStock(anyLong(), anyInt(), anyString());
        assertThrows(BusinessRuleException.class, () -> service.shipGoods(finalInvoice.id()));
    }

    @Test
    void ambiguousLegacyStockAndRepeatedShippingNeverDeductAgain() {
        put(invoice(1, Instant.parse("2026-07-01T13:45:00Z")));
        put(invoice(2, Instant.parse("2026-07-02T13:45:00Z")));
        var finalInvoice = put(invoice(3).asPartnerSettlement().withPaymentState(QuoteStatus.UITGEREIKT, null));
        assertThrows(BusinessRuleException.class, () -> service.shipGoods(finalInvoice.id()));
        verify(products, never()).sellStock(anyLong(), anyInt(), anyString());
        stored.remove(1L); stored.remove(2L);
        service.shipGoods(finalInvoice.id());
        assertThrows(BusinessRuleException.class, () -> service.shipGoods(finalInvoice.id()));
        verify(products).sellStock(9L, 4, "PARTNER-3");
        verify(orders, times(3)).lockById(3L);
    }

    @Test
    void partnerAmountHasNoAutomaticHandlingFeeOrMinimumAndRetainsExactPieces() {
        var product = product();
        var calculator = new SalesPricingCalculator(new PalletCalculator(), new DeliveryCalculator());
        var context = new SalesPricingCalculator.Context(country(), null, PalletSpec.euro(), List.of(), List.of(), null);
        var advance = invoice(1);
        for (var order : List.of(advance, advance.asPartnerSettlement())) {
            var priced = calculator.price(order, Map.of(9L, product), context);
            assertEquals(new BigDecimal("100.00"), priced.totals().total());
            assertEquals(new BigDecimal("0.00"), priced.totals().handling());
            assertTrue(priced.validation().meetsMinimum());
            assertEquals(4, priced.totals().pieces());
        }
        var standard = calculator.price(advance.withPurpose(SalesPurpose.STANDARD, null, SalesPaymentPlan.FULL),
                Map.of(9L, product), context);
        assertEquals(new BigDecimal("35.00"), standard.totals().handling());
        assertFalse(standard.validation().meetsMinimum());
        assertEquals(6, standard.totals().pieces());
    }

    private SalesOrder put(SalesOrder order) { stored.put(order.id(), order); return order; }

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

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true); when(instance.get()).thenReturn(value); return instance;
    }

    private static Country country() {
        return new Country("BE", "België", new BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("35"), new BigDecimal("21"), 1, true);
    }

    private static SalesOrder invoice(long id) {
        return invoice(id, null);
    }

    private static SalesOrder invoice(long id, Instant shippedAt) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, "PARTNER-" + id, 7L, "BE", today, today.plusDays(30), QuoteStatus.CONCEPT,
                "DAP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO, null, null,
                null, null, null, 0, null, null, null, null, DeliveryTermsState.VOLLEDIG,
                FreightState.AANGEVULD, BigDecimal.ZERO, LoadMode.PALLETS, PalletProfile.EURO_120X80,
                null, FreightPricingStrategy.FIXED, null, null, null, DocumentType.FACTUUR,
                today.plusDays(30), null, null, shippedAt,
                List.of(new SalesOrderLine(null, 9L, 4, new BigDecimal("25"), null, null, BigDecimal.TEN)), List.of())
                .withPartnerDeal(13L, new BigDecimal("50"));
    }

    private static Product product() {
        return new Product(9L, "SKU-9", "Rose", Dimensions.empty(), null, null, 1L, 1L, true,
                Barcodes.none(), null, new Carton(new Dimensions(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN), 6, BigDecimal.ONE),
                BigDecimal.ONE, Currency.USD, BigDecimal.ZERO, BigDecimal.ONE, "test", BigDecimal.ZERO,
                null, 100, List.of(), List.of());
    }
}
