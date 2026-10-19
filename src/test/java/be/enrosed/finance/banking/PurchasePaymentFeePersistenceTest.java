package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.Supplier;
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
 * Emre, 2026-10-19: "er zijn transactie kosten bij sommige chinese overschrijvingen voor containers". The bank's fee
 * inside a container transfer becomes a "Transactiekosten" payment (Bijkomende kosten) on the same container and
 * the same line: the rest of a larger line ("Rest als transactiekosten"), or a fee named in euro.
 */
@QuarkusTest
class PurchasePaymentFeePersistenceTest {
    @Inject PurchasePaymentSplitService splits;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;

    private static final LocalDate JAN_3 = LocalDate.of(2026, 1, 3);
    private static final Instant JAN_5 = Instant.parse("2026-01-05T09:00:00Z");
    private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
    private static final Instant SEP_5 = Instant.parse("2026-09-05T08:00:00Z");

    @Test
    @TestTransaction
    void theRestOfALargerTransferBecomesTheContainersTransactionCosts() {
        PurchaseOrder order = container();
        PurchasePayment goods = deposit(order, "10000.00");
        long line = line("-10025.00", SEP_5);
        var request = new PurchasePaymentSplitService.PartRequest(line, null, PurchasePaymentSplitService.Higher.FEE, null,
                new BigDecimal("10000.00"));

        var result = splits.linkPart(goods.id(), request);

        assertEquals(2, result.payments().size(), "the goods and the fee on the same transfer");
        PurchasePayment kept = byId(result.payments(), goods.id());
        assertEquals(new BigDecimal("10000.00"), kept.amountEur(), "the payment keeps its amount");
        assertEquals(LocalDate.of(2026, 9, 5), kept.paidOn());
        assertEquals(line, kept.bankStatementLineId());
        PurchasePayment fee = fee(result.payments());
        assertEquals(order.id(), fee.orderId());
        assertEquals("Transactiekosten", fee.label());
        assertEquals(Currency.EUR, fee.currency());
        assertEquals(new BigDecimal("25.00"), fee.amount());
        assertEquals(new BigDecimal("25.00"), fee.amountEur());
        assertEquals(LocalDate.of(2026, 9, 5), fee.paidOn(), "the line's day");
        assertFalse(fee.settles());
        assertNull(fee.instalmentDue());
        assertEquals(line, fee.bankStatementLineId());
        var described = result.lines().getFirst();
        assertEquals(new BigDecimal("-10025.00"), described.allocatedAmountEur);
        assertEquals(0, described.allocationDifferenceEur.signum());
        assertEquals("Datum gevolgd van de transactie: 05/09/2026 (was 03/09/2026). Transactiekosten € 25,00 bij "
                + order.number() + " op dezelfde transactie.", result.notice());
        String notes = purchases.get(order.id()).notes();
        assertTrue(notes.contains("Betaald 05/09/2026: € 25,00 aan andere kosten · Transactiekosten."), notes);
        PurchaseReconciliation.Stream additional = purchases.reconciliation(order.id()).streams().stream()
                .filter(stream -> stream.payee() == PurchasePayment.Payee.OTHER).findFirst().orElseThrow();
        assertEquals(new BigDecimal("25.00"), additional.paidEur(), "Bijkomende kosten in the nacalculatie");
        assertEquals(PurchaseReconciliation.Status.ADDITIONAL, additional.status());

        var again = splits.linkPart(goods.id(), request);
        assertEquals(result.payments(), again.payments());
        assertNull(again.notice());
        assertEquals(2, purchases.payments(order.id()).size(), "the retry booked no second fee");
        assertEquals(notes, purchases.get(order.id()).notes());

        /* Koppeling losmaken: like the inspection rest, the fee stays booked and leaves the line with the goods. */
        bank.unlinkExisting(line);
        em.flush();
        List<PurchasePayment> after = purchases.payments(order.id());
        assertEquals(2, after.size());
        assertTrue(after.stream().allMatch(payment -> payment.bankStatementLineId() == null));
        assertEquals(new BigDecimal("25.00"), fee(after).amountEur());
    }

    /** Review 2026-10-20: the fee "Koppeling losmaken" left open goes back on the line; Bijkomende kosten never double. */
    @Test
    @TestTransaction
    void aLinkAfterKoppelingLosmakenTakesTheOpenFeeAgain() {
        PurchaseOrder order = container();
        PurchasePayment goods = deposit(order, "10000.00");
        long line = line("-10025.00", SEP_5);
        var rest = new PurchasePaymentSplitService.PartRequest(line, null, PurchasePaymentSplitService.Higher.FEE, null,
                new BigDecimal("10000.00"));
        long booked = fee(splits.linkPart(goods.id(), rest).payments()).id();

        /* "Rest als transactiekosten" again. */
        bank.unlinkExisting(line);
        em.flush();
        var again = splits.linkPart(goods.id(), rest);
        assertEquals(booked, fee(again.payments()).id(), "the open fee, not a second one");
        assertEquals(line, fee(again.payments()).bankStatementLineId());
        assertEquals(1, fees(order));
        assertEquals(0, again.lines().getFirst().allocationDifferenceEur.signum());
        assertEquals(new BigDecimal("25.00"), additionalPaid(order));

        /* The same fee named in euro. */
        bank.unlinkExisting(line);
        em.flush();
        var named = splits.linkPart(goods.id(), fee(line, null, null, "25"));
        assertEquals(booked, fee(named.payments()).id());
        assertEquals(1, fees(order));
        assertEquals(new BigDecimal("25.00"), additionalPaid(order));

        /* Another fee: the open one follows it. */
        bank.unlinkExisting(line);
        em.flush();
        var other = splits.linkPart(goods.id(), fee(line, null, PurchasePaymentSplitService.Higher.ADJUST, "20"));
        PurchasePayment followed = fee(other.payments());
        assertEquals(booked, followed.id());
        assertEquals(new BigDecimal("20.00"), followed.amountEur());
        assertEquals(new BigDecimal("10005.00"), byId(other.payments(), goods.id()).amountEur());
        assertEquals(1, fees(order));
        assertEquals(new BigDecimal("20.00"), additionalPaid(order));
        assertEquals(0, other.lines().getFirst().allocationDifferenceEur.signum());
    }

    @Test
    @TestTransaction
    void aNamedFeeComesOffTheLineAndTheEuroPaymentTakesTheRest() {
        PurchaseOrder order = container();
        PurchasePayment exact = deposit(order, "10000.00");
        long first = line("-10025.00", SEP_5);
        var whole = splits.linkPart(exact.id(), fee(first, null, null, "25"));
        assertEquals(new BigDecimal("10000.00"), byId(whole.payments(), exact.id()).amountEur());
        assertEquals(new BigDecimal("25.00"), fee(whole.payments()).amountEur());
        assertEquals(0, whole.lines().getFirst().allocationDifferenceEur.signum());

        /* Line − fee still above the payment: the owner's choice, as without a fee. */
        PurchasePayment adjusted = deposit(order, "10000.00");
        long second = line("-10045.00", SEP_5);
        assertRefused("Transactie is € 20,00 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 10.020,00'.",
                () -> splits.linkPart(adjusted.id(), fee(second, null, null, "25")));
        assertEquals(1, fees(order), "nothing written while the choice is missing");
        var grown = splits.linkPart(adjusted.id(), fee(second, null, PurchasePaymentSplitService.Higher.ADJUST, "25"));
        assertEquals(new BigDecimal("10020.00"), byId(grown.payments(), adjusted.id()).amountEur());
        assertEquals(new BigDecimal("25.00"), fee(onLine(grown.payments(), second)).amountEur());

        /* "Rest als transactiekosten" with a named fee: one fee payment of both. */
        PurchasePayment rest = deposit(order, "10000.00");
        long third = line("-10045.00", SEP_5);
        var both = splits.linkPart(rest.id(), fee(third, null, PurchasePaymentSplitService.Higher.FEE, "25"));
        assertEquals(new BigDecimal("10000.00"), byId(both.payments(), rest.id()).amountEur());
        assertEquals(new BigDecimal("45.00"), fee(onLine(both.payments(), third)).amountEur());
        assertTrue(both.notice().endsWith("Transactiekosten € 45,00 bij " + order.number() + " op dezelfde transactie."),
                both.notice());

        /* A smaller line: the part is carved out of what the fee leaves. */
        PurchasePayment carved = deposit(order, "10000.00");
        long fourth = line("-5025.00", SEP_5);
        var part = splits.linkPart(carved.id(), fee(fourth, null, null, "25"));
        assertEquals(new BigDecimal("5000.00"), byId(part.payments(), carved.id()).amountEur());
        assertEquals(new BigDecimal("25.00"), fee(onLine(part.payments(), fourth)).amountEur());
        assertEquals("Deel van deze betaling: € 5.000,00 van € 10.000,00; de rest (€ 5.000,00) blijft open als tweede "
                + "transactie. Transactiekosten € 25,00 bij " + order.number() + " op dezelfde transactie.", part.notice());
        assertEquals(0, part.lines().getFirst().allocationDifferenceEur.signum());
    }

    @Test
    @TestTransaction
    void aFeeMustLeaveSomethingOfTheLineAndRefusalsWriteNothing() {
        PurchaseOrder order = container();
        PurchasePayment goods = deposit(order, "10000.00");
        PurchasePayment noted = purchases.addPayment(order.id(), SEP_3, new BigDecimal("10000"), Currency.EUR,
                "Transport", PurchasePayment.Payee.LOGISTICS);
        long line = line("-10025.00", SEP_5);
        String notes = purchases.get(order.id()).notes();

        assertRefused("Geef transactiekosten groter dan nul op", () -> splits.linkPart(goods.id(), fee(line, null, null, "0")));
        assertRefused("Geef transactiekosten groter dan nul op", () -> splits.linkPart(goods.id(), fee(line, null, null, "-5")));
        assertRefused("De transactiekosten (€ 10.025,00) moeten kleiner zijn dan de banktransactie (€ 10.025,00)",
                () -> splits.linkPart(goods.id(), fee(line, null, null, "10025")));
        assertRefused("Betalingsbedragen mogen maximaal twee decimalen bevatten",
                () -> splits.linkPart(goods.id(), fee(line, null, null, "25.001")));
        assertRefused("De andere betalingen en de transactiekosten (€ 10.025,00) zijn samen niet kleiner dan de "
                + "banktransactie (€ 10.025,00)", () -> splits.linkPart(goods.id(), new PurchasePaymentSplitService.PartRequest(
                        line, null, null, List.of(noted.id()), null, new BigDecimal("25"))));

        assertEquals(2, purchases.payments(order.id()).size(), "no fee booked");
        assertTrue(purchases.payments(order.id()).stream().allMatch(payment -> payment.bankStatementLineId() == null));
        assertEquals(notes, purchases.get(order.id()).notes());
    }

    @Test
    @TestTransaction
    void aForeignPaymentTakesTheBanksEuroWithoutTheFee() {
        PurchaseOrder order = container();
        PurchasePayment usd = usd(order);
        long line = line("-10300.00", JAN_5);
        assertRefused("Een betaling in USD neemt het eurobedrag van de transactie over: een verschil in euro is koers. "
                + "Geef de transactiekosten op in euro.",
                () -> splits.linkPart(usd.id(), fee(line, null, PurchasePaymentSplitService.Higher.FEE, null)));
        assertEquals(1, purchases.payments(order.id()).size());

        var result = splits.linkPart(usd.id(), fee(line, null, null, "30"));

        PurchasePayment linked = byId(result.payments(), usd.id());
        assertEquals(new BigDecimal("12000.00"), linked.amount(), "the dollars that left");
        assertEquals(new BigDecimal("10270.00"), linked.amountEur(), "the bank's euro without the fee");
        assertEquals(line, linked.bankStatementLineId());
        PurchasePayment fee = fee(result.payments());
        assertEquals(new BigDecimal("30.00"), fee.amountEur());
        assertEquals(Currency.EUR, fee.currency());
        assertEquals(LocalDate.of(2026, 1, 5), fee.paidOn());
        assertEquals(0, result.lines().getFirst().allocationDifferenceEur.signum());
        assertEquals("Bedrag en datum gevolgd van de transactie: € 10.270,00 op 05/01/2026 (was € 10.250,00 op 03/01/2026). "
                + "Transactiekosten € 30,00 bij " + order.number() + " op dezelfde transactie.", result.notice());

        /* A part named in dollars: carved out, it takes the line's euro without the fee. */
        PurchasePayment balance = usd(order);
        long half = line("-5120.00", JAN_5);
        var carved = splits.linkPart(balance.id(), fee(half, new BigDecimal("6000"), null, "30"));
        PurchasePayment part = byId(carved.payments(), balance.id());
        assertEquals(new BigDecimal("6000.00"), part.amount());
        assertEquals(new BigDecimal("5090.00"), part.amountEur());
        assertEquals(new BigDecimal("30.00"), fee(onLine(carved.payments(), half)).amountEur());
        assertEquals("Deel van deze betaling: US$ 6.000,00 (€ 5.090,00) van US$ 12.000,00; de rest (US$ 6.000,00) blijft "
                + "open als tweede transactie. Transactiekosten € 30,00 bij " + order.number() + " op dezelfde transactie.",
                carved.notice());
        assertEquals(0, carved.lines().getFirst().allocationDifferenceEur.signum());
    }

    private static PurchasePaymentSplitService.PartRequest fee(long line, BigDecimal amount,
                                                              PurchasePaymentSplitService.Higher higher, String feeEur) {
        return new PurchasePaymentSplitService.PartRequest(line, amount, higher, null, null,
                feeEur == null ? null : new BigDecimal(feeEur));
    }

    private static PurchasePayment byId(List<PurchasePayment> payments, long id) {
        return payments.stream().filter(payment -> payment.id() == id).findFirst().orElseThrow();
    }

    /** The one "Transactiekosten" payment among these. */
    private static PurchasePayment fee(List<PurchasePayment> payments) {
        List<PurchasePayment> fees = payments.stream().filter(payment -> payment.payee() == PurchasePayment.Payee.OTHER).toList();
        assertEquals(1, fees.size(), payments.toString());
        return fees.getFirst();
    }

    private static List<PurchasePayment> onLine(List<PurchasePayment> payments, long line) {
        return payments.stream().filter(payment -> Long.valueOf(line).equals(payment.bankStatementLineId())).toList();
    }

    private long fees(PurchaseOrder order) {
        return purchases.payments(order.id()).stream().filter(payment -> payment.payee() == PurchasePayment.Payee.OTHER).count();
    }

    /** Bijkomende kosten paid in the nacalculatie. */
    private BigDecimal additionalPaid(PurchaseOrder order) {
        return purchases.reconciliation(order.id()).streams().stream()
                .filter(stream -> stream.payee() == PurchasePayment.Payee.OTHER).findFirst().orElseThrow().paidEur();
    }

    private static void assertRefused(String message, org.junit.jupiter.api.function.Executable call) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new Supplier(null, "Fee supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private PurchasePayment deposit(PurchaseOrder order, String amount) {
        return purchases.addPayment(order.id(), SEP_3, new BigDecimal(amount), Currency.EUR, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
    }

    private PurchasePayment usd(PurchaseOrder order) {
        return purchases.addPayment(order.id(), JAN_3, new BigDecimal("12000"), Currency.USD, "Aanbetaling 30%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED, new BigDecimal("10250.00"));
    }

    private long line(String amount, Instant at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), at, "Europe/Brussels", "Goederen + SWIFT OUR", "Supplier", at,
                false)).id();
    }
}
