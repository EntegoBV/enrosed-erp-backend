package be.enrosed.finance.odoo;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.banking.BankStatementService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The user's own route for a received Odoo invoice (a bank line's list, preview and registration), over HTTP as the
 * Facturen "Registreren bij transactie…" sheet and the bank panel call it, one request each. FIX2 (2026-10-26): after
 * the cost of a registered invoice was deleted in ENROSED, the invoice is offered and booked again as a new cost bound to
 * its source.
 */
@QuarkusTest
class OdooPurchaseImportServiceTest {
    private static final String HASH = "d".repeat(64);
    @Inject OdooPurchaseImportService service;
    @Inject CompanyCostService costs;
    @Inject BankStatementService bank;
    @Inject OdooCostImportStore imports;
    @Inject EntityManager em;
    @InjectMock OdooDocumentService documents;
    private OdooConfiguration config;
    private OdooConfiguration previousConfig;
    private long documentId;

    @BeforeEach void setup() {
        documentId = 300000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000);
        config = new OdooConfiguration(true, Optional.of("https://purchase-import-test.example"), Optional.of("purchase-import-test"),
                Optional.of("test-only-placeholder"), Optional.of(1L), Optional.empty());
        OdooPurchaseImportService actual = io.quarkus.arc.ClientProxy.unwrap(service);
        previousConfig = actual.config; actual.config = config;
        var document = new OdooDocumentService.Document(documentId, "BILL/" + documentId, "PI-" + documentId, "Purchase Import " + documentId,
                "2026-09-24", "in_invoice", "posted", "EUR", new BigDecimal("121.00"), "done", null,
                List.of(new OdooDocumentService.Attachment(31, "bill.xml", "application/xml", 40, "XML")));
        when(documents.purchaseSnapshot(documentId)).thenReturn(new OdooDocumentService.PurchaseSnapshot(config.sourceNamespace(), 1, document,
                41, "BE0123456789", "BE", new BigDecimal("100.00"), new BigDecimal("21.00"), new BigDecimal("121.00"), "2026-09-24 12:00:00", HASH));
        when(documents.documents("incoming", 0, 50)).thenReturn(new OdooDocumentService.Page(List.of(document), documentId, false, true));
        when(documents.download(documentId, 31)).thenReturn(new OdooDocumentService.Download("bill.xml", "application/xml",
                ("<Invoice><ID>synthetic " + documentId + "</ID></Invoice>").getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach void restoreConfiguration() {
        OdooPurchaseImportService actual = io.quarkus.arc.ClientProxy.unwrap(service);
        actual.config = previousConfig;
    }

    @Test @TestSecurity(user = "test-admin", roles = "admin")
    void anInvoiceWhoseCostWasDeletedIsOfferedAndBookedAgainAsANewCost() {
        long line = bank.create(new BankStatementService.ManualRequest("TEST", new BigDecimal("121.00"), BankStatementService.Direction.OUTGOING,
                LocalDate.of(2026, 9, 25).atTime(12, 0).toInstant(ZoneOffset.UTC), "Europe/Brussels", "PI-" + documentId,
                "Purchase Import " + documentId, UUID.randomUUID().toString())).id;
        String base = "/api/bank-statements/" + line;
        Map<String, Object> request = Map.of("odooInvoiceId", documentId, "snapshotHash", HASH);
        Long second = null;
        try {
            long first = given().contentType("application/json").body(request).post(base + "/odoo-purchase-invoice")
                    .then().statusCode(200).body("alreadyRegistered", equalTo(false)).extract().jsonPath().getLong("companyCostId");
            given().get(base + "/odoo-purchase-invoices").then().statusCode(200).body("documents[0].registered", equalTo(true));
            given().delete("/api/costs/" + first).then().statusCode(204); // the app's own delete: payments, media link, cost

            given().get(base + "/odoo-purchase-invoices").then().statusCode(200)
                    .body("documents[0].id", equalTo((int) documentId))
                    .body("documents[0].registered", equalTo(false)).body("documents[0].companyCostId", nullValue());
            given().get(base + "/odoo-purchase-invoices/" + documentId + "/preview").then().statusCode(200)
                    .body("eligible", equalTo(true)).body("unavailableReason", nullValue())
                    .body("companyCostId", nullValue()).body("paymentAmountEur", equalTo(121.0f));

            var again = given().contentType("application/json").body(request).post(base + "/odoo-purchase-invoice")
                    .then().statusCode(200).body("alreadyRegistered", equalTo(false))
                    .body("documentsSaved", equalTo(1)).extract().jsonPath();
            second = again.getLong("companyCostId");
            assertNotEquals(first, second);
            long boundTo = second;
            assertEquals(boundTo, QuarkusTransaction.requiringNew().call(() -> imports.find(config.sourceNamespace(), documentId).companyCostId),
                    "the source is bound to the new cost");
            given().get("/api/costs/" + second).then().statusCode(200)
                    .body("payments.size()", equalTo(1)).body("payments[0].bankStatementLineId", equalTo((int) line));
            given().get(base + "/odoo-purchase-invoices").then().statusCode(200)
                    .body("documents[0].registered", equalTo(true)).body("documents[0].companyCostId", equalTo((int) boundTo));
            verify(documents, times(2)).download(documentId, 31);
        } finally {
            if (second != null) costs.delete(second);
            bank.delete(line);
            QuarkusTransaction.requiringNew().run(() -> {
                var binding = em.find(OdooCostImportEntity.class, config.sourceNamespace() + ":" + documentId);
                if (binding != null) em.remove(binding);
            });
        }
    }
}
