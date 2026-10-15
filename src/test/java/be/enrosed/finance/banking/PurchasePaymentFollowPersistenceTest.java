package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 2 (R2-2): linking one payment to one bank transaction makes the
 * payment follow it, its day and its amount, in one call.
 */
@QuarkusTest
class PurchasePaymentFollowPersistenceTest {
    @Inject PurchasePaymentSplitService splits;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    private static final LocalDate JAN_3 = LocalDate.of(2026, 1, 3);
    private static final Instant JAN_5 = Instant.parse("2026-01-05T09:00:00Z");
    private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
    private static final Instant SEP_5 = Instant.parse("2026-09-05T08:00:00Z");

    @Test
    @TestTransaction
    void aMatchingTransferGivesItsDayAndItsCents() throws Exception {
        PurchaseOrder order = container(null);
        PurchasePayment deposit = deposit(order, "10000.00", SEP_3);
        long line = line("-10000.02", SEP_5);

        var result = splits.linkPart(deposit.id(), part(line, null));

        PurchasePayment linked = only(result.payments());
        assertEquals(new BigDecimal("10000.02"), linked.amount(), "a euro payment takes the transfer's cents");
        assertEquals(new BigDecimal("10000.02"), linked.amountEur());
        assertEquals(LocalDate.of(2026, 9, 5), linked.paidOn());
        assertEquals(line, linked.bankStatementLineId());
        assertEquals(0, result.lines().getFirst().allocationDifferenceEur.signum(), "no Verschil left on the line");
        assertEquals("Bedrag en datum gevolgd van de transactie: € 10.000,02 op 05/09/2026 (was € 10.000,00 op 03/09/2026).",
                result.notice());
        String notes = purchases.get(order.id()).notes();
        assertTrue(notes.contains("Betaald 05/09/2026: € 10.000,02 aan de leverancier · Aanbetaling 30% · termijn bij bestelling."), notes);
        assertFalse(notes.contains("Betaald 03/09/2026"), notes);
        JsonNode changes = changes(order, PurchasePaymentSplitService.LINKED_SUMMARY);
        assertChange(changes, "payment.date", "2026-09-03", "2026-09-05");
        assertChange(changes, "payment.amount", "10000", "10000.02");

        /* An identical retry answers the same, writes nothing and has nothing to tell. */
        var again = splits.linkPart(deposit.id(), part(line, null));
        assertEquals(result.payments(), again.payments());
        assertNull(again.notice());
        assertEquals(notes, purchases.get(order.id()).notes());

        /* The single-line path of bank-lines follows the transfer the same way. */
        PurchasePayment balance = deposit(order, "500.00", SEP_3);
        long close = line("-499.99", SEP_5);
        var single = splits.linkLines(balance.id(), new PurchasePaymentSplitService.LinesRequest(
                List.of(new PurchasePaymentSplitService.LineRef(close, null))));
        assertEquals(new BigDecimal("499.99"), only(single.payments().stream()
                .filter(payment -> payment.id().equals(balance.id())).toList()).amountEur());
        assertEquals("Bedrag en datum gevolgd van de transactie: € 499,99 op 05/09/2026 (was € 500,00 op 03/09/2026).",
                single.notice());
    }

    @Test
    @TestTransaction
    void aLargerTransferNeedsTheOwnersChoiceAndAdjustFollowsIt() throws Exception {
        PurchaseOrder order = container(null);
        PurchasePayment deposit = deposit(order, "10250.00", JAN_3);
        long line = line("-10300.00", JAN_5);
        String notes = purchases.get(order.id()).notes();

        assertRefused("Transactie is € 50,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 10.300,00'.",
                () -> splits.linkPart(deposit.id(), part(line, null)));
        assertRefused(PurchaseOrderService.INSPECTION_REST_REFUSED,
                () -> splits.linkPart(deposit.id(), choice(line, PurchasePaymentSplitService.Higher.INSPECTION)));
        assertNull(stored(order, deposit.id()).bankStatementLineId());
        assertEquals(notes, purchases.get(order.id()).notes(), "nothing written while the choice is missing");

        var result = splits.linkPart(deposit.id(), choice(line, PurchasePaymentSplitService.Higher.ADJUST));

        PurchasePayment linked = only(result.payments());
        assertEquals(new BigDecimal("10300.00"), linked.amount());
        assertEquals(new BigDecimal("10300.00"), linked.amountEur());
        assertEquals(LocalDate.of(2026, 1, 5), linked.paidOn());
        assertEquals(line, linked.bankStatementLineId());
        assertEquals("Bedrag en datum gevolgd van de transactie: € 10.300,00 op 05/01/2026 (was € 10.250,00 op 03/01/2026).",
                result.notice());
        JsonNode changes = changes(order, PurchasePaymentSplitService.LINKED_SUMMARY);
        assertChange(changes, "payment.amount", "10250", "10300");
        assertChange(changes, "payment.date", "2026-01-03", "2026-01-05");
    }

    @Test
    @TestTransaction
    void aForeignPaymentTakesTheBanksEuroAndKeepsItsDollars() {
        PurchaseOrder order = container(null);
        PurchasePayment usd = purchases.addPayment(order.id(), JAN_3, new BigDecimal("12000"), Currency.USD,
                "Aanbetaling 30%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED,
                new BigDecimal("10250.00"));
        long close = line("-10250.02", JAN_5);

        var result = splits.linkPart(usd.id(), part(close, null));

        PurchasePayment linked = only(result.payments());
        assertEquals(new BigDecimal("12000.00"), linked.amount(), "the dollars stay what left");
        assertEquals(Currency.USD, linked.currency());
        assertEquals(new BigDecimal("10250.02"), linked.amountEur(), "the euro is what the bank charged");
        assertEquals(LocalDate.of(2026, 1, 5), linked.paidOn());
        assertTrue(purchases.get(order.id()).notes().contains("Betaald 05/01/2026: US$ 12.000,00 (≈ € 10.250,02)"),
                purchases.get(order.id()).notes());

        PurchasePayment balance = purchases.addPayment(order.id(), JAN_3, new BigDecimal("28000"), Currency.USD,
                "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED,
                new BigDecimal("23900.00"));
        long larger = line("-24000.00", JAN_5);
        /* A euro more than the order's rate said is the exchange rate, not a surplus: no choice is asked. */
        var adjusted = splits.linkPart(balance.id(), part(larger, null));
        PurchasePayment followed = adjusted.payments().stream().filter(payment -> payment.id().equals(balance.id()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("28000.00"), followed.amount());
        assertEquals(new BigDecimal("24000.00"), followed.amountEur());
        assertEquals("Bedrag en datum gevolgd van de transactie: € 24.000,00 op 05/01/2026 (was € 23.900,00 op 03/01/2026).",
                adjusted.notice());
    }

    @Test
    @TestTransaction
    void theRestOfALargerTransferBecomesTheInspectionPaidThroughTheSupplier() {
        PurchaseOrder order = container(new BigDecimal("300.00"));
        PurchasePayment goods = deposit(order, "10000.00", SEP_3);
        long line = line("-10300.00", SEP_5);
        assertRefused("Transactie is € 300,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 10.300,00' of "
                + "'Rest als inspectie via leverancier'.", () -> splits.linkPart(goods.id(), part(line, null)));

        var request = choice(line, PurchasePaymentSplitService.Higher.INSPECTION);
        var result = splits.linkPart(goods.id(), request);

        assertEquals(2, result.payments().size(), "the goods and the inspection on the same transfer");
        PurchasePayment kept = result.payments().stream().filter(payment -> payment.id().equals(goods.id())).findFirst().orElseThrow();
        PurchasePayment inspection = result.payments().stream().filter(payment -> !payment.id().equals(goods.id())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10000.00"), kept.amountEur(), "the goods keep their amount");
        assertEquals(LocalDate.of(2026, 9, 5), kept.paidOn());
        assertEquals(line, kept.bankStatementLineId());
        assertEquals(PurchasePayment.Payee.SEPARATE, inspection.payee());
        assertEquals(new BigDecimal("300.00"), inspection.amountEur());
        assertEquals("Aanbetaling 30% · inspectie", inspection.label());
        assertEquals(LocalDate.of(2026, 9, 5), inspection.paidOn());
        assertEquals(line, inspection.bankStatementLineId());
        assertNull(inspection.instalmentDue());
        var described = result.lines().getFirst();
        assertEquals(new BigDecimal("-10300.00"), described.allocatedAmountEur);
        assertEquals(0, described.allocationDifferenceEur.signum());
        assertTrue(purchases.get(order.id()).inspectionPaidViaSupplier());
        assertEquals("Datum gevolgd van de transactie: 05/09/2026 (was 03/09/2026). Inspectie via leverancier € 300,00 "
                + "toegevoegd op dezelfde transactie.", result.notice());
        String notes = purchases.get(order.id()).notes();
        assertTrue(notes.contains("Betaald 05/09/2026: € 300,00 aan inspectie & andere kosten · Aanbetaling 30% · inspectie."), notes);

        var again = splits.linkPart(goods.id(), request);
        assertEquals(result.payments(), again.payments());
        assertEquals(2, purchases.payments(order.id()).size(), "the retry booked no second inspection");
        assertEquals(notes, purchases.get(order.id()).notes());
        assertRefused("Deze transactie draagt al Inspectie € 300,00 (via leverancier); een tweede afsplitsing kan niet. "
                + "Maak de koppeling los om de inspectie te wijzigen.", () -> splits.splitInspection(goods.id(),
                new PurchasePaymentSplitService.InspectionRequest(new BigDecimal("100"), null, null, new BigDecimal("10000.00"))));

        PurchasePayment transport = purchases.addPayment(order.id(), SEP_3, new BigDecimal("1000"), Currency.EUR,
                "Transport", PurchasePayment.Payee.LOGISTICS);
        long more = line("-1200.00", SEP_5);
        assertRefused("Transactie is € 200,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 1.200,00'.",
                () -> splits.linkPart(transport.id(), part(more, null)));
        assertRefused(PurchaseOrderService.INSPECTION_REST_REFUSED,
                () -> splits.linkPart(transport.id(), choice(more, PurchasePaymentSplitService.Higher.INSPECTION)));
    }

    @Test
    @TestTransaction
    void theGoodsAbsorbADifferenceAndTheInspectionKeepsItsAmount() {
        PurchaseOrder order = container(new BigDecimal("300.00"));
        PurchasePayment goods = deposit(order, "10000.00", SEP_3);
        PurchasePayment inspection = purchases.addPayment(order.id(), SEP_3, new BigDecimal("300"), Currency.EUR,
                "Inspectie", PurchasePayment.Payee.SEPARATE);
        long close = line("-10300.02", SEP_5);

        var result = splits.linkPart(goods.id(), new PurchasePaymentSplitService.PartRequest(close, null, null,
                List.of(inspection.id()), null));

        PurchasePayment goodsAfter = result.payments().stream().filter(payment -> payment.id().equals(goods.id())).findFirst().orElseThrow();
        PurchasePayment inspectionAfter = result.payments().stream().filter(payment -> payment.id().equals(inspection.id())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("10000.02"), goodsAfter.amountEur(), "the goods take the cents");
        assertEquals(new BigDecimal("300.00"), inspectionAfter.amountEur(), "never the inspection");
        assertEquals(close, goodsAfter.bankStatementLineId());
        assertEquals(close, inspectionAfter.bankStatementLineId());
        assertEquals(LocalDate.of(2026, 9, 5), inspectionAfter.paidOn(), "the inspection takes the transfer's day too");
        assertEquals(0, result.lines().getFirst().allocationDifferenceEur.signum(), "line total = parts total");

        PurchasePayment goods2 = deposit(order, "5000.00", SEP_3);
        PurchasePayment inspection2 = purchases.addPayment(order.id(), SEP_3, new BigDecimal("300"), Currency.EUR,
                "Inspectie 2", PurchasePayment.Payee.SEPARATE);
        long larger = line("-5400.00", SEP_5);
        var together = new PurchasePaymentSplitService.PartRequest(larger, null, null, List.of(inspection2.id()), null);
        /* The inspection already goes on this transfer: no second one is offered. */
        assertRefused("Transactie is € 100,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 5.100,00'.",
                () -> splits.linkPart(goods2.id(), together));
        var adjusted = splits.linkPart(goods2.id(), new PurchasePaymentSplitService.PartRequest(larger, null,
                PurchasePaymentSplitService.Higher.ADJUST, List.of(inspection2.id()), null));
        assertEquals(new BigDecimal("5100.00"), adjusted.payments().stream()
                .filter(payment -> payment.id().equals(goods2.id())).findFirst().orElseThrow().amountEur());
        assertEquals(new BigDecimal("300.00"), adjusted.payments().stream()
                .filter(payment -> payment.id().equals(inspection2.id())).findFirst().orElseThrow().amountEur());
        assertEquals(0, adjusted.lines().getFirst().allocationDifferenceEur.signum());

        PurchaseOrder other = container(null);
        PurchasePayment foreign = deposit(other, "100.00", SEP_3);
        PurchasePayment goods3 = deposit(order, "700.00", SEP_3);
        assertRefused("Kies betalingen van dezelfde container voor één banktransactie",
                () -> splits.linkPart(goods3.id(), new PurchasePaymentSplitService.PartRequest(line("-800.00", SEP_5),
                        null, null, List.of(foreign.id()), null)));
    }

    @Test
    @TestTransaction
    void aSmallerTransferSaysWhichPartItTookAndAStaleAmountIsRefused() {
        PurchaseOrder order = container(null);
        PurchasePayment balance = deposit(order, "12000.00", SEP_3);
        long smaller = line("-5000.00", SEP_5);

        assertRefused(PurchasePaymentSplitService.CHANGED, () -> splits.linkPart(balance.id(),
                new PurchasePaymentSplitService.PartRequest(smaller, null, null, null, new BigDecimal("11000.00"))));

        var carved = splits.linkPart(balance.id(), new PurchasePaymentSplitService.PartRequest(smaller, null, null, null,
                new BigDecimal("12000.00")));

        assertEquals("Deel van deze betaling: € 5.000,00 van € 12.000,00; de rest (€ 7.000,00) blijft open als tweede transactie.",
                carved.notice());
        PurchasePayment rest = carved.payments().stream().filter(payment -> payment.bankStatementLineId() == null)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("7000.00"), rest.amountEur());

        /* The group's root names its one open transfer; the retry addressed to the root answers the same. */
        long last = line("-7000.00", Instant.parse("2026-09-24T08:00:00Z"));
        var whole = splits.linkPart(balance.id(), part(last, null));
        PurchasePayment second = whole.payments().stream().filter(payment -> payment.id().equals(rest.id())).findFirst().orElseThrow();
        assertEquals(last, second.bankStatementLineId());
        assertEquals(LocalDate.of(2026, 9, 24), second.paidOn());
        assertEquals("Datum gevolgd van de transactie: 24/09/2026 (was 03/09/2026).", whole.notice());
        var again = splits.linkPart(balance.id(), part(last, null));
        assertEquals(whole.payments(), again.payments());
        assertNull(again.notice());
    }

    /** Review of round 2: a USD/CNY payment is whole on its transfer whatever euro the bank charged. */
    @Test
    @TestTransaction
    void aForeignPaymentIsWholeOnItsTransferUnlessAPartIsNamedInItsCurrency() {
        PurchaseOrder order = container(new BigDecimal("300.00"));
        PurchasePayment usd = usd(order, "12000", "10250.00");
        long lower = line("-10180.00", JAN_5);

        var result = splits.linkPart(usd.id(), part(lower, null));

        PurchasePayment linked = only(result.payments());
        assertEquals(new BigDecimal("12000.00"), linked.amount(), "the dollars that left");
        assertEquals(new BigDecimal("10180.00"), linked.amountEur(), "the euro the bank charged");
        assertEquals(LocalDate.of(2026, 1, 5), linked.paidOn());
        assertEquals(lower, linked.bankStatementLineId());
        assertNull(linked.groupId(), "no made-up second transfer");
        assertEquals(1, purchases.payments(order.id()).size());
        assertEquals(0, result.lines().getFirst().allocationDifferenceEur.signum());
        assertEquals("Bedrag en datum gevolgd van de transactie: € 10.180,00 op 05/01/2026 (was € 10.250,00 op 03/01/2026).",
                result.notice());

        /* The whole amount named, or the owner's ADJUST on a lower euro: the same whole link. */
        PurchasePayment named = usd(order, "12000", "10250.00");
        assertEquals(new BigDecimal("10100.00"), splits.linkPart(named.id(), part(line("-10100.00", JAN_5), new BigDecimal("12000")))
                .payments().stream().filter(payment -> payment.id().equals(named.id())).findFirst().orElseThrow().amountEur());
        PurchasePayment adjusted = usd(order, "12000", "10250.00");
        assertEquals(new BigDecimal("10200.00"), splits.linkPart(adjusted.id(), choice(line("-10200.00", JAN_5),
                PurchasePaymentSplitService.Higher.ADJUST)).payments().stream()
                .filter(payment -> payment.id().equals(adjusted.id())).findFirst().orElseThrow().amountEur());

        /* A euro gap is the rate, never an inspection; a part larger than the payment is no part. */
        PurchasePayment goods = usd(order, "12000", "10250.00");
        long higher = line("-10600.00", JAN_5);
        assertRefused("Een betaling in USD neemt het eurobedrag van de transactie over: een verschil in euro is koers, geen "
                + "inspectie. Splits de inspectie na het koppelen af.",
                () -> splits.linkPart(goods.id(), choice(higher, PurchasePaymentSplitService.Higher.INSPECTION)));
        assertRefused("Het deel (US$ 13.000,00) is meer dan de betaling (US$ 12.000,00); pas eerst het bedrag aan",
                () -> splits.linkPart(goods.id(), part(higher, new BigDecimal("13000"))));
        assertRefused("Elk deel moet groter dan nul zijn", () -> splits.linkPart(goods.id(), part(higher, BigDecimal.ZERO)));
        assertNull(stored(order, goods.id()).bankStatementLineId());

        /* A part named in dollars is carved out: it takes the bank's euro, the rest stays open at the booked rate. */
        PurchasePayment balance = usd(order, "12000", "10250.00");
        long half = line("-5090.00", JAN_5);
        var carved = splits.linkPart(balance.id(), part(half, new BigDecimal("6000")));
        assertEquals("Deel van deze betaling: US$ 6.000,00 (€ 5.090,00) van US$ 12.000,00; de rest (US$ 6.000,00) blijft "
                + "open als tweede transactie.", carved.notice());
        PurchasePayment first = carved.payments().stream().filter(payment -> payment.id().equals(balance.id())).findFirst().orElseThrow();
        PurchasePayment rest = carved.payments().stream().filter(payment -> !payment.id().equals(balance.id())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("6000.00"), first.amount());
        assertEquals(new BigDecimal("5090.00"), first.amountEur());
        assertEquals(half, first.bankStatementLineId());
        assertEquals(LocalDate.of(2026, 1, 5), first.paidOn());
        assertEquals(new BigDecimal("6000.00"), rest.amount());
        assertEquals(new BigDecimal("5125.00"), rest.amountEur(), "half of the booked € 10.250,00");
        assertNull(rest.bankStatementLineId());
        assertEquals(JAN_3, rest.paidOn());
        assertEquals(balance.id(), rest.groupId());
        assertEquals(0, carved.lines().getFirst().allocationDifferenceEur.signum());
    }

    /** Review of round 2: "Rest als inspectie" never books a second inspection, on the transfer or on the container. */
    @Test
    @TestTransaction
    void theRestIsNeverASecondInspection() {
        PurchaseOrder order = container(new BigDecimal("300.00"));
        PurchasePayment goods = deposit(order, "10000.00", SEP_3);
        PurchasePayment noted = purchases.addPayment(order.id(), SEP_3, new BigDecimal("300"), Currency.EUR,
                "Inspectie", PurchasePayment.Payee.SEPARATE);
        long line = line("-10500.00", SEP_5);
        assertRefused("Transactie is € 200,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 10.200,00'.",
                () -> splits.linkPart(goods.id(), new PurchasePaymentSplitService.PartRequest(line, null, null,
                        List.of(noted.id()), null)));
        assertRefused("Op deze transactie staat al Inspectie € 300,00 (via leverancier); een tweede inspectie kan niet. "
                + "Kies 'Bedrag aanpassen naar € 10.200,00'.", () -> splits.linkPart(goods.id(),
                new PurchasePaymentSplitService.PartRequest(line, null, PurchasePaymentSplitService.Higher.INSPECTION,
                        List.of(noted.id()), null)));
        assertEquals(2, purchases.payments(order.id()).size(), "no second inspection booked");
        assertNull(stored(order, goods.id()).bankStatementLineId());

        /* The container's inspection already paid through the supplier on the deposit: the balance's surplus is no inspection. */
        PurchaseOrder other = container(new BigDecimal("300.00"));
        PurchasePayment deposit = deposit(other, "10000.00", SEP_3);
        PurchasePayment balance = purchases.addPayment(other.id(), SEP_3, new BigDecimal("20000"), Currency.EUR,
                "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
        splits.linkPart(deposit.id(), choice(line("-10300.00", SEP_5), PurchasePaymentSplitService.Higher.INSPECTION));
        long second = line("-20300.00", SEP_5);
        assertRefused("Transactie is € 300,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 20.300,00'.",
                () -> splits.linkPart(balance.id(), part(second, null)));
        assertRefused("Inspectie via leverancier staat al geboekt: € 300,00 van € 300,00 inspectiekost; een tweede inspectie "
                + "kan niet. Kies 'Bedrag aanpassen naar € 20.300,00'.",
                () -> splits.linkPart(balance.id(), choice(second, PurchasePaymentSplitService.Higher.INSPECTION)));
        assertEquals(1, purchases.payments(other.id()).stream()
                .filter(payment -> payment.payee() == PurchasePayment.Payee.SEPARATE).count());

        /* Part of the inspection cost booked so far: the rest may still be the inspection. */
        PurchaseOrder third = container(new BigDecimal("300.00"));
        PurchasePayment first = deposit(third, "10000.00", SEP_3);
        PurchasePayment last = purchases.addPayment(third.id(), SEP_3, new BigDecimal("20000"), Currency.EUR,
                "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
        splits.linkPart(first.id(), choice(line("-10100.00", SEP_5), PurchasePaymentSplitService.Higher.INSPECTION));
        var rest = splits.linkPart(last.id(), choice(line("-20200.00", SEP_5), PurchasePaymentSplitService.Higher.INSPECTION));
        assertEquals("Datum gevolgd van de transactie: 05/09/2026 (was 03/09/2026). Inspectie via leverancier € 200,00 "
                + "toegevoegd op dezelfde transactie.", rest.notice());
    }

    /** Review of round 2 ("Transactie toevoegen"): a payment with every transfer on the bank grows by one more. */
    @Test
    @TestTransaction
    void aPaymentWithEveryTransferOnTheBankGrowsByTheNextOne() throws Exception {
        PurchaseOrder order = container(null);
        PurchasePayment balance = purchases.addPayment(order.id(), SEP_3, new BigDecimal("12000"), Currency.EUR,
                "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
        splits.linkLines(balance.id(), new PurchasePaymentSplitService.LinesRequest(List.of(
                new PurchasePaymentSplitService.LineRef(line("-5000.00", SEP_5), null),
                new PurchasePaymentSplitService.LineRef(line("-7000.00", SEP_5), null))));
        long extra = line("-500.00", Instant.parse("2026-09-24T08:00:00Z"));
        String notes = purchases.get(order.id()).notes();

        assertRefused("Alle transacties van deze betaling zijn gekoppeld: kies 'Bedrag aanpassen naar € 12.500,00' om deze "
                + "transactie (€ 500,00) toe te voegen.", () -> splits.linkPart(balance.id(), part(extra, null)));
        assertRefused("Alle transacties van deze betaling zijn gekoppeld: kies 'Bedrag aanpassen naar € 12.500,00' om deze "
                + "transactie (€ 500,00) toe te voegen.",
                () -> splits.linkPart(balance.id(), choice(extra, PurchasePaymentSplitService.Higher.INSPECTION)));
        assertEquals(notes, purchases.get(order.id()).notes(), "nothing written without the choice");

        var grown = splits.linkPart(balance.id(), choice(extra, PurchasePaymentSplitService.Higher.ADJUST));

        assertEquals(3, grown.payments().size(), "the group with its new transfer");
        PurchasePayment added = grown.payments().stream().filter(payment -> Long.valueOf(extra).equals(payment.bankStatementLineId()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("500.00"), added.amount());
        assertEquals(new BigDecimal("500.00"), added.amountEur());
        assertEquals(LocalDate.of(2026, 9, 24), added.paidOn());
        assertEquals(balance.id(), added.groupId());
        assertEquals(PurchasePayment.Payee.SUPPLIER, added.payee());
        assertEquals(PaymentTerms.Moment.SHIPPED, added.instalmentDue());
        assertEquals("Saldo 70%", added.label());
        assertEquals(new BigDecimal("12500.00"), grown.payments().stream().map(PurchasePayment::amountEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(grown.payments().stream().allMatch(payment -> payment.bankStatementLineId() != null));
        assertEquals(0, grown.lines().getFirst().allocationDifferenceEur.signum());
        assertEquals("Transactie van € 500,00 op 24/09/2026 toegevoegd; de betaling is nu € 12.500,00 (was € 12.000,00).",
                grown.notice());
        assertTrue(purchases.get(order.id()).notes().contains("Betaald 24/09/2026: € 500,00 aan de leverancier · Saldo 70%"),
                purchases.get(order.id()).notes());
        assertChange(changes(order, PurchasePaymentSplitService.GROWN_SUMMARY), "payment.parts", "2", "3");

        var again = splits.linkPart(balance.id(), choice(extra, PurchasePaymentSplitService.Higher.ADJUST));
        assertEquals(grown.payments(), again.payments());
        assertNull(again.notice());
        assertEquals(3, purchases.payments(order.id()).size(), "the retry added nothing");

        /* One payment on one transfer becomes a payment in parts; a USD one grows at its own rate. */
        PurchasePayment single = usd(order, "12000", "10250.00");
        splits.linkPart(single.id(), part(line("-10250.00", SEP_5), null));
        var dollars = splits.linkPart(single.id(), choice(line("-512.50", SEP_5), PurchasePaymentSplitService.Higher.ADJUST));
        assertEquals(2, dollars.payments().size());
        assertTrue(dollars.payments().stream().allMatch(payment -> single.id().equals(payment.groupId())));
        assertEquals(new BigDecimal("600.00"), dollars.payments().stream().filter(payment -> !payment.id().equals(single.id()))
                .findFirst().orElseThrow().amount(), "12.000 dollars over € 10.250,00");
        assertEquals("Transactie van € 512,50 (US$ 600,00) op 05/09/2026 toegevoegd; de betaling is nu US$ 12.600,00 "
                + "(was US$ 12.000,00).", dollars.notice());
    }

    private PurchasePayment usd(PurchaseOrder order, String amount, String amountEur) {
        return purchases.addPayment(order.id(), JAN_3, new BigDecimal(amount), Currency.USD, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED, new BigDecimal(amountEur));
    }

    private static PurchasePaymentSplitService.PartRequest part(long line, BigDecimal amount) {
        return new PurchasePaymentSplitService.PartRequest(line, amount);
    }

    private static PurchasePaymentSplitService.PartRequest choice(long line, PurchasePaymentSplitService.Higher higher) {
        return new PurchasePaymentSplitService.PartRequest(line, null, higher, null, null);
    }

    private static PurchasePayment only(List<PurchasePayment> payments) {
        assertEquals(1, payments.size(), payments.toString());
        return payments.getFirst();
    }

    private PurchasePayment stored(PurchaseOrder order, long id) {
        return purchases.payments(order.id()).stream().filter(payment -> payment.id() == id).findFirst().orElseThrow();
    }

    private static void assertRefused(String message, org.junit.jupiter.api.function.Executable call) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private PurchaseOrder container(BigDecimal inspectionCostEur) {
        var supplier = suppliers.save(new Supplier(null, "Follow supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder order = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        if (inspectionCostEur != null) {
            em.find(SourcingEntities.PurchaseOrderEntity.class, order.id()).inspectionCostEur = inspectionCostEur;
            em.flush();
        }
        return order;
    }

    private PurchasePayment deposit(PurchaseOrder order, String amount, LocalDate day) {
        return purchases.addPayment(order.id(), day, new BigDecimal(amount), Currency.EUR, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
    }

    private long line(String amount, Instant at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), at, "Europe/Brussels", "Goederen", "Supplier", at, false)).id();
    }

    /** The changes of the order's newest activity with this summary. */
    private JsonNode changes(PurchaseOrder order, String summary) throws Exception {
        List<ActivityLogEntity> entries = ActivityLogEntity.list("entityType = ?1 and entityId = ?2 and summary = ?3 order by id desc",
                ActivityLogService.ENTITY_PURCHASE_ORDER, order.id().toString(), summary);
        assertFalse(entries.isEmpty(), summary);
        return json.readTree(entries.getFirst().changesJson);
    }

    private static void assertChange(JsonNode changes, String field, String before, String after) {
        var change = java.util.stream.StreamSupport.stream(changes.spliterator(), false)
                .filter(node -> field.equals(node.path("field").asText())).findFirst().orElseThrow(() -> new AssertionError(field));
        assertEquals(before, change.path("beforeValue").asText(), field);
        assertEquals(after, change.path("afterValue").asText(), field);
    }
}
