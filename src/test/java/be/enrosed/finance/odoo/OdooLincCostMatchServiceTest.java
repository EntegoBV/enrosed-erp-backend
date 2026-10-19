package be.enrosed.finance.odoo;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.banking.BankStatementLineEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class OdooLincCostMatchServiceTest {
    @Inject OdooLincCostMatchService service;
    @Inject CompanyCostService costs;
    @Inject OdooCostImportStore imports;
    @Inject EntityManager em;
    @Inject ObjectMapper json;
    @InjectMock OdooClient client;
    @InjectMock OdooDocumentService documents;
    private OdooConfiguration config;
    private OdooConfiguration previousConfig;
    private boolean previousAutomaticEnabled;
    private long documentId;
    private String reference;
    private static final String STRUCTURED = "260108481132";
    private List<Long> remoteBankIds;
    private boolean hasInvoice;

    @BeforeEach void setup() throws Exception {
        reset(client, documents);
        documentId = 100000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000);
        reference = "LINC-TEST-" + documentId;
        config = new OdooConfiguration(true, Optional.of("https://linc-test.example"), Optional.of("linc-test"),
                Optional.of("test-only-placeholder"), Optional.of(1L), Optional.of("9"));
        OdooLincCostMatchService actual = io.quarkus.arc.ClientProxy.unwrap(service);
        previousConfig = actual.config; previousAutomaticEnabled = actual.automaticEnabled;
        actual.config = config; actual.automaticEnabled = true;
        makeDue(); remoteBankIds = new ArrayList<>(); hasInvoice = true;
        when(client.fields("account.move")).thenReturn(Set.of("payment_reference"));
        when(client.search(anyString(), anyList(), anyList(), anyInt(), anyString())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0); List<?> domain = invocation.getArgument(1); List<?> fields = invocation.getArgument(2);
            if (model.equals("res.company")) return json.readTree("[{\"id\":1,\"vat\":\"BE1034273386\"}]");
            if (model.equals("res.partner")) return json.readTree("[{\"id\":21,\"vat\":\"BE1018089234\"}]");
            if (model.equals("account.move")) {
                if (!fields.contains("company_id")) return json.readTree("[{\"id\":" + documentId + ",\"payment_reference\":\"+++260/1084/81132+++\"}]");
                boolean nextPage = domain.stream().filter(List.class::isInstance).map(List.class::cast)
                        .anyMatch(term -> term.size() == 3 && "id".equals(term.get(0)) && ">".equals(term.get(1)) && ((Number) term.get(2)).longValue() >= documentId);
                if (nextPage || !hasInvoice) return json.readTree("[]");
                return json.readTree("[{\"id\":" + documentId + ",\"company_id\":[1,\"ENROSED\"],\"partner_id\":[21,\"Linc Partners NV\"],\"move_type\":\"in_invoice\",\"state\":\"draft\",\"payment_reference\":\"+++260/1084/81132+++\"}]");
            }
            if (model.equals("account.bank.statement.line")) {
                var array = json.createArrayNode();
                for (long id : remoteBankIds) array.add(json.readTree("{\"id\":" + id + ",\"company_id\":[1,\"ENROSED\"],\"partner_id\":[21,\"Linc Partners NV\"],\"journal_id\":[9,\"Bank\"],\"amount\":-121.00,\"date\":\"2026-10-15\",\"payment_ref\":\"LINC Partners NV CORE-LP-TEST SCOR BBA " + STRUCTURED + "\"}"));
                return array;
            }
            throw new AssertionError("Unexpected model " + model);
        });
        when(documents.purchaseSnapshot(documentId)).thenReturn(snapshot(reference));
        when(documents.download(documentId, 22)).thenReturn(new OdooDocumentService.Download("synthetic-invoice.xml", "application/xml", "<Invoice>synthetic test</Invoice>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @AfterEach void restoreConfiguration() {
        OdooLincCostMatchService actual = io.quarkus.arc.ClientProxy.unwrap(service);
        actual.config = previousConfig; actual.automaticEnabled = previousAutomaticEnabled;
    }

    private OdooDocumentService.PurchaseSnapshot snapshot(String sourceReference) {
        var document = new OdooDocumentService.Document(documentId, "BILL/" + documentId, sourceReference, "Test " + documentId + " Linc Partners NV",
                "2026-10-01", "in_invoice", "draft", "EUR", new BigDecimal("121.00"), "done", null,
                List.of(new OdooDocumentService.Attachment(22, "synthetic-invoice.xml", "application/xml", 39, "XML")));
        return new OdooDocumentService.PurchaseSnapshot(config.sourceNamespace(), 1, document, 21, "BE1018089234", "BE",
                new BigDecimal("100.00"), new BigDecimal("21.00"), new BigDecimal("121.00"), "2026-10-01 12:00:00", "a".repeat(64));
    }

    @Test void exactInvoiceAndDirectDebitAreRegisteredOnceWithOriginalAttachment() {
        long bank = addBank(501);
        var first = service.syncIfDue().orElseThrow();
        assertEquals(1, first.registered(), first.toString()); assertTrue(first.complete());
        long costId = first.items().getFirst().companyCostId();
        assertEquals(bank, first.items().getFirst().bankStatementLineId());
        var cost = costs.view(costs.get(costId)); assertEquals(new BigDecimal("100.00"), cost.cost().amountExclEur());
        assertEquals(new BigDecimal("21.00"), cost.cost().vatEur()); assertEquals(1, cost.payments().size());
        assertEquals(bank, cost.payments().getFirst().bankStatementLineId());
        assertEquals(1, imports.source(costId).documents().size());
        makeDue(); var repeated = service.syncIfDue().orElseThrow(); assertEquals(1, repeated.existing(), repeated.toString());
        assertEquals(costId, repeated.items().getFirst().companyCostId());
        verify(documents, times(1)).download(documentId, 22);
    }

    @Test void missingInvoiceOrMissingBankNeverCreatesAnExpense() {
        addBank(502); hasInvoice = false;
        var withoutInvoice = service.syncIfDue().orElseThrow(); assertEquals(0, withoutInvoice.scanned());
        verifyNoInteractions(documents);
        hasInvoice = true; remoteBankIds.clear(); makeDue();
        var withoutBank = service.syncIfDue().orElseThrow(); assertEquals("WAITING_FOR_BANK", withoutBank.items().getFirst().code());
        assertNull(imports.find(config.sourceNamespace(), documentId));
        verify(documents, never()).download(anyLong(), anyLong());
    }

    @Test void exactExistingUnpaidExpenseIsAdoptedWithoutCreatingADuplicate() {
        long bank = addBank(506);
        var manual = costs.createImported(snapshot(reference), "BOEKHOUDER");
        var report = service.syncIfDue().orElseThrow();
        assertEquals(1, report.registered(), report.toString());
        assertEquals(manual.id(), report.items().getFirst().companyCostId());
        assertEquals(1, costs.view(costs.get(manual.id())).payments().size());
        assertEquals(bank, costs.view(costs.get(manual.id())).payments().getFirst().bankStatementLineId());
        assertEquals(1, imports.source(manual.id()).documents().size());
    }

    @Test void duplicateBankCandidatesAndBadManualReferencesRequireReview() {
        addBank(503); addBank(504);
        var ambiguous = service.syncIfDue().orElseThrow(); assertEquals("AMBIGUOUS_BANK", ambiguous.items().getFirst().code());
        remoteBankIds.removeLast(); makeDue();
        var manual = costs.createImported(snapshot("UNRELATED-BANK-TEXT-" + documentId), "BOEKHOUDER");
        var review = service.syncIfDue().orElseThrow(); assertEquals("REVIEW_EXISTING_COST", review.items().getFirst().code(), review.toString());
        var status = service.status();
        assertNotNull(status.lastSuccessAt()); assertEquals("LINC_REVIEW", status.lastCode());
        assertEquals(Duration.ofHours(12), Duration.between(status.lastSuccessAt(), status.nextRunAt()));
        assertNull(imports.find(config.sourceNamespace(), documentId));
        assertEquals(0, costs.view(costs.get(manual.id())).payments().size());
        verify(documents, never()).download(anyLong(), anyLong());
    }

    @Test void wrongRecipientAndInvalidStructuredReferenceCannotTriggerMatching() throws Exception {
        when(client.search(eq("res.company"), anyList(), anyList(), eq(1), eq("id asc")))
                .thenReturn(json.readTree("[{\"id\":1,\"vat\":\"BE0000000000\"}]"));
        var report = service.syncIfDue().orElseThrow(); assertEquals("LINC_COMPANY_MISMATCH", report.code()); assertFalse(report.complete());
        verifyNoInteractions(documents);
        assertEquals(STRUCTURED, OdooLincCostMatchService.structuredReference("+++260/1084/81132+++"));
        assertNull(OdooLincCostMatchService.structuredReference("+++260/1084/81133+++"));
        assertNull(OdooLincCostMatchService.structuredReference("Factuur 2608481"));
        assertTrue(OdooLincCostMatchService.containsStructured("CORE-LP-1190 SCOR BBA 260108481132", STRUCTURED));
        assertFalse(OdooLincCostMatchService.containsStructured("1260108481132", STRUCTURED));
    }

    @Test void technicalProviderFailureRetriesAfterThirtyMinutes() {
        addBank(505);
        when(documents.purchaseSnapshot(documentId)).thenThrow(new OdooFailure("UNAVAILABLE"));
        var report = service.syncIfDue().orElseThrow();
        assertEquals("UNAVAILABLE", report.code()); assertFalse(report.complete());
        var status = service.status();
        assertTrue(Duration.between(Instant.now(), status.nextRunAt()).toMinutes() >= 29);
        assertTrue(Duration.between(Instant.now(), status.nextRunAt()).toMinutes() <= 30);
        assertNull(imports.find(config.sourceNamespace(), documentId));
    }

    private long addBank(long providerId) {
        remoteBankIds.add(providerId);
        return QuarkusTransaction.requiringNew().call(() -> {
            // Provider identities are unique; replace only this test's previous synthetic line if present.
            em.createQuery("delete from BankStatementLineEntity where sourceId = :source").setParameter("source", config.sourceNamespace() + ":" + providerId).executeUpdate();
            var line = new BankStatementLineEntity(); line.account = "LINC_TEST"; line.fingerprint = UUID.randomUUID().toString();
            line.amountEur = new BigDecimal("-121.00"); line.bookedAt = LocalDate.of(2026, 10, 15).atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant();
            line.timeZone = "Europe/Brussels"; line.reference = "LINC Partners NV SCOR BBA " + STRUCTURED;
            line.counterparty = "Linc Partners NV"; line.recordedAt = Instant.now(); line.source = "ODOO"; line.sourceId = config.sourceNamespace() + ":" + providerId;
            em.persist(line); em.flush(); return line.id;
        });
    }
    private void makeDue() {
        QuarkusTransaction.requiringNew().run(() -> {
            var row = em.find(OdooSyncStateEntity.class, OdooLincCostMatchService.scheduleKey(config.sourceNamespace()));
            if (row != null) { row.bankNextAutoAt = Instant.EPOCH; row.leaseUntil = null; row.leaseOwner = null; }
        });
    }
}
