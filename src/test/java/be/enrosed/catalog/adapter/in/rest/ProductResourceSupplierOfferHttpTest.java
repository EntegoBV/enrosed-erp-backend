package be.enrosed.catalog.adapter.in.rest;

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
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/products?supplierId=} as the purchase editor reads it: the products that supplier makes, priced as
 * that supplier sells them, with its offer, inactive offers included; the plain list stays as it was.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductResourceSupplierOfferHttpTest {

    @Inject ProductService products;
    @Inject ProductSupplierService suppliers;

    @Test
    void aSuppliersListCarriesItsOwnPriceAndOfferIncludingInactiveOnes() {
        long yiwu = 9_300_000L + ThreadLocalRandom.current().nextInt(100_000);
        long culinan = yiwu + 200_000L;
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Product dome = products.create(product("OFFER-DOME-" + tag, yiwu, "2.35"));
            Product vase = products.create(product("OFFER-VASE-" + tag, yiwu, "4.00"));
            Product other = products.create(product("OFFER-OTHER-" + tag, yiwu, "1.00"));
            suppliers.add(dome.id(), new ProductSupplierWrite(culinan, "B · dikker glas", "CUL-GD-10",
                    new BigDecimal("2.10"), Currency.CNY, new BigDecimal("0.15"), 600, 45, "Glass 3 mm", null),
                    "Culinan");
            ProductSupplier vaseAtCulinan = suppliers.add(vase.id(), new ProductSupplierWrite(culinan, null, null,
                    new BigDecimal("3.80"), Currency.USD, null, null, null, null, null), "Culinan");
            suppliers.setActive(vase.id(), vaseAtCulinan.id(), false, "Culinan");
            return new long[] {dome.id(), vase.id(), other.id()};
        });
        try {
            JsonPath atCulinan = given().queryParam("supplierId", culinan).get("/api/products")
                    .then().statusCode(200).extract().jsonPath();
            List<Map<String, Object>> rows = atCulinan.getList("$");
            assertEquals(2, rows.size(), "the dome and the vase, not the product Culinan does not make");
            Map<String, Object> dome = byId(rows, ids[0]);
            assertEquals(0, new BigDecimal("2.10").compareTo(new BigDecimal(dome.get("exwPrice").toString())));
            assertEquals("CNY", dome.get("exwCurrency"));
            assertEquals(0, new BigDecimal("0.15").compareTo(new BigDecimal(dome.get("extraUnitCost").toString())));
            assertEquals(((Number) dome.get("supplierId")).longValue(), yiwu, "supplierId stays the Voorkeur");
            @SuppressWarnings("unchecked")
            Map<String, Object> offer = (Map<String, Object>) dome.get("offer");
            assertNotNull(offer);
            assertNotNull(offer.get("id"));
            assertEquals(culinan, ((Number) offer.get("supplierId")).longValue());
            assertEquals(false, offer.get("preferred"));
            assertEquals(true, offer.get("active"));
            assertEquals("B · dikker glas", offer.get("versionLabel"));
            assertEquals("CUL-GD-10", offer.get("supplierReference"));
            assertEquals(600, offer.get("moq"));
            assertEquals(45, offer.get("leadTimeDays"));
            assertTrue(offer.keySet().containsAll(List.of("id", "supplierId", "preferred", "active", "versionLabel",
                    "supplierReference", "exwPrice", "exwCurrency", "extraUnitCost", "moq", "leadTimeDays")));
            @SuppressWarnings("unchecked")
            Map<String, Object> vaseOffer = (Map<String, Object>) byId(rows, ids[1]).get("offer");
            assertEquals(false, vaseOffer.get("active"), "inactive offers stay in the list");

            JsonPath atYiwu = given().queryParam("supplierId", yiwu).get("/api/products")
                    .then().statusCode(200).extract().jsonPath();
            Map<String, Object> domeAtYiwu = byId(atYiwu.getList("$"), ids[0]);
            assertEquals(0, new BigDecimal("2.35").compareTo(new BigDecimal(domeAtYiwu.get("exwPrice").toString())));
            @SuppressWarnings("unchecked")
            Map<String, Object> yiwuOffer = (Map<String, Object>) domeAtYiwu.get("offer");
            assertEquals(true, yiwuOffer.get("preferred"));
            assertEquals(3, atYiwu.getList("$").size());

            JsonPath all = given().get("/api/products").then().statusCode(200).extract().jsonPath();
            Map<String, Object> plain = byId(all.getList("$"), ids[0]);
            assertNull(plain.get("offer"), "the plain list carries no offer");
            assertEquals(0, new BigDecimal("2.35").compareTo(new BigDecimal(plain.get("exwPrice").toString())));
            assertEquals("USD", plain.get("exwCurrency"));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                for (long id : ids) products.delete(id);
            });
        }
    }

    private static Map<String, Object> byId(List<Map<String, Object>> rows, long id) {
        return rows.stream().filter(row -> ((Number) row.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    private static Product product(String sku, long supplierId, String exw) {
        return new Product(null, sku, "Offer " + sku, Dimensions.empty(), "Rood", null, null, supplierId,
                true, Barcodes.none(), null, Carton.empty(), new BigDecimal(exw), Currency.USD, null,
                null, null, null, null, 0, List.of(), List.of());
    }
}
