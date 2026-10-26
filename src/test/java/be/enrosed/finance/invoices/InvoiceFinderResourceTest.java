package be.enrosed.finance.invoices;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.application.CostPartyService;
import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.media.MediaService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sourcing.application.PurchaseInvoiceService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceFinderResourceTest {
    private static final String PERIOD = "&from=2031-03-01&to=2031-03-31";
    static final Set<String> ROW_KEYS = Set.of("key", "source", "direction", "kind", "docLabel", "number", "date", "sortDate",
            "dateKind", "dueDate", "amount", "currency", "amountEur", "paidEur", "openEur", "party", "payee", "description",
            "category", "salesPurpose", "status", "statusReason", "origin", "peppolState", "odooMoveId", "odoo", "container",
            "links", "files", "missingFile");

    @Inject EntityManager em;
    @Inject SupplierService suppliers;
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseInvoiceService invoices;
    @Inject CompanyCostService costs;
    @Inject CostPartyService parties;
    @Inject MediaService media;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject OdooDocumentMarks marks;

    private static InvoiceFinderFixtures fx;

    @BeforeEach
    void setUp() {
        if (fx == null) {
            fx = new InvoiceFinderFixtures().build(new InvoiceFinderFixtures.Deps(em, suppliers, purchases, invoices, costs,
                    parties, media, sales, customers, incoming, marks));
        }
        fx.odoo.failWith = null;
        fx.odoo.queries.clear();
    }

    /** The committed fixtures leave the lists other suites read first (sales documents, bank lines). */
    @AfterAll
    void tearDown() {
        if (fx != null) fx.tearDown(em);
        fx = null;
    }

    private static void useFakeOdoo() {
        QuarkusMock.installMockForType(fx.odoo, OdooInvoices.class);
    }

    @Test
    void withoutALoginNothingIsListed() {
        given().get("/api/invoice-finder").then().statusCode(401);
        given().get("/api/invoice-finder/rows/pi:1").then().statusCode(401);
        given().contentType("application/json").body(Map.of("resolution", "HANDLED"))
                .put("/api/invoice-finder/odoo/1/mark").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "test-admin", roles = "admin")
    void theListHasTheContractsShape() {
        useFakeOdoo();
        JsonPath page = given().get("/api/invoice-finder?q=" + fx.token + PERIOD).then().statusCode(200)
                .extract().jsonPath();
        Map<String, Object> top = page.getMap("");
        assertEquals(Set.of("rows", "nextCursor", "erpCount", "odoo"), top.keySet());
        assertEquals(Map.of("state", "READY", "included", true), withoutNulls(page.getMap("odoo")));
        assertEquals(Set.of("state", "code", "message", "included"), page.getMap("odoo").keySet());
        assertEquals(9, page.getInt("erpCount"));
        List<Map<String, Object>> rows = page.getList("rows");
        assertEquals(15, rows.size());
        for (Map<String, Object> row : rows) assertEquals(ROW_KEYS, row.keySet(), String.valueOf(row.get("key")));

        Map<String, Object> ci = byKey(rows, "pi:" + fx.ciInvoice);
        assertEquals("2031-03-10", ci.get("date"));
        assertEquals("2031-03-10", ci.get("sortDate"));
        assertEquals("INVOICE", ci.get("dateKind"));
        assertEquals(1000.0, ((Number) ci.get("amount")).doubleValue());
        assertEquals("EUR", ci.get("currency"));
        assertEquals(Set.of("name", "type", "id"), map(ci.get("party")).keySet());
        assertEquals(Set.of("id", "number", "alias"), map(ci.get("container")).keySet());
        assertEquals(Set.of("type", "id", "label", "date", "account"), map(list(ci.get("links")).get(1)).keySet());
        assertEquals("2031-03-05", map(list(ci.get("links")).get(1)).get("date"));
        assertEquals(Set.of("type", "id", "orderId", "moveId", "name", "contentType", "kind", "sizeBytes", "archived"),
                map(list(ci.get("files")).getFirst()).keySet());

        Map<String, Object> marked = byKey(rows, "odoo:" + fx.m9);
        Map<String, Object> odoo = map(marked.get("odoo"));
        assertEquals(Set.of("moveId", "name", "moveType", "state", "paymentState", "registrable", "blockedBy", "mark"), odoo.keySet());
        assertEquals(Set.of("resolution", "purchaseOrderId", "note", "markedAt", "markedBy"), map(odoo.get("mark")).keySet());
        assertEquals("MARKED", odoo.get("blockedBy"));
        assertEquals(false, odoo.get("registrable"));
        assertEquals("2031-03-12", marked.get("sortDate"));

        String cursor = given().get("/api/invoice-finder?limit=2&q=" + fx.token + PERIOD).then().statusCode(200)
                .body("rows.size()", equalTo(2)).extract().jsonPath().getString("nextCursor");
        assertNotNull(cursor);
        given().get("/api/invoice-finder?limit=2&q=" + fx.token + PERIOD + "&cursor=" + cursor).then().statusCode(200)
                .body("rows.size()", lessThanOrEqualTo(2));
        given().get("/api/invoice-finder?direction=OUT&scope=CREDIT_NOTES&q=" + fx.token + PERIOD).then().statusCode(200)
                .body("rows.key", contains("so:" + fx.salesCredit, "odoo:" + fx.m12));
        given().get("/api/invoice-finder/rows/pd:" + fx.ciDocument).then().statusCode(200).body("key", equalTo("pi:" + fx.ciInvoice));
        given().get("/api/invoice-finder/rows/pi:" + fx.trashedInvoice).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "test-admin", roles = "admin")
    void requestErrorsAnswerFourHundredWithACode() {
        useFakeOdoo();
        refused("/api/invoice-finder?scope=CREDIT_NOTES", "INVALID_SCOPE");
        refused("/api/invoice-finder?direction=SIDEWAYS", "INVALID_SCOPE");
        refused("/api/invoice-finder?q=" + "x".repeat(201), "INVALID_QUERY");
        refused("/api/invoice-finder?from=2031-03-10&to=2031-03-01", "INVALID_PERIOD");
        refused("/api/invoice-finder?from=10-03-2031", "INVALID_PERIOD");
        refused("/api/invoice-finder?limit=0", "INVALID_LIMIT");
        refused("/api/invoice-finder?limit=101", "INVALID_LIMIT");
        refused("/api/invoice-finder?limit=veel", "INVALID_LIMIT");
        refused("/api/invoice-finder?cursor=niet-geldig", "INVALID_CURSOR");
        refused("/api/invoice-finder?orderId=abc", "INVALID_ORDER");
        refused("/api/invoice-finder/rows/xx:1", "INVALID_KEY");
        refused("/api/invoice-finder/rows/pi:0", "INVALID_KEY");
        refused("/api/invoice-finder/rows/pi:1234567890123456789", "INVALID_KEY");
        given().get("/api/invoice-finder/rows/pi:123456789012345678").then().statusCode(404);
    }

    private static void refused(String path, String code) {
        given().get(path).then().statusCode(400).body("status", equalTo(400)).body("code", equalTo(code))
                .body("message", not(emptyOrNullString()));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = "admin")
    void withTheDefaultTestConfigOdooIsNotConnected() {
        JsonPath page = given().get("/api/invoice-finder?q=" + fx.token + PERIOD).then().statusCode(200).extract().jsonPath();
        assertEquals("NOT_CONNECTED", page.getString("odoo.state"));
        assertEquals("DISABLED", page.getString("odoo.code"));
        assertFalse(page.getBoolean("odoo.included"));
        assertEquals(9, page.getList("rows").size());
        given().get("/api/invoice-finder?scope=ODOO_NEW&q=" + fx.token + PERIOD).then().statusCode(200)
                .body("rows.size()", equalTo(0)).body("odoo.state", equalTo("NOT_CONNECTED"));
        given().get("/api/invoice-finder/rows/odoo:" + fx.m2).then().statusCode(503).body("code", equalTo("DISABLED"));
        given().contentType("application/json").body(Map.of("resolution", "HANDLED"))
                .put("/api/invoice-finder/odoo/" + fx.m2 + "/mark").then().statusCode(503).body("code", equalTo("DISABLED"));
        given().delete("/api/invoice-finder/odoo/" + fx.m2 + "/mark").then().statusCode(503);
    }

    @Test
    @TestSecurity(user = "test-admin", roles = "admin")
    void marksFollowTheirRulesAndRoundTrip() {
        useFakeOdoo();
        markRefused(fx.m2, Map.of("resolution", "DONE"), 400, "INVALID_MARK");
        markRefused(fx.m2, Map.of("resolution", "CONTAINER"), 400, "INVALID_MARK");
        markRefused(fx.m2, Map.of("resolution", "HANDLED", "purchaseOrderId", fx.orderA.id()), 400, "INVALID_MARK");
        markRefused(fx.m2, Map.of("resolution", "HANDLED", "note", "n".repeat(201)), 400, "INVALID_MARK");
        markRefused(fx.m12, Map.of("resolution", "HANDLED"), 400, "NOT_INCOMING");
        markRefused(fx.base + 99, Map.of("resolution", "HANDLED"), 404, null);
        given().contentType("application/json").body(Map.of("resolution", "HANDLED")).put("/api/invoice-finder/odoo/" + fx.m13 + "/mark")
                .then().statusCode(409).body("message", equalTo("Deze factuur is geannuleerd in Odoo."));
        given().contentType("application/json").body(Map.of("resolution", "HANDLED")).put("/api/invoice-finder/odoo/" + fx.m4 + "/mark")
                .then().statusCode(409).body("message", equalTo("Deze factuur is al als kost geregistreerd."));
        given().contentType("application/json").body(Map.of("resolution", "HANDLED")).put("/api/invoice-finder/odoo/" + fx.m1 + "/mark")
                .then().statusCode(409).body("message", equalTo("Deze factuur staat al in ENROSED."));
        given().contentType("application/json").body(Map.of("resolution", "HANDLED")).put("/api/invoice-finder/odoo/" + fx.m6 + "/mark")
                .then().statusCode(409).body("message", equalTo("Een TICA-afrekening verwerk je bij TICA."));
        markRefused(fx.m2, Map.of("resolution", "CONTAINER", "purchaseOrderId", fx.orderTrashed), 404, null);
        markRefused(fx.m2, Map.of("resolution", "CONTAINER", "purchaseOrderId", 999_999_999L), 404, null);
        assertTrue(marks.all(fx.namespace).keySet().containsAll(List.of(fx.m8, fx.m9)));
        assertFalse(marks.all(fx.namespace).containsKey(fx.m2), "a refused mark writes nothing");

        // A move whose cost was deleted may be marked, the mark changed and cleared; it is then new again, reason as info.
        given().contentType("application/json").body(Map.of("resolution", "HANDLED")).put("/api/invoice-finder/odoo/" + fx.m5 + "/mark")
                .then().statusCode(200).body("key", equalTo("odoo:" + fx.m5)).body("status", equalTo("HANDLED"))
                .body("statusReason", nullValue()).body("odoo.blockedBy", equalTo("MARKED"))
                .body("odoo.mark.markedBy", equalTo("test-admin"));
        given().contentType("application/json").body(Map.of("resolution", "CONTAINER", "purchaseOrderId", fx.orderA.id()))
                .put("/api/invoice-finder/odoo/" + fx.m5 + "/mark").then().statusCode(200)
                .body("status", equalTo("CONTAINER")).body("container.id", equalTo(fx.orderA.id().intValue()));
        given().delete("/api/invoice-finder/odoo/" + fx.m5 + "/mark").then().statusCode(200)
                .body("status", equalTo("NEW")).body("statusReason", equalTo("COST_REMOVED")).body("odoo.mark", nullValue())
                .body("odoo.blockedBy", nullValue()).body("odoo.registrable", equalTo(true));

        // PUT, replace, DELETE, DELETE again.
        Map<String, Object> body = new HashMap<>();
        body.put("resolution", "CONTAINER");
        body.put("purchaseOrderId", fx.orderA.id());
        body.put("note", "  zit in de container  ");
        given().contentType("application/json").body(body).put("/api/invoice-finder/odoo/" + fx.m2 + "/mark")
                .then().statusCode(200).body("status", equalTo("CONTAINER")).body("container.id", equalTo(fx.orderA.id().intValue()))
                .body("odoo.mark.resolution", equalTo("CONTAINER")).body("odoo.mark.note", equalTo("zit in de container"))
                .body("links[0].type", equalTo("CONTAINER")).body("odoo.registrable", equalTo(false));
        given().contentType("application/json").body(Map.of("resolution", "HANDLED")).put("/api/invoice-finder/odoo/" + fx.m2 + "/mark")
                .then().statusCode(200).body("status", equalTo("HANDLED")).body("container", nullValue());
        given().get("/api/invoice-finder?scope=ODOO_NEW&q=" + fx.token + PERIOD).then().statusCode(200)
                .body("rows.key", contains("odoo:" + fx.m5));
        given().delete("/api/invoice-finder/odoo/" + fx.m2 + "/mark").then().statusCode(200)
                .body("status", equalTo("NEW")).body("odoo.mark", nullValue()).body("odoo.registrable", equalTo(true));
        given().delete("/api/invoice-finder/odoo/" + fx.m2 + "/mark").then().statusCode(200).body("status", equalTo("NEW"));
        given().get("/api/invoice-finder?scope=ODOO_NEW&q=" + fx.token + PERIOD).then().statusCode(200)
                .body("rows.key", contains("odoo:" + fx.m2, "odoo:" + fx.m5));
    }

    private static void markRefused(long moveId, Map<String, Object> body, int status, String code) {
        var response = given().contentType("application/json").body(body).put("/api/invoice-finder/odoo/" + moveId + "/mark")
                .then().statusCode(status);
        if (code != null) response.body("code", equalTo(code));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = "admin")
    void filePreviewsServeAPdfOrSayThereIsNone() {
        useFakeOdoo();
        byte[] head = given().get("/api/invoice-finder/media/" + fx.pdfAsset + "/pdf").then().statusCode(200)
                .contentType("application/pdf").header("Content-Disposition", startsWith("inline"))
                .header("Cache-Control", equalTo("no-store")).header("X-Content-Type-Options", equalTo("nosniff"))
                .extract().asByteArray();
        assertEquals("%PDF-", new String(head, 0, 5));
        given().get("/api/invoice-finder/media/" + fx.pngAsset + "/pdf").then().statusCode(404)
                .body("code", equalTo("NO_PDF")).body("message", equalTo("Geen PDF in dit bestand."));
        given().get("/api/invoice-finder/media/999999999/pdf").then().statusCode(404);

        fx.odoo.files.put(fx.m1 + ":11", new OdooDocumentService.Download("VT 1.pdf", "application/pdf", "%PDF-1.7 demo".getBytes()));
        fx.odoo.files.put(fx.m1 + ":12", new OdooDocumentService.Download("VT1.xml", "application/xml",
                "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>".getBytes()));
        given().get("/api/invoice-finder/odoo/" + fx.m1 + "/attachments/11").then().statusCode(200)
                .header("Content-Disposition", equalTo("attachment; filename=\"VT 1.pdf\""));
        given().get("/api/invoice-finder/odoo/" + fx.m1 + "/attachments/11/pdf").then().statusCode(200)
                .contentType("application/pdf").header("Content-Disposition", startsWith("inline"));
        given().get("/api/invoice-finder/odoo/" + fx.m1 + "/attachments/12/pdf").then().statusCode(404).body("code", equalTo("NO_PDF"));
        given().get("/api/invoice-finder/odoo/" + fx.m1 + "/attachments/13").then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    private static Map<String, Object> byKey(List<Map<String, Object>> rows, String key) {
        return rows.stream().filter(row -> key.equals(row.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError(key + " missing"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> map) {
        Map<String, Object> copy = new HashMap<>();
        map.forEach((key, value) -> {
            if (value != null) copy.put(key, value);
        });
        return copy;
    }
}
