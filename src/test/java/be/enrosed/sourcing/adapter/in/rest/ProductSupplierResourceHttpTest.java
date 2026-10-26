package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierService;
import be.enrosed.catalog.application.ProductSupplierWrite;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductSupplier;
import be.enrosed.shared.Currency;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * The suppliers of a product over HTTP, as the editor and the product page read and change them, and the products of
 * one supplier as the supplier page lists them.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductSupplierResourceHttpTest {

    @Inject ProductService products;
    @Inject ProductSupplierService rows;
    @Inject SupplierService suppliers;
    @Inject PurchaseOrderService purchases;
    @Inject EntityManager entities;

    private record Fixture(long a, long b, long c, long d, long dome, long vase, long bowl, long bRow,
                           long vaseB, long placed, String placedNumber, long trashed) {}

    @Test
    void rowsCarryTheirFactsAndEveryChangeAnswersWithTheRowsAndTheCard() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Fixture f = QuarkusTransaction.requiringNew().call(() -> fixture(tag));
        try {
            /* GET: preferred first, the supplier's name and terms, last order and last cost, line count. */
            JsonPath listed = given().get("/api/products/{id}/suppliers", f.dome())
                    .then().statusCode(200).extract().jsonPath();
            List<Map<String, Object>> rowsNow = listed.getList("$");
            assertEquals(2, rowsNow.size());
            Map<String, Object> voorkeur = rowsNow.get(0);
            assertEquals(true, voorkeur.get("preferred"));
            assertEquals(f.a(), number(voorkeur.get("supplierId")).longValue());
            assertEquals("HTTP A " + tag, voorkeur.get("supplierName"));
            assertEquals("Kunming", voorkeur.get("supplierCity"));
            assertEquals("USD", voorkeur.get("supplierCurrency"));
            assertEquals("FOB", voorkeur.get("supplierIncoterm"));
            assertEquals(40, number(voorkeur.get("supplierLeadTimeDays")).intValue());
            assertEquals(0, number(voorkeur.get("photoCount")).intValue());
            assertNull(voorkeur.get("lastOrder"));
            assertNull(voorkeur.get("lastCost"));
            assertEquals(0, number(voorkeur.get("purchaseLineCount")).intValue());
            assertMicros((String) voorkeur.get("updatedAt"));
            assertTrue(voorkeur.keySet().containsAll(List.of("id", "productId", "supplierId", "supplierName",
                    "supplierCity", "supplierCurrency", "supplierIncoterm", "supplierLeadTimeDays", "preferred",
                    "active", "versionLabel", "supplierReference", "exwPrice", "exwCurrency", "extraUnitCost", "moq",
                    "leadTimeDays", "supplierNote", "photoCount", "lastOrder", "lastCost", "purchaseLineCount",
                    "updatedAt")), voorkeur.keySet().toString());

            Map<String, Object> ofB = rowsNow.get(1);
            assertEquals(f.bRow(), number(ofB.get("id")).longValue());
            assertEquals(false, ofB.get("preferred"));
            assertEquals("B · dikker glas", ofB.get("versionLabel"));
            assertEquals("CUL-GD-10", ofB.get("supplierReference"));
            assertEquals(600, number(ofB.get("moq")).intValue());
            assertEquals(1, number(ofB.get("purchaseLineCount")).intValue());
            Map<String, Object> lastOrder = map(ofB.get("lastOrder"));
            assertEquals(f.placed(), number(lastOrder.get("orderId")).longValue());
            assertEquals(f.placedNumber(), lastOrder.get("number"));
            assertEquals("Versie B " + tag, lastOrder.get("name"), "the Herkenbare naam");
            assertEquals("BESTELD", lastOrder.get("status"));
            assertEquals(0, new BigDecimal("2.10").compareTo(new BigDecimal(lastOrder.get("exwPrice").toString())));
            assertEquals(LocalDate.now(ZoneId.of("Europe/Brussels")).toString(), lastOrder.get("orderDate"));
            Map<String, Object> lastCost = map(ofB.get("lastCost"));
            assertNotNull(lastCost.get("unitEur"));
            assertEquals(f.placedNumber(), lastCost.get("orderNumber"));
            assertEquals(LocalDate.now(ZoneId.of("Europe/Brussels")).toString(), lastCost.get("appliedAt"));

            /* POST: a third supplier, not preferred; the card stays A's. */
            JsonPath added = given().contentType(ContentType.JSON)
                    .body(Map.of("supplierId", f.c(), "versionLabel", "C", "exwPrice", 1.5, "exwCurrency", "USD"))
                    .post("/api/products/{id}/suppliers", f.dome())
                    .then().statusCode(201).extract().jsonPath();
            assertEquals(3, added.getList("rows").size());
            assertEquals(f.a(), number(added.get("product.supplierId")).longValue());
            assertEquals(0, new BigDecimal("2.35").compareTo(new BigDecimal(added.get("product.exwPrice").toString())));
            Map<String, Object> cRow = rowOf(added.getList("rows"), f.c());
            long cId = number(cRow.get("id")).longValue();

            /* Two PUTs in a row, each echoing the previous answer's updatedAt: never a 409. */
            String stamp = (String) cRow.get("updatedAt");
            for (String price : List.of("1.60", "1.70")) {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("versionLabel", "C · dun");
                body.put("supplierReference", null);
                body.put("exwPrice", new BigDecimal(price));
                body.put("exwCurrency", "USD");
                body.put("extraUnitCost", null);
                body.put("moq", null);
                body.put("leadTimeDays", null);
                body.put("updatedAt", stamp);
                JsonPath put = given().contentType(ContentType.JSON).body(body)
                        .put("/api/products/{id}/suppliers/{row}", f.dome(), cId)
                        .then().statusCode(200).extract().jsonPath();
                Map<String, Object> after = rowOf(put.getList("rows"), f.c());
                assertEquals(0, new BigDecimal(price).compareTo(new BigDecimal(after.get("exwPrice").toString())));
                stamp = (String) after.get("updatedAt");
                assertMicros(stamp);
            }

            /* Note only: set, then '' clears. */
            JsonPath noted = given().contentType(ContentType.JSON)
                    .body(Map.of("supplierNote", "Glass 1 mm", "updatedAt", stamp))
                    .put("/api/products/{id}/suppliers/{row}/note", f.dome(), cId)
                    .then().statusCode(200).extract().jsonPath();
            Map<String, Object> withNote = rowOf(noted.getList("rows"), f.c());
            assertEquals("Glass 1 mm", withNote.get("supplierNote"));
            JsonPath cleared = given().contentType(ContentType.JSON)
                    .body(Map.of("supplierNote", "", "updatedAt", withNote.get("updatedAt")))
                    .put("/api/products/{id}/suppliers/{row}/note", f.dome(), cId)
                    .then().statusCode(200).extract().jsonPath();
            assertNull(rowOf(cleared.getList("rows"), f.c()).get("supplierNote"));

            /* Inactive cannot become Voorkeur; active again it can, and the card follows. */
            JsonPath inactive = given().contentType(ContentType.JSON).body(Map.of("active", false))
                    .put("/api/products/{id}/suppliers/{row}/active", f.dome(), cId)
                    .then().statusCode(200).extract().jsonPath();
            assertEquals(false, rowOf(inactive.getList("rows"), f.c()).get("active"));
            assertEquals("Zet deze leverancier eerst weer actief.",
                    given().put("/api/products/{id}/suppliers/{row}/preferred", f.dome(), cId)
                            .then().statusCode(409).extract().jsonPath().getString("message"));
            given().contentType(ContentType.JSON).body(Map.of("active", true))
                    .put("/api/products/{id}/suppliers/{row}/active", f.dome(), cId).then().statusCode(200);
            JsonPath promoted = given().put("/api/products/{id}/suppliers/{row}/preferred", f.dome(), cId)
                    .then().statusCode(200).extract().jsonPath();
            assertEquals(f.c(), number(promoted.get("product.supplierId")).longValue());
            assertEquals(0, new BigDecimal("1.70").compareTo(new BigDecimal(promoted.get("product.exwPrice").toString())));
            assertEquals(f.c(), number(promoted.getList("rows", Map.class).get(0).get("supplierId")).longValue(),
                    "the Voorkeur comes first");
            long aRow = number(rowOf(promoted.getList("rows"), f.a()).get("id")).longValue();
            given().put("/api/products/{id}/suppliers/{row}/preferred", f.dome(), aRow).then().statusCode(200);

            /* DELETE: a row no line names goes; one on a trashed container's line is refused. */
            JsonPath deleted = given().delete("/api/products/{id}/suppliers/{row}", f.dome(), cId)
                    .then().statusCode(200).extract().jsonPath();
            assertEquals(2, deleted.getList("rows").size());
            assertEquals("Deze leverancier staat op inkooporders, ook in de prullenbak. Zet hem op inactief.",
                    given().delete("/api/products/{id}/suppliers/{row}", f.vase(), f.vaseB())
                            .then().statusCode(409).extract().jsonPath().getString("message"));
            given().delete("/api/products/{id}/suppliers/{row}", f.dome(), 987_654_321L).then().statusCode(404);

            /* The supplier page: B's products with their row facts. */
            JsonPath ofSupplier = given().get("/api/suppliers/{id}/products", f.b())
                    .then().statusCode(200).extract().jsonPath();
            List<Map<String, Object>> made = ofSupplier.getList("$");
            assertEquals(2, made.size());
            Map<String, Object> dome = made.stream()
                    .filter(row -> number(row.get("productId")).longValue() == f.dome()).findFirst().orElseThrow();
            assertEquals(f.bRow(), number(dome.get("rowId")).longValue());
            assertEquals(false, dome.get("preferred"));
            assertEquals(true, dome.get("active"));
            assertEquals(true, dome.get("productActive"));
            assertEquals("B · dikker glas", dome.get("versionLabel"));
            assertEquals(0, new BigDecimal("2.10").compareTo(new BigDecimal(dome.get("exwPrice").toString())));
            assertEquals(f.placedNumber(), map(dome.get("lastOrder")).get("number"));
            assertNull(dome.get("photoUrl"), "no photo yet");
            assertTrue(dome.keySet().containsAll(List.of("rowId", "productId", "sku", "name", "colour", "variantSize",
                    "photoUrl", "productActive", "preferred", "active", "versionLabel", "supplierReference",
                    "exwPrice", "exwCurrency", "lastOrder")), dome.keySet().toString());

            /* Supplier delete is refused while only an inactive row remains, and says so; usage counts rows. */
            assertEquals(1, number(given().get("/api/suppliers/{id}/usage", f.d()).then().statusCode(200)
                    .extract().jsonPath().get("products")).intValue());
            assertEquals("Er hangen nog 1 product(en) aan HTTP D " + tag + ", waarvan 1 op inactief",
                    given().delete("/api/suppliers/{id}", f.d()).then().statusCode(409)
                            .extract().jsonPath().getString("message"));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> cleanUp(f));
        }
    }

    private Fixture fixture(String tag) {
        Supplier a = supplier("HTTP A " + tag);
        Supplier b = supplier("HTTP B " + tag);
        Supplier c = supplier("HTTP C " + tag);
        Supplier d = supplier("HTTP D " + tag);
        Product dome = products.create(product("HTTP-DOME-" + tag, a.id(), "2.35"));
        Product vase = products.create(product("HTTP-VASE-" + tag, a.id(), "4.00"));
        Product bowl = products.create(product("HTTP-BOWL-" + tag, a.id(), "3.00"));
        ProductSupplier bRow = rows.add(dome.id(), new ProductSupplierWrite(b.id(), "B · dikker glas", "CUL-GD-10",
                new BigDecimal("2.10"), Currency.USD, new BigDecimal("0.15"), 600, 45, "Glass 3 mm", null), b.name());
        ProductSupplier vaseB = rows.add(vase.id(), new ProductSupplierWrite(b.id(), null, null,
                new BigDecimal("3.80"), Currency.USD, null, null, null, null, null), b.name());
        ProductSupplier bowlD = rows.add(bowl.id(), new ProductSupplierWrite(d.id(), null, null,
                new BigDecimal("2.80"), Currency.USD, null, null, null, null, null), d.name());
        rows.setActive(bowl.id(), bowlD.id(), false, d.name());

        PurchaseOrder concept = purchases.create(b.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.TEN);
        PurchaseOrder placed = purchases.update(concept.id(), concept.withReceipt(PurchaseOrderStatus.BESTELD, null,
                null, false, null, List.of(new PurchaseOrderLine(null, dome.id(), 100, null, null, null, null))))
                .order();
        entities.find(be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity.class,
                placed.id()).alias = "Versie B " + tag;
        entities.flush();
        purchases.applyToProducts(placed.id());

        PurchaseOrder toTrash = purchases.create(b.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.TEN);
        purchases.update(toTrash.id(), toTrash.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false, null,
                List.of(new PurchaseOrderLine(null, vase.id(), 10, null, null, null, null))));
        purchases.delete(toTrash.id());
        return new Fixture(a.id(), b.id(), c.id(), d.id(), dome.id(), vase.id(), bowl.id(), bRow.id(), vaseB.id(),
                placed.id(), placed.number(), toTrash.id());
    }

    private void cleanUp(Fixture f) {
        List<Long> orders = List.of(f.placed(), f.trashed());
        List<Long> productIds = List.of(f.dome(), f.vase(), f.bowl());
        native_("delete from purchase_order_line where order_id in (:ids)", orders);
        native_("delete from deleted_item where source_kind = 'PURCHASE' and source_id in (:ids)", orders);
        native_("delete from purchase_order where id in (:ids)", orders);
        native_("delete from product_cost_history where product_id in (:ids)", productIds);
        native_("delete from product_supplier where product_id in (:ids)", productIds);
        List<Long> left = new ArrayList<>();
        for (long id : productIds) {
            try {
                products.delete(id);
            } catch (RuntimeException kept) {
                left.add(id);
            }
        }
        if (!left.isEmpty()) native_("delete from product where id in (:ids)", left);
        native_("delete from supplier where id in (:ids)", List.of(f.a(), f.b(), f.c(), f.d()));
    }

    private void native_(String sql, List<Long> ids) {
        entities.createNativeQuery(sql).setParameter("ids", ids).executeUpdate();
    }

    private Supplier supplier(String name) {
        return suppliers.save(new Supplier(null, name, "CN", "Kunming", null, null, null, Currency.USD, "FOB",
                "Ningbo", 40, null));
    }

    private static Product product(String sku, long supplierId, String exw) {
        return new Product(null, sku, "Http " + sku, Dimensions.empty(), "Rood", null, null, supplierId,
                true, Barcodes.none(), null, Carton.empty(), new BigDecimal(exw), Currency.USD, null,
                null, null, null, null, 0, List.of(), List.of());
    }

    private static Map<String, Object> rowOf(List<Map<String, Object>> list, long supplierId) {
        return list.stream().filter(row -> number(row.get("supplierId")).longValue() == supplierId)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertNotNull(value);
        return (Map<String, Object>) value;
    }

    private static Number number(Object value) {
        assertNotNull(value);
        return (Number) value;
    }

    /** What the client gets back equals what Postgres stores: at most six fraction digits. */
    private static void assertMicros(String instant) {
        assertNotNull(instant);
        java.util.regex.Matcher fraction = java.util.regex.Pattern.compile("\\.(\\d+)").matcher(instant);
        assertFalse(fraction.find() && fraction.group(1).length() > 6, instant);
    }
}
