package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.CatalogExportOrderEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CatalogExportOrderResourceHttpTest {
    private static final String ENDPOINT = "/api/catalog/order";
    @Inject EntityManager entities;
    private Long first;
    private Long second;
    private Long demo;

    @BeforeEach
    void setup() {
        cleanup();
        QuarkusTransaction.requiringNew().run(() -> {
            first = product("FIRST", false);
            second = product("SECOND", false);
            demo = product("DEMO", true);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            entities.createQuery("delete from CatalogExportOrderEntity").executeUpdate();
            entities.createQuery("delete from ProductEntity p where p.sku like 'CATALOG-ORDER-TEST-%'")
                    .executeUpdate();
        });
    }

    @Test
    void savedOrderSurvivesFreshRequestsAndIsSharedBetweenAdministrators() {
        read("emre").then().statusCode(200).header("Cache-Control", "no-store")
                .body("revision", equalTo(0)).body("orderedIds", equalTo(List.of()))
                .body("updatedAt", nullValue());
        save(0, List.of(second, first)).then().statusCode(200).header("Cache-Control", "no-store")
                .body("revision", equalTo(1)).body("updatedAt", notNullValue());
        Response reloaded = read("berat");
        assertEquals(List.of(second, first), ids(reloaded));
        assertEquals(1, reloaded.jsonPath().getLong("revision"));
        QuarkusTransaction.requiringNew().run(() -> {
            CatalogExportOrderEntity stored = entities.find(CatalogExportOrderEntity.class, 1L);
            assertNotNull(stored);
            assertEquals(1, stored.revision);
            assertEquals("[" + second + "," + first + "]", stored.orderedIdsJson);
        });
        // A later product is intentionally absent from the saved prefix; the UI appends it.
        QuarkusTransaction.requiringNew().run(() -> product("NEW", false));
        assertEquals(List.of(second, first), ids(read("emre")));
        save(1, List.of()).then().statusCode(200).body("revision", equalTo(2));
        assertEquals(List.of(), ids(read("berat")));
    }

    @Test
    void staleRevisionDoesNotOverwriteAnotherSave() {
        save(0, List.of(first, second)).then().statusCode(200);
        save(0, List.of(second, first)).then().statusCode(409)
                .body("message", equalTo("De catalogusvolgorde is intussen gewijzigd. Herlaad de bewaarde volgorde voordat je opnieuw opslaat."));
        assertEquals(List.of(first, second), ids(read("berat")));
        save(1, List.of(second, first)).then().statusCode(200).body("revision", equalTo(2));
        assertEquals(List.of(second, first), ids(read("emre")));
    }

    @Test
    void missingProductsAndDemosAreIgnoredWithoutLosingSurvivingOrder() {
        save(0, List.of(second, Long.MAX_VALUE, demo, first)).then().statusCode(200);
        assertEquals(List.of(second, first), ids(read("emre")));
        QuarkusTransaction.requiringNew().run(() -> {
            entities.remove(entities.find(ProductEntity.class, second));
            entities.find(ProductEntity.class, first).demo = true;
        });
        assertEquals(List.of(), ids(read("berat")));
        read("berat").then().body("revision", equalTo(1));
    }

    @Test
    void malformedIdsCannotChangeTheSavedOrder() {
        List<String> bodies = new ArrayList<>(List.of(
                "null", "{}", "{\"revision\":0}", "{\"revision\":0,\"orderedIds\":null}",
                "{\"revision\":0,\"orderedIds\":[null]}", "{\"revision\":0,\"orderedIds\":[0]}",
                "{\"revision\":0,\"orderedIds\":[-1]}", "{\"revision\":0,\"orderedIds\":[1.5]}",
                "{\"revision\":0,\"orderedIds\":[\"1\"]}", "{\"revision\":-1,\"orderedIds\":[]}",
                "{\"revision\":0.5,\"orderedIds\":[]}",
                "{\"revision\":0,\"orderedIds\":[9223372036854775808]}",
                "{\"revision\":0,\"orderedIds\":[" + first + "," + first + "]}"));
        for (String body : bodies) {
            given().auth().preemptive().basic("emre", "named-auth-test-password")
                    .contentType("application/json").body(body).put(ENDPOINT)
                    .then().statusCode(400);
        }
        save(0, Collections.nCopies(10_001, first)).then().statusCode(400);
        read("emre").then().statusCode(200).body("revision", equalTo(0))
                .body("orderedIds", equalTo(List.of())).body("updatedAt", nullValue());
    }

    @Test
    void concurrentFirstSavesProduceOneWinnerAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstSave = workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return save(0, List.of(first, second));
            });
            var secondSave = workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return save(0, List.of(second, first));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Response a = firstSave.get(20, TimeUnit.SECONDS);
            Response b = secondSave.get(20, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409), List.of(a.statusCode(), b.statusCode()).stream().sorted().toList());
            Response winner = a.statusCode() == 200 ? a : b;
            Response persisted = read("berat");
            assertEquals(ids(winner), ids(persisted));
            assertEquals(1, persisted.jsonPath().getLong("revision"));
        }
    }

    @Test
    void anonymousUsersCannotReadOrSaveTheOrder() {
        given().get(ENDPOINT).then().statusCode(401);
        given().contentType("application/json").body(Map.of("revision", 0, "orderedIds", List.of(first)))
                .put(ENDPOINT).then().statusCode(401);
    }

    private Long product(String suffix, boolean demo) {
        ProductEntity entity = new ProductEntity();
        entity.sku = "CATALOG-ORDER-TEST-" + suffix;
        entity.name = "Catalogue order test " + suffix;
        entity.demo = demo;
        entities.persist(entity);
        return entity.id;
    }

    private static Response read(String user) {
        return given().auth().preemptive().basic(user, "named-auth-test-password").get(ENDPOINT);
    }

    private static Response save(long revision, List<Long> ids) {
        return given().auth().preemptive().basic("emre", "named-auth-test-password")
                .contentType("application/json").body(Map.of("revision", revision, "orderedIds", ids))
                .put(ENDPOINT);
    }

    private static List<Long> ids(Response response) {
        return response.jsonPath().getList("orderedIds", Long.class);
    }
}
