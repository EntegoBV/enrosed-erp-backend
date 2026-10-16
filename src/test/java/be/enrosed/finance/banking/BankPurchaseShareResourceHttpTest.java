package be.enrosed.finance.banking;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** POST /api/bank-statements/{id}/purchase-share: admin only, Dutch refusals, the answer the sheet reads. */
@QuarkusTest
class BankPurchaseShareResourceHttpTest {
    private static final Instant AT = Instant.parse("2026-09-28T08:00:00Z");

    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject BankStatementService bank;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test
    void theRouteIsNotPublic() {
        given().contentType("application/json").body("{}").post("/api/bank-statements/1/purchase-share")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "fixture-staff", roles = "staff")
    void theRouteNeedsAnAdmin() {
        given().contentType("application/json").body("{}").post("/api/bank-statements/1/purchase-share")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "fixture-admin", roles = "admin")
    void refusalsAnswerInDutchAndAnUnknownLineIsNotFound() {
        given().contentType("application/json").body(Map.of("parts", List.of()))
                .post("/api/bank-statements/999999/purchase-share")
                .then().statusCode(409).body("message", equalTo("Kies één tot tien delen"));
        given().contentType("application/json").body(Map.of("parts", List.of(Map.of("amountEur", 10))))
                .post("/api/bank-statements/999999/purchase-share")
                .then().statusCode(409).body("message", equalTo("Kies per deel een bestaande betaling of een container"));
        given().contentType("application/json").body(Map.of("parts", List.of(Map.of("purchaseOrderId", 1, "amountEur", 10))))
                .post("/api/bank-statements/987654321/purchase-share").then().statusCode(404);
    }

    @Test
    void theBodyReadsAsTheSheetSendsIt() throws Exception {
        var request = json.readValue("{\"parts\":[{\"paymentId\":381,\"amountEur\":3801.13,\"expectedAmountEur\":3070.20},"
                + "{\"purchaseOrderId\":40,\"amountEur\":3801.12,\"payee\":\"SUPPLIER\",\"instalmentDue\":\"ORDERED\","
                + "\"label\":\"30% bij bestelling\",\"settles\":false,\"invoiceId\":null,\"inspectionViaSupplier\":null}]}",
                BankPurchaseShareService.ShareRequest.class);
        var existing = request.parts().getFirst();
        assertEquals(381L, existing.paymentId());
        assertNull(existing.purchaseOrderId());
        assertEquals(0, new BigDecimal("3070.20").compareTo(existing.expectedAmountEur()));
        var created = request.parts().get(1);
        assertEquals(40L, created.purchaseOrderId());
        assertEquals(PaymentTerms.Moment.ORDERED, created.instalmentDue());
        assertFalse(created.settles());
        assertNull(created.amount());
        assertNull(created.currency());
    }

    @Test
    @TestSecurity(user = "emre", roles = "admin")
    void aShareAnswersTheLineThePaymentsAndWhatHappenedPerPart() {
        long[] f = QuarkusTransaction.requiringNew().call(() -> {
            var supplier = suppliers.save(new Supplier(null, "Share endpoint supplier", "CN", "Kunming", null, null, null,
                    Currency.USD, "FOB", "Ningbo", 30, null));
            var a = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
            var b = purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
            var open = purchases.addPayment(a.id(), LocalDate.of(2026, 9, 20), new BigDecimal("3070.20"), Currency.EUR,
                    "30% bij bestelling", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
            long line = bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                    "BE94731074082814", new BigDecimal("-7602.25"), AT, "Europe/Brussels", "DEPOSIT", "Supplier", AT, false)).id();
            return new long[]{a.id(), b.id(), supplier.id(), open.id(), line};
        });
        try {
            given().contentType("application/json").body(Map.of("parts", List.of(
                            Map.of("paymentId", f[3], "amountEur", 3801.13, "expectedAmountEur", 3070.20),
                            Map.of("purchaseOrderId", f[1], "amountEur", 3801.12, "payee", "SUPPLIER", "instalmentDue", "ORDERED",
                                    "label", "30% bij bestelling", "settles", false))))
                    .post("/api/bank-statements/" + f[4] + "/purchase-share").then().statusCode(200)
                    .body("line.allocated", equalTo(true))
                    .body("line.allocationDifferenceEur", equalTo(0.0f))
                    .body("payments", hasSize(2))
                    .body("payments[0].id", equalTo((int) f[3]))
                    .body("payments[0].bankStatementLineId", equalTo((int) f[4]))
                    .body("payments[0].paidOn", equalTo("2026-09-28"))
                    .body("payments[1].orderId", equalTo((int) f[1]))
                    .body("parts", hasSize(2))
                    .body("parts[0].outcome", equalTo("GREW"))
                    .body("parts[0].paymentId", equalTo((int) f[3]))
                    .body("parts[0].wasEur", equalTo(3070.2f))
                    .body("parts[0].wasPaidOn", equalTo("2026-09-20"))
                    .body("parts[0].restPaymentId", nullValue())
                    .body("parts[1].outcome", equalTo("CREATED"))
                    .body("parts[1].paymentId", notNullValue())
                    .body("parts[1].amountEur", equalTo(3801.12f));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createQuery("delete from PurchasePaymentBankAdjustmentEntity a where a.paymentId=:id")
                        .setParameter("id", f[3]).executeUpdate();
                em.createQuery("delete from PurchasePaymentEntity p where p.orderId in :ids")
                        .setParameter("ids", List.of(f[0], f[1])).executeUpdate();
                em.remove(em.find(BankStatementLineEntity.class, f[4]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, f[0]));
                em.remove(em.find(SourcingEntities.PurchaseOrderEntity.class, f[1]));
                em.remove(em.find(SourcingEntities.SupplierEntity.class, f[2]));
            });
        }
    }
}
