package be.enrosed.finance.banking;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.PurchasePaymentBankAdjustmentEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Emre, 2026-10-16: "met 1 transactie 3 containers hun deelbetalingen betaald". One bank transaction shared over
 * containers: open payments are linked (and follow, grow or are carved), the others get a new payment, in one call.
 */
@QuarkusTest
class BankPurchaseShareServiceTest {
    private static final LocalDate NOTED = LocalDate.of(2026, 9, 20);
    private static final Instant SEP_28 = Instant.parse("2026-09-28T08:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final String TERM = "30% bij bestelling";

    @Inject BankPurchaseShareService service;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CompanyCostService costs;
    @Inject EntityManager em;

    @Test @TestTransaction
    void threeContainersOneTransferTwoOpenPaymentsGrowAndTheThirdIsNew() {
        var supplier = supplier("Yunnan Pubei Flowers Co., Ltd");
        var a = container(supplier);
        var b = container(supplier);
        var c = container(supplier);
        em.find(SourcingEntities.PurchaseOrderEntity.class, c.id()).alias = "voor Frans";
        em.flush();
        var openA = deposit(a, "3070.20");
        var openB = deposit(b, "3070.20");
        long line = line("-11403.38");

        var result = service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(openA.id(), eur("3801.13"), eur("3070.20")),
                BankPurchaseShareService.SharePart.existing(openB.id(), eur("3801.13"), eur("3070.20")),
                BankPurchaseShareService.SharePart.created(c.id(), eur("3801.12"), null, PaymentTerms.Moment.ORDERED, TERM))));

        assertTrue(result.line().allocated);
        assertEquals("Inkoopbetaling", result.line().allocationLabel);
        assertEquals(eur("0.00"), result.line().allocationDifferenceEur);
        assertEquals(List.of(BankPurchaseShareService.Outcome.GREW, BankPurchaseShareService.Outcome.GREW,
                BankPurchaseShareService.Outcome.CREATED), result.parts().stream().map(BankPurchaseShareService.PartResult::outcome).toList());
        assertEquals(eur("3070.20"), result.parts().getFirst().wasEur());
        assertEquals(NOTED, result.parts().getFirst().wasPaidOn());
        assertNull(result.parts().get(2).wasEur());
        assertEquals(List.of(openA.id(), openB.id()), result.payments().subList(0, 2).stream().map(PurchasePayment::id).toList(),
                "the open payments are linked, never a second payment");
        assertEquals(List.of(a.id(), b.id(), c.id()), result.payments().stream().map(PurchasePayment::orderId).toList());

        for (var order : List.of(a, b)) {
            var payments = purchases.payments(order.id());
            assertEquals(1, payments.size(), "one payment on " + order.number());
            assertEquals(eur("3801.13"), payments.getFirst().amountEur());
            assertEquals(DAY, payments.getFirst().paidOn(), "the line's day");
            assertEquals(line, payments.getFirst().bankStatementLineId());
            assertEquals(eur("3070.20"), listed(payments.getFirst().id()).bankAdjustedFromEur(), "aangepast aan de bank (was ...)");
        }
        var added = purchases.payments(c.id());
        assertEquals(1, added.size());
        assertEquals(eur("3801.12"), added.getFirst().amountEur());
        assertEquals(Currency.EUR, added.getFirst().currency());
        assertEquals(DAY, added.getFirst().paidOn());
        assertEquals(PaymentTerms.Moment.ORDERED, added.getFirst().instalmentDue());
        assertEquals(TERM, added.getFirst().label());
        assertEquals(PurchasePayment.Payee.SUPPLIER, added.getFirst().payee());
        assertEquals(line, added.getFirst().bankStatementLineId());
        assertEquals(3, onLine(line), "exactly the three parts carry the line");

        String head = "Banktransactie van 28/09/2026 (€ 11.403,38) verdeeld over 3 containers · deze container ";
        assertEquals(List.of(head + "€ 3.801,13 · ook " + name(b) + ", voor Frans"), shares(a));
        assertEquals(List.of(head + "€ 3.801,13 · ook " + name(a) + ", voor Frans"), shares(b));
        assertEquals(List.of(head + "€ 3.801,12 · ook " + name(a) + ", " + name(b)), shares(c));
        assertTrue(audits(line).contains("Verdeeld over 3 containers: " + name(a) + " € 3.801,13 · " + name(b)
                + " € 3.801,13 · voor Frans € 3.801,12"), audits(line).toString());
        assertTrue(purchases.get(c.id()).notes().contains("aan de leverancier · " + TERM), "the new payment writes its diary line");
    }

    @Test @TestTransaction
    void aSmallerPartIsCarvedOutAndTheRestStaysOpenInItsGroup() {
        var supplier = supplier("Carve supplier");
        var a = container(supplier);
        var b = container(supplier);
        var openA = deposit(a, "3070.20");
        var openB = deposit(b, "1000.00");
        long line = line("-3000.00");

        var result = service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(openA.id(), eur("2000.00"), eur("3070.20")),
                BankPurchaseShareService.SharePart.existing(openB.id(), eur("1000.00"), null))));

        var carved = result.parts().getFirst();
        assertEquals(BankPurchaseShareService.Outcome.CARVED, carved.outcome());
        assertEquals(openA.id(), carved.paymentId(), "the linked row keeps the id (and its proof files)");
        assertNotNull(carved.restPaymentId());
        assertEquals(BankPurchaseShareService.Outcome.LINKED, result.parts().get(1).outcome());
        assertEquals(NOTED, result.parts().get(1).wasPaidOn());

        var payments = purchases.payments(a.id());
        assertEquals(2, payments.size());
        var linked = payments.stream().filter(payment -> payment.id().equals(openA.id())).findFirst().orElseThrow();
        var rest = payments.stream().filter(payment -> payment.id().equals(carved.restPaymentId())).findFirst().orElseThrow();
        assertEquals(eur("2000.00"), linked.amountEur());
        assertEquals(DAY, linked.paidOn());
        assertEquals(line, linked.bankStatementLineId());
        assertEquals(eur("1070.20"), rest.amountEur());
        assertEquals(NOTED, rest.paidOn(), "the rest keeps the old day");
        assertNull(rest.bankStatementLineId(), "the rest stays open");
        assertEquals(openA.id(), rest.groupId());
        assertEquals(openA.id(), linked.groupId());
        assertEquals(eur("3070.20"), linked.amountEur().add(rest.amountEur()), "the group total is unchanged");
        assertNull(adjustment(openA.id()), "a carved part is a new transfer, not a followed payment");

        var other = purchases.payments(b.id()).getFirst();
        assertEquals(eur("1000.00"), other.amountEur());
        assertEquals(DAY, other.paidOn(), "the day follows the line");
        assertEquals(line, other.bankStatementLineId());
        assertEquals(2, onLine(line));
    }

    @Test @TestTransaction
    void anIdenticalRetryAnswersTheSameRowsAndAnotherAmountIsRefused() {
        var supplier = supplier("Retry share supplier");
        var a = container(supplier);
        var b = container(supplier);
        var c = container(supplier);
        var openA = deposit(a, "3070.20");
        var openB = deposit(b, "3070.20");
        long line = line("-9000.00");
        var request = new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(openA.id(), eur("2000.00"), eur("3070.20")),
                BankPurchaseShareService.SharePart.existing(openB.id(), eur("3500.00"), eur("3070.20")),
                BankPurchaseShareService.SharePart.created(c.id(), eur("3500.00"), PurchasePayment.Payee.SUPPLIER,
                        PaymentTerms.Moment.ORDERED, "  " + TERM + " ")));

        var saved = service.share(line, request);
        List<String> notes = List.of(purchases.get(a.id()).notes(), purchases.get(b.id()).notes(), purchases.get(c.id()).notes());
        var adjustedB = adjustment(openB.id());
        assertNotNull(adjustedB);
        int audits = audits(line).size();

        var retry = service.share(line, request);
        assertEquals(saved.payments(), retry.payments(), "same line, same parts: the same rows");
        assertEquals(List.of(BankPurchaseShareService.Outcome.LINKED, BankPurchaseShareService.Outcome.LINKED,
                BankPurchaseShareService.Outcome.CREATED), retry.parts().stream().map(BankPurchaseShareService.PartResult::outcome).toList());
        assertTrue(retry.parts().stream().allMatch(part -> part.wasEur() == null && part.wasPaidOn() == null
                && part.restPaymentId() == null), "a retry reports nothing as changed");
        assertTrue(retry.line().allocated);
        assertEquals(2, purchases.payments(a.id()).size(), "the carve once");
        assertEquals(1, purchases.payments(b.id()).size());
        assertEquals(1, purchases.payments(c.id()).size(), "no second new payment");
        assertEquals(notes, List.of(purchases.get(a.id()).notes(), purchases.get(b.id()).notes(), purchases.get(c.id()).notes()));
        assertEquals(1, shares(a).size(), "no second share activity");
        assertEquals(1, shares(c).size());
        assertEquals(audits, audits(line).size());
        assertEquals(adjustedB.adjustedAt, adjustment(openB.id()).adjustedAt);

        refused("Deze banktransactie is al aan een andere betaling gekoppeld", () -> service.share(line,
                new BankPurchaseShareService.ShareRequest(List.of(
                        BankPurchaseShareService.SharePart.existing(openA.id(), eur("2000.00"), null),
                        BankPurchaseShareService.SharePart.existing(openB.id(), eur("3400.00"), null),
                        BankPurchaseShareService.SharePart.created(c.id(), eur("3600.00"), null, PaymentTerms.Moment.ORDERED, TERM)))));
        refused("Deze banktransactie is al aan een andere betaling gekoppeld", () -> service.share(line,
                new BankPurchaseShareService.ShareRequest(List.of(
                        BankPurchaseShareService.SharePart.existing(openA.id(), eur("2000.00"), null),
                        BankPurchaseShareService.SharePart.existing(openB.id(), eur("3500.00"), null),
                        BankPurchaseShareService.SharePart.created(c.id(), eur("3500.00"), null, PaymentTerms.Moment.ORDERED, "Saldo")))));
        assertEquals(1, purchases.payments(c.id()).size());
    }

    @Test @TestTransaction
    void aForeignPaymentGoesWholeItsEuroBecomesThePart() {
        var supplier = supplier("Dollar supplier");
        var a = container(supplier);
        var b = container(supplier);
        var usd = purchases.addPayment(a.id(), NOTED, eur("4000"), Currency.USD, TERM, PurchasePayment.Payee.SUPPLIER,
                false, PaymentTerms.Moment.ORDERED, eur("3680.00"));
        var lower = purchases.addPayment(b.id(), NOTED, eur("4000"), Currency.USD, TERM, PurchasePayment.Payee.SUPPLIER,
                false, PaymentTerms.Moment.ORDERED, eur("3680.00"));
        long line = line("-6700.00");

        var result = service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(usd.id(), eur("3700.00"), eur("3680.00")),
                BankPurchaseShareService.SharePart.existing(lower.id(), eur("3000.00"), eur("3680.00")))));

        assertEquals(BankPurchaseShareService.Outcome.GREW, result.parts().getFirst().outcome());
        assertEquals(BankPurchaseShareService.Outcome.LINKED, result.parts().get(1).outcome(), "a lower euro is the rate, never a carve");
        var linked = purchases.payments(a.id()).getFirst();
        assertEquals(new BigDecimal("4000.00"), linked.amount(), "the dollars stay");
        assertEquals(Currency.USD, linked.currency());
        assertEquals(eur("3700.00"), linked.amountEur(), "the bank's euro");
        assertEquals(DAY, linked.paidOn());
        assertEquals(line, linked.bankStatementLineId());
        var whole = purchases.payments(b.id());
        assertEquals(1, whole.size(), "no rest row for a USD payment");
        assertEquals(new BigDecimal("4000.00"), whole.getFirst().amount());
        assertEquals(eur("3000.00"), whole.getFirst().amountEur());
        assertEquals(line, whole.getFirst().bankStatementLineId());
    }

    @Test @TestTransaction
    void theRequestShapeTheCostLinkAndTheClassificationAreRefusedFirst() {
        var supplier = supplier("Shape supplier");
        var a = container(supplier);
        var open = deposit(a, "500.00");
        long line = line("-500.00");

        refused("Kies één tot tien delen", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of())));
        refused("Kies één tot tien delen", () -> service.share(line, null));
        refused("Kies één tot tien delen", () -> service.share(line, new BankPurchaseShareService.ShareRequest(
                Collections.nCopies(11, BankPurchaseShareService.SharePart.created(a.id(), eur("10.00"), null, null, null)))));
        refused("Kies per deel een bestaande betaling of een container", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                new BankPurchaseShareService.SharePart(open.id(), a.id(), eur("500.00"), null, null, null, null, false, null, null, null, null)))));
        refused("Kies per deel een bestaande betaling of een container", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                new BankPurchaseShareService.SharePart(null, null, eur("500.00"), null, null, null, null, false, null, null, null, null)))));
        refused("Kies elke betaling maar één keer", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(open.id(), eur("250.00"), null),
                BankPurchaseShareService.SharePart.existing(open.id(), eur("250.00"), null)))));
        refused("Geef voor elk deel een bedrag groter dan nul op", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(open.id(), eur("0.00"), null)))));
        refused("Betalingsbedragen mogen maximaal twee decimalen bevatten", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(open.id(), eur("500.001"), null)))));
        refused("Een nieuw deel van een gedeelde transactie is in euro", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                new BankPurchaseShareService.SharePart(null, a.id(), eur("500.00"), null, null, null, null, false, null, null,
                        eur("560.00"), Currency.USD)))));
        refused("Een nieuw deel van een gedeelde transactie is in euro", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                new BankPurchaseShareService.SharePart(null, a.id(), eur("500.00"), null, null, null, null, false, null, null,
                        eur("499.00"), null)))));
        refused("De omschrijving mag maximaal 255 tekens bevatten", () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.created(a.id(), eur("500.00"), null, null, "x".repeat(256))))));
        assertThrows(NotFoundException.class, () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(987654321L, eur("500.00"), null)))));
        assertThrows(NotFoundException.class, () -> service.share(line, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.created(987654321L, eur("500.00"), null, null, null)))));
        assertThrows(NotFoundException.class, () -> service.share(987654321L, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(open.id(), eur("500.00"), null)))));

        var cost = costs.create(new CompanyCost(null, LocalDate.of(2026, 9, 8), "BANK", "Kosten overschrijving", "KBC",
                new BigDecimal("12.50"), null, null, null, null, null, null, null, null, null, VatRegime.OUT_OF_SCOPE, true,
                null, null, null, null, null, null, null, null));
        long costLine = line("-12.50");
        bank.recordCostPayment(costLine, new BankStatementService.CostAllocation(cost.id()));
        refused("Kies een vrije uitgaande banktransactie", () -> service.share(costLine, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.created(a.id(), eur("12.50"), null, null, null)))));
        long incoming = line("500.00");
        refused("Kies een vrije uitgaande banktransactie", () -> service.share(incoming, new BankPurchaseShareService.ShareRequest(List.of(
                BankPurchaseShareService.SharePart.existing(open.id(), eur("500.00"), null)))));
        long classified = line("-500.00");
        em.find(BankStatementLineEntity.class, classified).classification = "OTHER";
        em.flush();
        refused("Deze banktransactie is geboekt als Andere; maak dat eerst ongedaan", () -> service.share(classified,
                new BankPurchaseShareService.ShareRequest(List.of(BankPurchaseShareService.SharePart.existing(open.id(), eur("500.00"), null)))));

        var stored = purchases.payments(a.id());
        assertEquals(1, stored.size(), "no refusal wrote a payment");
        assertNull(stored.getFirst().bankStatementLineId());
        assertEquals(NOTED, stored.getFirst().paidOn());
        assertFalse(bank.describe(em.find(BankStatementLineEntity.class, line)).allocated);
    }

    /** Every refusal after the locks undoes everything, also once the open payments were already followed or carved. */
    @Test
    void refusalsWriteNothing() {
        long[] f = QuarkusTransaction.requiringNew().call(() -> {
            var supplier = supplier("Share rollback supplier");
            var a = container(supplier);
            var b = container(supplier);
            var c = container(supplier);
            var openA = deposit(a, "3070.20");
            var openB = deposit(b, "3070.20");
            var elsewhere = deposit(a, "500.00");
            long other = line("-500.00");
            bank.linkExisting(other, new BankStatementService.OutgoingLinks(List.of(elsewhere.id()), null, null));
            var offset = purchases.addPayment(b.id(), NOTED, eur("1000.00"), Currency.EUR, "Verrekend tegoed", PurchasePayment.Payee.SUPPLIER);
            var credit = new SourcingEntities.PurchaseSupplierCreditEntity();
            credit.orderId = b.id();
            credit.notedOn = NOTED;
            credit.amount = eur("1000.00");
            credit.currency = Currency.EUR;
            credit.amountEur = eur("1000.00");
            credit.reason = PurchaseSupplierCredit.Reason.PRICE;
            credit.status = PurchaseSupplierCredit.Status.OFFSET;
            credit.offsetOrderId = b.id();
            credit.offsetPaymentId = offset.id();
            credit.recordedAt = Instant.now();
            em.persist(credit);
            em.flush();
            return new long[]{a.id(), b.id(), c.id(), supplier.id(), openA.id(), openB.id(), elsewhere.id(), offset.id(),
                    line("-11403.38"), other, credit.id};
        });
        long line = f[8];
        try {
            List<String> notes = QuarkusTransaction.requiringNew().call(() ->
                    List.of(purchases.get(f[0]).notes(), purchases.get(f[1]).notes(), purchases.get(f[2]).notes()));
            String nameA = QuarkusTransaction.requiringNew().call(() -> name(purchases.get(f[0])));
            String nameB = QuarkusTransaction.requiringNew().call(() -> name(purchases.get(f[1])));

            refused("Samen € 11.403,41; de transactie is € 11.403,38. De delen moeten samen de transactie maken (maximaal € 0,02 verschil).",
                    () -> service.share(line, request(
                            BankPurchaseShareService.SharePart.existing(f[4], eur("3801.13"), null),
                            BankPurchaseShareService.SharePart.existing(f[5], eur("3801.13"), null),
                            BankPurchaseShareService.SharePart.created(f[2], eur("3801.15"), null, PaymentTerms.Moment.ORDERED, TERM))));
            refused(nameA + ": deze betaling staat al op de bank; maak die koppeling eerst los", () -> service.share(line, request(
                    BankPurchaseShareService.SharePart.existing(f[6], eur("500.00"), null),
                    BankPurchaseShareService.SharePart.existing(f[5], eur("3070.20"), null),
                    BankPurchaseShareService.SharePart.created(f[2], eur("7833.18"), null, PaymentTerms.Moment.ORDERED, TERM))));
            refused(nameB + ": een verrekend leverancierstegoed is geen bankbetaling", () -> service.share(line, request(
                    BankPurchaseShareService.SharePart.existing(f[4], eur("3070.20"), null),
                    BankPurchaseShareService.SharePart.existing(f[7], eur("1000.00"), null),
                    BankPurchaseShareService.SharePart.created(f[2], eur("7333.18"), null, PaymentTerms.Moment.ORDERED, TERM))));
            refused("De betaling van " + nameA + " is intussen gewijzigd; vernieuw en probeer opnieuw", () -> service.share(line, request(
                    BankPurchaseShareService.SharePart.existing(f[4], eur("3801.13"), eur("3000.00")),
                    BankPurchaseShareService.SharePart.existing(f[5], eur("3801.13"), eur("3070.20")),
                    BankPurchaseShareService.SharePart.created(f[2], eur("3801.12"), null, PaymentTerms.Moment.ORDERED, TERM))));
            /* After A was carved and B grew, the new part's term is refused: everything goes back. */
            refused("Een betaaltermijn kan alleen aan een leveranciersbetaling worden gekoppeld", () -> service.share(line, request(
                    BankPurchaseShareService.SharePart.existing(f[4], eur("2000.00"), eur("3070.20")),
                    BankPurchaseShareService.SharePart.existing(f[5], eur("3801.13"), eur("3070.20")),
                    BankPurchaseShareService.SharePart.created(f[2], eur("5602.25"), PurchasePayment.Payee.LOGISTICS,
                            PaymentTerms.Moment.ORDERED, "Vracht"))));

            QuarkusTransaction.requiringNew().run(() -> {
                var a = purchases.payments(f[0]);
                assertEquals(2, a.size(), "no carved rest");
                var openA = a.stream().filter(payment -> payment.id() == f[4]).findFirst().orElseThrow();
                assertEquals(eur("3070.20"), openA.amountEur());
                assertEquals(NOTED, openA.paidOn());
                assertNull(openA.groupId());
                assertNull(openA.bankStatementLineId());
                var openB = purchases.payments(f[1]).stream().filter(payment -> payment.id() == f[5]).findFirst().orElseThrow();
                assertEquals(eur("3070.20"), openB.amountEur(), "the grown payment went back");
                assertEquals(NOTED, openB.paidOn());
                assertNull(openB.bankStatementLineId());
                assertNull(em.find(PurchasePaymentBankAdjustmentEntity.class, f[5]), "no adjustment memory either");
                assertTrue(purchases.payments(f[2]).isEmpty(), "no new payment");
                assertEquals(notes, List.of(purchases.get(f[0]).notes(), purchases.get(f[1]).notes(), purchases.get(f[2]).notes()));
                assertFalse(bank.describe(em.find(BankStatementLineEntity.class, line)).allocated);
                assertEquals(0, ActivityLogEntity.count("entityType = ?1 and entityId in ?2 and summary like ?3",
                        ActivityLogService.ENTITY_PURCHASE_ORDER, List.of(Long.toString(f[0]), Long.toString(f[1]), Long.toString(f[2])),
                        "Banktransactie van %"));
            });
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.remove(em.find(SourcingEntities.PurchaseSupplierCreditEntity.class, f[10]));
                em.createQuery("delete from PurchasePaymentBankAdjustmentEntity a where a.paymentId in :ids")
                        .setParameter("ids", List.of(f[4], f[5], f[6], f[7])).executeUpdate();
                em.createQuery("delete from PurchasePaymentEntity p where p.orderId in :ids")
                        .setParameter("ids", List.of(f[0], f[1], f[2])).executeUpdate();
                em.remove(em.find(BankStatementLineEntity.class, f[8]));
                em.remove(em.find(BankStatementLineEntity.class, f[9]));
                for (int i = 0; i < 3; i++) em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, f[i]));
                em.remove(em.find(SourcingEntities.SupplierEntity.class, f[3]));
            });
        }
    }

    /** 2026-10-19 (pin): two parts on one container, its open payment and a new "Transactiekosten" payment. */
    @Test @TestTransaction
    void anOpenPaymentAndItsTransactionCostsShareOneTransferOnOneContainer() {
        var a = container(supplier("Fee share supplier"));
        var open = deposit(a, "3070.20");
        long line = line("-3095.20");
        var request = request(
                BankPurchaseShareService.SharePart.existing(open.id(), eur("3070.20"), eur("3070.20")),
                BankPurchaseShareService.SharePart.created(a.id(), eur("25.00"), PurchasePayment.Payee.OTHER, null,
                        "Transactiekosten"));

        var result = service.share(line, request);

        assertEquals(List.of(BankPurchaseShareService.Outcome.LINKED, BankPurchaseShareService.Outcome.CREATED),
                result.parts().stream().map(BankPurchaseShareService.PartResult::outcome).toList());
        assertEquals(open.id(), result.payments().getFirst().id(), "the open payment is linked, never a second one");
        assertEquals(DAY, result.payments().getFirst().paidOn());
        var fee = result.payments().get(1);
        assertEquals(a.id(), fee.orderId());
        assertEquals(PurchasePayment.Payee.OTHER, fee.payee());
        assertEquals("Transactiekosten", fee.label());
        assertEquals(eur("25.00"), fee.amountEur());
        assertEquals(Currency.EUR, fee.currency());
        assertNull(fee.instalmentDue());
        assertEquals(DAY, fee.paidOn());
        assertEquals(2, onLine(line));
        assertTrue(result.line().allocated);
        assertEquals(eur("0.00"), result.line().allocationDifferenceEur);
        assertEquals(List.of("Banktransactie van 28/09/2026 (€ 3.095,20) verdeeld over 1 container · deze container € 3.095,20"),
                shares(a));

        var retry = service.share(line, request);
        assertEquals(result.payments(), retry.payments(), "an identical retry answers the same rows");
        assertEquals(2, purchases.payments(a.id()).size());
    }

    private static BankPurchaseShareService.ShareRequest request(BankPurchaseShareService.SharePart... parts) {
        return new BankPurchaseShareService.ShareRequest(List.of(parts));
    }

    private static void refused(String message, Executable call) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private List<String> shares(PurchaseOrder order) {
        em.flush();
        List<ActivityLogEntity> entries = ActivityLogEntity.list("entityType = ?1 and entityId = ?2 and summary like ?3 order by id",
                ActivityLogService.ENTITY_PURCHASE_ORDER, order.id().toString(), "Banktransactie van %");
        List<String> summaries = new ArrayList<>();
        for (ActivityLogEntity entry : entries) summaries.add(entry.summary);
        return summaries;
    }

    private List<String> audits(long line) {
        em.flush();
        List<ActivityLogEntity> entries = ActivityLogEntity.list("entityType = ?1 and entityId = ?2 order by id",
                "BANK_STATEMENT", Long.toString(line));
        return entries.stream().map(entry -> entry.summary).toList();
    }

    private long onLine(long line) {
        return em.createQuery("select count(p) from PurchasePaymentEntity p where p.bankStatementLineId=:id", Long.class)
                .setParameter("id", line).getSingleResult();
    }

    private PurchasePaymentBankAdjustmentEntity adjustment(long paymentId) {
        em.flush();
        return em.find(PurchasePaymentBankAdjustmentEntity.class, paymentId);
    }

    private PurchaseOrderService.PaymentRow listed(long paymentId) {
        return purchases.paymentsSince(null).stream().filter(row -> row.id() == paymentId).findFirst().orElseThrow();
    }

    private static String name(PurchaseOrder order) {
        return PurchaseOrderName.display(order.id(), order.number(), order.alias());
    }

    private static BigDecimal eur(String amount) {
        return new BigDecimal(amount);
    }

    private PurchasePayment deposit(PurchaseOrder order, String amount) {
        return purchases.addPayment(order.id(), NOTED, new BigDecimal(amount), Currency.EUR, TERM,
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
    }

    private Supplier supplier(String name) {
        return suppliers.save(new Supplier(null, name, "CN", "Kunming", null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
    }

    private PurchaseOrder container(Supplier supplier) {
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private long line(String amount) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(), "BE94731074082814",
                new BigDecimal(amount), SEP_28, "Europe/Brussels", "DEPOSIT 30PCT 3 CONTAINERS", "Yunnan Pubei Flowers Co., Ltd",
                SEP_28, false)).id();
    }
}
