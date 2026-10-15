package be.enrosed.finance.banking;

import be.enrosed.sourcing.adapter.in.rest.SourcingResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The split routes have a prefix of their own: admin only, and the sourcing payment routes stay reachable. */
@QuarkusTest
class PurchasePaymentSplitResourceHttpTest {
    @Inject ObjectMapper json;

    @Test
    void theSplitRoutesAreNotPublic() {
        given().contentType("application/json").body("{}").post("/api/purchase-payment-splits/1/bank-lines")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "fixture-staff", roles = "staff")
    void theSplitRoutesNeedAnAdmin() {
        given().contentType("application/json").body("{}").post("/api/purchase-payment-splits/1/bank-part")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "fixture-admin", roles = "admin")
    void refusalsAnswerInDutchAndUnknownPaymentsAreNotFound() {
        given().contentType("application/json").body(Map.of("lines", java.util.List.of()))
                .post("/api/purchase-payment-splits/999999/bank-lines")
                .then().statusCode(409).body("message", equalTo("Kies één tot tien banktransacties"));
        given().contentType("application/json").body(Map.of("bankStatementLineId", 1))
                .post("/api/purchase-payment-splits/999999/bank-part").then().statusCode(404);
        given().contentType("application/json").body(Map.of("amountEur", 300))
                .post("/api/purchase-payment-splits/999999/inspection")
                .then().statusCode(409).body("message", equalTo("Vernieuw de betaling en probeer opnieuw"));
        /* The sourcing routes for one payment are not shadowed. */
        given().contentType("application/json").body(Map.of("amount", 10))
                .put("/api/purchase-orders/999999/payments/1").then().statusCode(404);
        given().contentType("application/json").body(Map.of("value", true))
                .put("/api/purchase-orders/999999/inspection-via-supplier").then().statusCode(404);
        given().get("/api/purchase-orders/999999/invoices").then().statusCode(404);
    }

    /** Round 2: the owner's choice for a larger transfer and the other parts travel in the bank-part body. */
    @Test
    void aBankPartBodyCarriesTheChoiceAndTheAnswerItsNotice() throws Exception {
        var plain = json.readValue("{\"bankStatementLineId\":5}", PurchasePaymentSplitService.PartRequest.class);
        assertNull(plain.higher());
        assertNull(plain.withPaymentIds());
        assertNull(plain.expectedAmountEur());
        var chosen = json.readValue("{\"bankStatementLineId\":5,\"higher\":\"INSPECTION\",\"withPaymentIds\":[7],"
                + "\"expectedAmountEur\":10250.00}", PurchasePaymentSplitService.PartRequest.class);
        assertEquals(PurchasePaymentSplitService.Higher.INSPECTION, chosen.higher());
        assertEquals(java.util.List.of(7L), chosen.withPaymentIds());
        assertEquals(0, new java.math.BigDecimal("10250").compareTo(chosen.expectedAmountEur()));
        var tree = json.readTree(json.writeValueAsString(new PurchasePaymentSplitService.SplitResult(
                java.util.List.of(), java.util.List.of(), "Datum gevolgd van de transactie: 05/09/2026 (was 03/09/2026).")));
        assertEquals("Datum gevolgd van de transactie: 05/09/2026 (was 03/09/2026).", tree.get("notice").asText());
        assertTrue(json.readTree(json.writeValueAsString(new PurchasePaymentSplitService.SplitResult(
                java.util.List.of(), java.util.List.of()))).has("notice"));
    }

    @Test
    void aMissingInvoiceKeepsTheStoredChoiceAndAnExplicitNullMeansAutomatic() throws Exception {
        var missing = json.readValue("{\"amount\":10}", SourcingResource.PaymentRequest.class);
        assertFalse(missing.invoiceIdProvided());
        assertNull(missing.applyToGroup);
        var automatic = json.readValue("{\"amount\":10,\"invoiceId\":null}", SourcingResource.PaymentRequest.class);
        assertTrue(automatic.invoiceIdProvided());
        assertNull(automatic.invoiceId());
        var chosen = json.readValue("{\"amount\":10,\"invoiceId\":7,\"applyToGroup\":true,\"inspectionViaSupplier\":true}",
                SourcingResource.PaymentRequest.class);
        assertEquals(7L, chosen.invoiceId());
        assertTrue(chosen.applyToGroup);
        assertTrue(chosen.inspectionViaSupplier);
    }
}
