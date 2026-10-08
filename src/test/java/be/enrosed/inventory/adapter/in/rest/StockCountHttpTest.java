package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.application.InventoryClock;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The count endpoints as the phone calls them: the statuses, the JSON of a
 * session and its lines, and the Dutch refusals with the code the screen
 * decides on.
 */
@QuarkusTest
class StockCountHttpTest {
    private static final String BASE = "/api/stock-counts";
    private static final int YEAR = InventoryClock.today().getYear();

    @Inject StockService stock;
    @Inject EntityManager em;

    private final List<Long> locationIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void removeRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (Long location : locationIds) {
                List<Long> counts = em.createQuery("select id from StockCountEntity where locationId = ?1", Long.class)
                        .setParameter(1, location).getResultList();
                for (Long count : counts) {
                    em.createQuery("delete from StockCountLineEntity where countId = ?1").setParameter(1, count).executeUpdate();
                    em.createQuery("delete from ActivityLogEntity where entityType = 'STOCK_COUNT' and entityId = ?1")
                            .setParameter(1, String.valueOf(count)).executeUpdate();
                }
                em.createQuery("delete from StockCountEntity where locationId = ?1").setParameter(1, location).executeUpdate();
                em.createQuery("delete from StockMovementEntity where locationId = ?1").setParameter(1, location).executeUpdate();
                em.createQuery("delete from StockLevelEntity where locationId = ?1").setParameter(1, location).executeUpdate();
                em.createQuery("delete from StockLocationEntity where id = ?1").setParameter(1, location).executeUpdate();
            }
            for (Long product : productIds) {
                ProductEntity stored = em.find(ProductEntity.class, product);
                if (stored != null) em.remove(stored);
            }
        });
        locationIds.clear();
        productIds.clear();
    }

    @Test
    void withoutASignedInUserEveryRouteAnswers401() {
        for (ValidatableResponse refused : everyRoute()) refused.statusCode(401);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "viewer")
    void withoutTheAdminRoleEveryRouteAnswers403() {
        for (ValidatableResponse refused : everyRoute()) refused.statusCode(403);
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aCountIsStartedCountedCheckedAndBooked() {
        long shelf = location("Http telling");
        long beta = product("Beta", true);
        long alfa = product("Alfa", true);
        long hidden = product("Later toegevoegd", false);
        level(alfa, shelf, 10);
        level(beta, shelf, 4);

        given().queryParam("year", YEAR).get(BASE).then().statusCode(200)
                .body("year", equalTo(YEAR))
                .body("years", notNullValue())
                .body("locations.find { it.locationId == " + shelf + " }.kindLabel", equalTo("Verkooppunt"))
                .body("locations.find { it.locationId == " + shelf + " }.active", equalTo(true))
                .body("locations.find { it.locationId == " + shelf + " }.productsWithStock", equalTo(2))
                .body("locations.find { it.locationId == " + shelf + " }.open", nullValue())
                .body("locations.find { it.locationId == " + shelf + " }.booked", nullValue())
                .body("locations.find { it.locationId == " + shelf + " }.correctionCount", equalTo(0));
        given().get(BASE).then().statusCode(200).body("year", equalTo(YEAR));

        ValidatableResponse started = post(BASE, Map.of("countYear", YEAR, "locationId", shelf, "note", "ronde 1")).statusCode(201)
                .body("countYear", equalTo(YEAR))
                .body("locationId", equalTo((int) shelf))
                .body("locationName", notNullValue())
                .body("status", equalTo("OPEN"))
                .body("correctsCountId", nullValue())
                .body("note", equalTo("ronde 1"))
                .body("lineCount", equalTo(2))
                .body("countedCount", equalTo(0))
                .body("differenceCount", equalTo(0))
                .body("missingReasonCount", equalTo(0))
                .body("startedByName", equalTo("emre"))
                .body("startedAt", notNullValue())
                .body("bookedByName", nullValue())
                .body("bookedAt", nullValue())
                .body("warnings", hasKey("unbookedContainers"))
                .body("warnings", hasKey("unshippedInvoices"))
                .body("warnings.olderUnshippedInvoiceCount", notNullValue())
                .body("warnings.orphanLevels", empty())
                .body("reasons.code", contains("BESCHADIGD", "NIET_GEVONDEN", "TELFOUT", "ANDERE_LOCATIE", "DEMO",
                        "TERUGGEVONDEN", "ANDERS"))
                .body("reasons.find { it.code == 'TELFOUT' }.label", equalTo("Eerdere tel- of boekfout"))
                .body("reasons.find { it.code == 'TELFOUT' }.noteRequired", equalTo(false))
                .body("reasons.find { it.code == 'ANDERS' }.label", equalTo("Andere reden"))
                .body("reasons.find { it.code == 'ANDERS' }.noteRequired", equalTo(true));
        int count = started.extract().path("id");
        List<Integer> mine = started.extract().jsonPath().getList(
                "lines.findAll { it.productId == " + alfa + " || it.productId == " + beta + " }.productId");
        assertEquals(List.of((int) alfa, (int) beta), mine, "sorted by category name, then product name");
        int alfaLine = started.extract().path("lines.find { it.productId == " + alfa + " }.id");
        int betaLine = started.extract().path("lines.find { it.productId == " + beta + " }.id");
        started.body("lines.find { it.id == " + alfaLine + " }.productName", equalTo("Alfa"))
                .body("lines.find { it.id == " + alfaLine + " }.salesUnit", equalTo("PIECE"))
                .body("lines.find { it.id == " + alfaLine + " }.unitKey", equalTo("stuk"))
                .body("lines.find { it.id == " + alfaLine + " }.addedByHand", equalTo(false))
                .body("lines.find { it.id == " + alfaLine + " }.liveQuantity", equalTo(10))
                .body("lines.find { it.id == " + alfaLine + " }.expectedQuantity", nullValue())
                .body("lines.find { it.id == " + alfaLine + " }.countedQuantity", nullValue())
                .body("lines.find { it.id == " + alfaLine + " }.revision", equalTo(0))
                .body("lines.find { it.id == " + alfaLine + " }.moved", equalTo(false))
                .body("lines.find { it.id == " + alfaLine + " }.openDocuments", empty())
                .body("lines.find { it.id == " + alfaLine + " }.documentsConfirmed", equalTo(false));

        refusal(post(BASE, Map.of("countYear", YEAR, "locationId", shelf)), "TELLING_LOOPT")
                .body("message", matchesPattern("Voor Http telling .* loopt al een telling"))
                .body("details.countId", equalTo(count));
        given().get(BASE + "/{id}", count).then().statusCode(200).body("id", equalTo(count)).body("status", equalTo("OPEN"));
        given().queryParam("year", YEAR).get(BASE).then().statusCode(200)
                .body("locations.find { it.locationId == " + shelf + " }.open.id", equalTo(count))
                .body("counts.id", hasItem(count))
                .body("years", hasItem(YEAR));

        /* A count saves without a reason; the other phone, still on revision 0, is told who was first. */
        put(count, alfaLine, write(8, null, null, 0)).statusCode(200)
                .body("id", equalTo(alfaLine))
                .body("expectedQuantity", equalTo(10))
                .body("countedQuantity", equalTo(8))
                .body("difference", equalTo(-2))
                .body("reasonCode", nullValue())
                .body("reasonLabel", nullValue())
                .body("countedByName", equalTo("emre"))
                .body("countedAt", notNullValue())
                .body("revision", equalTo(1))
                .body("moved", equalTo(false))
                .body("bookedQuantity", nullValue());
        refusal(put(count, alfaLine, write(9, null, null, 0)), "REGEL_GEWIJZIGD")
                .body("message", matchesPattern("emre telde hier al 8 \\(\\d\\d:\\d\\d\\)"))
                .body("details.line.id", equalTo(alfaLine))
                .body("details.line.productId", equalTo((int) alfa))
                .body("details.line.productName", equalTo("Alfa"))
                .body("details.line.countedQuantity", equalTo(8))
                .body("details.line.countedByName", equalTo("emre"))
                .body("details.line.liveQuantity", equalTo(10))
                .body("details.line.revision", equalTo(1))
                .body("details.line.openDocuments", empty());

        invalid(put(count, alfaLine, write(-1, null, null, 1)), "Geteld aantal kan niet negatief zijn");
        invalid(put(count, alfaLine, write(8, "ANDERS", null, 1)), "Vul bij 'Andere reden' een notitie in");
        invalid(put(count, alfaLine, write(8, "VERKOCHT", null, 1)), "Onbekende reden");
        put(count, 987_654_321L, write(8, null, null, 1)).statusCode(404);
        put(987_654_321L, alfaLine, write(8, null, null, 1)).statusCode(404).body("message", equalTo("Telling 987654321 bestaat niet"));
        put(count, betaLine, write(4, null, null, 0)).statusCode(200).body("difference", equalTo(0));

        ValidatableResponse added = post(BASE + "/" + count + "/lines", Map.of("productId", hidden)).statusCode(201)
                .body("productId", equalTo((int) hidden))
                .body("addedByHand", equalTo(true))
                .body("countedQuantity", nullValue())
                .body("revision", equalTo(0));
        int hiddenLine = added.extract().path("id");
        post(BASE + "/" + count + "/lines", Map.of("productId", hidden)).statusCode(200).body("id", equalTo(hiddenLine));
        post(BASE + "/" + count + "/lines", Map.of("productId", 987_654_321L)).statusCode(404)
                .body("message", equalTo("Product 987654321 bestaat niet"));
        post(BASE + "/987654321/lines", Map.of("productId", hidden)).statusCode(404);

        ValidatableResponse check = given().get(BASE + "/{id}/booking-check", count).then().statusCode(200)
                .body("uncounted", empty())
                .body("missingReasons", contains(alfaLine))
                .body("openDocuments", empty())
                .body("moved", empty())
                .body("negative", empty())
                .body("summary.lines", equalTo(2))
                .body("summary.equal", equalTo(1))
                .body("summary.short", equalTo(1))
                .body("summary.shortUnits", equalTo(2))
                .body("summary.over", equalTo(0))
                .body("summary.overUnits", equalTo(0))
                .body("summary", not(hasKey("shortLines")))
                .body("checkToken", matchesPattern("[0-9a-f]{64}"));
        refusal(post(BASE + "/" + count + "/book", Map.of("checkToken", check.extract().path("checkToken"))), "REDEN_ONTBREEKT")
                .body("message", equalTo("Bij 1 verschil ontbreekt een reden"));

        put(count, alfaLine, write(8, "BESCHADIGD", "doos nat", 1)).statusCode(200)
                .body("reasonLabel", equalTo("Beschadigd of stuk"))
                .body("reasonNote", equalTo("doos nat"))
                .body("expectedQuantity", equalTo(10))
                .body("revision", equalTo(2));
        refusal(post(BASE + "/" + count + "/book", Map.of("checkToken", check.extract().path("checkToken"))), "TELLING_GEWIJZIGD")
                .body("message", equalTo("De voorraad of de telling is intussen gewijzigd; bekijk de controle opnieuw"));
        refusal(post(BASE + "/" + count + "/book", Map.of()), "TELLING_GEWIJZIGD");

        /* A sale during the count: the check shows the row and what the level becomes. */
        QuarkusTransaction.requiringNew().run(() -> stock.sell(alfa, shelf, 3, "F-HTTP"));
        String token = given().get(BASE + "/{id}/booking-check", count).then().statusCode(200)
                .body("moved", hasSize(1))
                .body("moved[0].lineId", equalTo(alfaLine))
                .body("moved[0].expectedQuantity", equalTo(10))
                .body("moved[0].countedQuantity", equalTo(8))
                .body("moved[0].difference", equalTo(-2))
                .body("moved[0].liveQuantity", equalTo(7))
                .body("moved[0].resultQuantity", equalTo(5))
                .body("moved[0].movements", hasSize(1))
                .body("moved[0].movements[0].delta", equalTo(-3))
                .body("moved[0].movements[0].kindLabel", equalTo("Verkocht"))
                .body("moved[0].movements[0].reference", equalTo("F-HTTP"))
                .body("moved[0].movements[0].at", notNullValue())
                .body("moved[0].movements[0]", hasKey("actor"))
                .extract().path("checkToken");
        given().get(BASE + "/{id}", count).then().statusCode(200)
                .body("lines.find { it.id == " + alfaLine + " }.moved", equalTo(true))
                .body("lineCount", equalTo(3))
                .body("countedCount", equalTo(2));

        post(BASE + "/" + count + "/book", Map.of("checkToken", token)).statusCode(200)
                .body("status", equalTo("GEBOEKT"))
                .body("bookedByName", equalTo("emre"))
                .body("bookedAt", notNullValue())
                .body("lines.find { it.id == " + alfaLine + " }.bookedQuantity", equalTo(5))
                .body("lines.find { it.id == " + betaLine + " }.bookedQuantity", equalTo(4))
                .body("lines.find { it.id == " + hiddenLine + " }.bookedQuantity", nullValue());
        assertEquals(5, stock.quantityAt(alfa, shelf), "live 7 plus the counted difference of -2");

        /* A write that starts after the booking committed finds the session closed. */
        refusal(put(count, alfaLine, write(9, null, null, 2)), "TELLING_GESLOTEN").body("message", equalTo("Deze telling is al geboekt"));
        refusal(post(BASE + "/" + count + "/lines", Map.of("productId", hidden)), "TELLING_GESLOTEN");
        refusal(given().get(BASE + "/{id}/booking-check", count).then(), "TELLING_GESLOTEN");
        refusal(post(BASE + "/" + count + "/book", Map.of("checkToken", token)), "TELLING_GESLOTEN");
        refusal(post(BASE + "/" + count + "/cancel", Map.of()), "TELLING_GESLOTEN")
                .body("message", equalTo("Een geboekte telling kan niet geannuleerd worden"));
        given().queryParam("year", YEAR).get(BASE).then().statusCode(200)
                .body("locations.find { it.locationId == " + shelf + " }.open", nullValue())
                .body("locations.find { it.locationId == " + shelf + " }.booked.id", equalTo(count))
                .body("locations.find { it.locationId == " + shelf + " }.booked.status", equalTo("GEBOEKT"));

        /* A correction of that count: no lines until the user adds one. */
        int correction = post(BASE, Map.of("countYear", YEAR, "locationId", shelf, "correctsCountId", count)).statusCode(201)
                .body("correctsCountId", equalTo(count))
                .body("lines", empty())
                .extract().path("id");
        given().contentType("application/json").post(BASE + "/{id}/cancel", correction).then().statusCode(200)
                .body("status", equalTo("GEANNULEERD"));
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void aStartIsValidatedAndACancelledCountRefusesWrites() {
        long shelf = location("Http annuleren");
        long rose = product("Roos", true);
        level(rose, shelf, 6);

        invalid(post(BASE, Map.of("locationId", shelf)), "Kies een boekjaar");
        invalid(post(BASE, Map.of("countYear", YEAR)), "Kies een locatie voor de telling");
        invalid(given().contentType("application/json").post(BASE).then(), "Kies een boekjaar");
        post(BASE, Map.of("countYear", YEAR, "locationId", 987_654_321L)).statusCode(404)
                .body("message", equalTo("Locatie 987654321 bestaat niet"));
        refusal(post(BASE, Map.of("countYear", YEAR, "locationId", shelf, "correctsCountId", 987_654_321L)),
                "GEEN_TELLING_OM_TE_CORRIGEREN")
                .body("message", matchesPattern("Corrigeren kan alleen op de laatste geboekte telling van Http annuleren .* voor " + YEAR))
                .body("details", equalTo(Map.of()));
        for (String path : List.of("", "/booking-check")) given().get(BASE + "/987654321" + path).then().statusCode(404)
                .body("message", equalTo("Telling 987654321 bestaat niet"));
        for (String path : List.of("/book", "/cancel")) post(BASE + "/987654321" + path, Map.of()).statusCode(404);

        int count = post(BASE, Map.of("countYear", YEAR, "locationId", shelf)).statusCode(201).extract().path("id");
        int line = given().get(BASE + "/{id}", count).then().extract().path("lines.find { it.productId == " + rose + " }.id");
        put(count, line, write(2, "NIET_GEVONDEN", null, 0)).statusCode(200);

        post(BASE + "/" + count + "/cancel", Map.of()).statusCode(200)
                .body("status", equalTo("GEANNULEERD"))
                .body("bookedAt", nullValue());

        assertEquals(6, stock.quantityAt(rose, shelf), "a cancelled count books nothing");
        refusal(put(count, line, write(3, null, null, 1)), "TELLING_GESLOTEN").body("message", equalTo("Deze telling is geannuleerd"));
        refusal(post(BASE + "/" + count + "/cancel", Map.of()), "TELLING_GESLOTEN").body("message", equalTo("Deze telling is geannuleerd"));
        refusal(given().get(BASE + "/{id}/booking-check", count).then(), "TELLING_GESLOTEN");
        List<String> logged = QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                "select action from ActivityLogEntity where entityType = 'STOCK_COUNT' and entityId = ?1", String.class)
                .setParameter(1, String.valueOf(count)).getResultList());
        assertEquals(List.of("STATUS_CHANGED"), logged);
        assertTrue(given().queryParam("year", YEAR).get(BASE).then().statusCode(200).extract().jsonPath()
                .getList("counts.id", Integer.class).contains(count));
    }

    /* ---------------------------------------------------------------- helpers */

    private List<ValidatableResponse> everyRoute() {
        return List.of(
                given().get(BASE).then(),
                post(BASE, Map.of("countYear", YEAR, "locationId", 1)),
                given().get(BASE + "/1").then(),
                put(1, 1, write(1, null, null, 0)),
                post(BASE + "/1/lines", Map.of("productId", 1)),
                given().get(BASE + "/1/booking-check").then(),
                post(BASE + "/1/book", Map.of("checkToken", "x")),
                post(BASE + "/1/cancel", Map.of()));
    }

    /** A 409 of the count: the code the screen decides on, next to the sentence it shows. */
    private static ValidatableResponse refusal(ValidatableResponse response, String code) {
        return response.statusCode(409)
                .body("status", equalTo(409))
                .body("code", equalTo(code))
                .body("message", notNullValue())
                .body("$", hasKey("details"))
                .body("timestamp", notNullValue());
    }

    private static void invalid(ValidatableResponse response, String message) {
        response.statusCode(422)
                .body("status", equalTo(422))
                .body("message", equalTo(message))
                .body("$", not(hasKey("code")));
    }

    private static ValidatableResponse post(String path, Map<String, Object> body) {
        return given().contentType("application/json").body(body).post(path).then();
    }

    private static ValidatableResponse put(long count, long line, Map<String, Object> body) {
        return given().contentType("application/json").body(body).put(BASE + "/{id}/lines/{lineId}", count, line).then();
    }

    private static Map<String, Object> write(Integer counted, String reason, String note, int revision) {
        Map<String, Object> body = new HashMap<>();
        body.put("countedQuantity", counted);
        body.put("reasonCode", reason);
        body.put("reasonNote", note);
        body.put("revision", revision);
        return body;
    }

    private long location(String name) {
        long id = QuarkusTransaction.requiringNew().call(() -> stock.saveLocation(new StockLocation(null, null,
                name + " " + UUID.randomUUID().toString().substring(0, 8), StockLocation.Kind.SALES_POINT, null,
                true, false, false, 9)).id());
        locationIds.add(id);
        return id;
    }

    private long product(String name, boolean active) {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            var product = new ProductEntity();
            product.sku = "COUNT-HTTP-" + UUID.randomUUID();
            product.name = name;
            product.active = active;
            product.piecesPerCarton = 12;
            product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
            product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
            product.cartonWeightKg = BigDecimal.ONE;
            em.persist(product);
            em.flush();
            return product.id;
        });
        productIds.add(id);
        return id;
    }

    private void level(long productId, long locationId, int quantity) {
        QuarkusTransaction.requiringNew().run(() -> stock.setLevel(productId, locationId, quantity,
                StockMovement.Kind.MANUAL_CORRECTION, "test"));
    }
}
