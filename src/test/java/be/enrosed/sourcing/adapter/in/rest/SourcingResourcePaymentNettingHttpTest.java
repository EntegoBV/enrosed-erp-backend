package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PriceBasis;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * PO-2026-002 over the wire: the container view and the purchase list read
 * one reconciliation, so the netted terms and the attention list agree.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class SourcingResourcePaymentNettingHttpTest {
    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entities;

    @Test
    void po35ShowsNothingOpenAndNoPaymentToDo() {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            String tag = UUID.randomUUID().toString();
            Supplier supplier = suppliers.save(new Supplier(null, "Netting " + tag, "CN", "Yiwu",
                    null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
            ProductEntity product = new ProductEntity();
            product.sku = "NET-" + tag;
            product.name = "Netting rose";
            product.active = true;
            product.supplierId = supplier.id();
            product.piecesPerCarton = 1;
            product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
            product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
            entities.persist(product);
            PurchaseOrder concept = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"),
                    new BigDecimal("0.90"), BigDecimal.ZERO);
            long id = purchaseOrders.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null,
                    false, null, List.of(new PurchaseOrderLine(null, product.id, 4, new BigDecimal("7762.01"),
                            Currency.EUR, null, null, PriceBasis.DDP, null)))).order().id();
            PurchaseOrderEntity entity = entities.find(PurchaseOrderEntity.class, id);
            entity.status = PurchaseOrderStatus.ONTVANGEN;
            entity.paymentTerms = PaymentTerms.DEPOSIT_30_70;
            entities.flush();
            purchaseOrders.addPayment(id, LocalDate.of(2026, 3, 2), new BigDecimal("9375.69"), Currency.EUR,
                    "30%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
            purchaseOrders.addPayment(id, LocalDate.of(2026, 6, 2), new BigDecimal("21672.85"), Currency.EUR,
                    "70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
            return new long[] {id, product.id, supplier.id()};
        });
        long id = ids[0];
        try {
            given().get("/api/purchase-orders/" + id).then().statusCode(200)
                    .body("attention", empty())
                    .body("reconciliation.totals.remainingEur", equalTo(0.0f))
                    .body("reconciliation.totals.forecastExternalEur", equalTo(31048.54f))
                    .body("reconciliation.streams.find { it.payee == 'SUPPLIER' }.status", equalTo("OVERPAID"))
                    .body("reconciliation.streams.find { it.payee == 'SUPPLIER' }.overpaidEur", equalTo(0.5f))
                    .body("reconciliation.supplierInstalments.label", equalTo(List.of("30% bij bestelling", "70% bij vertrek")))
                    .body("reconciliation.supplierInstalments.offsetEur", equalTo(List.of(-60.78f, 60.78f)))
                    .body("reconciliation.supplierInstalments.remainingEur", equalTo(List.of(0.0f, 0.0f)))
                    .body("reconciliation.supplierInstalments.overpaidEur", equalTo(List.of(0.5f, 0.0f)))
                    .body("reconciliation.supplierInstalments.finalized", equalTo(List.of(false, true)));
            given().get("/api/purchase-orders").then().statusCode(200)
                    .body("find { it.order.id == " + id + " }.attention", empty());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                entities.createQuery("delete from PurchasePaymentEntity where orderId = :id")
                        .setParameter("id", id).executeUpdate();
                entities.createNativeQuery("delete from purchase_order_line where order_id = :id")
                        .setParameter("id", id).executeUpdate();
                entities.createNativeQuery("delete from purchase_order where id = :id")
                        .setParameter("id", id).executeUpdate();
                entities.createNativeQuery("delete from product where id = :id")
                        .setParameter("id", ids[1]).executeUpdate();
                entities.createNativeQuery("delete from supplier where id = :id")
                        .setParameter("id", ids[2]).executeUpdate();
            });
        }
    }
}
