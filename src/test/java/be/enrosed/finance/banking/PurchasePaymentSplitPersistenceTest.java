package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.LotCostCalculator;
import be.enrosed.sourcing.application.PurchaseInvoiceService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierPaymentAllocation;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.Supplier;
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

/** Spec example (c): "Saldo 70%" of € 21.733,63 left the bank in two transfers. */
@QuarkusTest
class PurchasePaymentSplitPersistenceTest {
    @Inject PurchasePaymentSplitService splits;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseInvoiceService invoices;
    @Inject SupplierService suppliers;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    private static final LocalDate NOTED = LocalDate.of(2026, 9, 10);
    /** A day on neither transfer: every part that goes onto a line must leave it. */
    private static final LocalDate RECORDED = LocalDate.of(2026, 9, 5);
    private static final Instant SEP_10 = Instant.parse("2026-09-10T08:00:00Z");
    private static final Instant SEP_24 = Instant.parse("2026-09-24T08:00:00Z");

    @Test
    @TestTransaction
    void routeOneSplitsThePaymentOverBothTransfersEachOnItsOwnDay() throws Exception {
        PurchaseOrder order = container();
        var invoice = invoices.add(order.id(), new PurchaseInvoiceService.InvoiceRequest(PurchasePayment.Payee.SUPPLIER,
                "CI-2026-118", LocalDate.of(2026, 8, 20), new BigDecimal("31048.04"), Currency.EUR, null, null, null));
        PurchasePayment balance = balance(order, RECORDED);
        long first = line("-10000.00", SEP_10);
        long second = line("-11733.63", SEP_24);

        var result = splits.linkLines(balance.id(), lines(second, first));

        assertEquals(2, result.payments().size());
        PurchasePayment part0 = result.payments().get(0);
        PurchasePayment part1 = result.payments().get(1);
        assertEquals(balance.id(), part0.id(), "the original id stays on the first transfer");
        assertEquals(new BigDecimal("10000.00"), part0.amountEur());
        assertEquals(LocalDate.of(2026, 9, 10), part0.paidOn(), "the first part leaves 05/09 for its transfer's day");
        assertEquals(first, part0.bankStatementLineId());
        assertEquals(new BigDecimal("11733.63"), part1.amountEur());
        assertEquals(new BigDecimal("11733.63"), part1.amount());
        assertEquals(LocalDate.of(2026, 9, 24), part1.paidOn());
        assertEquals(second, part1.bankStatementLineId());
        for (PurchasePayment part : result.payments()) {
            assertEquals(balance.id(), part.groupId());
            assertEquals("Saldo 70%", part.label());
            assertEquals(PaymentTerms.Moment.SHIPPED, part.instalmentDue());
            assertEquals(PurchasePayment.Payee.SUPPLIER, part.payee());
        }
        assertEquals(List.of(first, second), result.lines().stream().map(line -> line.id).toList());
        assertTrue(result.lines().stream().allMatch(line -> line.allocated));

        String notes = purchases.get(order.id()).notes();
        assertFalse(notes.contains("€ 21.733,63"), notes);
        assertFalse(notes.contains("Betaald 05/09/2026"), notes);
        assertTrue(notes.contains("Betaald 10/09/2026: € 10.000,00 aan de leverancier · Saldo 70% · termijn bij vertrek."), notes);
        assertTrue(notes.contains("Betaald 24/09/2026: € 11.733,63 aan de leverancier · Saldo 70% · termijn bij vertrek."), notes);

        var row = purchases.paymentsSince(null).stream().filter(candidate -> candidate.id().equals(part1.id()))
                .findFirst().orElseThrow();
        assertEquals(order.id(), row.orderId());
        assertEquals(order.number(), row.orderNumber());
        assertEquals(new BigDecimal("11733.63"), row.amount());
        assertEquals(Currency.EUR, row.currency());
        assertEquals(PaymentTerms.Moment.SHIPPED, row.instalmentDue());
        assertFalse(row.settles());
        assertEquals(balance.id(), row.groupId());
        assertEquals(invoice.id(), row.resolvedInvoiceId());
        assertEquals("CI-2026-118", row.invoiceNumber());
        assertFalse(row.creditOffset());
        assertEquals(second, row.bankStatementLineId());
        var tree = json.readTree(json.writeValueAsString(row));
        for (String field : List.of("id", "orderId", "orderNumber", "orderAlias", "paidOn", "amountEur", "label", "payee",
                "bankStatementLineId", "amount", "currency", "instalmentDue", "settles", "groupId", "resolvedInvoiceId",
                "invoiceNumber", "creditOffset")) {
            assertTrue(tree.has(field), field);
        }
        assertEquals("2026-09-24", tree.get("paidOn").asText());
        assertEquals(List.of(balance.id(), part1.id()), invoices.list(order.id()).getFirst().paymentIds());
        assertDateChange(order, "Betaling verdeeld over 2 banktransacties", "2026-09-05", "2026-09-10");
    }

    @Test
    @TestTransaction
    void theLastTransferMayDifferTwoCentsButNotMore() {
        PurchaseOrder order = container();
        PurchasePayment balance = balance(order);
        long first = line("-10000.00", SEP_10);
        long close = line("-11733.65", SEP_24);
        long far = line("-11700.00", SEP_24);

        var refusal = assertThrows(BusinessRuleException.class, () -> splits.linkLines(balance.id(), lines(first, far)));
        assertEquals("De gekozen banktransacties (€ 21.700,00) verschillen € 33,63 van de betaling (€ 21.733,63); "
                + "maximaal € 0,02 verschil is toegestaan. Pas eerst het bedrag of 'Afgeschreven in euro' aan.", refusal.getMessage());

        var result = splits.linkLines(balance.id(), lines(first, close));
        assertEquals(new BigDecimal("11733.63"), result.payments().get(1).amountEur(), "the stored total never changes");
        var described = result.lines().stream().filter(line -> line.id == close).findFirst().orElseThrow();
        assertEquals(new BigDecimal("-0.02"), described.allocationDifferenceEur);
    }

    @Test
    @TestTransaction
    void routeTwoCarvesAPartAndTheLastTransferTakesItsOwnDay() throws Exception {
        PurchaseOrder order = container();
        PurchasePayment balance = balance(order, RECORDED);
        long first = line("-10000.00", SEP_10);
        long second = line("-11733.63", SEP_24);

        var carved = splits.linkPart(balance.id(), new PurchasePaymentSplitService.PartRequest(first, null));

        assertEquals(2, carved.payments().size());
        /* By day, then id: the open rest still carries 05/09, the carved part now 10/09. */
        PurchasePayment rest = carved.payments().get(0);
        PurchasePayment linked = carved.payments().get(1);
        assertEquals(balance.id(), linked.id());
        assertEquals(new BigDecimal("10000.00"), linked.amountEur());
        assertEquals(first, linked.bankStatementLineId());
        assertEquals(LocalDate.of(2026, 9, 10), linked.paidOn(), "the carved part takes its transfer's day");
        assertEquals(new BigDecimal("11733.63"), rest.amountEur());
        assertNull(rest.bankStatementLineId());
        assertEquals(RECORDED, rest.paidOn(), "the open rest keeps the payment's day");
        assertEquals(balance.id(), rest.groupId());
        assertEquals(List.of(first), carved.lines().stream().map(line -> line.id).toList());

        String before = purchases.get(order.id()).notes();
        assertFalse(before.contains("€ 21.733,63"), before);
        assertTrue(before.contains("Betaald 10/09/2026: € 10.000,00 aan de leverancier · Saldo 70%"), before);
        assertTrue(before.contains("Betaald 05/09/2026: € 11.733,63 aan de leverancier · Saldo 70%"), before);
        assertDateChange(order, "Deel van de betaling aan een banktransactie gekoppeld", "2026-09-05", "2026-09-10");
        var whole = splits.linkPart(rest.id(), new PurchasePaymentSplitService.PartRequest(second, null));
        PurchasePayment last = whole.payments().stream().filter(payment -> payment.id().equals(rest.id())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 24), last.paidOn(), "a whole match takes the line's day");
        assertEquals(second, last.bankStatementLineId());
        assertEquals(2, whole.payments().size(), "the group comes back whole");
        String after = purchases.get(order.id()).notes();
        assertFalse(after.contains("Betaald 05/09/2026: € 11.733,63"), after);
        assertTrue(after.contains("Betaald 24/09/2026: € 11.733,63"), after);
        assertTrue(after.contains("Betaald 10/09/2026: € 10.000,00"), after);
        assertDateChange(order, "Betaling aan een banktransactie gekoppeld", "2026-09-05", "2026-09-24");

        /* The bank feed from 20/09 brings the whole group, the older transfer included. */
        var feed = purchases.paymentsSince(LocalDate.of(2026, 9, 20)).stream()
                .filter(row -> row.orderId() == order.id()).map(PurchaseOrderService.PaymentRow::id).toList();
        assertEquals(List.of(balance.id(), rest.id()), feed);
    }

    @Test
    @TestTransaction
    void retriesAnswerTheSameGroupWithoutWritingTwice() {
        PurchaseOrder order = container();
        PurchasePayment balance = balance(order);
        long first = line("-10000.00", SEP_10);
        long second = line("-11733.63", SEP_24);
        var request = lines(first, second);
        var done = splits.linkLines(balance.id(), request);
        String notes = purchases.get(order.id()).notes();

        var retry = splits.linkLines(balance.id(), request);

        assertEquals(done.payments(), retry.payments());
        assertEquals(2, purchases.payments(order.id()).size());
        assertEquals(notes, purchases.get(order.id()).notes());

        PurchaseOrder other = container();
        PurchasePayment deposit = purchases.addPayment(other.id(), NOTED, new BigDecimal("9314.41"), Currency.EUR,
                "Aanbetaling 30%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
        long smaller = line("-4000.00", SEP_10);
        var carved = splits.linkPart(deposit.id(), new PurchasePaymentSplitService.PartRequest(smaller, null));
        var carvedAgain = splits.linkPart(deposit.id(), new PurchasePaymentSplitService.PartRequest(smaller, null));
        assertEquals(carved.payments(), carvedAgain.payments());
        assertEquals(2, purchases.payments(other.id()).size());
    }

    @Test
    @TestTransaction
    void aForeignPaymentIsSharedProRataAndKeepsItsContainerRateValue() {
        PurchaseOrder order = container();
        PurchasePayment usd = purchases.addPayment(order.id(), NOTED, new BigDecimal("20.10"), Currency.USD,
                "Klein saldo", PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("17.00"));
        BigDecimal unsplit = LotCostCalculator.normalised(order, List.of(usd)).getFirst().amountEur();
        assertEquals(new BigDecimal("18.09"), unsplit, "20.10 USD at 0.90");

        var result = splits.linkLines(usd.id(), lines(line("-8.50", SEP_10), line("-8.50", SEP_24)));

        assertEquals(List.of(new BigDecimal("10.05"), new BigDecimal("10.05")),
                result.payments().stream().map(PurchasePayment::amount).toList());
        assertEquals(List.of(new BigDecimal("8.50"), new BigDecimal("8.50")),
                result.payments().stream().map(PurchasePayment::amountEur).toList());
        BigDecimal split = LotCostCalculator.normalised(order, result.payments()).stream()
                .map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(unsplit, split, "valued as one group: not 9.05 + 9.05");
        assertEquals(result.payments().getFirst().groupId(),
                LotCostCalculator.normalised(order, result.payments()).getFirst().groupId(), "the copy keeps its group");

        PurchasePayment given = purchases.addPayment(order.id(), NOTED, new BigDecimal("20.10"), Currency.USD,
                "Opgegeven delen", PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("17.00"));
        long a = line("-8.50", SEP_10);
        long b = line("-8.50", SEP_24);
        var refusal = assertThrows(BusinessRuleException.class, () -> splits.linkLines(given.id(),
                new PurchasePaymentSplitService.LinesRequest(List.of(new PurchasePaymentSplitService.LineRef(a, new BigDecimal("10.00")),
                        new PurchasePaymentSplitService.LineRef(b, new BigDecimal("10.00"))))));
        assertEquals("De delen in USD moeten samen US$ 20,10 zijn", refusal.getMessage());
        var zero = assertThrows(BusinessRuleException.class, () -> splits.linkLines(given.id(),
                new PurchasePaymentSplitService.LinesRequest(List.of(new PurchasePaymentSplitService.LineRef(a, BigDecimal.ZERO),
                        new PurchasePaymentSplitService.LineRef(b, new BigDecimal("20.10"))))));
        assertEquals("Elk deel moet groter dan nul zijn", zero.getMessage());
        var chosen = splits.linkLines(given.id(), new PurchasePaymentSplitService.LinesRequest(List.of(
                new PurchasePaymentSplitService.LineRef(a, new BigDecimal("12.10")),
                new PurchasePaymentSplitService.LineRef(b, new BigDecimal("8.00")))));
        assertEquals(List.of(new BigDecimal("12.10"), new BigDecimal("8.00")),
                chosen.payments().stream().map(PurchasePayment::amount).toList());
    }

    @Test
    @TestTransaction
    void refusalsLeaveEverythingAsItWas() {
        PurchaseOrder order = container();
        PurchasePayment balance = balance(order);
        long first = line("-10000.00", SEP_10);
        long incoming = line("11733.63", SEP_24);
        long taken = line("-11733.63", SEP_24);
        PurchasePayment other = purchases.addPayment(order.id(), NOTED, new BigDecimal("11733.63"), Currency.EUR,
                "Andere", PurchasePayment.Payee.SUPPLIER);
        bank.linkExisting(taken, new BankStatementService.OutgoingLinks(List.of(other.id()), null, null));

        assertRefused("Kies één tot tien banktransacties", () -> splits.linkLines(balance.id(),
                new PurchasePaymentSplitService.LinesRequest(List.of())));
        assertRefused("Kies elke banktransactie maar één keer", () -> splits.linkLines(balance.id(), lines(first, first)));
        assertRefused("Banktransactie van 24/09/2026 (€ 11.733,63) is al gekoppeld of niet uitgaand; kies een vrije uitgaande banktransactie",
                () -> splits.linkLines(balance.id(), lines(first, incoming)));
        assertRefused("Banktransactie van 24/09/2026 (€ 11.733,63) is al gekoppeld of niet uitgaand; kies een vrije uitgaande banktransactie",
                () -> splits.linkLines(balance.id(), lines(first, taken)));
        long big = line("-25000.00", SEP_24);
        /* Round 2: a larger transfer asks the owner's choice instead of sending him to Kosten & bank. */
        assertRefused("Transactie is € 3.266,37 hoger dan deze betaling: kies 'Bedrag aanpassen naar € 25.000,00'.",
                () -> splits.linkPart(balance.id(), new PurchasePaymentSplitService.PartRequest(big, null)));
        assertRefused("Deze betaling staat al op de bank; maak de koppeling eerst los",
                () -> splits.linkLines(other.id(), lines(first)));
        assertThrows(be.enrosed.shared.NotFoundException.class, () -> splits.linkLines(999999L, lines(first)));

        PurchasePayment offset = purchases.addPayment(order.id(), NOTED, new BigDecimal("10000.00"), Currency.EUR,
                "Verrekend tegoed", PurchasePayment.Payee.SUPPLIER);
        var credit = new SourcingEntities.PurchaseSupplierCreditEntity();
        credit.orderId = order.id();
        credit.notedOn = NOTED;
        credit.amount = new BigDecimal("10000.00");
        credit.currency = Currency.EUR;
        credit.amountEur = new BigDecimal("10000.00");
        credit.reason = PurchaseSupplierCredit.Reason.PRICE;
        credit.status = PurchaseSupplierCredit.Status.OFFSET;
        credit.offsetOrderId = order.id();
        credit.offsetPaymentId = offset.id();
        credit.recordedAt = Instant.now();
        em.persist(credit);
        em.flush();
        assertRefused("Een verrekend leverancierstegoed is geen bankbetaling", () -> splits.linkLines(offset.id(), lines(first)));
        assertRefused("Een verrekend leverancierstegoed is geen bankbetaling",
                () -> splits.linkPart(offset.id(), new PurchasePaymentSplitService.PartRequest(first, null)));

        assertEquals(new BigDecimal("21733.63"), purchases.payments(order.id()).stream()
                .filter(payment -> payment.id().equals(balance.id())).findFirst().orElseThrow().amountEur());
        assertEquals(3, purchases.payments(order.id()).size());
        assertTrue(purchases.paymentsSince(null).stream().filter(row -> row.id().equals(offset.id()))
                .findFirst().orElseThrow().creditOffset());
    }

    /**
     * An untied (legacy) supplier payment is allocated by date: once split,
     * its later transfer no longer fills a term that a settling payment
     * closed in between.
     */
    @Test
    @TestTransaction
    void anUntiedPaymentSplitAcrossASettledTermFillsTheNextTerm() {
        PurchaseOrder order = container();
        PurchasePayment untied = purchases.addPayment(order.id(), LocalDate.of(2026, 8, 1), new BigDecimal("1000"),
                Currency.EUR, "Oud saldo", PurchasePayment.Payee.SUPPLIER);
        purchases.addPayment(order.id(), LocalDate.of(2026, 8, 15), new BigDecimal("600"), Currency.EUR,
                "Aanbetaling", PurchasePayment.Payee.SUPPLIER, true, PaymentTerms.Moment.ORDERED);
        var before = SupplierPaymentAllocation.calculate(purchases.get(order.id()), new BigDecimal("3000"),
                purchases.payments(order.id()));
        assertEquals(new BigDecimal("1600.00"), before.getFirst().paidEur());

        splits.linkLines(untied.id(), lines(line("-400.00", Instant.parse("2026-08-01T08:00:00Z")),
                line("-600.00", Instant.parse("2026-08-20T08:00:00Z"))));

        var after = SupplierPaymentAllocation.calculate(purchases.get(order.id()), new BigDecimal("3000"),
                purchases.payments(order.id()));
        assertEquals(new BigDecimal("1000.00"), after.get(0).paidEur(), "the transfer of 20/08 comes after the settlement");
        assertEquals(new BigDecimal("600.00"), after.get(1).paidEur());
    }

    private static void assertRefused(String message, org.junit.jupiter.api.function.Executable call) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private static PurchasePaymentSplitService.LinesRequest lines(long... ids) {
        return new PurchasePaymentSplitService.LinesRequest(java.util.Arrays.stream(ids)
                .mapToObj(id -> new PurchasePaymentSplitService.LineRef(id, null)).toList());
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new Supplier(null, "Split supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private PurchasePayment balance(PurchaseOrder order) {
        return balance(order, NOTED);
    }

    private PurchasePayment balance(PurchaseOrder order, LocalDate day) {
        return purchases.addPayment(order.id(), day, new BigDecimal("21733.63"), Currency.EUR, "Saldo 70%",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
    }

    /** The order's activity with this summary records the payment's day before and after. */
    private void assertDateChange(PurchaseOrder order, String summary, String before, String after) throws Exception {
        List<ActivityLogEntity> entries = ActivityLogEntity.list("entityType = ?1 and entityId = ?2 and summary = ?3",
                ActivityLogService.ENTITY_PURCHASE_ORDER, order.id().toString(), summary);
        assertEquals(1, entries.size(), summary);
        var change = java.util.stream.StreamSupport.stream(json.readTree(entries.getFirst().changesJson).spliterator(), false)
                .filter(node -> "payment.date".equals(node.path("field").asText())).findFirst().orElseThrow();
        assertEquals(before, change.path("beforeValue").asText(), summary);
        assertEquals(after, change.path("afterValue").asText(), summary);
    }

    private long line(String amount, Instant at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal(amount), at, "Europe/Brussels", "Saldo", "Supplier", at, false)).id();
    }
}
