package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Round 2 (R2-3) over the wire: an invoice created with its file in one
 * multipart call next to the JSON one, a file given to an invoice, and one
 * invoice assigned to every part-payment.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class SourcingResourceInvoiceHttpTest {
    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entities;

    @Test
    void anInvoiceWithItsFileServesEveryPartPayment() {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Supplier supplier = suppliers.save(new Supplier(null, "Invoice wire " + UUID.randomUUID(), "CN", "Yiwu",
                    null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
            PurchaseOrder order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                    BigDecimal.ZERO);
            purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 8, 2), new BigDecimal("9314.41"), Currency.EUR,
                    "Aanbetaling 30%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
            purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 10), new BigDecimal("21733.63"), Currency.EUR,
                    "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
            return new long[] {order.id(), supplier.id()};
        });
        long id = ids[0];
        String base = "/api/purchase-orders/" + id + "/invoices";
        try {
            given().multiPart("file", "CI-2026-118.pdf", "%PDF-1.4 CI".getBytes(), "application/pdf")
                    .multiPart("payee", "SUPPLIER").multiPart("number", "CI-2026-118")
                    .multiPart("invoiceDate", "2026-08-20").multiPart("amount", "31048.04")
                    .multiPart("currency", "EUR").multiPart("assignAll", "true")
                    .post(base).then().statusCode(201)
                    .body("number", equalTo("CI-2026-118"))
                    .body("documentName", equalTo("CI-2026-118.pdf"))
                    .body("documentId", notNullValue())
                    .body("paymentIds", hasSize(2));
            given().multiPart("file", "x.pdf", "%PDF-1.4 x".getBytes(), "application/pdf")
                    .multiPart("payee", "SUPPLIER").multiPart("number", "X").multiPart("invoiceDate", "20/08/2026")
                    .multiPart("amount", "10").post(base).then().statusCode(409)
                    .body("message", equalTo("De factuurdatum moet JJJJ-MM-DD zijn"));

            int correction = given().contentType("application/json").body(Map.of("payee", "SUPPLIER",
                            "number", "CI-2026-140", "invoiceDate", "2026-09-01", "amount", 500))
                    .post(base).then().statusCode(201).extract().path("id");
            given().contentType("application/json").body("{}").post(base + "/" + correction + "/assign-all").then().statusCode(200)
                    .body("find { it.number == 'CI-2026-140' }.paymentIds", hasSize(2))
                    .body("find { it.number == 'CI-2026-118' }.paymentIds", empty());
            given().multiPart("file", "CI-2026-140.pdf", "%PDF-1.4 correction".getBytes(), "application/pdf")
                    .post(base + "/" + correction + "/document").then().statusCode(200)
                    .body("documentName", equalTo("CI-2026-140.pdf"));
            given().multiPart("file", "again.pdf", "%PDF-1.4 again".getBytes(), "application/pdf")
                    .post(base + "/" + correction + "/document").then().statusCode(409)
                    .body("message", equalTo("Bij factuur CI-2026-140 hoort al een bestand (CI-2026-140.pdf); verwijder dat eerst"));

            given().get("/api/purchase-orders/" + id).then().statusCode(200)
                    .body("invoices", hasSize(2))
                    .body("invoiceFiles", empty());
            given().get("/api/purchase-orders/" + id + "/documents").then().statusCode(200)
                    .body("paymentId", equalTo(java.util.Arrays.asList(null, null)))
                    .body("kind", equalTo(List.of("COMMERCIAL_INVOICE", "COMMERCIAL_INVOICE")));
            given().contentType("application/json").body("{}").post("/api/purchase-orders/999999/invoices/1/assign-all").then().statusCode(404);
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                entities.createNativeQuery("delete from purchase_invoice where order_id = :id").setParameter("id", id).executeUpdate();
                entities.createQuery("delete from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseDocumentEntity where orderId = :id").setParameter("id", id).executeUpdate();
                entities.createQuery("delete from PurchasePaymentEntity where orderId = :id").setParameter("id", id).executeUpdate();
                entities.createNativeQuery("delete from purchase_order_line where order_id = :id").setParameter("id", id).executeUpdate();
                entities.createNativeQuery("delete from purchase_order where id = :id").setParameter("id", id).executeUpdate();
                entities.createNativeQuery("delete from supplier where id = :id").setParameter("id", ids[1]).executeUpdate();
            });
        }
    }
}
