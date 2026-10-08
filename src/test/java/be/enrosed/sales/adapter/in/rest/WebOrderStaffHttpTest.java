package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.WebOrderMails;
import be.enrosed.sales.application.WebOrderMailsExecutor;
import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff endpoints of a website order as the ERP calls them: the revision
 * of the screen in the query string, the conflict answer the screen reloads
 * on, "In verwerking nemen", the customer mails, the two blocks of the view,
 * and the refusals of an invoice that is not what the customer ordered.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class WebOrderStaffHttpTest {
    private static final String BASE = "/api/sales-orders";

    @Inject QuoteService quotes;
    @Inject EntityManager em;
    @Inject MockMailbox mailbox;
    @Inject WebOrderMails webOrderMails;

    private final Shop shop = new Shop();

    @BeforeEach
    void emptyMailbox() {
        mailbox.clear();
        WebOrderMailsExecutor.direct(webOrderMails);
    }

    @AfterEach
    void removeRows() {
        WebOrderMailsExecutor.direct(webOrderMails);
        shop.remove();
    }

    @Test
    void theButtonTakesTheOrderOnceAndSaysSoInTheView() {
        Shop.Placed order = shop.place();

        take(order.id(), 1).statusCode(200)
                .body("order.id", equalTo((int) order.id()))
                .body("webOrder.revision", equalTo(1))
                .body("webOrder.processingTrigger", equalTo("KNOP"))
                .body("webOrder.processingStartedBy", equalTo("emre"))
                .body("webOrder.processingStartedAt", notNullValue())
                .body("webOrder.customerEditable", equalTo(false))
                .body("webOrder.termsState", equalTo("ORDER_EQUAL"));
        String takenAt = get(order.id()).extract().path("webOrder.processingStartedAt");

        take(order.id(), 1).statusCode(200)
                .body("webOrder.processingStartedAt", equalTo(takenAt))
                .body("webOrder.processingTrigger", equalTo("KNOP"));
        List<String> history = given().get(BASE + "/{id}/history", order.id()).then().statusCode(200)
                .extract().jsonPath().getList("findAll { it.type == 'IN_VERWERKING' }.summary");
        assertEquals(List.of("In verwerking genomen"), history, "taking twice is taking once");

        given().post(BASE + "/{id}/take-into-processing", 987_654_321L).then().statusCode(404);
    }

    @Test
    void aDocumentThatIsNoOrderAndACancelledOrderAnswerTheirOwnSentence() {
        long plain = shop.legacyRequests(1).getFirst();
        given().post(BASE + "/{id}/take-into-processing", plain).then().statusCode(409)
                .body("message", equalTo("Dit document is geen websitebestelling van een ingelogde klant."))
                .body("$", not(hasKey("code")));

        Shop.Placed order = shop.place();
        shop.customerCancels(order.id());
        take(order.id(), 2).statusCode(409)
                .body("message", equalTo("De klant heeft deze bestelling geannuleerd."));
        get(order.id()).body("webOrder.customerCancelledAt", notNullValue())
                .body("webOrder.processingStartedAt", nullValue())
                .body("webOrder.termsState", nullValue());
    }

    @Test
    void aScreenTheCustomerHasOvertakenGetsTheConflictItReloadsOn() {
        Shop.Placed order = shop.place();
        Map<String, Object> screen = order(order.id());
        shop.customerChanges(order.id(), 144);

        for (ValidatableResponse refused : List.of(
                take(order.id(), 1),
                given().queryParam("webOrderRevision", 1).contentType("application/json").body(screen)
                        .put(BASE + "/{id}", order.id()).then(),
                given().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                        .post(BASE + "/{id}/send", order.id()).then(),
                given().queryParam("webOrderRevision", 1).contentType("application/json").post(BASE + "/{id}/invoice", order.id()).then(),
                given().queryParam("webOrderRevision", 1).contentType("application/json").delete(BASE + "/{id}", order.id()).then())) {
            refused.statusCode(409)
                    .body("status", equalTo(409))
                    .body("code", equalTo("WEB_ORDER_CHANGED"))
                    .body("webOrderRevision", equalTo(2))
                    .body("message", equalTo("De klant heeft deze bestelling intussen gewijzigd of geannuleerd. Je wijzigingen zijn niet opgeslagen; laad de laatste versie."))
                    .body("timestamp", notNullValue());
        }

        /* No number is no revision: an old screen, refused once the customer ever changed the order. */
        for (String noRevision : List.of("", "abc", "-2", "1.0", "12345678901")) {
            given().queryParam("webOrderRevision", noRevision).post(BASE + "/{id}/take-into-processing", order.id())
                    .then().statusCode(409)
                    .body("code", equalTo("WEB_ORDER_CHANGED"))
                    .body("webOrderRevision", equalTo(2))
                    .body("message", containsString("Dit scherm is verouderd; laad de laatste versie"));
        }
        get(order.id()).body("webOrder.processingStartedAt", nullValue())
                .body("webOrder.revision", equalTo(2))
                .body("webOrder.customerEditable", equalTo(true))
                .body("webOrder.customerChangeSummary", equalTo("aantal gewijzigd, opmerking gewijzigd"))
                .body("order.lines[0].quantity", equalTo(144));

        take(order.id(), 2).statusCode(200).body("webOrder.processingTrigger", equalTo("KNOP"));
    }

    @Test
    void theViewCarriesTheOrderStateAndTheDeliveryBesideTheDocument() {
        Shop.Placed order = shop.place();
        long legacy = shop.legacyRequests(1).getFirst();

        get(order.id())
                .body("order", allOf(not(hasKey("webOrder")), not(hasKey("delivery"))))
                .body("webOrder.revision", equalTo(1))
                .body("webOrder.accountEmail", notNullValue())
                .body("webOrder.placedAt", notNullValue())
                .body("webOrder.customerEditable", equalTo(true))
                .body("webOrder.processingStartedAt", nullValue())
                .body("webOrder.termsState", equalTo("ORDER_EQUAL"))
                .body("webOrder.orderedTotalExclVat", equalTo(1320.0f))
                .body("webOrder.orderedTotalInclVat", equalTo(1597.2f))
                .body("webOrder.differences", empty())
                .body("webOrder.mailDue", equalTo(false))
                .body("delivery.fulfillment", equalTo("DELIVERY"))
                .body("delivery.address", equalTo("Industrieweg 1"))
                .body("delivery.postalCode", equalTo("3980"))
                .body("delivery.city", equalTo("Tessenderlo"))
                .body("delivery.countryCode", equalTo("BE"))
                .body("delivery.contactName", equalTo("Jan Besteller"))
                .body("delivery.phone", equalTo("+32 13 00 00 00"))
                .body("delivery.pickupLabel", nullValue())
                .body("delivery.differsFromCustomerRecord", equalTo(true));
        get(legacy).body("webOrder", nullValue()).body("delivery", nullValue());

        /* Staff change the freight: the detail names the difference, the list only the state. */
        take(order.id(), 1).statusCode(200);
        Map<String, Object> edited = order(order.id());
        edited.put("manualFreightEur", 140);
        put(order.id(), 1, edited).statusCode(200)
                .body("webOrder.termsState", equalTo("ORDER_DIFFERENT"))
                .body("webOrder.differences", equalTo(List.of("Vracht: besteld € 120,00, nu € 140,00",
                        "Totaal excl. btw: besteld € 1.320,00, nu € 1.340,00")));
        String listed = "find { it.order.id == " + order.id() + " }";
        given().get(BASE).then().statusCode(200)
                .body(listed + ".webOrder.termsState", equalTo("ORDER_DIFFERENT"))
                .body(listed + ".webOrder.differences", empty())
                .body(listed + ".webOrder.orderedTotalExclVat", nullValue())
                .body(listed + ".webOrder.orderedTotalInclVat", nullValue())
                .body(listed + ".webOrder.processingTrigger", equalTo("KNOP"))
                .body(listed + ".delivery.address", equalTo("Industrieweg 1"))
                .body(listed + ".order", not(hasKey("webOrder")))
                .body("find { it.order.id == " + legacy + " }.webOrder", nullValue())
                .body("find { it.order.id == " + legacy + " }.delivery", nullValue());
    }

    @Test
    void theListReadsEveryOrderStateAndDeliveryInTwoQueriesWhateverItsLength() {
        List<Long> ids = shop.legacyRequests(30);
        Statistics statistics = em.unwrap(Session.class).getSessionFactory().getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            given().get(BASE).then().statusCode(200).body("find { it.order.id == " + ids.getFirst() + " }.delivery", nullValue());
            long withoutOrders = statistics.getPrepareStatementCount();

            shop.turnIntoOrders(ids);
            statistics.clear();
            given().get(BASE).then().statusCode(200)
                    .body("find { it.order.id == " + ids.getFirst() + " }.delivery.postalCode", equalTo("3980"))
                    .body("find { it.order.id == " + ids.getLast() + " }.webOrder.revision", equalTo(1));
            long withOrders = statistics.getPrepareStatementCount();

            /* Thirty orders with thirty delivery rows cost one more statement than none: the customer records, read once. */
            assertEquals(withoutOrders + 1, withOrders, "no document looks its own order state or delivery up");
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void anInvoiceIsMadeOnlyOfWhatTheCustomerOrderedOrApproved() {
        Shop.Placed unchanged = shop.place();
        given().queryParam("webOrderRevision", 1).contentType("application/json").post(BASE + "/{id}/invoice", unchanged.id()).then().statusCode(200)
                .body("order.docType", equalTo("FACTUUR"))
                .body("webOrder", nullValue())
                .body("delivery.address", equalTo("Industrieweg 1"));
        get(unchanged.id()).body("webOrder.processingTrigger", equalTo("AUTOMATISCH"))
                .body("webOrder.customerEditable", equalTo(false));

        Shop.Placed order = shop.place();
        take(order.id(), 1).statusCode(200);
        String sku = order.sku();

        Map<String, Object> moreCartons = order(order.id());
        line(moreCartons).put("quantity", 132);
        put(order.id(), 1, moreCartons).statusCode(200);
        invoice(order.id()).statusCode(409).body("message", equalTo(
                "Deze websitebestelling wijkt af van wat de klant bestelde (besteld € 1.320,00 excl. btw, nu € 1.440,00): "
                        + "Aantal " + sku + ": besteld 120, nu 132; Totaal excl. btw: besteld € 1.320,00, nu € 1.440,00. "
                        + "Verstuur ze ter goedkeuring; factureren kan zodra de klant akkoord gaat."));

        Map<String, Object> otherPrice = order(order.id());
        line(otherPrice).put("quantity", 120);
        line(otherPrice).put("unitPriceEur", 11);
        put(order.id(), 1, otherPrice).statusCode(200);
        invoice(order.id()).statusCode(409).body("message", allOf(
                containsString("(besteld € 1.320,00 excl. btw, nu € 1.440,00)"),
                containsString("Prijs " + sku + ": besteld € 10,00, nu € 11,00")));

        Map<String, Object> otherFreight = order(order.id());
        line(otherFreight).put("unitPriceEur", 10);
        otherFreight.put("manualFreightEur", 140);
        put(order.id(), 1, otherFreight).statusCode(200);
        invoice(order.id()).statusCode(409).body("message", allOf(
                containsString("(besteld € 1.320,00 excl. btw, nu € 1.340,00)"),
                containsString("Vracht: besteld € 120,00, nu € 140,00")));

        Map<String, Object> extraLine = order(order.id());
        extraLine.put("manualFreightEur", 120);
        extraLine.put("extraLines", List.of(Map.of("description", "Verpakking", "quantity", 1, "unitPriceEur", 25)));
        put(order.id(), 1, extraLine).statusCode(200);
        invoice(order.id()).statusCode(409).body("message", allOf(
                containsString("(besteld € 1.320,00 excl. btw, nu € 1.345,00)"),
                containsString("Extra regels: niet besteld, nu € 25,00")));
        given().queryParam("webOrderRevision", 1).contentType("application/json").body(Map.of("percentage", 30))
                .post(BASE + "/{id}/advance-invoice", order.id()).then().statusCode(409)
                .body("message", containsString("Deze websitebestelling wijkt af van wat de klant bestelde"));

        /* The changed version goes for approval; until the customer approves there is no invoice. */
        String token = given().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .post(BASE + "/{id}/send", order.id()).then().statusCode(200)
                .body("webOrder.termsState", equalTo("AWAITING_APPROVAL"))
                .extract().path("order.portalToken");
        invoice(order.id()).statusCode(409).body("message", equalTo(
                "Deze websitebestelling wacht nog op het akkoord van de klant op de verstuurde versie. Factureren kan zodra de klant akkoord gaat."));

        quotes.acceptByCustomer(token, "An Peeters", null);
        get(order.id()).body("webOrder.termsState", equalTo("APPROVED"));
        invoice(order.id()).statusCode(200).body("order.docType", equalTo("FACTUUR"))
                .body("order.sourceQuoteId", equalTo((int) order.id()));
    }

    @Test
    void takingAnOrderAnswersBeforeTheCustomerMailLeavesAndShowsItSentOnlyAfterwards() {
        Shop.Placed order = shop.place("taker@login.example");
        List<Runnable> handed = WebOrderMailsExecutor.capture(webOrderMails);

        take(order.id(), 1).statusCode(200)
                .body("webOrder.processingStartedAt", notNullValue())
                .body("webOrder.processingMailSentAt", nullValue())
                .body("webOrder.receivedMailSentAt", nullValue())
                .body("webOrder.mailDue", equalTo(false))
                .body("webOrder.mailError", nullValue());
        assertEquals(1, handed.size(), "the answer left with the delivery still waiting");
        assertEquals(0, mailbox.getMailsSentTo("taker@login.example").size());

        handed.getFirst().run();

        assertEquals(1, mailbox.getMailsSentTo("taker@login.example").size());
        assertTrue(mailbox.getMailsSentTo("taker@login.example").getFirst().getSubject().contains("in verwerking"));
        get(order.id()).body("webOrder.processingMailSentAt", notNullValue())
                .body("webOrder.mailDue", equalTo(false))
                .body("webOrder.mailError", nullValue());
    }

    @Test
    void theCustomerMailOfAnOrderIsSentOnceAndRepeatedOnlyWhenAsked() {
        Shop.Placed order = shop.place("buyer@login.example");
        get(order.id()).body("webOrder.receivedMailSentAt", nullValue()).body("webOrder.mailDue", equalTo(false));

        /* Due for more than a minute: the screen offers "Opnieuw sturen". */
        shop.inTransaction(() -> em.find(SalesWebOrderEntity.class, order.id()).placedAt = Instant.now().minusSeconds(120));
        get(order.id()).body("webOrder.mailDue", equalTo(true));
        given().get(BASE).then().body("find { it.order.id == " + order.id() + " }.webOrder.mailDue", equalTo(true));

        given().post(BASE + "/{id}/web-order/mails", order.id()).then().statusCode(200)
                .body("webOrder.receivedMailSentAt", notNullValue())
                .body("webOrder.mailDue", equalTo(false))
                .body("webOrder.mailError", nullValue());
        assertEquals(1, mailbox.getMailsSentTo("buyer@login.example").size(), "the login that ordered is mailed");

        given().post(BASE + "/{id}/web-order/mails", order.id()).then().statusCode(409)
                .body("message", equalTo("Er staat geen e-mail voor de klant open."));
        given().queryParam("repeat", false).post(BASE + "/{id}/web-order/mails", order.id()).then().statusCode(409);
        assertEquals(1, mailbox.getMailsSentTo("buyer@login.example").size(), "a double click sends nothing twice");

        given().queryParam("repeat", true).post(BASE + "/{id}/web-order/mails", order.id()).then().statusCode(200)
                .body("webOrder.receivedMailSentAt", notNullValue());
        assertEquals(2, mailbox.getMailsSentTo("buyer@login.example").size());
        assertTrue(mailbox.getMailsSentTo("buyer@login.example").stream().allMatch(mail -> mail.getSubject().contains(order.number())));

        long plain = shop.legacyRequests(1).getFirst();
        given().queryParam("repeat", true).post(BASE + "/{id}/web-order/mails", plain).then().statusCode(409)
                .body("message", equalTo("Er staat geen e-mail voor de klant open."));
        given().post(BASE + "/{id}/web-order/mails", 987_654_321L).then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static ValidatableResponse get(long id) {
        return given().get(BASE + "/{id}", id).then().statusCode(200);
    }

    /** The document as the editor holds it, to send back whole. */
    private static Map<String, Object> order(long id) {
        return new HashMap<>(get(id).extract().jsonPath().getMap("order"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("lines")).getFirst();
    }

    private static ValidatableResponse take(long id, int revision) {
        return given().queryParam("webOrderRevision", revision).post(BASE + "/{id}/take-into-processing", id).then();
    }

    private static ValidatableResponse put(long id, int revision, Map<String, Object> order) {
        return given().queryParam("webOrderRevision", revision).contentType("application/json").body(order)
                .put(BASE + "/{id}", id).then();
    }

    private static ValidatableResponse invoice(long id) {
        return given().queryParam("webOrderRevision", 1).contentType("application/json").post(BASE + "/{id}/invoice", id).then();
    }
}
