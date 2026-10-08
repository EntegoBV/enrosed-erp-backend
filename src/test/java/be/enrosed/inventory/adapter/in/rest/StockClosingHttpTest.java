package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.CatalogWorkbook;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The closing endpoints as the screen calls them: the statuses, the Dutch
 * refusals with the code the screen decides on, and the two downloads.
 *
 * The closing is one for 2001, a year in which this book holds nothing, so
 * the walk computes on whatever other tests left behind without valuing it.
 */
@QuarkusTest
class StockClosingHttpTest {
    private static final String BASE = "/api/stock-closings";
    private static final String RULE = "/api/stock-valuation-rule";
    private static final String OPENING = "/api/stock-opening-layers";
    private static final int YEAR = 2001;
    private static final String FINAL = "Deze afsluiting is definitief. Maak een nieuwe versie om iets te corrigeren";

    @Inject EntityManager em;

    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void removeRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<Long> closings = em.createQuery("select id from StockClosingEntity where closingYear between 2000 and 2002", Long.class)
                    .getResultList();
            for (Long closing : closings) {
                for (String entity : List.of("StockClosingLineEntity", "StockClosingLayerEntity", "StockClosingLotEntity",
                        "StockClosingContainerEntity", "StockClosingArticleEntity", "StockClosingSeparateEntity",
                        "StockClosingWriteDownEntity", "StockClosingMovementEntity", "StockClosingDecisionEntity")) {
                    em.createQuery("delete from " + entity + " where closingId = ?1").setParameter(1, closing).executeUpdate();
                }
                em.createQuery("delete from ActivityLogEntity where entityType = 'STOCK_CLOSING' and entityId = ?1")
                        .setParameter(1, String.valueOf(closing)).executeUpdate();
                em.createQuery("delete from StockClosingEntity where id = ?1").setParameter(1, closing).executeUpdate();
            }
            if (em.createQuery("select count(c) from StockClosingEntity c", Long.class).getSingleResult() == 0) {
                em.createQuery("delete from StockValuationRuleEntity").executeUpdate();
            }
            for (Long product : productIds) {
                em.createQuery("delete from StockOpeningLayerEntity where productId = ?1").setParameter(1, product).executeUpdate();
                ProductEntity stored = em.find(ProductEntity.class, product);
                if (stored != null) em.remove(stored);
            }
        });
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
    void aClosingIsCreatedDecidedOnAndRefusedUntilNothingBlocksIt() {
        given().get(RULE).then().statusCode(204);
        invalid(post(BASE, Map.of()), "Kies een boekjaar");
        invalid(post(BASE, body("closingYear", YEAR, "closingDate", "2010-01-01")),
                "De afsluitdatum ligt meer dan twee jaar van het boekjaar");

        ValidatableResponse created = post(BASE, Map.of("closingYear", YEAR)).statusCode(201)
                .body("closingYear", equalTo(YEAR))
                .body("versionNo", equalTo(1))
                .body("closingDate", equalTo("2001-12-31"))
                .body("cutoffAt", equalTo("2001-12-31T23:00:00Z"))
                .body("status", equalTo("CONCEPT"))
                .body("superseded", equalTo(false))
                .body("supersedesId", nullValue())
                .body("previousClosing", nullValue())
                .body("versionChanges", nullValue())
                .body("rule.method", equalTo("FIFO_LOT"))
                .body("rule.methodLabel", equalTo("FIFO per ontvangen partij"))
                .body("rule.effectiveFromYear", equalTo(YEAR))
                .body("rule.text", startsWith("Handelsgoederen worden gewaardeerd tegen aanschaffingswaarde"))
                .body("totals", hasKey("totalValueEur"))
                .body("notices.code", hasItem("BTW_BEVESTIGING"))
                .body("writeDownReasons.code", equalTo(List.of("BESCHADIGD", "VEROUDERD", "TRAAG", "DEMO", "MARKT")))
                .body("dataSha256", matchesPattern("[0-9a-f]{64}"))
                .body("computedAt", notNullValue())
                .body("pdfSha256", nullValue())
                .body("canFinalize", equalTo(false))
                .body("canCorrect", equalTo(false));
        long id = created.extract().jsonPath().getLong("id");

        refusal(post(BASE, Map.of("closingYear", YEAR)), "BESTAAT_AL")
                .body("message", equalTo("Voor 2001 bestaat al een afsluiting. Open ze, of maak een nieuwe versie"))
                .body("details.closingId", equalTo((int) id));
        refusal(post(BASE, body("closingYear", YEAR + 1, "closingDate", "2001-12-01")), "DATUM_VOLGORDE")
                .body("message", equalTo("De afsluitdatum moet na die van 2001 liggen"));
        given().get(RULE).then().statusCode(200)
                .body("method", equalTo("FIFO_LOT"))
                .body("effectiveFromYear", equalTo(YEAR))
                .body("ruleVersion", equalTo("1"));
        given().get(BASE).then().statusCode(200)
                .body("rule.effectiveFromYear", equalTo(YEAR))
                .body("closings.find { it.id == " + id + " }.status", equalTo("CONCEPT"))
                .body("closings.find { it.id == " + id + " }.hasFiles", equalTo(false));
        given().get(BASE + "/" + id).then().statusCode(200).body("id", equalTo((int) id));
        missing(given().get(BASE + "/987654321").then(), "Afsluiting 987654321 bestaat niet");

        put(BASE + "/" + id, Map.of("closingDate", "2001-12-30")).statusCode(200).body("closingDate", equalTo("2001-12-30"));
        invalid(put(BASE + "/" + id, Map.of("closingDate", "2010-01-01")), "De afsluitdatum ligt meer dan twee jaar van het boekjaar");
        post(BASE + "/" + id + "/recompute", Map.of()).statusCode(200).body("status", equalTo("CONCEPT"));
        missing(post(BASE + "/987654321/recompute", Map.of()), "Afsluiting 987654321 bestaat niet");

        /* Decisions: what can never be valid is a 422 with the sentence the screen shows. */
        long product = product("Roos");
        String decisions = BASE + "/" + id + "/decisions";
        invalid(put(decisions, Map.of("kind", "IETS_ANDERS")), "Onbekende beslissing");
        invalid(put(decisions, Map.of("kind", "WRITE_DOWN")), "Kies het onderwerp van deze beslissing");
        invalid(put(decisions, Map.of("kind", "TRANSIT", "flag", true)), "Kies het onderwerp van deze beslissing");
        invalid(put(decisions, Map.of("kind", "THIRD_PARTY", "productId", product, "quantity", 1, "reason", "In bewaring")),
                "Vermeld de eigenaar");
        invalid(put(decisions, Map.of("kind", "THIRD_PARTY", "productId", product, "quantity", 0, "counterparty", "Jan",
                "reason", "In bewaring")), "Geef een aantal groter dan nul");
        invalid(put(decisions, Map.of("kind", "THIRD_PARTY", "productId", product, "quantity", 1, "counterparty", "Jan")),
                "Geef een reden");
        invalid(put(decisions, Map.of("kind", "WRITE_DOWN", "productId", product, "unitValueEur", 1, "reason", "x")),
                "Kies een reden voor de waardevermindering");
        invalid(put(decisions, Map.of("kind", "WRITE_DOWN", "productId", product, "unitValueEur", -1, "reasonCode", "MARKT",
                "reason", "x")), "Marktwaarde per stuk kan niet negatief zijn");
        invalid(put(decisions, Map.of("kind", "WRITE_DOWN", "productId", product, "quantity", 0, "unitValueEur", 1,
                "reasonCode", "MARKT", "reason", "x")), "Geef een aantal groter dan nul, of laat het leeg voor alle stuks");
        refusal(put(decisions, Map.of("kind", "WRITE_DOWN", "productId", product, "quantity", 5, "unitValueEur", 1,
                "reasonCode", "MARKT", "reason", "x")), "AFWAARDERING_TE_VEEL")
                .body("message", equalTo("De aantallen met een waardevermindering zijn samen meer dan de eigen voorraad (0)"));
        ValidatableResponse confirmed = put(decisions, Map.of("kind", "VAT_CONFIRMATION", "flag", true)).statusCode(200)
                .body("decisions.kind", equalTo(List.of("VAT_CONFIRMATION")))
                .body("decisions[0].kindLabel", equalTo("Bevestiging btw"))
                .body("decisions[0].decidedByName", equalTo("emre"))
                .body("notices.code", not(hasItem("BTW_BEVESTIGING")));
        long decision = confirmed.extract().jsonPath().getLong("decisions[0].id");
        missing(given().delete(decisions + "/987654321").then(), "Beslissing 987654321 bestaat niet");
        ValidatableResponse unconfirmed = given().delete(decisions + "/" + decision).then().statusCode(200)
                .body("decisions", empty())
                .body("notices.code", hasItem("BTW_BEVESTIGING"));
        String seen = unconfirmed.extract().jsonPath().getString("dataSha256");

        /* Definitief maken: a signer, the figures the screen read, and nothing that blocks. */
        String finalize = BASE + "/" + id + "/finalize";
        invalid(post(finalize, Map.of("dataSha256", seen)), "Vul in wie de inventaris ondertekent");
        invalid(post(finalize, Map.of("dataSha256", seen, "signerName", "  ")), "Vul in wie de inventaris ondertekent");
        refusal(post(finalize, Map.of("dataSha256", "0".repeat(64), "signerName", "Emre")), "CIJFERS_GEWIJZIGD")
                .body("message", equalTo("De gegevens zijn intussen gewijzigd. Herbereken en kijk de cijfers opnieuw na"));
        refusal(post(finalize, Map.of("dataSha256", seen, "signerName", "Emre")), "GEBLOKKEERD")
                .body("message", matchesPattern("Nog \\d+ punten houden de afsluiting tegen"))
                .body("details.notices.code", hasItem("BTW_BEVESTIGING"))
                .body("details.notices.severity", not(hasItem("WARNING")))
                .body("details.notices.find { it.code == 'BTW_BEVESTIGING' }.segment", equalTo("afsluiten"))
                .body("details.notices.find { it.code == 'BTW_BEVESTIGING' }.message", startsWith("Bevestig dat de betalingen onder"));
        missing(post(BASE + "/987654321/finalize", Map.of("dataSha256", seen, "signerName", "Emre")), "Afsluiting 987654321 bestaat niet");
        given().get(BASE + "/" + id).then().statusCode(200).body("status", equalTo("CONCEPT")).body("signerName", nullValue());

        /* A new version exists only of the valid final version. */
        String versions = BASE + "/" + id + "/versions";
        invalid(post(versions, Map.of()), "Geef de reden van de correctie");
        refusal(post(versions, Map.of("reason", "Te vroeg")), "GEEN_DEFINITIEVE")
                .body("message", equalTo("Alleen van de geldige definitieve versie kan een nieuwe versie gemaakt worden"));
        missing(post(BASE + "/987654321/versions", Map.of("reason", "Bestaat niet")), "Afsluiting 987654321 bestaat niet");

        /* The files of a concept are rendered at the moment they are asked for. */
        byte[] pdf = given().get(BASE + "/" + id + "/pdf").then().statusCode(200)
                .contentType("application/pdf")
                .header("Content-Disposition", "attachment; filename=\"jaarinventaris-2001-v1-concept.pdf\"")
                .header("Cache-Control", "no-store")
                .extract().asByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        byte[] xlsx = given().get(BASE + "/" + id + "/xlsx").then().statusCode(200)
                .contentType(CatalogWorkbook.MEDIA_TYPE)
                .header("Content-Disposition", "attachment; filename=\"jaarinventaris-2001-v1-concept.xlsx\"")
                .header("Cache-Control", "no-store")
                .extract().asByteArray();
        assertEquals("PK", new String(xlsx, 0, 2, StandardCharsets.US_ASCII));
        missing(given().get(BASE + "/987654321/pdf").then(), "Afsluiting 987654321 bestaat niet");
        missing(given().get(BASE + "/987654321/xlsx").then(), "Afsluiting 987654321 bestaat niet");

        /* Opening values know no closing: a row is added, replaced and retired, never removed. */
        invalid(post(OPENING, body("asOfDate", "2000-12-31", "source", " ", "rows", List.of(Map.of("productId", product,
                "quantity", 5, "unitValueEur", 1.5)))), "Vermeld de bron van deze beginwaarde");
        long layer = post(OPENING, body("asOfDate", "2000-12-31", "source", "Inventaris 2000", "rows", List.of(Map.of(
                "productId", product, "quantity", 5, "unitValueEur", 1.5)))).statusCode(201)
                .body("[0].productId", equalTo((int) product))
                .body("[0].quantity", equalTo(5))
                .body("[0].source", equalTo("Inventaris 2000"))
                .extract().jsonPath().getLong("[0].id");
        given().get(OPENING).then().statusCode(200).body("id", hasItem((int) layer));
        given().delete(OPENING + "/" + layer).then().statusCode(204);
        given().get(OPENING).then().statusCode(200).body("id", not(hasItem((int) layer)));
        missing(given().delete(OPENING + "/987654321").then(), "Beginwaarde 987654321 bestaat niet");

        given().delete(BASE + "/" + id).then().statusCode(204);
        missing(given().get(BASE + "/" + id).then(), "Afsluiting " + id + " bestaat niet");
        given().get(RULE).then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
    void everyWriteOnAFinalClosingIsRefusedAndACorrectionIsANewVersion() {
        long id = post(BASE, Map.of("closingYear", YEAR)).statusCode(201).extract().jsonPath().getLong("id");
        long decision = put(BASE + "/" + id + "/decisions", Map.of("kind", "VAT_CONFIRMATION", "flag", true)).statusCode(200)
                .extract().jsonPath().getLong("decisions[0].id");
        /* What "Definitief maken" sets on the row; the walk through the finalizer itself is StockClosingFlowTest. */
        QuarkusTransaction.requiringNew().run(() -> em.createQuery("update StockClosingEntity set status = 'DEFINITIEF',"
                + " finalizedByName = 'emre', signerName = 'Emre' where id = ?1").setParameter(1, id).executeUpdate());

        ValidatableResponse frozen = given().get(BASE + "/" + id).then().statusCode(200)
                .body("status", equalTo("DEFINITIEF"))
                .body("canFinalize", equalTo(false))
                .body("canCorrect", equalTo(true));
        String seen = frozen.extract().jsonPath().getString("dataSha256");

        refusal(put(BASE + "/" + id, Map.of("closingDate", "2001-12-30")), "DEFINITIEF").body("message", equalTo(FINAL));
        refusal(post(BASE + "/" + id + "/recompute", Map.of()), "DEFINITIEF").body("message", equalTo(FINAL));
        refusal(put(BASE + "/" + id + "/decisions", Map.of("kind", "VAT_CONFIRMATION", "flag", true)), "DEFINITIEF")
                .body("message", equalTo(FINAL));
        refusal(given().delete(BASE + "/" + id + "/decisions/" + decision).then(), "DEFINITIEF").body("message", equalTo(FINAL));
        refusal(post(BASE + "/" + id + "/finalize", Map.of("dataSha256", seen, "signerName", "Emre")), "DEFINITIEF")
                .body("message", equalTo(FINAL));
        refusal(given().delete(BASE + "/" + id).then(), "DEFINITIEF")
                .body("message", equalTo("Een definitieve afsluiting kan niet verwijderd worden"));
        given().get(BASE + "/" + id).then().statusCode(200)
                .body("status", equalTo("DEFINITIEF"))
                .body("dataSha256", equalTo(seen))
                .body("decisions.id", equalTo(List.of((int) decision)));

        /* Once a closing is final the rule is fixed: an earlier year is refused. */
        refusal(post(BASE, Map.of("closingYear", YEAR - 1)), "VOOR_REGEL")
                .body("message", equalTo("De waarderingsregel geldt vanaf 2001; een vroeger boekjaar kan niet"));

        /* The correction: a new concept that names the version it replaces, with a copy of the decisions. */
        ValidatableResponse started = post(BASE + "/" + id + "/versions", Map.of("reason", "Factuur kwam later")).statusCode(201)
                .body("closingYear", equalTo(YEAR))
                .body("versionNo", equalTo(2))
                .body("status", equalTo("CONCEPT"))
                .body("supersedesId", equalTo((int) id))
                .body("correctionReason", equalTo("Factuur kwam later"))
                .body("decisions.kind", equalTo(List.of("VAT_CONFIRMATION")))
                .body("versionChanges.againstClosingId", equalTo((int) id))
                .body("versionChanges.againstVersionNo", equalTo(1))
                .body("versions.versionNo", equalTo(List.of(2, 1)));
        long second = started.extract().jsonPath().getLong("id");
        refusal(post(BASE + "/" + id + "/versions", Map.of("reason", "Nog eens")), "CONCEPT_BESTAAT")
                .body("message", equalTo("Voor 2001 staat al een concept open"))
                .body("details.closingId", equalTo((int) second));
        given().get(BASE + "/" + id).then().statusCode(200)
                .body("status", equalTo("DEFINITIEF"))
                .body("superseded", equalTo(false))
                .body("canCorrect", equalTo(false));
        given().get(BASE + "/" + second + "/pdf").then().statusCode(200)
                .header("Content-Disposition", "attachment; filename=\"jaarinventaris-2001-v2-concept.pdf\"");
        given().get(BASE + "/" + id + "/xlsx").then().statusCode(200)
                .header("Content-Disposition", "attachment; filename=\"jaarinventaris-2001-v1.xlsx\"");

        /* The concept goes; the final version stays and can be corrected again. */
        given().delete(BASE + "/" + second).then().statusCode(204);
        given().get(BASE + "/" + id).then().statusCode(200).body("canCorrect", equalTo(true));
        given().get(RULE).then().statusCode(200).body("effectiveFromYear", equalTo(YEAR));
    }

    private List<ValidatableResponse> everyRoute() {
        return List.of(
                given().get(BASE).then(),
                post(BASE, Map.of("closingYear", YEAR)),
                given().get(BASE + "/1").then(),
                put(BASE + "/1", Map.of("closingDate", "2001-12-31")),
                post(BASE + "/1/recompute", Map.of()),
                put(BASE + "/1/decisions", Map.of("kind", "VAT_CONFIRMATION", "flag", true)),
                given().delete(BASE + "/1/decisions/1").then(),
                given().delete(BASE + "/1").then(),
                given().get(RULE).then(),
                post(BASE + "/1/finalize", Map.of("dataSha256", "x", "signerName", "x")),
                post(BASE + "/1/versions", Map.of("reason", "x")),
                given().get(BASE + "/1/pdf").then(),
                given().get(BASE + "/1/xlsx").then(),
                given().get(OPENING).then(),
                post(OPENING, Map.of("asOfDate", "2000-12-31", "source", "x", "rows", List.of())),
                given().delete(OPENING + "/1").then());
    }

    /** A 409 of the closing: the code the screen decides on, next to the sentence it shows. */
    private static ValidatableResponse refusal(ValidatableResponse response, String code) {
        return response.statusCode(409)
                .body("status", equalTo(409))
                .body("code", equalTo(code))
                .body("message", notNullValue())
                .body("$", hasKey("details"))
                .body("timestamp", notNullValue());
    }

    private static void invalid(ValidatableResponse response, String message) {
        response.statusCode(422).body("status", equalTo(422)).body("message", equalTo(message));
    }

    private static void missing(ValidatableResponse response, String message) {
        response.statusCode(404).body("message", equalTo(message));
    }

    private static ValidatableResponse post(String path, Object body) {
        return given().contentType(ContentType.JSON).body(body).post(path).then();
    }

    private static ValidatableResponse put(String path, Object body) {
        return given().contentType(ContentType.JSON).body(body).put(path).then();
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    private long product(String name) {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            var product = new ProductEntity();
            product.sku = "CLOSING-HTTP-" + UUID.randomUUID();
            product.name = name;
            product.active = true;
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
}
