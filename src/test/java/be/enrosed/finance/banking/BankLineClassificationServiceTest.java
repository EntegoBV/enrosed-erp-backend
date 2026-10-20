package be.enrosed.finance.banking;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.finance.odoo.OdooCostImportEntity;
import be.enrosed.finance.odoo.OdooCostImportStore;
import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BankLineClassificationServiceTest {
    private static final String KBC = "BE94731074082814";
    private static final String SAVINGS = "BE12000011112222";
    private static final Instant AT = Instant.parse("2026-10-14T08:00:00Z");

    @Inject BankLineClassificationService classifications;
    @Inject BankStatementService bank;
    @Inject BankPurchasePaymentService purchasePayments;
    @Inject BankCostRegistrationService costRegistration;
    @Inject PurchasePaymentSplitService splits;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CompanyCostService costs;
    @Inject OdooCostImportStore odooStore;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void anInternalTransferPairIsBookedAndUndoneTogether() throws Exception {
        long out = imported(KBC, "-5000.00", AT);
        long in = imported(SAVINGS, "5000.00", AT.plusSeconds(86400 * 2));
        var result = classifications.classify(out, request("INTERNAL_TRANSFER", "naar spaarrekening", in, null));
        assertEquals(List.of(Math.min(out, in), Math.max(out, in)), result.lines().stream().map(line -> line.id).toList());
        for (var line : result.lines()) {
            assertEquals("INTERNAL_TRANSFER", line.classification);
            assertEquals("naar spaarrekening", line.classificationNote);
            assertTrue(line.allocated);
            assertEquals("Interne overboeking", line.allocationLabel);
            assertEquals(0, line.allocatedAmountEur.compareTo(line.amountEur));
            assertEquals(new BigDecimal("0.00"), line.allocationDifferenceEur);
            assertNotNull(line.classifiedAt);
            assertNotNull(line.classifiedBy);
        }
        assertEquals(in, line(out).transferLineId);
        assertEquals(out, line(in).transferLineId);

        var retry = classifications.classify(out, request("INTERNAL_TRANSFER", "naar spaarrekening", in, null));
        assertEquals(result.lines().stream().map(line -> line.id).toList(), retry.lines().stream().map(line -> line.id).toList(),
                "the same booking again answers the lines as they are");
        var fromTheOtherSide = classifications.classify(in, request("INTERNAL_TRANSFER", "naar spaarrekening", out, null));
        assertEquals(2, fromTheOtherSide.lines().size());

        String listed = json.writeValueAsString(bank.list().stream().filter(row -> row.id.equals(out)).findFirst().orElseThrow());
        for (String field : List.of("\"classification\":\"INTERNAL_TRANSFER\"", "\"classificationNote\":\"naar spaarrekening\"",
                "\"transferLineId\":" + in, "\"vatPeriod\":null", "\"classifiedAt\":", "\"classifiedBy\":", "\"allocated\":true")) {
            assertTrue(listed.contains(field), "GET /api/bank-statements shows " + field + " in " + listed);
        }

        classifications.unclassify(in);
        for (long id : List.of(out, in)) {
            var line = described(id);
            assertNull(line.classification);
            assertNull(line.transferLineId);
            assertNull(line.classifiedAt);
            assertFalse(line.allocated, "undone, both legs are te koppelen again");
        }
        classifications.unclassify(in);
        assertEquals(List.of("Boeken als ongedaan gemaakt", "Geboekt als Interne overboeking · ⇄ lijn " + in),
                audits(out).subList(0, 2));
    }

    @Test @TestTransaction
    void vatNamesItsQuarterAndOtherNeedsANote() {
        long vat = imported(KBC, "-7412.00", AT);
        var booked = classifications.classify(vat, request("VAT", null, null, "2026-Q3")).lines().getFirst();
        assertEquals("2026-Q3", booked.vatPeriod);
        assertEquals("Btw", booked.allocationLabel);
        assertEquals("Geboekt als Btw · kwartaal 2026-Q3", audits(vat).getFirst());

        long deposit = imported(KBC, "1200.00", AT);
        refused(() -> classifications.classify(deposit, request("OTHER", "   ", null, null)), "Vul bij Andere een korte uitleg in");
        var other = classifications.classify(deposit, request("OTHER", " Borg terug van verhuurder ", null, null)).lines().getFirst();
        assertEquals("Borg terug van verhuurder", other.classificationNote);
        assertEquals("Andere", other.allocationLabel);

        long loan = imported(KBC, "25000.00", AT);
        assertEquals("Lening", classifications.classify(loan, request("LOAN", null, null, null)).lines().getFirst().allocationLabel);
        long gone = imported(SAVINGS, "-300.00", AT);
        assertNull(classifications.classify(gone, request("INTERNAL_TRANSFER", null, null, null)).lines().getFirst().transferLineId,
                "\"Andere kant staat niet in Enrosed\": a transfer without its other leg");
    }

    @Test @TestTransaction
    void everyRefusalInItsOrder() {
        long out = imported(KBC, "-5000.00", AT);
        assertThrows(NotFoundException.class, () -> classifications.classify(987654321L, request("LOAN", null, null, null)));
        long zero = manual("ZERO-TEST", "0.01", AT);
        line(zero).amountEur = new BigDecimal("0.00");
        em.flush();
        refused(() -> classifications.classify(zero, request("LOAN", null, null, null)), "Kies een banktransactie met een bedrag");

        long booked = imported(KBC, "-100.00", AT);
        classifications.classify(booked, request("PRIVATE", null, null, null));
        refused(() -> classifications.classify(booked, request("LOAN", null, null, null)),
                "Deze banktransactie is al geboekt als Privé; maak dat eerst ongedaan");
        refused(() -> classifications.classify(booked, request("PRIVATE", "andere uitleg", null, null)),
                "Deze banktransactie is al geboekt als Privé; maak dat eerst ongedaan");

        long linked = imported(KBC, "-121.00", AT);
        bank.recordCostPayment(linked, new BankStatementService.CostAllocation(cost("121.00").id()));
        refused(() -> classifications.classify(linked, request("LOAN", null, null, null)),
                "Deze banktransactie is al gekoppeld; maak de koppeling eerst los");

        refused(() -> classifications.classify(out, request(null, null, null, null)), "Kies hoe je deze banktransactie boekt");
        refused(() -> classifications.classify(out, request("DIVIDEND", null, null, null)), "Kies hoe je deze banktransactie boekt");
        refused(() -> classifications.classify(out, request("LOAN", "x".repeat(301), null, null)),
                "De uitleg mag maximaal 300 tekens bevatten");
        refused(() -> classifications.classify(out, request("OTHER", null, null, null)), "Vul bij Andere een korte uitleg in");
        refused(() -> classifications.classify(out, request("VAT", null, null, null)), "Kies het btw-kwartaal (bijv. 2026-Q3)");
        refused(() -> classifications.classify(out, request("VAT", null, null, "2026-Q5")), "Kies het btw-kwartaal (bijv. 2026-Q3)");
        refused(() -> classifications.classify(out, request("LOAN", null, null, "2026-Q3")),
                "Een btw-kwartaal hoort alleen bij een btw-betaling");
        long in = imported(SAVINGS, "5000.00", AT);
        refused(() -> classifications.classify(out, request("LOAN", null, in, null)),
                "Een andere kant hoort alleen bij een interne overboeking of annulatie");
        assertThrows(NotFoundException.class, () -> classifications.classify(out, request("INTERNAL_TRANSFER", null, 987654321L, null)));
        long sameAccount = imported(KBC, "5000.00", AT);
        refused(() -> classifications.classify(out, request("INTERNAL_TRANSFER", null, sameAccount, null)),
                "De andere kant van de overboeking moet op een andere eigen rekening staan");
        long wrongAmount = imported(SAVINGS, "4999.99", AT);
        refused(() -> classifications.classify(out, request("INTERNAL_TRANSFER", null, wrongAmount, null)),
                "De andere kant moet het tegengestelde bedrag hebben");
        long late = imported(SAVINGS, "5000.00", AT.plusSeconds(86400 * 6));
        refused(() -> classifications.classify(out, request("INTERNAL_TRANSFER", null, late, null)),
                "De andere kant ligt meer dan 5 dagen verder");
        classifications.classify(in, request("CAPITAL", null, null, null));
        refused(() -> classifications.classify(out, request("INTERNAL_TRANSFER", null, in, null)),
                "De andere kant is al gekoppeld of geboekt");
        assertNull(line(out).classification, "every refusal leaves the line open");
    }

    @Test @TestTransaction
    void everyLinkPathRefusesABookedLineAsBooked() {
        var supplier = suppliers.save(new Supplier(null, "Classified supplier", "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB", "Ningbo", 30, null));
        var order = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var payment = purchases.addPayment(order.id(), LocalDate.of(2026, 10, 10), new BigDecimal("100.00"), Currency.EUR, "Aanbetaling");
        var cost = cost("100.00");
        long line = imported(KBC, "-100.00", AT);
        classifications.classify(line, request("LOAN", null, null, null));
        String message = "Deze banktransactie is geboekt als Lening; maak dat eerst ongedaan";
        refused(() -> bank.allocate(line, new BankStatementService.Allocation(1L, null)), message);
        refused(() -> bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(payment.id()), null, null)), message);
        refused(() -> bank.recordCostPayment(line, new BankStatementService.CostAllocation(cost.id())), message);
        refused(() -> purchasePayments.record(line, new BankPurchasePaymentService.Request(order.id(), List.of(
                new BankPurchasePaymentService.Payment(new BigDecimal("100"), Currency.EUR, "Aanbetaling",
                        PurchasePayment.Payee.SUPPLIER, false, null, null)))), message);
        refused(() -> costRegistration.register(line, new BankCostRegistrationService.Request(UUID.randomUUID().toString(),
                costRequest("100.00"))), message);
        var slot = new OdooCostImportEntity();
        slot.id = "classified-test:4001";
        slot.namespace = "classified-test";
        slot.companyId = 1;
        slot.moveId = 4001;
        slot.createdAt = Instant.now();
        em.persist(slot);
        em.flush();
        var snapshot = new OdooDocumentService.PurchaseSnapshot("classified-test", 1, new OdooDocumentService.Document(4001,
                "BILL/4001", "INV-4001", "Example Supplies", "2026-10-10", "in_invoice", "draft", "EUR", new BigDecimal("100.00"),
                "done", "message-4001", List.of()), 9, "BE0123456789", "BE", new BigDecimal("100.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"), "2026-10-10 12:00:00", "a".repeat(64));
        refused(() -> odooStore.register(line, snapshot, "a".repeat(64), null, null, List.of()), message);
        assertEquals(message, odooStore.plan(line, snapshot, null).unavailableReason(), "the Odoo preview says so too");
        refused(() -> splits.linkPart(payment.id(), new PurchasePaymentSplitService.PartRequest(line, null)),
                "Banktransactie van 14/10/2026 (€ 100,00) is al gekoppeld of niet uitgaand; kies een vrije uitgaande banktransactie");

        long manual = manual("CLASSIFIED-MANUAL", "-25.00", AT);
        classifications.classify(manual, request("PRIVATE", null, null, null));
        refused(() -> bank.delete(manual), "Deze banktransactie is geboekt als Privé; maak dat eerst ongedaan");

        assertNull(purchases.payments(order.id()).getFirst().bankStatementLineId(), "nothing was linked");
        assertTrue(costs.view(costs.get(cost.id())).payments().isEmpty());
    }

    @Test @TestTransaction
    void anOdooReimportThatChangesABookedLineIsAConflict() {
        String source = "classified:" + UUID.randomUUID();
        long line = bank.importExternal(new BankStatementService.ExternalRequest(source, KBC, new BigDecimal("-50.00"), AT,
                "Europe/Brussels", "Transfer", "Party", AT, false)).id();
        classifications.classify(line, request("PRIVATE", null, null, null));
        assertEquals(BankStatementService.ImportOutcome.CONFLICT, bank.importExternal(new BankStatementService.ExternalRequest(source,
                KBC, new BigDecimal("-55.00"), AT, "Europe/Brussels", "Transfer", "Party", AT.plusSeconds(60), false)).outcome());
        assertEquals(new BigDecimal("-50.00"), line(line).amountEur);
    }

    @Test @TestTransaction
    void aCancellationAlwaysPairsThePaymentAndRefundWithoutFinancialDocuments() {
        long out = imported(KBC, "-279.96", AT);
        long in = imported(KBC, "279.96", AT.plusSeconds(86400 * 45));
        long costsBefore = count("CompanyCostEntity"), paymentsBefore = count("CostPaymentEntity");
        var result = classifications.classify(in, request("CANCELLATION", "Bestelling geannuleerd", out, null));
        assertEquals(List.of(out, in), result.lines().stream().map(row -> row.id).toList());
        for (var row : result.lines()) {
            assertEquals("CANCELLATION", row.classification); assertEquals("Annulatie", row.allocationLabel);
            assertTrue(row.allocated); assertEquals(0, row.allocationDifferenceEur.signum());
            assertEquals(row.amountEur, row.allocatedAmountEur);
            assertNull(row.salesPaymentId); assertNull(row.salesOrderId); assertNull(row.vatPeriod);
        }
        assertEquals(in, line(out).transferLineId); assertEquals(out, line(in).transferLineId);
        assertEquals(2, classifications.classify(out, request("CANCELLATION", "Bestelling geannuleerd", in, null)).lines().size());
        assertEquals(1, audits(out).size(), "retry creates no second audit or financial booking");
        assertEquals(costsBefore, count("CompanyCostEntity")); assertEquals(paymentsBefore, count("CostPaymentEntity"));
        assertTrue(bank.list().stream().filter(row -> row.id == out || row.id == in).allMatch(row -> row.allocated));
        classifications.unclassify(out);
        for (long id : List.of(out, in)) {
            var row = described(id); assertFalse(row.allocated); assertNull(row.classification); assertNull(row.transferLineId);
        }
        classifications.unclassify(in);
        long elsewhere = imported(SAVINGS, "279.96", AT.plusSeconds(86400 * 90));
        assertEquals(2, classifications.classify(out, request("CANCELLATION", null, elsewhere, null)).lines().size(),
                "a refund on another own account is allowed, without a date window");
    }

    @Test @TestTransaction
    void cancellationRejectsMissingSelfUnequalOrAlreadyUsedCounterEntries() {
        long out = imported(KBC, "-279.96", AT);
        refused(() -> classifications.classify(out, request("CANCELLATION", null, null, null)),
                "Kies de tegenboeking; een annulatie bestaat altijd uit twee banktransacties");
        refused(() -> classifications.classify(out, request("CANCELLATION", null, out, null)),
                "Kies een andere banktransactie als tegenboeking");
        long partial = imported(KBC, "279.95", AT);
        refused(() -> classifications.classify(out, request("CANCELLATION", null, partial, null)),
                "De andere kant moet het tegengestelde bedrag hebben");
        long sameDirection = imported(KBC, "-279.96", AT);
        refused(() -> classifications.classify(out, request("CANCELLATION", null, sameDirection, null)),
                "De andere kant moet het tegengestelde bedrag hebben");
        long in = imported(KBC, "279.96", AT);
        classifications.classify(in, request("OTHER", "Bestaande boeking", null, null));
        refused(() -> classifications.classify(out, request("CANCELLATION", null, in, null)),
                "De andere kant is al gekoppeld of geboekt");
        refused(() -> classifications.classify(in, request("CANCELLATION", null, out, null)),
                "Deze banktransactie is al geboekt als Andere; maak dat eerst ongedaan");
        classifications.unclassify(in);
        var tica = line(in); tica.classification = "TICA_SETTLEMENT"; tica.ticaSettlementId = 987654321L; em.flush();
        refused(() -> classifications.classify(out, request("CANCELLATION", null, in, null)),
                "De andere kant is al gekoppeld of geboekt");
        assertNull(line(out).classification); assertNull(line(partial).classification);
        long expense = imported(KBC, "-279.96", AT);
        bank.recordCostPayment(expense, new BankStatementService.CostAllocation(cost("279.96").id()));
        long refund = imported(KBC, "279.96", AT);
        refused(() -> classifications.classify(refund, request("CANCELLATION", null, expense, null)),
                "De andere kant is al gekoppeld of geboekt");
        assertNull(line(refund).classification);
    }

    @Test @TestTransaction
    void cancellationBlocksOtherPaymentPathsAndReimportKeepsThePair() {
        String source = "cancelled:" + UUID.randomUUID();
        var original = new BankStatementService.ExternalRequest(source, KBC, new BigDecimal("-279.96"), AT,
                "Europe/Brussels", "Orderref", "Merchant", AT, false);
        long out = bank.importExternal(original).id(), in = imported(KBC, "279.96", AT);
        classifications.classify(out, request("CANCELLATION", null, in, null));
        assertEquals(BankStatementService.ImportOutcome.UNCHANGED, bank.importExternal(original).outcome());
        var changed = new BankStatementService.ExternalRequest(source, KBC, new BigDecimal("-279.95"), AT,
                "Europe/Brussels", "Orderref", "Merchant", AT.plusSeconds(10), false);
        assertEquals(BankStatementService.ImportOutcome.CONFLICT, bank.importExternal(changed).outcome());
        var metadata = new BankStatementService.ExternalRequest(source, KBC, original.amountEur(), AT,
                "Europe/Brussels", "Orderref aangevuld", "Merchant", AT.plusSeconds(20), false);
        assertEquals(BankStatementService.ImportOutcome.UPDATED, bank.importExternal(metadata).outcome());
        assertEquals(in, line(out).transferLineId); assertEquals(out, line(in).transferLineId);
        assertEquals("Orderref aangevuld", line(out).reference);
        String message = "Deze banktransactie is geboekt als Annulatie; maak dat eerst ongedaan";
        refused(() -> bank.allocate(in, new BankStatementService.Allocation(1L, null)), message);
        refused(() -> bank.recordCostPayment(out, new BankStatementService.CostAllocation(cost("279.96").id())), message);
        refused(() -> bank.linkExisting(out, new BankStatementService.OutgoingLinks(List.of(), null, null)), message);
        refused(() -> bank.unlinkExisting(out), message);
        refused(() -> bank.unallocate(in), message);
        refused(() -> bank.delete(out), message);
    }

    @Test
    void concurrentCancellationsCannotUseOneRefundTwice() throws Exception {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> new long[]{imported(KBC, "-50.00", AT),
                imported(KBC, "-50.00", AT), imported(KBC, "50.00", AT)});
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        java.util.function.LongFunction<CompletableFuture<Boolean>> attempt = id -> CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent test did not start");
                classifications.classify(id, request("CANCELLATION", null, ids[2], null)); return true;
            } catch (BusinessRuleException alreadyUsed) { return false; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        try {
            var a = attempt.apply(ids[0]); var b = attempt.apply(ids[1]);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            boolean first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(first, second, "only one payment can own the refund");
            QuarkusTransaction.requiringNew().run(() -> {
                long winner = first ? ids[0] : ids[1], unused = first ? ids[1] : ids[0];
                assertEquals(winner, line(ids[2]).transferLineId); assertEquals(ids[2], line(winner).transferLineId);
                assertNull(line(unused).classification); assertNull(line(unused).transferLineId);
                classifications.unclassify(ids[2]);
                assertNull(line(winner).classification); assertNull(line(ids[2]).classification);
            });
        } finally {
            start.countDown();
            QuarkusTransaction.requiringNew().run(() -> {
                for (long id : ids) { var row = line(id); if (row != null) em.remove(row); }
            });
        }
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void theEndpointsBookAndUndoAndTheCostRegistrationAnswers422() {
        long line = QuarkusTransaction.requiringNew().call(() -> manual("CLASSIFY-HTTP", "-30.00", AT));
        try {
            given().contentType("application/json").body(Map.of("classification", "PRIVATE"))
                    .post("/api/bank-statements/" + line + "/classification").then().statusCode(200)
                    .body("lines[0].id", equalTo((int) line)).body("lines[0].classification", equalTo("PRIVATE"))
                    .body("lines[0].allocated", equalTo(true)).body("lines[0].allocationLabel", equalTo("Privé"));
            given().contentType("application/json").body(Map.of("requestId", UUID.randomUUID().toString(), "cost",
                            Map.of("date", "2026-10-14", "category", "SOFTWARE", "description", "Booked", "amountExclEur", 30,
                                    "vatRegime", "OUT_OF_SCOPE", "vatRegimeConfirmed", true)))
                    .post("/api/bank-statements/" + line + "/cost-registration").then().statusCode(422)
                    .body("message", equalTo("Deze banktransactie is geboekt als Privé; maak dat eerst ongedaan"));
            given().delete("/api/bank-statements/" + line + "/classification").then().statusCode(204);
            given().delete("/api/bank-statements/" + line + "/classification").then().statusCode(204);
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.remove(em.find(BankStatementLineEntity.class, line)));
        }
    }

    @Test
    void anonymousCannotBook() {
        given().contentType("application/json").body("{}").post("/api/bank-statements/1/classification").then().statusCode(401);
    }

    private static BankLineClassificationService.Request request(String classification, String note, Long other, String vatPeriod) {
        return new BankLineClassificationService.Request(classification, note, other, vatPeriod);
    }

    private static void refused(Executable call, String message) {
        assertEquals(message, assertThrows(BusinessRuleException.class, call).getMessage());
    }

    private BankStatementLineEntity line(long id) {
        return em.find(BankStatementLineEntity.class, id);
    }

    private long count(String entity) { return em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult(); }

    private BankStatementLineEntity described(long id) {
        return bank.list().stream().filter(row -> row.id == id).findFirst().orElseThrow();
    }

    private List<String> audits(long lineId) {
        return em.createQuery("select a.summary from ActivityLogEntity a where a.entityType='BANK_STATEMENT' and a.entityId=:id order by a.id desc",
                String.class).setParameter("id", Long.toString(lineId)).getResultList();
    }

    private long imported(String account, String amount, Instant at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(), account,
                new BigDecimal(amount), at, "Europe/Brussels", "Overschrijving", "Tegenpartij", at, false)).id();
    }

    private long manual(String account, String amount, Instant at) {
        var value = new BigDecimal(amount);
        return bank.create(new BankStatementService.ManualRequest(account, value.abs(), value.signum() > 0
                ? BankStatementService.Direction.INCOMING : BankStatementService.Direction.OUTGOING, at, "Europe/Brussels",
                "Fixture", "Fixture party", UUID.randomUUID().toString())).id;
    }

    private CompanyCost cost(String amount) {
        return costs.create(costRequest(amount));
    }

    private static CompanyCost costRequest(String amount) {
        return new CompanyCost(null, LocalDate.of(2026, 10, 10), "SOFTWARE", "Classified fixture", "Example Supplies",
                new BigDecimal(amount), null, null, null, null, null, null, null, null, null, VatRegime.OUT_OF_SCOPE, true, null,
                null, null, null, null, null, null, null);
    }
}
