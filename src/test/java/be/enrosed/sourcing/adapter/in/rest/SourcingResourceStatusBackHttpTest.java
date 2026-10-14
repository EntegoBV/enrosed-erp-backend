package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The step back travels as {@code ?stepBack=true} on the ordinary form save.
 * Without it, an earlier status in the body is an outdated screen and the
 * container keeps its status and departure day.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class SourcingResourceStatusBackHttpTest {
    private static final LocalDate DEPARTED_ON = LocalDate.of(2026, 9, 20);

    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entities;

    @Test
    void onlyTheFlaggedSaveStepsAContainerBack() {
        /* A placed container of one product, marked departed on a known day. */
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            String tag = UUID.randomUUID().toString();
            Supplier supplier = suppliers.save(new Supplier(null, "Status back " + tag, "CN", "Yiwu",
                    null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
            ProductEntity product = new ProductEntity();
            product.sku = "BACK-" + tag;
            product.name = "Status back rose";
            product.active = true;
            product.supplierId = supplier.id();
            product.piecesPerCarton = 10;
            product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
            product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
            entities.persist(product);
            PurchaseOrder concept = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"),
                    new BigDecimal("0.90"), BigDecimal.TEN);
            long id = purchaseOrders.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.BESTELD, null, null,
                    false, null, List.of(new PurchaseOrderLine(null, product.id, 100, BigDecimal.TEN, Currency.USD,
                            null, null)))).order().id();
            PurchaseOrderEntity entity = entities.find(PurchaseOrderEntity.class, id);
            entity.status = PurchaseOrderStatus.ONDERWEG;
            entity.shippedOn = DEPARTED_ON;
            return new long[] {id, product.id, supplier.id()};
        });
        long id = ids[0];
        String path = "/api/purchase-orders/" + id;
        try {
            Map<String, Object> outdated = given().get(path).then().statusCode(200)
                    .extract().jsonPath().getMap("order");
            outdated.put("status", "BESTELD");

            given().contentType("application/json").body(outdated).put(path).then().statusCode(409)
                    .body("message", equalTo("Deze container staat intussen op Vertrokken; laad hem opnieuw voor je opslaat."));
            given().get(path).then().statusCode(200)
                    .body("order.status", equalTo("ONDERWEG"))
                    .body("order.shippedOn", equalTo(DEPARTED_ON.toString()));

            given().contentType("application/json").body(outdated).queryParam("stepBack", true).put(path)
                    .then().statusCode(200)
                    .body("order.status", equalTo("BESTELD"))
                    .body("order.shippedOn", nullValue());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
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
