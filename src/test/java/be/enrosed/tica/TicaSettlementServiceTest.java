package be.enrosed.tica;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.banking.*;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.tica.TicaEntities.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static be.enrosed.tica.TicaCsvParserTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@QuarkusTest
class TicaSettlementServiceTest {
    @Inject TicaService tica;
    @Inject TicaCsvParser parser;
    @Inject TicaSettlementService settlements;
    @Inject CompanyCostService costs;
    @Inject BankStatementService bank;
    @Inject BankLineClassificationService classifications;
    @Inject EntityManager entities;

    @BeforeEach void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            TicaSettlementEntity.deleteAll(); Delivery.deleteAll(); Notice.deleteAll(); Sale.deleteAll(); Settings.deleteAll();
        });
        TicaService actual = io.quarkus.arc.ClientProxy.unwrap(tica);
        actual.enabled = true; actual.username = "TT_2132"; actual.password = Optional.of("test-only-placeholder");
        tica.ensureSettings();
        var claim = tica.claim(Instant.parse("2026-10-19T10:00:00Z"));
        tica.complete(claim, parser.parse(HEADER + line("1", "5", "100,00"), "2132", DATE, DATE), Instant.parse("2026-10-19T10:00:01Z"));
    }

    private TicaSettlementService.Request request(String ref) {
        return new TicaSettlementService.Request(ref, "TICA - Brussel", DATE, DATE, DATE, DATE,
                new BigDecimal("100.00"), new BigDecimal("21.00"), new BigDecimal("10.00"), new BigDecimal("2.10"),
                new BigDecimal("108.90"), "Synthetic source document", "https://example.invalid/settlement.pdf");
    }

    @Test void documentCreatesOneOffsetCostAndNoAdditionalRevenueOnConfirmationRetries() {
        var draft = settlements.create(request("TEST-SETTLEMENT-1"));
        assertEquals("DRAFT", draft.status()); assertNull(draft.commissionCostId());
        assertEquals(draft.id(), settlements.create(request("TEST-SETTLEMENT-1")).id());
        var confirmed = settlements.confirm(draft.id());
        assertEquals("OPEN", confirmed.status()); assertNotNull(confirmed.commissionCostId());
        var cost = costs.view(costs.get(confirmed.commissionCostId()));
        assertEquals(new BigDecimal("10.00"), cost.cost().amountExclEur());
        assertEquals(new BigDecimal("2.10"), cost.cost().vatEur());
        assertEquals(new BigDecimal("0.00"), cost.openEur());
        assertEquals(1, cost.payments().size()); assertNull(cost.payments().getFirst().bankStatementLineId());
        assertTrue(cost.payments().getFirst().settlementOffset()); assertEquals(confirmed.id(), cost.ticaSettlementId());
        assertNull(cost.payments().getFirst().account());
        assertEquals("TICA", cost.cost().salesChannel());
        assertEquals(confirmed.commissionCostId(), settlements.confirm(draft.id()).commissionCostId());
        assertEquals(new BigDecimal("100.00"), tica.analytics().getFirst().revenueExcl());
        assertThrows(BusinessRuleException.class, () -> costs.delete(confirmed.commissionCostId()));
        assertThrows(BusinessRuleException.class, () -> costs.addPayment(confirmed.commissionCostId(),
                new CostPayment(null, confirmed.commissionCostId(), DATE, new BigDecimal("12.10"), null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> settlements.update(draft.id(), request("TEST-SETTLEMENT-1")));
    }

    @Test void sourceMismatchAndOverlappingPeriodsNeverCreateCommissionCosts() {
        var initial = request("TEST-MISMATCH");
        var mismatch = new TicaSettlementService.Request(initial.reference(), initial.location(), DATE, DATE, DATE, DATE,
                new BigDecimal("101.00"), initial.salesVat(), initial.commissionExcl(), initial.commissionVat(),
                new BigDecimal("109.90"), initial.documentNote(), initial.sourceDocumentUrl());
        var draft = settlements.create(mismatch);
        assertThrows(BusinessRuleException.class, () -> settlements.confirm(draft.id()));
        assertNull(settlements.get(draft.id()).commissionCostId());
        var first = settlements.create(request("TEST-FIRST")); settlements.confirm(first.id());
        var second = settlements.create(request("TEST-OVERLAP"));
        assertThrows(BusinessRuleException.class, () -> settlements.confirm(second.id()));
        assertNull(settlements.get(second.id()).commissionCostId());
    }

    @Test void receiptMatchesOnlyOpenSettlementAndOtherBankPathsCannotReuseOrClearIt() {
        var draft = settlements.create(request("TEST-BANK")); var confirmed = settlements.confirm(draft.id());
        long bad = bankLine("109.00");
        assertThrows(BusinessRuleException.class, () -> settlements.linkBank(draft.id(), bad));
        long lineId = bankLine("108.91");
        var paid = settlements.linkBank(draft.id(), lineId);
        assertEquals("PAID", paid.status()); assertEquals(lineId, paid.bankStatementLineId());
        assertEquals(paid.id(), settlements.linkBank(draft.id(), lineId).id());
        var line = bank.list().stream().filter(r -> r.id == lineId).findFirst().orElseThrow();
        assertTrue(line.allocated); assertEquals(new BigDecimal("108.90"), line.allocatedAmountEur);
        assertEquals(new BigDecimal("0.01"), line.allocationDifferenceEur);
        assertThrows(BusinessRuleException.class, () -> bank.allocate(lineId, new BankStatementService.Allocation(999, null)));
        assertThrows(BusinessRuleException.class, () -> classifications.unclassify(lineId));
        assertThrows(BusinessRuleException.class, () -> bank.delete(lineId));
        long outgoing = bankLine("-12.10");
        assertThrows(BusinessRuleException.class, () -> bank.linkExisting(outgoing,
                new BankStatementService.OutgoingLinks(java.util.List.of(), confirmed.commissionCostId(), null)));
        assertEquals("OPEN", settlements.unlinkBank(draft.id()).status());
        assertFalse(QuarkusTransaction.requiringNew().call(() -> bank.list().stream().filter(r -> r.id == lineId).findFirst().orElseThrow().allocated));
        assertEquals("OPEN", settlements.unlinkBank(draft.id()).status()); // harmless retry with no link
    }

    @Test void odooIdentityAttachesToExistingManualDocumentWithoutDuplicatingCost() {
        var manual = settlements.create(request("TEST-ODOO")); settlements.confirm(manual.id());
        var imported = settlements.createImported(request("TEST-ODOO"), "odoo:test-namespace:123");
        assertEquals(manual.id(), imported.id());
        assertEquals("odoo:test-namespace:123", imported.sourceKey());
        assertEquals(imported.id(), settlements.createImported(request("TEST-ODOO"), "odoo:test-namespace:123").id());
        assertTrue(settlements.findBySource("odoo:test-namespace:123").isPresent());
        assertThrows(BusinessRuleException.class, () -> settlements.createImported(request("TEST-OTHER-REF"), "odoo:test-namespace:123"));
    }

    @Test void anExistingManualCostForTheSameTicaDocumentCannotBeBookedTwice() {
        costs.create(new be.enrosed.finance.domain.CompanyCost(null, DATE, "COMMISSIE", "Existing TICA commission", "TICA Brussel",
                new BigDecimal("10.00"), new BigDecimal("12.10"), new BigDecimal("21.00"), "TEST-DUPLICATE-COST", null, "TICA", null,
                null, null, null, be.enrosed.finance.domain.VatRegime.BE, true, "BE", be.enrosed.finance.domain.CostKind.SERVICES,
                be.enrosed.finance.domain.ExpenseType.STANDARD, new BigDecimal("100"), new BigDecimal("100"), null, null, DATE));
        var draft = settlements.create(request("TEST-DUPLICATE-COST"));
        assertThrows(BusinessRuleException.class, () -> settlements.confirm(draft.id()));
        assertNull(settlements.get(draft.id()).commissionCostId());
    }

    @Test void changedOrUnavailableOdooSourcesCannotBeConfirmed() {
        String sourceKey = "odoo:test-namespace:" + UUID.randomUUID();
        var draft = settlements.createImported(request("TEST-SOURCE-REVIEW"), sourceKey);
        assertThrows(BusinessRuleException.class, () -> settlements.confirm(draft.id()));
        QuarkusTransaction.requiringNew().run(() -> {
            var source = new be.enrosed.finance.odoo.OdooTicaDocumentEntity();
            source.id = sourceKey; source.sourceNamespace = UUID.randomUUID().toString(); source.moveId = 987;
            source.sourceState = "posted"; source.lastSeenAt = Instant.now(); source.code = "SOURCE_CHANGED";
            source.acceptedHash = "a".repeat(64); source.observedHash = "b".repeat(64); source.settlementId = draft.id();
            entities.persist(source);
        });
        assertThrows(BusinessRuleException.class, () -> settlements.confirm(draft.id()));
        assertNull(settlements.get(draft.id()).commissionCostId());
        QuarkusTransaction.requiringNew().run(() -> {
            var source = entities.find(be.enrosed.finance.odoo.OdooTicaDocumentEntity.class, sourceKey);
            source.code = "IMPORTED"; source.observedHash = source.acceptedHash;
        });
        assertEquals("OPEN", settlements.confirm(draft.id()).status());
    }

    @Test void manualDraftRequiresItsStatementDocumentBeforeFinancialConfirmation() {
        var source = request("TEST-DOCUMENT-REQUIRED");
        var draft = settlements.create(new TicaSettlementService.Request(source.reference(), source.location(), source.documentDate(),
                source.dueDate(), source.periodFrom(), source.periodTo(), source.salesExcl(), source.salesVat(), source.commissionExcl(),
                source.commissionVat(), source.netReceivable(), source.documentNote(), null));
        assertThrows(BusinessRuleException.class, () -> settlements.confirm(draft.id()));
        assertNull(settlements.get(draft.id()).commissionCostId());
        settlements.update(draft.id(), source);
        assertEquals("OPEN", settlements.confirm(draft.id()).status());
    }

    @Test void settlementEndpointsAreNotAnonymous() {
        given().get("/api/tica/settlements").then().statusCode(401);
        given().get("/api/tica/analytics").then().statusCode(401);
        given().contentType("application/json").body("{}").post("/api/tica/settlements/1/confirm").then().statusCode(401);
    }

    private long bankLine(String amount) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var row = new BankStatementLineEntity(); row.account = "KBC"; row.fingerprint = UUID.randomUUID().toString();
            row.amountEur = new BigDecimal(amount); row.bookedAt = Instant.parse("2026-10-19T11:00:00Z"); row.timeZone = "Europe/Brussels";
            row.reference = "TICA-TEST-" + UUID.randomUUID(); row.recordedAt = Instant.now(); entities.persist(row); entities.flush(); return row.id;
        });
    }
}
