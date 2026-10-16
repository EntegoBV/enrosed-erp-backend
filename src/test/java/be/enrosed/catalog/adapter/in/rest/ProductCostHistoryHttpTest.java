package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
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
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/products/{id}/cost-history} as the ERP reads it: the
 * basis, kind and parts of every row, the container's name, a copy made
 * through {@code POST /api/products/{id}/duplicate} that starts with the
 * cost it took over, and a trashed container that keeps its name.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductCostHistoryHttpTest {

    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entities;

    @Test
    void theHistoryCarriesBasisKindPartsAndContainerAndACopyStartsWithItsCost() {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            String tag = UUID.randomUUID().toString();
            Supplier supplier = suppliers.save(new Supplier(null, "Cost history http " + tag, "CN", "Yiwu",
                    null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
            ProductEntity product = new ProductEntity();
            product.sku = "CHH-" + tag;
            product.name = "Cost history diamond";
            product.colour = "Wit";
            product.active = true;
            product.supplierId = supplier.id();
            product.piecesPerCarton = 10;
            product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
            product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = new BigDecimal("40");
            product.packagingKind = PackagingKind.DISPLAY;
            product.packagingPiecesPerUnit = 12;
            product.packagingSalesUnit = SalesUnit.DISPLAY;
            product.packagingUnitKey = "diamant";
            entities.persist(product);
            PurchaseOrder concept = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"),
                    new BigDecimal("0.90"), BigDecimal.TEN);
            long id = purchaseOrders.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.CONCEPT, null, null,
                    false, null, List.of(new PurchaseOrderLine(null, product.id, 120, new BigDecimal("1.20"),
                            Currency.USD, new BigDecimal("0.10"), null)))).order().id();
            PurchaseOrderEntity order = entities.find(PurchaseOrderEntity.class, id);
            order.alias = "Kerstcontainer";
            order.freightUsd = new BigDecimal("1000");
            order.extraRevenueEur = new BigDecimal("120");
            return new long[] {id, product.id, supplier.id()};
        });
        long orderId = ids[0];
        long sourceId = ids[1];
        Long copyId = null;
        try {
            given().contentType("application/json").post("/api/purchase-orders/" + orderId + "/apply").then().statusCode(200);

            JsonPath source = given().get("/api/products/" + sourceId + "/cost-history")
                    .then().statusCode(200).extract().jsonPath();
            assertEquals(1, source.getList("$").size());
            Map<String, Object> applied = source.getMap("[0]");
            for (String field : List.of("id", "appliedAt", "landedUnitEur", "previousLandedUnitEur", "deltaEur",
                    "deltaPct", "source", "purchaseOrderId", "quantity", "exwPrice", "exwCurrency", "appliedBy",
                    "kind", "salesUnit", "piecesPerUnit", "unitKey", "extraUnitCost", "goodsEur", "originEur",
                    "freightEur", "dutyEur", "destinationEur", "enrosedEur", "separateEur", "containerName",
                    "containerGone")) {
                assertTrue(applied.containsKey(field), field);
            }
            assertEquals("APPLY", applied.get("kind"));
            assertEquals("DISPLAY", applied.get("salesUnit"));
            assertEquals(12, applied.get("piecesPerUnit"));
            assertEquals("diamant", applied.get("unitKey"));
            assertEquals(0, new BigDecimal("0.1").compareTo(new BigDecimal(applied.get("extraUnitCost").toString())));
            assertEquals(0, BigDecimal.ONE.compareTo(new BigDecimal(applied.get("enrosedEur").toString())));
            assertNotNull(applied.get("goodsEur"));
            assertNotNull(applied.get("freightEur"));
            assertEquals("Kerstcontainer", applied.get("containerName"));
            assertEquals(false, applied.get("containerGone"));
            assertEquals(120, applied.get("quantity"));

            JsonPath copy = given().contentType("application/json").body(Map.of("colour", "Rood"))
                    .post("/api/products/" + sourceId + "/duplicate")
                    .then().statusCode(201).extract().jsonPath();
            copyId = copy.getLong("id");
            List<Map<String, Object>> copied = given().get("/api/products/" + copyId + "/cost-history")
                    .then().statusCode(200).extract().jsonPath().getList("$");
            assertEquals(1, copied.size(), "the copy's history is not empty");
            Map<String, Object> first = copied.getFirst();
            assertEquals("COPY", first.get("kind"));
            assertEquals(applied.get("landedUnitEur").toString(), first.get("landedUnitEur").toString());
            assertNull(first.get("previousLandedUnitEur"));
            assertNull(first.get("deltaEur"));
            assertNull(first.get("quantity"));
            assertEquals(((Number) applied.get("purchaseOrderId")).longValue(),
                    ((Number) first.get("purchaseOrderId")).longValue());
            assertEquals("Kerstcontainer", first.get("containerName"));
            assertEquals(applied.get("goodsEur").toString(), first.get("goodsEur").toString());
            assertEquals("DISPLAY", first.get("salesUnit"));
            assertEquals("diamant", first.get("unitKey"));

            given().delete("/api/purchase-orders/" + orderId).then().statusCode(204);
            Map<String, Object> trashed = given().get("/api/products/" + sourceId + "/cost-history")
                    .then().statusCode(200).extract().jsonPath().getMap("[0]");
            assertEquals("Kerstcontainer", trashed.get("containerName"), "the trash keeps the name");
            assertEquals(true, trashed.get("containerGone"));
            assertFalse(given().get("/api/products/" + copyId + "/cost-history").then().statusCode(200)
                    .extract().jsonPath().getList("containerGone").contains(false));
        } finally {
            Long copied = copyId;
            QuarkusTransaction.requiringNew().run(() -> {
                List<Long> products = copied == null ? List.of(sourceId) : List.of(sourceId, copied);
                List<?> families = entities.createNativeQuery(
                                "select distinct familyId from product where id in (:ids) and familyId is not null")
                        .setParameter("ids", products).getResultList();
                entities.createNativeQuery("delete from product_cost_history where product_id in (:ids)")
                        .setParameter("ids", products).executeUpdate();
                entities.createNativeQuery("delete from deleted_item where source_kind = 'PURCHASE' and source_id = :id")
                        .setParameter("id", orderId).executeUpdate();
                entities.createNativeQuery("delete from purchase_order_line where order_id = :id")
                        .setParameter("id", orderId).executeUpdate();
                entities.createNativeQuery("delete from purchase_order where id = :id")
                        .setParameter("id", orderId).executeUpdate();
                entities.createNativeQuery("delete from product where id in (:ids)")
                        .setParameter("ids", products).executeUpdate();
                for (Object family : families) {
                    entities.createNativeQuery("delete from product_family where id = :id")
                            .setParameter("id", ((Number) family).longValue()).executeUpdate();
                }
                entities.createNativeQuery("delete from supplier where id = :id")
                        .setParameter("id", ids[2]).executeUpdate();
            });
        }
    }
}
