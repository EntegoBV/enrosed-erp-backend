package be.enrosed.sales.application;

import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.WebOrders.Row;
import be.enrosed.sales.application.WebOrders.TermsState;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesExtraLine;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A website order is invoiced without a second signature only at what the
 * customer ordered or approved. The ways around that which a review found:
 * a discount on the whole order, a changed order tier table, extra lines
 * that cancel out, other freight on the parts of a split, and a copy of the
 * draft invoice.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class WebOrderDiscountGuardTest {
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject SalesSplits splits;
    @Inject WebOrders webOrders;
    @Inject DiscountTierService tiers;
    @Inject StaffWebOrderRevision presented;

    private final Shop shop = new Shop();

    @AfterEach
    void removeRows() {
        presented.set(null);
        shop.remove();
    }

    @Test
    void anExtraDiscountOnTheOrderNeedsTheCustomersApproval() {
        Shop.Placed order = shop.place();
        assertMoney("1320.00", price(order.id()).totals().total());

        sales.update(order.id(), withExtraDiscount(order(order.id()), "10"));

        PricedOrder now = price(order.id());
        assertMoney("1200.00", now.totals().total());
        assertNotEquals(row(order.id()).orderedTerms(), WebOrderTerms.of(now));
        assertEquals(TermsState.ORDER_DIFFERENT, state(order.id()));
        String refusal = refusal(() -> sales.createInvoiceFrom(order.id()));
        assertTrue(refusal.contains("Korting op de hele bestelling: besteld € 0,00, nu € 120,00 (extra korting 10 %)"), refusal);
        assertTrue(refusal.contains("Totaal excl. btw: besteld € 1.320,00, nu € 1.200,00"), refusal);

        /* Taking the discount off again makes it the order the customer placed. */
        sales.update(order.id(), withExtraDiscount(order(order.id()), null));
        assertEquals(TermsState.ORDER_EQUAL, state(order.id()));
    }

    @Test
    void anExtraDiscountOnTheDraftInvoiceIsNotIssued() {
        Shop.Placed order = shop.place();
        SalesOrder invoice = shop.inTransaction(() -> sales.createInvoiceFrom(order.id()));

        sales.update(invoice.id(), withExtraDiscount(order(invoice.id()), "25"));

        String refusal = refusal(() -> sales.issueInvoice(invoice.id()));
        assertTrue(refusal.startsWith("Deze factuur wijkt af van wat de klant bestelde"), refusal);
        assertTrue(refusal.contains("extra korting 25 %"), refusal);
        assertEquals(QuoteStatus.CONCEPT, order(invoice.id()).status());

        sales.update(invoice.id(), withExtraDiscount(order(invoice.id()), null));
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(invoice.id()).status());
        assertMoney("1320.00", price(invoice.id()).totals().total());
    }

    @Test
    void afterTheApprovalAnExtraDiscountOnTheDraftInvoiceIsNotIssued() {
        long orderId = approvedAtElevenEuro();
        SalesOrder invoice = shop.inTransaction(() -> sales.createInvoiceFrom(orderId));

        sales.update(invoice.id(), withExtraDiscount(order(invoice.id()), "50"));

        assertTrue(refusal(() -> sales.issueInvoice(invoice.id()))
                .startsWith("Deze factuur wijkt af van de versie waarmee de klant akkoord ging"));
        assertEquals(QuoteStatus.CONCEPT, order(invoice.id()).status());
    }

    @Test
    void aChangedOrderTierTableIsNoLongerTheOrderTheCustomerPlaced() {
        List<DiscountTier> before = shop.inTransaction(() -> tiers.list(TierScope.ORDER));
        try {
            shop.inTransaction(() -> tiers.replace(TierScope.ORDER, List.of(
                    new DiscountTier(null, TierScope.ORDER, 0, BigDecimal.ZERO),
                    new DiscountTier(null, TierScope.ORDER, 60, new BigDecimal("10")))));
            Shop.Placed order = shop.place();
            PricedOrder ordered = price(order.id());
            assertMoney("120.00", ordered.totals().orderDiscountAmount());
            assertEquals(TermsState.ORDER_EQUAL, state(order.id()));

            /* The table loses its 10 %: the same document now costs more than the customer ordered. */
            shop.inTransaction(() -> tiers.replace(TierScope.ORDER, List.of(new DiscountTier(null, TierScope.ORDER, 0, BigDecimal.ZERO))));

            PricedOrder now = price(order.id());
            assertEquals(1, now.totals().total().compareTo(ordered.totals().total()));
            assertEquals(TermsState.ORDER_DIFFERENT, state(order.id()));
            String refusal = refusal(() -> sales.createInvoiceFrom(order.id()));
            assertTrue(refusal.contains("Korting op de hele bestelling: besteld € 120,00, nu € 0,00"), refusal);
        } finally {
            shop.inTransaction(() -> tiers.replace(TierScope.ORDER, before.stream()
                    .map(tier -> new DiscountTier(null, TierScope.ORDER, tier.minQuantity(), tier.percent())).toList()));
        }
    }

    @Test
    void extraLinesThatCancelOutAreStillLinesNobodyOrdered() {
        Shop.Placed order = shop.place();

        sales.update(order.id(), order(order.id()).withExtraLines(List.of(
                new SalesExtraLine("Verpakkingstoeslag", BigDecimal.ONE, new BigDecimal("50.00")),
                new SalesExtraLine("Commerciele korting", BigDecimal.ONE, new BigDecimal("-50.00")))));

        assertMoney("1320.00", price(order.id()).totals().total());
        assertEquals(TermsState.ORDER_DIFFERENT, state(order.id()));
        String refusal = refusal(() -> sales.createInvoiceFrom(order.id()));
        assertTrue(refusal.contains("Extra regels: niet besteld, nu 2 regels van samen € 0,00"), refusal);
    }

    @Test
    void theTwoDeliveriesOfAnApprovedOrderOnlyDivideWhatWasApproved() {
        long orderId = approvedAtElevenEuro();
        assertMoney("1440.00", price(orderId).totals().total());
        SalesOrder invoice = shop.inTransaction(() -> sales.createInvoiceFrom(orderId));
        long lineId = order(invoice.id()).lines().getFirst().id();

        /* Other freight per part, or another discount, is refused at the split itself. */
        String moreFreight = refusal(() -> splits.preview(invoice.id(), selection(lineId, "300.00", "300.00", null, null)));
        assertTrue(moreFreight.contains("blijven samen gelijk aan de versie waarmee de klant akkoord ging (verschil excl. btw € 480.00)"),
                moreFreight);
        assertTrue(refusal(() -> splits.preview(invoice.id(), selection(lineId, "60.00", "60.00", "40", "40")))
                .contains("laat de extra korting ongewijzigd"));
        assertNull(splits.fulfillment(order(invoice.id())), "nothing was split");

        /* The approved freight divided over the two parts is the approved order. */
        SalesSplits.Request divided = selection(lineId, "80.00", "40.00", null, null);
        String token = splits.preview(invoice.id(), divided).previewToken();
        SalesSplits.Result parts = splits.split(invoice.id(), new SalesSplits.Request(divided.lines(), null,
                divided.currentFreightEur(), divided.laterFreightEur(), null, null, token, divided.requestId()));
        assertMoney("1440.00", price(parts.current().id()).totals().total().add(price(parts.later().id()).totals().total()));

        /* Freight and extra discount of a part stay editable; a part that no longer is its share does not leave. */
        long first = parts.current().id();
        sales.update(first, shop.edited(order(first), 60, "11.00", "300.00"));
        String changed = refusal(() -> sales.issueInvoice(first));
        assertTrue(changed.startsWith("Deze deellevering wijkt af van de verdeling van de versie waarmee de klant akkoord ging: "
                + "transport € 80.00 (nu € 300.00)"), changed);
        sales.update(first, withExtraDiscount(shop.edited(order(first), 60, "11.00", "80.00"), "20"));
        assertTrue(refusal(() -> sales.issueInvoice(first)).startsWith("Deze deellevering wijkt af"));
        assertEquals(QuoteStatus.CONCEPT, order(first).status());

        sales.update(first, withExtraDiscount(order(first), null));
        assertEquals(QuoteStatus.UITGEREIKT, sales.issueInvoice(first).status());

        /* The later part has no source quote of its own and is held to its share all the same. */
        long later = parts.later().id();
        assertNull(order(later).sourceQuoteId());
        sales.update(later, shop.edited(order(later), 60, "11.00", "400.00"));
        assertTrue(refusal(() -> sales.issueInvoice(later)).contains("transport € 40.00 (nu € 400.00)"));
    }

    @Test
    void theDraftInvoiceOfAnOrderIsNotCopiedButTheOrderIs() {
        Shop.Placed order = shop.place();
        SalesOrder invoice = shop.inTransaction(() -> sales.createInvoiceFrom(order.id()));

        assertEquals("De factuur van een websitebestelling kopieer je niet. "
                + "Maak een nieuwe kopie van de offerte en verstuur die ter goedkeuring.", refusal(() -> sales.duplicate(invoice.id())));

        SalesOrder copy = shop.inTransaction(() -> sales.duplicate(order.id()));
        assertTrue(!copy.isInvoice());
        assertTrue(shop.inTransaction(() -> webOrders.find(copy.id())).isEmpty(), "a plain quote that goes out for approval");
    }

    // ------------------------------------------------------------------ helpers

    /** Staff raise the price to 11,00, send, and the customer approves in the portal: 1320,00 goods and 120,00 freight. */
    private long approvedAtElevenEuro() {
        Shop.Placed order = shop.place();
        sales.update(order.id(), shop.edited(order(order.id()), 120, "11.00", "120.00"));
        quotes.send(order.id(), null);
        quotes.acceptByCustomer(order(order.id()).portalToken(), "Jan Besteller", null);
        assertEquals(QuoteStatus.GEACCEPTEERD, order(order.id()).status());
        return order.id();
    }

    private static SalesSplits.Request selection(long lineId, String currentFreight, String laterFreight,
                                                 String currentDiscount, String laterDiscount) {
        return new SalesSplits.Request(List.of(new SalesSplits.Choice(lineId, 60)), null,
                new BigDecimal(currentFreight), new BigDecimal(laterFreight),
                currentDiscount == null ? null : new BigDecimal(currentDiscount),
                laterDiscount == null ? null : new BigDecimal(laterDiscount), null, UUID.randomUUID().toString());
    }

    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }

    private PricedOrder price(long id) {
        return shop.inTransaction(() -> sales.price(sales.get(id)));
    }

    private Row row(long id) {
        return shop.inTransaction(() -> webOrders.find(id).orElseThrow());
    }

    private TermsState state(long id) {
        return WebOrders.termsState(order(id), row(id), WebOrderTerms.of(price(id)));
    }

    private static String refusal(Runnable work) {
        return assertThrows(BusinessRuleException.class, work::run).getMessage();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static SalesOrder withExtraDiscount(SalesOrder order, String percent) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(), order.orderDate(),
                order.validUntil(), order.status(), order.incoterm(), order.paymentTerms(), order.notes(),
                order.markupMode(), order.orderMarkupPct(), percent == null ? null : new BigDecimal(percent),
                percent == null ? null : "Speciale korting",
                order.portalToken(), order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(), order.deliveryTerms(),
                order.freight(), order.manualFreightEur(), order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(), order.freightCarrierId(), order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(), order.goodsShippedAt(),
                order.lines(), order.pallets()).carrying(order);
    }
}
