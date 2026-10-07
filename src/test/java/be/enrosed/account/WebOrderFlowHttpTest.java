package be.enrosed.account;

import be.enrosed.publicform.PublicFormRateBucketEntity;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.shared.Language;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One customer and one staff member walk the whole round over real HTTP: the customer logs
 * in, orders, changes and cancels on the public endpoints with a session; staff work in
 * the ERP endpoints with their own login; the mails are the ones the mailer would send.
 * Nothing is stubbed. Four orders: confirmed unchanged by its invoice, changed and approved
 * through the mailed link, cancelled by the customer, cancelled by Enrosed.
 */
@QuarkusTest
class WebOrderFlowHttpTest {
    private static final String ACCOUNT = "/api/v1/public/account";
    private static final String ORDERS = ACCOUNT + "/orders";
    private static final String DOCUMENTS = ACCOUNT + "/documents";
    private static final String STAFF = "/api/sales-orders";
    private static final String PASSWORD = "roses-in-a-dome";
    private static final String STAFF_PASSWORD = "named-auth-test-password";
    private static final String STALE = "De klant heeft deze bestelling intussen gewijzigd of geannuleerd. "
            + "Je wijzigingen zijn niet opgeslagen; laad de laatste versie.";
    private static final String ORDER_LOCKED = "{\"code\":\"ORDER_LOCKED\","
            + "\"message\":\"The order can no longer be changed\",\"fieldErrors\":{}}";

    @Inject CustomerAccountService accounts;
    @Inject MockMailbox mailbox;

    private final Shop shop = new Shop();
    private long customerId;
    private long productId;
    private String recordEmail;
    private String loginEmail;
    private String session;
    private String formToken;

    @BeforeEach
    void aCustomerWithALoginOnTheOrderPage() throws InterruptedException {
        /* Buckets other test classes filled per network are not this walk's. */
        QuarkusTransaction.requiringNew().run(() -> PublicFormRateBucketEntity.deleteAll());
        /* A customer as a login approval creates it: no address on the record yet. */
        customerId = shop.customer("Bloemen Peeters BV", null, null, null);
        recordEmail = shop.recordEmail(customerId);
        productId = shop.product();
        loginEmail = "inkoper-" + UUID.randomUUID().toString().substring(0, 12) + "@login.example";
        var grant = accounts.grant(customerId, loginEmail, "Jan Besteller", Language.NL);
        accounts.activate(grant.rawToken(), PASSWORD);

        Response form = given().queryParam("purpose", "QUOTE").when().get("/api/v1/public/forms/configuration");
        form.then().statusCode(200);
        formToken = form.path("formToken");
        long wait = Duration.between(Instant.now(),
                Instant.parse(form.<String>path("minimumSubmitAt")).plusMillis(1_200)).toMillis();
        if (wait > 0) Thread.sleep(wait);
        mailbox.clear();
    }

    @AfterEach
    void removeRows() {
        shop.remove();
        QuarkusTransaction.requiringNew().run(() -> PublicFormRateBucketEntity.deleteAll());
        mailbox.clear();
    }

    @Test
    void aCustomerOrdersAndEnrosedConfirmsChangesOrCancels() throws Exception {
        // the customer logs in and opens the order page: nothing to prefill yet
        session = given().contentType("application/json")
                .body(Map.of("email", loginEmail, "password", PASSWORD, "formToken", accountFormToken()))
                .when().post(ACCOUNT + "/session")
                .then().statusCode(200).extract().path("sessionToken");
        customer().when().get(DOCUMENTS + "/delivery-defaults")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("source", equalTo("NONE"))
                .body("fulfillment", nullValue())
                .body("destination", nullValue());

        // ---------------------------------------------------------------- order 1: confirmed unchanged
        float orderedTotal = customer().contentType("application/json").body(previewBody(6, "FR", null))
                .when().post(ORDERS + "/preview")
                .then().statusCode(200)
                .body("pricesVisible", equalTo(true))
                .body("totals.goodsNet", equalTo(720.0f))
                .body("shipping.status", equalTo("CALCULATED"))
                .body("validation.meetsMinimum", equalTo(true))
                .extract().path("totals.totalNet");

        Response placed = place(body(6, "FR", Map.of()));
        placed.then().statusCode(201).body("revision", equalTo(1)).body("status", equalTo("RECEIVED"));
        long first = placed.jsonPath().getLong("id");
        String firstNumber = placed.path("number");

        /* The received mail, in the language of the page the order was placed on, to the login that ordered. */
        Mail received = lastMailTo(loginEmail, 1);
        assertEquals("Votre commande " + firstNumber + " a bien été reçue", received.getSubject());
        assertTrue(received.getHtml().contains("Nous avons bien reçu votre commande"), received.getHtml());
        assertTrue(received.getHtml().contains("Industrieweg 1"), "the delivery address typed for this order");
        assertTrue(mailbox.getMailsSentTo(recordEmail) == null || mailbox.getMailsSentTo(recordEmail).isEmpty(),
                "the record's address gets no copy of an order mail");

        customer().when().get(DOCUMENTS + "?kind=ORDERS")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].id", equalTo((int) first))
                .body("items[0].kind", equalTo("ORDER"))
                .body("items[0].status", equalTo("RECEIVED"))
                .body("items[0].canChange", equalTo(true))
                .body("items[0].totalExclVat", equalTo(orderedTotal));
        Map<String, Object> staleScreen = staffOrder(first);

        // the customer changes the order twice
        change(first, body(7, "FR", Map.of("baseRevision", 1))).then().statusCode(200).body("revision", equalTo(2));
        change(first, body(8, "FR", Map.of("baseRevision", 2, "notes", "Livraison avant midi")))
                .then().statusCode(200).body("revision", equalTo(3)).body("status", equalTo("RECEIVED"));
        assertEquals(1, mailbox.getMailsSentTo(loginEmail).size(), "a change sends the customer no mail");

        // a staff screen from before those changes cannot save over them
        staffPut(first, 1, staleScreen).statusCode(409)
                .body("code", equalTo("WEB_ORDER_CHANGED"))
                .body("webOrderRevision", equalTo(3))
                .body("message", equalTo(STALE));
        staff().when().get(STAFF + "/{id}", first).then().statusCode(200)
                .body("order.lines[0].quantity", equalTo(96))
                .body("order.notes", equalTo("Livraison avant midi"))
                .body("webOrder.revision", equalTo(3))
                .body("webOrder.processingStartedAt", nullValue())
                .body("webOrder.customerEditable", equalTo(true))
                .body("webOrder.termsState", equalTo("ORDER_EQUAL"))
                .body("delivery.address", equalTo("Industrieweg 1"))
                .body("delivery.contactName", equalTo("Jan Besteller"));

        // staff take the order: the customer is told and can no longer change it
        staff().queryParam("webOrderRevision", 3).when().post(STAFF + "/{id}/take-into-processing", first)
                .then().statusCode(200)
                .body("webOrder.processingTrigger", equalTo("KNOP"))
                .body("webOrder.customerEditable", equalTo(false));
        Mail processing = lastMailTo(loginEmail, 2);
        assertEquals("Votre commande " + firstNumber + " est en cours de traitement", processing.getSubject());

        /* The old screen stays refused although the order is taken: it never saw the customer's version. */
        staffPut(first, 1, staleScreen).statusCode(409).body("code", equalTo("WEB_ORDER_CHANGED"));
        staff().when().get(STAFF + "/{id}", first).then().statusCode(200).body("order.lines[0].quantity", equalTo(96));

        Response locked = change(first, body(9, "FR", Map.of("baseRevision", 3)));
        assertEquals(409, locked.statusCode());
        assertEquals(ORDER_LOCKED, locked.asString());
        customer().when().get(DOCUMENTS + "?kind=ORDERS")
                .then().statusCode(200)
                .body("items[0].status", equalTo("IN_PROCESSING"))
                .body("items[0].canChange", equalTo(false))
                .body("items[0].canCancel", equalTo(false));

        // path A: the invoice is the confirmation, and it must be what the customer ordered
        ValidatableResponse draft = staff().queryParam("webOrderRevision", 3).contentType("application/json")
                .when().post(STAFF + "/{id}/invoice", first).then().statusCode(200)
                .body("order.docType", equalTo("FACTUUR"))
                .body("order.sourceQuoteId", equalTo((int) first))
                .body("delivery.address", equalTo("Industrieweg 1"));
        long firstInvoice = draft.extract().jsonPath().getLong("order.id");
        String firstInvoiceNumber = draft.extract().path("order.number");
        customer().when().get(DOCUMENTS + "?kind=ORDERS").then().statusCode(200)
                .body("items[0].status", equalTo("IN_PROCESSING"));
        customer().when().get(DOCUMENTS + "?kind=INVOICES").then().statusCode(200).body("items", hasSize(0));

        /* Staff complete the customer record for the invoice. It is the billing address: the freight of
           the order stays priced on the address the customer ordered for, so the invoice still equals the order. */
        Map<String, Object> record = new HashMap<>(staff().when().get("/api/customers/{id}", customerId)
                .then().statusCode(200).extract().jsonPath().getMap("$"));
        record.put("address", "Bloemenlaan 5");
        record.put("postalCode", "2000");
        record.put("city", "Antwerpen");
        staff().contentType("application/json").body(record).when().put("/api/customers/{id}", customerId)
                .then().statusCode(200);
        staff().when().get(STAFF + "/{id}", firstInvoice).then().statusCode(200)
                .body("delivery.postalCode", equalTo("3980"))
                .body("delivery.differsFromCustomerRecord", equalTo(true))
                .body("webOrder", nullValue());

        Map<String, Object> dearer = staffOrder(firstInvoice);
        line(dearer).put("unitPriceEur", 11);
        staffPut(firstInvoice, null, dearer).statusCode(200);
        staff().contentType("application/json").when().post(STAFF + "/{id}/issue", firstInvoice)
                .then().statusCode(409)
                .body("message", allOf(containsString("Deze factuur wijkt af van wat de klant bestelde: "),
                        containsString("besteld € 10,00, nu € 11,00"),
                        containsString("Zet de factuur terug gelijk aan de bestelling")));
        Map<String, Object> asOrdered = staffOrder(firstInvoice);
        line(asOrdered).put("unitPriceEur", 10);
        staffPut(firstInvoice, null, asOrdered).statusCode(200);
        staff().contentType("application/json").when().post(STAFF + "/{id}/issue", firstInvoice)
                .then().statusCode(200).body("order.status", equalTo("UITGEREIKT"));

        customer().when().get(DOCUMENTS + "?kind=ORDERS").then().statusCode(200)
                .body("items[0].status", equalTo("CONFIRMED"))
                .body("items[0].relatedNumber", equalTo(firstInvoiceNumber));
        float invoiced = customer().when().get(DOCUMENTS + "?kind=INVOICES").then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].kind", equalTo("INVOICE"))
                .body("items[0].number", equalTo(firstInvoiceNumber))
                .body("items[0].status", equalTo("ISSUED"))
                .body("items[0].hasPdf", equalTo(true))
                .body("items[0].relatedNumber", equalTo(firstNumber))
                .extract().path("items[0].totalInclVat");

        /* A payment is booked; the customer's download stays the invoice as issued. */
        staff().contentType("application/json")
                .body(Map.of("amountEur", 100, "receivedAt", Instant.now().toString(), "timeZone", "Europe/Brussels",
                        "reference", "Eerste storting"))
                .when().post(STAFF + "/{id}/payments", firstInvoice).then().statusCode(200);
        Response download = customer().when().get(DOCUMENTS + "/" + firstInvoice + "/pdf");
        download.then().statusCode(200).contentType("application/pdf")
                .header("Content-Disposition", "attachment; filename=\"" + firstInvoiceNumber + ".pdf\"");
        String invoicePdf = text(download.asByteArray());
        assertTrue(invoicePdf.contains(firstInvoiceNumber), invoicePdf);
        assertTrue(invoicePdf.contains(dutch(invoiced)), "the invoice amount as issued: " + invoicePdf);
        for (String told : List.of("Ontvangen", "Eerste storting", "100,00", "Volledig voldaan"))
            assertFalse(invoicePdf.contains(told), "'" + told + "' in the customer's copy: " + invoicePdf);
        customer().when().get(DOCUMENTS + "?kind=INVOICES").then().statusCode(200)
                .body("items[0].status", equalTo("ISSUED"));

        // ---------------------------------------------------------------- order 2: changed, approved by the mailed link
        Response second = place(body(6, "NL", Map.of("notes", "Tweede bestelling")));
        second.then().statusCode(201);
        long secondId = second.jsonPath().getLong("id");
        String secondNumber = second.path("number");
        int customerMails = mailbox.getMailsSentTo(loginEmail).size();
        staff().queryParam("webOrderRevision", 1).when().post(STAFF + "/{id}/take-into-processing", secondId)
                .then().statusCode(200);
        assertEquals("Uw bestelling " + secondNumber + " is in verwerking",
                lastMailTo(loginEmail, customerMails + 1).getSubject());

        Map<String, Object> raised = staffOrder(secondId);
        String sku = (String) staff().when().get(STAFF + "/{id}", secondId).then().extract().path("priced.lines[0].sku");
        line(raised).put("unitPriceEur", 11);
        staffPut(secondId, 1, raised).statusCode(200)
                .body("webOrder.termsState", equalTo("ORDER_DIFFERENT"))
                .body("webOrder.differences[0]", equalTo("Prijs " + sku + ": besteld € 10,00, nu € 11,00"));
        staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .when().post(STAFF + "/{id}/invoice", secondId)
                .then().statusCode(409)
                .body("message", allOf(containsString("Deze websitebestelling wijkt af van wat de klant bestelde"),
                        containsString("Prijs " + sku + ": besteld € 10,00, nu € 11,00"),
                        containsString("Verstuur ze ter goedkeuring")));
        Number lineId = (Number) line(raised).get("id");
        staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("lines", List.of(Map.of("lineId", lineId, "laterQuantity", 36)), "requestId", UUID.randomUUID().toString()))
                .when().post(STAFF + "/{id}/split", secondId)
                .then().statusCode(409)
                .body("message", equalTo("Een websitebestelling splits je pas nadat de klant akkoord ging. "
                        + "Verstuur ze ter goedkeuring; maak na het akkoord de conceptfactuur en splits die."));

        /* The changed version goes for approval, worded as their order, to the login that ordered. */
        String token = staff().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .when().post(STAFF + "/{id}/send", secondId)
                .then().statusCode(200)
                .body("webOrder.termsState", equalTo("AWAITING_APPROVAL"))
                .extract().path("order.portalToken");
        Mail approval = lastMailTo(loginEmail, customerMails + 2);
        assertEquals("Uw bestelling " + secondNumber + ": aangepaste versie ter goedkeuring", approval.getSubject());
        assertEquals(List.of(loginEmail), approval.getTo());
        assertEquals(List.of(recordEmail), approval.getCc(), "the customer record's address reads along");
        assertTrue(approval.getHtml().contains(token), "the mail carries the link to approve");

        Response afterSend = change(secondId, body(7, "NL", Map.of("baseRevision", 1)));
        assertEquals(409, afterSend.statusCode());
        assertEquals(ORDER_LOCKED, afterSend.asString());
        customer().when().get(DOCUMENTS + "/" + secondId).then().statusCode(200)
                .body("kind", equalTo("ORDER"))
                .body("status", equalTo("AWAITING_APPROVAL"))
                .body("basis", equalTo("CURRENT"))
                .body("canChange", equalTo(false))
                .body("hasPdf", equalTo(true))
                .body("validUntil", notNullValue())
                .body("lines[0].unitPrice", equalTo(11.0f));
        byte[] quotePdf = customer().when().get(DOCUMENTS + "/" + secondId + "/pdf")
                .then().statusCode(200).contentType("application/pdf").extract().asByteArray();
        String quoteText = text(quotePdf);
        assertTrue(quoteText.contains(secondNumber), quoteText);
        assertFalse(quoteText.contains(token), "no approval link in the customer's download");
        assertFalse(new String(quotePdf, java.nio.charset.StandardCharsets.ISO_8859_1).contains(token));

        /* Staff change the freight after sending: the mailed link no longer approves figures nobody mailed. */
        staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("state", "BEREKEND", "manualFreightEur", 150, "freightPricingStrategy", "FIXED"))
                .when().put(STAFF + "/{id}/freight", secondId)
                .then().statusCode(200).body("webOrder.termsState", equalTo("RESEND_REQUIRED"));
        given().contentType("application/json").body(Map.of("signedByName", "Jan Besteller"))
                .when().post("/api/portal/{token}/accept", token)
                .then().statusCode(409)
                .body("message", containsString("Deze offerte wordt momenteel bijgewerkt"));
        staff().when().get(STAFF + "/{id}", secondId).then().statusCode(200)
                .body("order.status", equalTo("VERZONDEN"));

        String resent = staff().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .when().post(STAFF + "/{id}/send", secondId)
                .then().statusCode(200)
                .body("webOrder.termsState", equalTo("AWAITING_APPROVAL"))
                .extract().path("order.portalToken");
        given().contentType("application/json").body(Map.of("signedByName", "Jan Besteller"))
                .when().post("/api/portal/{token}/accept", resent)
                .then().statusCode(200);
        staff().when().get(STAFF + "/{id}", secondId).then().statusCode(200)
                .body("order.status", equalTo("GEACCEPTEERD"))
                .body("webOrder.termsState", equalTo("APPROVED"));
        customer().when().get(DOCUMENTS + "/" + secondId).then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));

        ValidatableResponse secondDraft = staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .when().post(STAFF + "/{id}/invoice", secondId).then().statusCode(200)
                .body("order.docType", equalTo("FACTUUR"));
        long secondInvoice = secondDraft.extract().jsonPath().getLong("order.id");
        Map<String, Object> otherFreight = staffOrder(secondInvoice);
        otherFreight.put("manualFreightEur", 175);
        staffPut(secondInvoice, null, otherFreight).statusCode(200);
        staff().contentType("application/json").when().post(STAFF + "/{id}/issue", secondInvoice)
                .then().statusCode(409)
                .body("message", containsString("Deze factuur wijkt af van de versie waarmee de klant akkoord ging."));
        Map<String, Object> approvedFreight = staffOrder(secondInvoice);
        approvedFreight.put("manualFreightEur", 150);
        staffPut(secondInvoice, null, approvedFreight).statusCode(200);
        staff().contentType("application/json").when().post(STAFF + "/{id}/issue", secondInvoice)
                .then().statusCode(200).body("order.status", equalTo("UITGEREIKT"));
        customer().when().get(DOCUMENTS + "?kind=INVOICES").then().statusCode(200).body("items", hasSize(2));

        // ---------------------------------------------------------------- order 3: cancelled by the customer
        long third = place(body(6, "NL", Map.of("notes", "Derde bestelling"))).then().statusCode(201)
                .extract().jsonPath().getLong("id");
        int beforeCancel = mailbox.getMailsSentTo(loginEmail).size();
        customer().contentType("application/json").header("Idempotency-Key", "cancel-" + UUID.randomUUID())
                .body(Map.of("baseRevision", 1))
                .when().post(ORDERS + "/" + third + "/cancellation")
                .then().statusCode(200).body("revision", equalTo(2)).body("status", equalTo("CANCELLED"));
        assertEquals(beforeCancel, mailbox.getMailsSentTo(loginEmail).size(), "no mail about the customer's own cancel");
        customer().when().get(DOCUMENTS + "/" + third).then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("cancelledBy", equalTo("CUSTOMER"))
                .body("hasPdf", equalTo(false));
        staff().when().get(STAFF + "/{id}", third).then().statusCode(200)
                .body("order.status", equalTo("GEANNULEERD"))
                .body("order.portalToken", nullValue())
                .body("webOrder.customerCancelledAt", notNullValue())
                .body("webOrder.processingStartedAt", nullValue());
        staff().queryParam("webOrderRevision", 2).contentType("application/json")
                .when().post(STAFF + "/{id}/reopen", third)
                .then().statusCode(409)
                .body("message", equalTo("Deze bestelling is door de klant geannuleerd. "
                        + "Maak een nieuwe offerte als de klant toch wil bestellen."));
        staff().queryParam("webOrderRevision", 2).when().delete(STAFF + "/{id}", third).then().statusCode(204);
        customer().when().get(DOCUMENTS + "/" + third).then().statusCode(404).body("code", equalTo("ORDER_NOT_FOUND"));

        // ---------------------------------------------------------------- order 4: cancelled by Enrosed
        Response fourth = place(body(6, "DE", Map.of("notes", "Vierte Bestellung",
                "destination", Map.of("countryCode", "BE", "postalCode", "3500", "city", "Hasselt",
                        "address", "Kempische Steenweg 10"))));
        fourth.then().statusCode(201);
        long fourthId = fourth.jsonPath().getLong("id");
        String fourthNumber = fourth.path("number");
        int beforeStaffCancel = mailbox.getMailsSentTo(loginEmail).size();
        staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("message", "Dit product is niet meer leverbaar.", "notifyCustomer", true))
                .when().post(STAFF + "/{id}/cancel", fourthId)
                .then().statusCode(200).body("order.status", equalTo("GEANNULEERD"));
        Mail cancellation = lastMailTo(loginEmail, beforeStaffCancel + 1);
        assertEquals("Bestellung " + fourthNumber + " storniert", cancellation.getSubject());
        assertTrue(cancellation.getHtml().contains("Ihre Bestellung wurde storniert"), cancellation.getHtml());
        assertTrue(cancellation.getHtml().contains("Dit product is niet meer leverbaar."));
        Response afterStaffCancel = customer().contentType("application/json").body(Map.of("baseRevision", 1))
                .when().post(ORDERS + "/" + fourthId + "/cancellation");
        assertEquals(409, afterStaffCancel.statusCode());
        assertEquals(ORDER_LOCKED, afterStaffCancel.asString());
        customer().when().get(DOCUMENTS + "/" + fourthId).then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("cancelledBy", equalTo("ENROSED"))
                .body("cancellationMessage", equalTo("Dit product is niet meer leverbaar."));

        // the next order starts from the last one, cancelled or not
        customer().when().get(DOCUMENTS + "/delivery-defaults")
                .then().statusCode(200)
                .body("source", equalTo("LAST_ORDER"))
                .body("fulfillment", equalTo("DELIVERY"))
                .body("destination.countryCode", equalTo("BE"))
                .body("destination.postalCode", equalTo("3500"))
                .body("destination.city", equalTo("Hasselt"))
                .body("destination.address", equalTo("Kempische Steenweg 10"))
                .body("contactName", equalTo("Jan Besteller"));
        customer().when().get(DOCUMENTS + "?kind=ORDERS").then().statusCode(200)
                .body("items", hasSize(3))
                .body("items.status", equalTo(List.of("CANCELLED", "CONFIRMED", "CONFIRMED")));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private RequestSpecification customer() {
        return given().header("Authorization", "Bearer " + session);
    }

    private static RequestSpecification staff() {
        return given().auth().preemptive().basic("emre", STAFF_PASSWORD);
    }

    /** The login form has its own token; it too must be three seconds old. */
    private static String accountFormToken() throws InterruptedException {
        Response form = given().queryParam("purpose", "ACCOUNT").when().get("/api/v1/public/forms/configuration");
        form.then().statusCode(200);
        long wait = Duration.between(Instant.now(),
                Instant.parse(form.<String>path("minimumSubmitAt")).plusMillis(1_200)).toMillis();
        if (wait > 0) Thread.sleep(wait);
        return form.path("formToken");
    }

    private Response place(Map<String, Object> body) {
        return customer().contentType("application/json").header("Idempotency-Key", "order-" + UUID.randomUUID())
                .body(body).when().post(ORDERS);
    }

    private Response change(long id, Map<String, Object> body) {
        return customer().contentType("application/json").header("Idempotency-Key", "change-" + UUID.randomUUID())
                .body(body).when().post(ORDERS + "/" + id + "/changes");
    }

    private Map<String, Object> previewBody(int cartons, String language, Long orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", language);
        body.put("fulfillment", "DELIVERY");
        body.put("destination", Map.of("countryCode", "BE", "postalCode", "3980",
                "city", "Tessenderlo", "address", "Industrieweg 1"));
        body.put("items", List.of(Map.of("productId", productId, "cartons", cartons)));
        if (orderId != null) body.put("orderId", orderId);
        return body;
    }

    private Map<String, Object> body(int cartons, String language, Map<String, Object> changes) {
        Map<String, Object> body = previewBody(cartons, language, null);
        body.put("contactName", "Jan Besteller");
        body.put("phone", "+32 13 00 00 00");
        body.put("notes", "Eerste bestelling");
        body.put("privacyAccepted", true);
        body.put("website", "");
        body.put("formToken", formToken);
        body.putAll(changes);
        return body;
    }

    /** The document as the ERP editor holds it, to send back whole. */
    private static Map<String, Object> staffOrder(long id) {
        return new HashMap<>(staff().when().get(STAFF + "/{id}", id).then().statusCode(200)
                .extract().jsonPath().getMap("order"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("lines")).getFirst();
    }

    private static ValidatableResponse staffPut(long id, Integer revision, Map<String, Object> order) {
        RequestSpecification request = staff().contentType("application/json").body(order);
        if (revision != null) request = request.queryParam("webOrderRevision", revision);
        return request.when().put(STAFF + "/{id}", id).then();
    }

    /** The newest mail to this address, once exactly this many have been sent to it. */
    private Mail lastMailTo(String address, int expected) {
        List<Mail> mails = mailbox.getMailsSentTo(address);
        assertEquals(expected, mails == null ? 0 : mails.size(), "mails to " + address);
        return mails.getLast();
    }

    private static String dutch(float amount) {
        return String.format(new java.util.Locale("nl", "BE"), "%,.2f", amount);
    }

    private static String text(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
