package be.enrosed.finance.application;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.adapter.out.persistence.CostPartyEntity;
import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.finance.banking.BankStatementLineEntity;
import be.enrosed.finance.domain.CostParty;
import be.enrosed.finance.odoo.OdooCostImportEntity;
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

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CostPartyMergePersistenceTest {
    @Inject CostPartyService service;
    @Inject CostParties parties;
    @Inject FairSeed seed;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void mergeSurvivesBackfillPreservesEveryFinancialFieldAndRestoresTheOriginalParty() throws Exception {
        String suffix = UUID.randomUUID().toString();
        CostParty createdSource = service.create(new CostParty(null, "Linc bank " + suffix, "OVERIG", BigDecimal.ZERO, null, null, null));
        CostParty createdTarget = service.create(new CostParty(null, "Linc facturen " + suffix, "BOEKHOUDER", new BigDecimal("21"), null, null, null));
        em.flush();
        em.clear();
        CostParty source = service.get(createdSource.id());
        CostParty target = service.get(createdTarget.id());
        String originalSource = json.writeValueAsString(em.find(CostPartyEntity.class, source.id()).toDomain());
        var bank = new BankStatementLineEntity();
        bank.account = "alias-test"; bank.fingerprint = suffix; bank.amountEur = new BigDecimal("-363.00");
        bank.bookedAt = Instant.parse("2026-10-01T10:00:00Z"); bank.timeZone = "Europe/Brussels";
        bank.recordedAt = bank.bookedAt; bank.counterparty = source.name(); bank.source = "ODOO"; bank.sourceId = suffix;
        bank.reference = "Original bank source reference"; em.persist(bank);
        var cost = new CompanyCostEntity();
        cost.date = LocalDate.of(2026, 10, 1); cost.category = "BOEKHOUDER"; cost.description = "Distinct original invoice";
        cost.party = source.name(); cost.amountExclEur = new BigDecimal("300.00"); cost.amountInclEur = new BigDecimal("363.00");
        cost.vatPct = new BigDecimal("21.00"); cost.reference = suffix; cost.bankStatementLineId = bank.id; em.persist(cost);
        var payment = new CostPaymentEntity();
        payment.companyCostId = cost.id; payment.paidOn = cost.date; payment.amountEur = new BigDecimal("363.00");
        payment.bankStatementLineId = bank.id; payment.account = bank.account; payment.recordedAt = bank.bookedAt; em.persist(payment);
        var imported = new OdooCostImportEntity();
        imported.id = suffix; imported.namespace = suffix; imported.companyId = 1; imported.moveId = 42;
        imported.companyCostId = cost.id; imported.snapshotHash = suffix; imported.snapshotJson = "{\"reference\":\"original\"}";
        imported.documentsJson = "[{\"mediaAssetId\":123}]"; imported.createdAt = bank.bookedAt; em.persist(imported);
        em.flush();
        String financialBefore = json.writeValueAsString(List.of(cost, payment, bank, imported));
        long partyCount = em.createQuery("select count(p) from CostPartyEntity p", Long.class).getSingleResult();

        CostParty merged = service.merge(source.id(), target.id());
        assertEquals(List.of(source.name()), merged.aliases());
        assertEquals(new BigDecimal("21.00"), merged.vatPct());
        assertEquals(target.id(), parties.findByKey(CostParty.keyOf(source.name())).orElseThrow().id());
        assertFalse(service.list().stream().anyMatch(p -> p.id().equals(source.id())));
        assertEquals(source, service.aliases(target.id()).getFirst().party());
        assertEquals(target, service.merge(source.id(), target.id()).withAliases(List.of()), "lost-response retry is idempotent");

        seed.onStart(null);
        em.flush();
        assertEquals(partyCount, em.createQuery("select count(p) from CostPartyEntity p", Long.class).getSingleResult(),
                "startup backfill recognises the bank spelling through its alias");
        assertEquals(financialBefore, json.writeValueAsString(List.of(cost, payment, bank, imported)));
        assertEquals(originalSource, json.writeValueAsString(em.find(CostPartyEntity.class, source.id()).toDomain()),
                "all original source defaults remain intact");

        assertEquals(source, service.restore(source.id()));
        assertEquals(source.id(), parties.findByKey(CostParty.keyOf(source.name())).orElseThrow().id());
        assertTrue(service.aliases(target.id()).isEmpty());
        assertEquals(financialBefore, json.writeValueAsString(List.of(cost, payment, bank, imported)));
    }
}
