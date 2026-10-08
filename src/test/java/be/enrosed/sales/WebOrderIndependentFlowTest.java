package be.enrosed.sales;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.publicform.PublicFormRateBucketEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reviewer's walk through the website order of a logged-in customer, written without
 * reading the builders' own flow test. Everything goes through real HTTP: the two customers
 * get their login the way a visitor does (form, staff approval, invitation mail, password),
 * staff sign in with HTTP Basic, the form tokens come from the configuration endpoint, the
 * buckets count and the mails land in the mock mailbox. Nothing is mocked or spied. The
 * entity manager is used to make products, to move a list price, to count side rows and to
 * clean up.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebOrderIndependentFlowTest {
    private static final String ACCOUNT = "/api/v1/public/account";
    private static final String DOCUMENTS = ACCOUNT + "/documents";
    private static final String ORDERS = ACCOUNT + "/orders";
    private static final String STAFF = "/api/sales-orders";
    private static final String STAFF_PASSWORD = "named-auth-test-password";
    private static final String TEAM = "hello@enrosed.com";
    private static final String LOCKED = "{\"code\":\"ORDER_LOCKED\","
            + "\"message\":\"The order can no longer be changed\",\"fieldErrors\":{}}";
    private static final String ORDER_NOT_FOUND = "{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"Not found\",\"fieldErrors\":{}}";

    record Login(String token, long customerId, long accountId, String email) {}

    @Inject EntityManager em;
    @Inject MockMailbox mailbox;
    @Inject CustomerService customers;

    private final List<Long> customerIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private Login anna;
    private Login boris;
    private long rose;
    private String roseSku;
    private long peony;
    private String peonySku;
    private String accountFormToken;
    private String quoteFormToken;

    @BeforeEach
    void twoCustomersWithALoginAndTwoProducts() throws InterruptedException {
        settle();
        if (anna != null) {
            listPrice(rose, "10.00");
            mailbox.clear();
            return;
        }
        /* Buckets other test classes filled per network are not this walk's. */
        QuarkusTransaction.requiringNew().run(() -> PublicFormRateBucketEntity.deleteAll());
        String[] roseProduct = product("10.00");
        rose = Long.parseLong(roseProduct[0]);
        roseSku = roseProduct[1];
        String[] peonyProduct = product("20.00");
        peony = Long.parseLong(peonyProduct[0]);
        peonySku = peonyProduct[1];

        Response account = given().queryParam("purpose", "ACCOUNT").when().get("/api/v1/public/forms/configuration");
        Response quote = given().queryParam("purpose", "QUOTE").when().get("/api/v1/public/forms/configuration");
        accountFormToken = account.path("formToken");
        quoteFormToken = quote.path("formToken");
        long wait = Duration.between(Instant.now(),
                Instant.parse(quote.<String>path("minimumSubmitAt")).plusMillis(1_200)).toMillis();
        if (wait > 0) Thread.sleep(wait);

        anna = register("Fleurs Anna SRL", "FR");
        boris = register("Bloemen Boris BV", "NL");
        settle();
        mailbox.clear();
    }

    @AfterAll
    void cleanUp() {
        settle();
        QuarkusTransaction.requiringNew().run(() -> {
            for (long customerId : customerIds) {
                em.createNativeQuery("update sales_order set deleted_at = null where customerId = :id")
                        .setParameter("id", customerId).executeUpdate();
                List<SalesOrderEntity> documents = em.createQuery("from " + SalesOrderEntity.class.getName()
                                + " o where o.customerId = :id order by o.id desc", SalesOrderEntity.class)
                        .setParameter("id", customerId).getResultList();
                for (SalesOrderEntity document : documents) {
                    long id = document.id;
                    em.createNativeQuery("delete from sales_advance_billing where sales_order_id = :id or quote_id = :id")
                            .setParameter("id", id).executeUpdate();
                    em.createNativeQuery("delete from sales_payment where sales_order_id = :id").setParameter("id", id).executeUpdate();
                    em.createNativeQuery("delete from sales_customer_request_message where sales_order_id = :id")
                            .setParameter("id", id).executeUpdate();
                    em.createNativeQuery("delete from quote_event where salesOrderId = :id").setParameter("id", id).executeUpdate();
                    em.createNativeQuery("delete from activity_log where entity_type = 'SALES_ORDER' and entity_id = :id")
                            .setParameter("id", Long.toString(id)).executeUpdate();
                    em.createQuery("delete from SalesWebOrderEntity w where w.salesOrderId = :id").setParameter("id", id).executeUpdate();
                    em.createQuery("delete from SalesOrderDeliveryEntity d where d.salesOrderId = :id").setParameter("id", id).executeUpdate();
                }
                em.flush();
                em.clear();
                em.createQuery("from " + SalesOrderEntity.class.getName() + " o where o.customerId = :id order by o.id desc",
                        SalesOrderEntity.class).setParameter("id", customerId).getResultList().forEach(em::remove);
                em.flush();
            }
            em.createNativeQuery("delete from customer_login_request").executeUpdate();
        });
        customerIds.forEach(customers::delete);
        QuarkusTransaction.requiringNew().run(() -> {
            for (long productId : productIds) {
                ProductEntity product = em.find(ProductEntity.class, productId);
                if (product != null) em.remove(product);
            }
            PublicFormRateBucketEntity.deleteAll();
        });
        mailbox.clear();
    }

    // ------------------------------------------------------------ (a) to (f)

    @Test
    @Order(1)
    void anOrderIsPlacedChangedTakenAndConfirmedByItsInvoiceWithoutASignature() throws Exception {
        // (a) defaults, estimate, order
        JsonPath defaults = bearer(anna).when().get(DOCUMENTS + "/delivery-defaults")
                .then().statusCode(200).header("Cache-Control", "no-store").extract().jsonPath();
        assertEquals("NONE", defaults.getString("source"), "a customer made at login approval has no address yet");
        assertNull(defaults.get("destination"));

        Map<String, Object> first = orderBody(Map.of(rose, 6), "Rue du Marché 1", "1000", "Bruxelles");
        JsonPath estimate = bearer(anna).contentType("application/json").body(first)
                .when().post(ORDERS + "/preview").then().statusCode(200).extract().jsonPath();
        assertEquals(true, estimate.getBoolean("pricesVisible"));
        assertEquals(720.0f, estimate.getFloat("totals.goodsNet"), 0.001f);
        assertEquals(true, estimate.getBoolean("validation.meetsMinimum"));
        Float estimatedTotal = estimate.get("totals.totalNet");
        assertNotNull(estimatedTotal, "the Belgian freight is known, so the estimate has a total: " + estimate.prettify());

        long teamBefore = mailbox.getMailsSentTo(TEAM).size();
        long rowsBefore = webOrderRows();
        String key = "place-" + UUID.randomUUID();
        Response placed = bearer(anna).contentType("application/json").header("Idempotency-Key", key)
                .body(first).when().post(ORDERS);
        placed.then().statusCode(201).header("Cache-Control", "no-store");
        /* The page retries the same request: the stored answer, no second order. */
        Response replayed = bearer(anna).contentType("application/json").header("Idempotency-Key", key)
                .body(first).when().post(ORDERS);
        assertEquals(201, replayed.statusCode());
        assertEquals(placed.asString(), replayed.asString());
        assertEquals("RECEIVED", placed.path("status"));
        assertEquals(1, (int) placed.path("revision"));
        long id = ((Number) placed.path("id")).longValue();
        String number = placed.path("number");
        assertEquals(4, placed.jsonPath().getMap("$").size(), "the receipt has four fields: " + placed.asString());
        settle();
        assertEquals(rowsBefore + 1, webOrderRows(), "one order, also after the retry");

        List<Mail> toAnna = mailbox.getMailsSentTo(anna.email());
        assertEquals(1, toAnna.size(), "one mail to the customer: " + subjects(toAnna));
        assertEquals(List.of(anna.email()), toAnna.getFirst().getTo());
        assertEquals(String.format(DocumentText.of(Language.FR).get("mailOrderReceivedSubject"), number),
                toAnna.getFirst().getSubject());
        assertTrue(toAnna.getFirst().getHtml().contains("<html lang=\"fr\">"), "the received mail is French");
        assertTrue(toAnna.getFirst().getHtml().contains("Rue du Marché 1"), "the mail names the delivery address");
        List<Mail> team = mailbox.getMailsSentTo(TEAM).subList((int) teamBefore, mailbox.getMailsSentTo(TEAM).size());
        assertEquals(1, team.size(), "one staff notice: " + subjects(team));
        assertTrue(team.getFirst().getSubject().startsWith("Nieuwe websitebestelling " + number), team.getFirst().getSubject());
        assertTrue(team.getFirst().getHtml().contains("Rue du Marché 1"), "the staff notice names the order's address");
        assertTrue(team.getFirst().getHtml().contains(anna.email()), "the staff notice names the login");

        /* The next order starts from this one's address. */
        JsonPath next = bearer(anna).when().get(DOCUMENTS + "/delivery-defaults").then().statusCode(200).extract().jsonPath();
        assertEquals("LAST_ORDER", next.getString("source"));
        assertEquals("Rue du Marché 1", next.getString("destination.address"));
        assertEquals("1000", next.getString("destination.postalCode"));

        // (b) my orders and the staff view
        JsonPath mine = orders(anna);
        assertEquals(1, mine.getList("items").size());
        assertEquals((int) id, mine.getInt("items[0].id"));
        assertEquals("ORDER", mine.getString("items[0].kind"));
        assertEquals("RECEIVED", mine.getString("items[0].status"));
        assertEquals(true, mine.getBoolean("items[0].canChange"));
        assertEquals(true, mine.getBoolean("items[0].canCancel"));
        assertEquals(false, mine.getBoolean("items[0].hasPdf"));
        assertEquals(estimatedTotal, mine.getFloat("items[0].totalExclVat"), 0.001f, "the list shows the total of the estimate");
        JsonPath detail = detail(anna, id);
        assertEquals("AS_ORDERED", detail.getString("basis"));
        assertEquals(1, detail.getInt("revision"));
        assertEquals(72, detail.getInt("lines[0].quantity"));
        assertEquals(10.0f, detail.getFloat("lines[0].unitPrice"), 0.001f);
        assertEquals("Rue du Marché 1", detail.getString("destination.address"));

        Response staleScreen = staff().when().get(STAFF + "/" + id);
        staleScreen.then().statusCode(200);
        JsonPath staffView = staleScreen.jsonPath();
        assertEquals("CONCEPT", staffView.getString("order.status"));
        assertEquals(1, staffView.getInt("webOrder.revision"));
        assertEquals(true, staffView.getBoolean("webOrder.customerEditable"));
        assertEquals(anna.email(), staffView.getString("webOrder.accountEmail"));
        assertNull(staffView.get("webOrder.processingStartedAt"));
        assertEquals("ORDER_EQUAL", staffView.getString("webOrder.termsState"));
        assertEquals("Rue du Marché 1", staffView.getString("delivery.address"));
        assertEquals(estimatedTotal, staffView.getFloat("webOrder.orderedTotalExclVat"), 0.001f,
                "the ERP computes what the customer was shown");
        JsonPath staffList = staff().when().get(STAFF).then().statusCode(200).extract().jsonPath();
        assertEquals(true, staffList.getBoolean("find { it.order.id == " + id + " }.webOrder.customerEditable"));

        // (c) the customer changes quantities, adds a product and moves the delivery; the list price moved meanwhile
        listPrice(rose, "12.00");
        mailbox.clear();
        Map<String, Object> second = orderBody(Map.of(rose, 8, peony, 2), "Stationsstraat 9", "3500", "Hasselt");
        second.put("baseRevision", 1);
        Response changed = change(anna, id, second);
        changed.then().statusCode(200);
        assertEquals(2, (int) changed.path("revision"));
        assertEquals("RECEIVED", changed.path("status"));
        settle();
        assertTrue(mailbox.getMailsSentTo(anna.email()).isEmpty(), "a change sends the customer nothing");
        List<Mail> changeNotice = mailbox.getMailsSentTo(TEAM);
        assertEquals(1, changeNotice.size(), subjects(changeNotice));
        assertTrue(changeNotice.getFirst().getSubject().startsWith("Websitebestelling " + number + " gewijzigd door de klant"),
                changeNotice.getFirst().getSubject());

        JsonPath afterChange = detail(anna, id);
        assertEquals(2, afterChange.getInt("revision"));
        assertEquals(2, afterChange.getList("lines").size());
        String roseLine = "lines.find { it.productId == " + rose + " }";
        String peonyLine = "lines.find { it.productId == " + peony + " }";
        assertEquals(96, afterChange.getInt(roseLine + ".quantity"));
        assertEquals(10.0f, afterChange.getFloat(roseLine + ".unitPrice"), 0.001f, "the kept product keeps its price");
        assertEquals(24, afterChange.getInt(peonyLine + ".quantity"));
        assertEquals(20.0f, afterChange.getFloat(peonyLine + ".unitPrice"), 0.001f, "the new product has today's price");
        assertEquals("Stationsstraat 9", afterChange.getString("destination.address"));
        assertEquals("3500", afterChange.getString("destination.postalCode"));

        JsonPath staffAfterChange = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals(2, staffAfterChange.getInt("webOrder.revision"));
        assertNotNull(staffAfterChange.get("webOrder.customerChangedAt"));
        String summary = staffAfterChange.getString("webOrder.customerChangeSummary");
        assertTrue(summary.contains(roseSku + ": 6 → 8 dozen"), summary);
        assertTrue(summary.contains(peonySku + " toegevoegd (2 dozen)"), summary);
        assertTrue(summary.contains("leveradres gewijzigd"), summary);
        assertTrue(summary.contains("prijs " + roseSku + " behouden"), summary);
        assertEquals("Stationsstraat 9", staffAfterChange.getString("delivery.address"));
        assertEquals("Hasselt", staffAfterChange.getString("delivery.city"));
        assertEquals(2, staffAfterChange.getList("order.lines").size());
        assertEquals(10.0f, staffAfterChange.getFloat("order.lines.find { it.productId == " + rose + " }.unitPriceEur"), 0.001f);
        assertEquals("ORDER_EQUAL", staffAfterChange.getString("webOrder.termsState"));
        assertEquals(true, staffAfterChange.getBoolean("webOrder.customerEditable"));

        // (d) a save from the screen that was loaded before the change
        Map<String, Object> staleOrder = new LinkedHashMap<>(staleScreen.jsonPath().getMap("order"));
        staleOrder.put("internalNotes", "typed on the old screen");
        mailbox.clear();
        Response refused = staff().queryParam("webOrderRevision", 1).contentType("application/json").body(staleOrder)
                .when().put(STAFF + "/" + id);
        refused.then().statusCode(409);
        assertEquals("WEB_ORDER_CHANGED", refused.path("code"));
        assertEquals(2, (int) refused.path("webOrderRevision"));
        assertTrue(refused.<String>path("message").contains("intussen gewijzigd of geannuleerd"), refused.asString());
        /* A screen from before this round sends no revision at all: refused as well once the customer changed it. */
        Response refusedOld = staff().contentType("application/json").body(staleOrder).when().put(STAFF + "/" + id);
        refusedOld.then().statusCode(409);
        assertEquals("WEB_ORDER_CHANGED", refusedOld.path("code"));
        /* The same for the button on the stale screen. */
        staff().queryParam("webOrderRevision", 1).when().post(STAFF + "/" + id + "/take-into-processing")
                .then().statusCode(409);
        settle();
        JsonPath untouched = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals(2, untouched.getList("order.lines").size(), "the customer's lines are intact");
        assertEquals(96, untouched.getInt("order.lines.find { it.productId == " + rose + " }.quantity"));
        assertFalse(String.valueOf(untouched.getString("order.internalNotes")).contains("typed on the old screen"));
        assertNull(untouched.get("webOrder.processingStartedAt"), "a refused save does not take the order");
        assertEquals(true, untouched.getBoolean("webOrder.customerEditable"));
        assertEquals(0, mailbox.getTotalMessagesSent(), "a refused save mails nobody");
        assertEquals("RECEIVED", orders(anna).getString("items[0].status"));

        // (e) staff take it into processing
        Response taken = staff().queryParam("webOrderRevision", 2).when().post(STAFF + "/" + id + "/take-into-processing");
        taken.then().statusCode(200);
        assertEquals("KNOP", taken.path("webOrder.processingTrigger"));
        assertEquals(false, taken.path("webOrder.customerEditable"));
        assertNotNull(taken.path("webOrder.processingStartedBy"));
        settle();
        staff().queryParam("webOrderRevision", 2).when().post(STAFF + "/" + id + "/take-into-processing")
                .then().statusCode(200);
        settle();
        List<Mail> processing = mailbox.getMailsSentTo(anna.email());
        assertEquals(1, processing.size(), "one mail about processing, also after a second press: " + subjects(processing));
        assertEquals(String.format(DocumentText.of(Language.FR).get("mailOrderProcessingSubject"), number),
                processing.getFirst().getSubject());
        assertTrue(processing.getFirst().getHtml().contains("<html lang=\"fr\">"), "the processing mail is French");
        assertEquals(List.of(anna.email()), processing.getFirst().getTo());

        Map<String, Object> third = orderBody(Map.of(rose, 9, peony, 2), "Stationsstraat 9", "3500", "Hasselt");
        third.put("baseRevision", 2);
        Response lateChange = change(anna, id, third);
        assertEquals(409, lateChange.statusCode());
        assertEquals(LOCKED, lateChange.asString());
        Response lateCancel = cancel(anna, id, 2);
        assertEquals(409, lateCancel.statusCode());
        assertEquals(LOCKED, lateCancel.asString());
        bearer(anna).contentType("application/json").body(previewOf(third, id)).when().post(ORDERS + "/preview")
                .then().statusCode(409);
        JsonPath inProcessing = orders(anna);
        assertEquals("IN_PROCESSING", inProcessing.getString("items[0].status"));
        assertEquals(false, inProcessing.getBoolean("items[0].canChange"));
        assertEquals(false, inProcessing.getBoolean("items[0].canCancel"));
        assertEquals(96, detail(anna, id).getInt(roseLine + ".quantity"));

        // (f) path A of D1: the invoice from the unchanged order, no signature
        mailbox.clear();
        Response draft = staff().queryParam("webOrderRevision", 2).contentType("application/json").body("{}").when().post(STAFF + "/" + id + "/invoice");
        assertEquals(200, draft.statusCode(), draft.asString());
        long invoiceId = ((Number) draft.path("order.id")).longValue();
        String draftType = draft.path("order.docType");
        assertEquals("FACTUUR", draftType);
        assertEquals("CONCEPT", draft.path("order.status"));
        assertNull(draft.path("webOrder"), "the invoice is not itself the web order");
        assertEquals("Stationsstraat 9", draft.path("delivery.address"), "the invoice carries the order's delivery");
        assertEquals(0, invoices(anna).getList("items").size(), "a draft invoice is never shown");
        assertEquals("IN_PROCESSING", orders(anna).getString("items[0].status"));

        /* A customer made at login approval has no address on record, and an invoice needs one: the
           address typed for the order does not count. Staff fill in the record first. */
        Response noAddress = issue(invoiceId);
        assertEquals(409, noAddress.statusCode(), noAddress.asString());
        String refusal = noAddress.path("message");
        assertTrue(refusal.startsWith("De factuur kan niet uitgereikt worden: bij klant ")
                && refusal.endsWith(" ontbreken straat en nummer, postcode en stad. Vul dit in bij de klantgegevens."), refusal);
        Map<String, Object> record = new LinkedHashMap<>(staff().when().get("/api/customers/" + anna.customerId())
                .then().statusCode(200).extract().jsonPath().getMap("$"));
        record.put("address", "Avenue Louise 100");
        record.put("postalCode", "1050");
        record.put("city", "Ixelles");
        staff().contentType("application/json").body(record).when().put("/api/customers/" + anna.customerId())
                .then().statusCode(200);
        JsonPath withRecord = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals(true, withRecord.getBoolean("delivery.differsFromCustomerRecord"));
        assertEquals("ORDER_EQUAL", withRecord.getString("webOrder.termsState"),
                "filling in the billing address changes no figure of the order (Belgian country tariff; a carrier priced by zone is not walked here)");

        Response issued = issue(invoiceId);
        assertEquals(200, issued.statusCode(), issued.asString());
        String invoiceNumber = issued.path("order.number");
        assertNotEquals("CONCEPT", issued.<String>path("order.status"));
        JsonPath quoteAfter = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertNull(quoteAfter.get("order.signedByName"), "nobody signed");
        assertNotEquals("GEACCEPTEERD", quoteAfter.getString("order.status"));
        assertNull(quoteAfter.get("order.sentAt"), "the order itself was never sent for approval");

        JsonPath confirmed = orders(anna);
        assertEquals("CONFIRMED", confirmed.getString("items[0].status"));
        assertEquals(invoiceNumber, confirmed.getString("items[0].relatedNumber"));
        Response invoiceList = bearer(anna).queryParam("kind", "INVOICES").when().get(DOCUMENTS);
        invoiceList.then().statusCode(200);
        assertEquals(1, invoiceList.jsonPath().getList("items").size());
        assertEquals((int) invoiceId, invoiceList.jsonPath().getInt("items[0].id"));
        assertEquals("INVOICE", invoiceList.jsonPath().getString("items[0].kind"));
        assertEquals("ISSUED", invoiceList.jsonPath().getString("items[0].status"));
        assertEquals(invoiceNumber, invoiceList.jsonPath().getString("items[0].number"));
        assertEquals(true, invoiceList.jsonPath().getBoolean("items[0].hasPdf"));
        assertEquals(false, invoiceList.jsonPath().getBoolean("items[0].hasDetail"));
        assertEquals(number, invoiceList.jsonPath().getString("items[0].relatedNumber"));
        assertNoPaymentWord(invoiceList.asString());

        Response pdf = bearer(anna).when().get(DOCUMENTS + "/" + invoiceId + "/pdf");
        assertEquals(200, pdf.statusCode());
        assertTrue(pdf.contentType().startsWith("application/pdf"), pdf.contentType());
        assertEquals("no-store", pdf.header("Cache-Control"));
        assertTrue(pdf.header("Content-Disposition").contains(invoiceNumber + ".pdf"), pdf.header("Content-Disposition"));
        String unpaidCopy = text(pdf.asByteArray());
        assertTrue(unpaidCopy.contains(invoiceNumber), "the PDF is this invoice");

        /* Staff book the payment: the customer's list and PDF say nothing about it. */
        String staffBefore = text(staff().when().get(STAFF + "/" + invoiceId + "/pdf").then().statusCode(200)
                .extract().asByteArray());
        Response paid = staff().contentType("application/json").body("{}").when().post(STAFF + "/" + invoiceId + "/mark-paid");
        assertEquals(200, paid.statusCode(), paid.asString());
        String staffAfter = text(staff().when().get(STAFF + "/" + invoiceId + "/pdf").then().statusCode(200)
                .extract().asByteArray());
        assertNotEquals(staffBefore, staffAfter, "the staff PDF does show the payment (so the comparison below means something)");
        Response paidList = bearer(anna).queryParam("kind", "INVOICES").when().get(DOCUMENTS);
        assertEquals("ISSUED", paidList.jsonPath().getString("items[0].status"));
        assertNoPaymentWord(paidList.asString());
        assertEquals(unpaidCopy, text(bearer(anna).when().get(DOCUMENTS + "/" + invoiceId + "/pdf")
                .then().statusCode(200).extract().asByteArray()), "the customer's PDF is the same before and after payment");
        assertEquals("CONFIRMED", orders(anna).getString("items[0].status"));

        /* Another customer gets nothing of it, with the one not-found answer. */
        for (String path : List.of(DOCUMENTS + "/" + id, DOCUMENTS + "/" + id + "/pdf",
                DOCUMENTS + "/" + invoiceId, DOCUMENTS + "/" + invoiceId + "/pdf")) {
            Response foreign = bearer(boris).when().get(path);
            assertEquals(404, foreign.statusCode(), path);
            assertEquals(ORDER_NOT_FOUND, foreign.asString(), path);
        }
        assertEquals(0, orders(boris).getList("items").size());
        assertEquals(0, invoices(boris).getList("items").size());
        Map<String, Object> theft = orderBody(Map.of(rose, 6), "Markt 1", "2400", "Mol");
        theft.put("baseRevision", 2);
        assertEquals(404, change(boris, id, theft).statusCode());
        assertEquals(404, cancel(boris, id, 2).statusCode());
        /* Without a session: nothing. */
        given().queryParam("kind", "ORDERS").when().get(DOCUMENTS).then().statusCode(401);
        given().when().get(DOCUMENTS + "/" + invoiceId + "/pdf").then().statusCode(401);
        listPrice(rose, "10.00");
    }

    // ------------------------------------------------------------ (g)

    @Test
    @Order(2)
    void aChangedOrderGoesThroughSendAndThePortalAndIsInvoicedOnlyAsApproved() {
        Response placed = place(anna, orderBody(Map.of(rose, 7), "Rue du Marché 1", "1000", "Bruxelles"));
        placed.then().statusCode(201);
        long id = ((Number) placed.path("id")).longValue();
        String number = placed.path("number");
        settle();
        mailbox.clear();

        staff().queryParam("webOrderRevision", 1).when().post(STAFF + "/" + id + "/take-into-processing")
                .then().statusCode(200);
        Response freight = staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("state", "BEREKEND", "manualFreightEur", 55))
                .when().put(STAFF + "/" + id + "/freight");
        assertEquals(200, freight.statusCode(), freight.asString());
        assertEquals("ORDER_DIFFERENT", freight.path("webOrder.termsState"));
        List<String> differences = freight.path("webOrder.differences");
        assertTrue(differences.stream().anyMatch(line -> line.startsWith("Vracht: besteld")), String.valueOf(differences));

        /* Not as ordered any more: no invoice without the customer's approval. */
        Response tooEarly = staff().queryParam("webOrderRevision", 1).contentType("application/json").body("{}").when().post(STAFF + "/" + id + "/invoice");
        assertEquals(409, tooEarly.statusCode(), tooEarly.asString());
        assertTrue(tooEarly.<String>path("message").contains("wijkt af van wat de klant bestelde"), tooEarly.asString());
        assertTrue(tooEarly.<String>path("message").contains("Vracht: besteld"), tooEarly.asString());
        settle();
        /* What the customer sees is still what they ordered, not staff's unsent figure. */
        JsonPath unsent = detail(anna, id);
        assertEquals("AS_ORDERED", unsent.getString("basis"));
        assertNotEquals(55.0f, unsent.getFloat("totals.shipping"), "an unsent staff edit never leaves");
        assertEquals("IN_PROCESSING", unsent.getString("status"));
        assertEquals(1, mailbox.getMailsSentTo(anna.email()).size(), "the processing mail");

        mailbox.clear();
        Response sent = staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("message", "Le transport a été adapté."))
                .when().post(STAFF + "/" + id + "/send");
        assertEquals(200, sent.statusCode(), sent.asString());
        assertEquals("VERZONDEN", sent.path("order.status"));
        assertEquals("AWAITING_APPROVAL", sent.path("webOrder.termsState"));
        settle();
        List<Mail> approval = mailbox.getMailsSentTo(anna.email());
        assertEquals(1, approval.size(), subjects(approval));
        assertEquals(String.format(DocumentText.of(Language.FR).get("mailSubjectOrderApproval"), number),
                approval.getFirst().getSubject());
        String portal = staff().when().get(STAFF + "/" + id + "/portal-link").then().statusCode(200)
                .extract().path("url");
        assertNotNull(portal);
        Matcher tokenIn = Pattern.compile("([A-Za-z0-9_-]{16,})/?$").matcher(portal);
        assertTrue(tokenIn.find(), portal);
        String token = tokenIn.group(1);
        assertTrue(approval.getFirst().getHtml().contains(token), "the approval mail carries the portal link");

        JsonPath awaiting = orders(anna);
        String row = "items.find { it.id == " + id + " }";
        assertEquals("AWAITING_APPROVAL", awaiting.getString(row + ".status"));
        assertEquals(true, awaiting.getBoolean(row + ".hasPdf"));
        assertEquals(false, awaiting.getBoolean(row + ".canChange"));
        JsonPath current = detail(anna, id);
        assertEquals("CURRENT", current.getString("basis"));
        assertEquals(55.0f, current.getFloat("totals.shipping"), 0.001f);
        bearer(anna).when().get(DOCUMENTS + "/" + id + "/pdf").then().statusCode(200);
        /* Reading it under "my orders" is not opening the portal. */
        assertEquals("VERZONDEN", staff().when().get(STAFF + "/" + id).then().extract().path("order.status"));

        /* Staff touch the freight after sending and do not resend: the link refuses the approval. */
        staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("state", "BEREKEND", "manualFreightEur", 56))
                .when().put(STAFF + "/" + id + "/freight").then().statusCode(200)
                .body("webOrder.termsState", org.hamcrest.Matchers.equalTo("RESEND_REQUIRED"));
        Response notThisVersion = given().contentType("application/json").body(Map.of("signedByName", "Claire Dupont"))
                .when().post("/api/portal/" + token + "/accept");
        assertEquals(409, notThisVersion.statusCode(), notThisVersion.asString());
        staff().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("state", "BEREKEND", "manualFreightEur", 55))
                .when().put(STAFF + "/" + id + "/freight").then().statusCode(200)
                .body("webOrder.termsState", org.hamcrest.Matchers.equalTo("AWAITING_APPROVAL"));

        Response accepted = given().contentType("application/json").body(Map.of("signedByName", "Claire Dupont"))
                .when().post("/api/portal/" + token + "/accept");
        assertEquals(200, accepted.statusCode(), accepted.asString());
        settle();
        assertEquals("CONFIRMED", orders(anna).getString(row + ".status"));
        JsonPath approved = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals("GEACCEPTEERD", approved.getString("order.status"));
        assertEquals("APPROVED", approved.getString("webOrder.termsState"));
        assertEquals("Claire Dupont", approved.getString("order.signedByName"));

        Response draft = staff().queryParam("webOrderRevision", 1).contentType("application/json").body("{}").when().post(STAFF + "/" + id + "/invoice");
        assertEquals(200, draft.statusCode(), draft.asString());
        long invoiceId = ((Number) draft.path("order.id")).longValue();

        /* Staff change a figure on the draft invoice after the approval. */
        Map<String, Object> edited = new LinkedHashMap<>(draft.jsonPath().getMap("order"));
        edited.put("manualFreightEur", 80);
        Response savedEdit = staff().contentType("application/json").body(edited).when().put(STAFF + "/" + invoiceId);
        assertEquals(200, savedEdit.statusCode(), savedEdit.asString());
        Response refused = issue(invoiceId);
        assertEquals(409, refused.statusCode(), refused.asString());
        assertTrue(refused.<String>path("message").contains("wijkt af van de versie waarmee de klant akkoord ging"),
                refused.asString());
        assertEquals(0, invoices(anna).getList("items.findAll { it.id == " + invoiceId + " }").size());

        Map<String, Object> restored = new LinkedHashMap<>(savedEdit.jsonPath().getMap("order"));
        restored.put("manualFreightEur", 55);
        staff().contentType("application/json").body(restored).when().put(STAFF + "/" + invoiceId).then().statusCode(200);
        Response issued = issue(invoiceId);
        assertEquals(200, issued.statusCode(), issued.asString());
        JsonPath done = orders(anna);
        assertEquals("CONFIRMED", done.getString(row + ".status"));
        assertEquals(issued.<String>path("order.number"), done.getString(row + ".relatedNumber"));
        assertEquals(1, invoices(anna).getList("items.findAll { it.id == " + invoiceId + " }").size());
    }

    // ------------------------------------------------------------ (h)

    @Test
    @Order(3)
    void theCustomerCancelsBeforeAnybodyTookTheOrder() {
        /* Ordered on the Dutch page by a customer whose record says French: the order mail follows the page. */
        Map<String, Object> dutchPage = orderBody(Map.of(rose, 6), "Rue du Marché 1", "1000", "Bruxelles");
        dutchPage.put("language", "NL");
        Response placed = place(anna, dutchPage);
        placed.then().statusCode(201);
        long id = ((Number) placed.path("id")).longValue();
        String number = placed.path("number");
        settle();
        assertEquals(String.format(DocumentText.of(Language.NL).get("mailOrderReceivedSubject"), number),
                mailbox.getMailsSentTo(anna.email()).getLast().getSubject());
        mailbox.clear();

        Response cancelled = cancel(anna, id, 1);
        assertEquals(200, cancelled.statusCode(), cancelled.asString());
        assertEquals("CANCELLED", cancelled.path("status"));
        assertEquals(2, (int) cancelled.path("revision"));
        settle();
        assertTrue(mailbox.getMailsSentTo(anna.email()).isEmpty(), "no customer mail for their own cancel");
        List<Mail> team = mailbox.getMailsSentTo(TEAM);
        assertEquals(1, team.size(), subjects(team));
        assertTrue(team.getFirst().getSubject().startsWith("Websitebestelling " + number + " geannuleerd door de klant"),
                team.getFirst().getSubject());

        String row = "items.find { it.id == " + id + " }";
        JsonPath mine = orders(anna);
        assertEquals("CANCELLED", mine.getString(row + ".status"));
        assertEquals("CUSTOMER", mine.getString(row + ".cancelledBy"));
        assertEquals(false, mine.getBoolean(row + ".canChange"));

        JsonPath staffView = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals("GEANNULEERD", staffView.getString("order.status"));
        assertNotNull(staffView.get("webOrder.customerCancelledAt"));
        assertNull(staffView.get("webOrder.processingStartedAt"));
        assertNull(staffView.get("order.portalToken"), "a customer cancel mints no portal link");
        JsonPath staffList = staff().when().get(STAFF).then().statusCode(200).extract().jsonPath();
        assertNotNull(staffList.get("find { it.order.id == " + id + " }.webOrder.customerCancelledAt"),
                "the list tells who cancelled");
        assertEquals(false, staffList.getBoolean("find { it.order.id == " + id + " }.webOrder.customerEditable"));
        List<Map<String, Object>> history = staff().when().get(STAFF + "/" + id + "/history").then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertTrue(history.stream().anyMatch(event -> String.valueOf(event).contains("Bestelling geannuleerd door de klant")),
                String.valueOf(history));

        /* Staff cannot take or reopen it; the stale screen (revision 1) is told so, the fresh one gets the reason. */
        staff().queryParam("webOrderRevision", 1).when().post(STAFF + "/" + id + "/take-into-processing")
                .then().statusCode(409).body("code", org.hamcrest.Matchers.equalTo("WEB_ORDER_CHANGED"));
        Response take = staff().queryParam("webOrderRevision", 2).when().post(STAFF + "/" + id + "/take-into-processing");
        assertEquals(409, take.statusCode());
        assertTrue(take.<String>path("message").contains("De klant heeft deze bestelling geannuleerd"), take.asString());
        Response reopen = staff().queryParam("webOrderRevision", 2).contentType("application/json").body("{}").when().post(STAFF + "/" + id + "/reopen");
        assertEquals(409, reopen.statusCode(), reopen.asString());
        /* And the customer cannot cancel or change twice. */
        assertEquals(LOCKED, cancel(anna, id, 2).asString());
        settle();
        assertTrue(mailbox.getMailsSentTo(anna.email()).isEmpty());
    }

    // ------------------------------------------------------------ (i)

    @Test
    @Order(4)
    void aStaffSaveWithoutTheButtonTakesTheOrderAndMailsOnce() {
        Response placed = place(anna, orderBody(Map.of(rose, 6), "Rue du Marché 1", "1000", "Bruxelles"));
        placed.then().statusCode(201);
        long id = ((Number) placed.path("id")).longValue();
        String number = placed.path("number");
        settle();
        mailbox.clear();

        Response screen = staff().when().get(STAFF + "/" + id);
        Map<String, Object> order = new LinkedHashMap<>(screen.jsonPath().getMap("order"));
        order.put("internalNotes", String.valueOf(order.get("internalNotes")) + "\nNagekeken door de reviewer");
        /* An ERP tab from before this round sends no revision: allowed while the order is at its first version. */
        Response saved = staff().contentType("application/json").body(order).when().put(STAFF + "/" + id);
        assertEquals(200, saved.statusCode(), saved.asString());
        assertEquals("AUTOMATISCH", saved.path("webOrder.processingTrigger"));
        assertEquals(false, saved.path("webOrder.customerEditable"));
        assertEquals("ORDER_EQUAL", saved.path("webOrder.termsState"), "a note is not a figure");
        settle();
        /* A second save and a late press of the button mail nobody again. */
        Map<String, Object> again = new LinkedHashMap<>(saved.jsonPath().getMap("order"));
        again.put("internalNotes", String.valueOf(again.get("internalNotes")) + "\nNog eens");
        staff().queryParam("webOrderRevision", 1).contentType("application/json").body(again)
                .when().put(STAFF + "/" + id).then().statusCode(200);
        Response button = staff().queryParam("webOrderRevision", 1).when().post(STAFF + "/" + id + "/take-into-processing");
        button.then().statusCode(200);
        assertEquals("AUTOMATISCH", button.path("webOrder.processingTrigger"), "the first trigger stays");
        settle();
        List<Mail> toAnna = mailbox.getMailsSentTo(anna.email());
        assertEquals(1, toAnna.size(), subjects(toAnna));
        assertEquals(String.format(DocumentText.of(Language.FR).get("mailOrderProcessingSubject"), number),
                toAnna.getFirst().getSubject());
        List<Map<String, Object>> history = staff().when().get(STAFF + "/" + id + "/history").then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertEquals(1, history.stream()
                .filter(event -> String.valueOf(event).contains("In verwerking genomen (automatisch")).count(),
                String.valueOf(history));
        assertEquals(LOCKED, cancel(anna, id, 1).asString());
        assertEquals("IN_PROCESSING", orders(anna).getString("items.find { it.id == " + id + " }.status"));
    }

    // ------------------------------------------------------------ (j)

    @Test
    @Order(5)
    void belowTheMinimumNothingIsPlacedAndNothingIsChanged() {
        long rowsBefore = webOrderRows();
        Map<String, Object> small = orderBody(Map.of(rose, 2), "Rue du Marché 1", "1000", "Bruxelles");
        JsonPath estimate = bearer(anna).contentType("application/json").body(small)
                .when().post(ORDERS + "/preview").then().statusCode(200).extract().jsonPath();
        assertEquals(false, estimate.getBoolean("validation.meetsMinimum"));
        assertEquals(600.0f, estimate.getFloat("validation.minimumOrderNet"), 0.001f);
        assertEquals(360.0f, estimate.getFloat("validation.minimumShortfallNet"), 0.001f, "the amount missing");

        Response refused = place(anna, small);
        assertEquals(422, refused.statusCode(), refused.asString());
        assertEquals("MINIMUM_NOT_MET", refused.path("fieldErrors.items"));
        settle();
        assertEquals(rowsBefore, webOrderRows(), "nothing was placed");
        assertEquals(0, mailbox.getTotalMessagesSent());

        Response placed = place(anna, orderBody(Map.of(rose, 6), "Rue du Marché 1", "1000", "Bruxelles"));
        placed.then().statusCode(201);
        long id = ((Number) placed.path("id")).longValue();
        settle();
        mailbox.clear();
        Map<String, Object> shrink = orderBody(Map.of(rose, 2), "Rue du Marché 1", "1000", "Bruxelles");
        shrink.put("baseRevision", 1);
        JsonPath editEstimate = bearer(anna).contentType("application/json").body(previewOf(shrink, id))
                .when().post(ORDERS + "/preview").then().statusCode(200).extract().jsonPath();
        assertEquals(false, editEstimate.getBoolean("validation.meetsMinimum"));
        assertEquals(360.0f, editEstimate.getFloat("validation.minimumShortfallNet"), 0.001f);
        Response refusedChange = change(anna, id, shrink);
        assertEquals(422, refusedChange.statusCode(), refusedChange.asString());
        assertEquals("MINIMUM_NOT_MET", refusedChange.path("fieldErrors.items"));
        settle();
        JsonPath still = detail(anna, id);
        assertEquals(1, still.getInt("revision"));
        assertEquals(72, still.getInt("lines[0].quantity"));
        assertEquals(true, still.getBoolean("canChange"));
        assertEquals(0, mailbox.getTotalMessagesSent());
        /* The old logged-in quote request cannot be used to get around it. */
        Map<String, Object> legacy = new LinkedHashMap<>(small);
        legacy.put("vatNumber", "BE0123456789");
        legacy.put("companyCountryCode", "BE");
        legacy.put("companyName", "Fleurs Anna SRL");
        legacy.put("email", anna.email());
        Response oldRoute = bearer(anna).contentType("application/json")
                .header("Idempotency-Key", "legacy-" + UUID.randomUUID()).body(legacy)
                .when().post(ACCOUNT + "/quotes/requests");
        assertEquals(422, oldRoute.statusCode(), oldRoute.asString());
        assertEquals("MINIMUM_NOT_MET", oldRoute.path("fieldErrors.items"));
    }

    // ------------------------------------------------------------ (k)

    @Test
    @Order(6)
    void theAnonymousQuoteRequestIsWhatItWasAndIsNoWebOrder() {
        long rowsBefore = webOrderRows();
        String email = "visitor-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        /* Two cartons: below the minimum, which an anonymous request may still be. */
        Map<String, Object> body = orderBody(Map.of(rose, 2), "Markt 1", "2400", "Mol");
        body.put("language", "NL");
        body.put("vatNumber", "BE0123456789");
        body.put("companyCountryCode", "BE");
        body.put("companyName", "Typed Company BV");
        body.put("email", email);
        Response submitted = given().contentType("application/json")
                .header("Idempotency-Key", "quote-" + UUID.randomUUID()).body(body)
                .when().post("/api/v1/public/quotes/requests");
        assertEquals(201, submitted.statusCode(), submitted.asString());
        assertEquals("RECEIVED", submitted.path("status"));
        String reference = submitted.path("reference");
        assertNotNull(submitted.path("estimate"));
        settle();

        assertEquals(rowsBefore, webOrderRows(), "an anonymous request makes no web-order row");
        JsonPath list = staff().when().get(STAFF).then().statusCode(200).extract().jsonPath();
        String found = "find { it.order.number == '" + reference + "' }";
        Integer id = list.get(found + ".order.id");
        assertNotNull(id, "staff see the request");
        long customerId = ((Number) list.get(found + ".order.customerId")).longValue();
        customerIds.add(customerId);
        assertNull(list.get(found + ".webOrder"));
        assertNull(list.get(found + ".delivery"));
        assertEquals("CONCEPT", list.getString(found + ".order.status"));
        JsonPath view = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertNull(view.get("webOrder"));
        assertNull(view.get("delivery"));
        assertEquals(24, view.getInt("order.lines[0].quantity"));

        List<Mail> team = mailbox.getMailsSentTo(TEAM);
        assertEquals(1, team.size(), subjects(team));
        assertTrue(team.getFirst().getSubject().startsWith("Nieuwe websiteaanvraag " + reference), team.getFirst().getSubject());
        assertTrue(mailbox.getMailsSentTo(email).isEmpty(), "the visitor gets no mail, as before");

        /* Staff work on it without any revision parameter and nothing of the order machinery answers. */
        Map<String, Object> order = new LinkedHashMap<>(view.getMap("order"));
        order.put("internalNotes", String.valueOf(order.get("internalNotes")) + "\nGebeld");
        Response saved = staff().contentType("application/json").body(order).when().put(STAFF + "/" + id);
        assertEquals(200, saved.statusCode(), saved.asString());
        assertNull(saved.path("webOrder"));
        Response take = staff().when().post(STAFF + "/" + id + "/take-into-processing");
        assertEquals(409, take.statusCode());
        assertTrue(take.<String>path("message").contains("geen websitebestelling"), take.asString());
        settle();
        assertEquals(1, mailbox.getTotalMessagesSent(), "no order mail for a quote request");
        /* The account endpoints need a session. */
        given().when().get(DOCUMENTS + "/delivery-defaults").then().statusCode(401);
        given().contentType("application/json").body(body).when().post(ORDERS + "/preview").then().statusCode(401);
        given().contentType("application/json").header("Idempotency-Key", "x-" + UUID.randomUUID()).body(body)
                .when().post(ORDERS).then().statusCode(401);
    }

    // ------------------------------------------------------------ beyond the list

    @Test
    @Order(7)
    void sendingAsTheFirstStaffActionMailsTheApprovalAndNoProcessingMail() {
        Response placed = place(anna, orderBody(Map.of(rose, 6), "Rue du Marché 1", "1000", "Bruxelles"));
        placed.then().statusCode(201);
        long id = ((Number) placed.path("id")).longValue();
        String number = placed.path("number");
        settle();
        mailbox.clear();

        Response sent = staff().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .when().post(STAFF + "/" + id + "/send");
        assertEquals(200, sent.statusCode(), sent.asString());
        assertEquals("AUTOMATISCH", sent.path("webOrder.processingTrigger"));
        assertEquals(false, sent.path("webOrder.mailDue"));
        settle();
        List<Mail> toAnna = mailbox.getMailsSentTo(anna.email());
        assertEquals(1, toAnna.size(), "the approval mail is the message, no processing mail beside it: " + subjects(toAnna));
        assertEquals(String.format(DocumentText.of(Language.FR).get("mailSubjectOrderApproval"), number),
                toAnna.getFirst().getSubject());
        JsonPath after = staff().when().get(STAFF + "/" + id).then().statusCode(200).extract().jsonPath();
        assertNull(after.get("webOrder.processingMailSentAt"));
        assertEquals(false, after.getBoolean("webOrder.mailDue"));
        /* Sent but not approved: no invoice. */
        Response tooEarly = staff().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .when().post(STAFF + "/" + id + "/invoice");
        assertEquals(409, tooEarly.statusCode(), tooEarly.asString());
        assertTrue(tooEarly.<String>path("message").contains("wacht nog op het akkoord van de klant"), tooEarly.asString());
        assertEquals(LOCKED, cancel(anna, id, 1).asString());
    }

    // ------------------------------------------------------------------ helpers

    /** A visitor asks for a login, staff approve it for a new customer, the visitor follows the mailed link. */
    private Login register(String company, String language) {
        String email = "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        String vat = "BE0" + (100_000_000 + (long) (Math.random() * 899_999_999));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("language", language);
        form.put("companyName", company);
        form.put("companyCountryCode", "BE");
        form.put("vatNumber", vat);
        form.put("contactName", "Claire Dupont");
        form.put("email", email);
        form.put("phone", "+32 470 00 00 00");
        form.put("message", "Nous sommes fleuristes.");
        form.put("privacyAccepted", true);
        form.put("website", "");
        form.put("formToken", accountFormToken);
        given().contentType("application/json").header("Idempotency-Key", "login-" + UUID.randomUUID()).body(form)
                .when().post(ACCOUNT + "/requests").then().statusCode(202);
        settle();
        int requestId = staff().when().get("/api/login-requests").then().statusCode(200)
                .extract().path("find { it.email == '" + email + "' }.id");
        Response approved = staff().contentType("application/json")
                .body(Map.of("newCustomer", Map.of("company", company, "vatNumber", vat, "countryCode", "BE",
                        "contact", "Claire Dupont", "phone", "+32 470 00 00 00", "language", language)))
                .when().post("/api/login-requests/" + requestId + "/approve");
        assertEquals(200, approved.statusCode(), approved.asString());
        long customerId = ((Number) approved.path("request.customerId")).longValue();
        long accountId = ((Number) approved.path("account.id")).longValue();
        customerIds.add(customerId);
        settle();
        Mail invitation = mailbox.getMailsSentTo(email).getLast();
        Matcher link = Pattern.compile("#activate=(eci1_[A-Za-z0-9_-]{43})").matcher(invitation.getHtml());
        assertTrue(link.find(), "the invitation carries a link");
        String session = given().contentType("application/json")
                .body(Map.of("token", link.group(1), "password", "roses-in-a-dome"))
                .when().post(ACCOUNT + "/activation").then().statusCode(200).extract().path("sessionToken");
        return new Login(session, customerId, accountId, email);
    }

    private String[] product(String price) {
        String sku = "FLOW-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
        long id = QuarkusTransaction.requiringNew().call(() -> {
            ProductEntity product = new ProductEntity();
            product.sku = sku;
            product.name = "Flow test " + sku;
            product.active = true;
            product.websiteStatus = PublicationState.PUBLISHED;
            product.piecesPerCarton = 12;
            product.inventoryKnown = true;
            product.stockQuantity = 100_000;
            product.cartonLengthCm = new BigDecimal("40");
            product.cartonWidthCm = new BigDecimal("30");
            product.cartonHeightCm = new BigDecimal("20");
            product.cartonWeightKg = new BigDecimal("5");
            product.landedCostEur = BigDecimal.ONE;
            product.fixedSalesPriceEur = new BigDecimal(price);
            em.persist(product);
            em.flush();
            return product.id;
        });
        productIds.add(id);
        return new String[]{Long.toString(id), sku};
    }

    private void listPrice(long productId, String price) {
        QuarkusTransaction.requiringNew().run(() ->
                em.find(ProductEntity.class, productId).fixedSalesPriceEur = new BigDecimal(price));
    }

    private Map<String, Object> orderBody(Map<Long, Integer> cartons, String address, String postalCode, String city) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", "FR");
        body.put("fulfillment", "DELIVERY");
        body.put("destination", Map.of("countryCode", "BE", "postalCode", postalCode, "city", city, "address", address));
        body.put("items", cartons.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.of("productId", entry.getKey(), "cartons", entry.getValue())).toList());
        body.put("contactName", "Claire Dupont");
        body.put("phone", "+32 470 00 00 00");
        body.put("notes", "Livraison le matin");
        body.put("privacyAccepted", true);
        body.put("website", "");
        body.put("formToken", quoteFormToken);
        return body;
    }

    private static Map<String, Object> previewOf(Map<String, Object> order, long orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String key : List.of("language", "fulfillment", "destination", "items")) body.put(key, order.get(key));
        body.put("orderId", orderId);
        return body;
    }

    private static Response place(Login login, Map<String, Object> body) {
        return bearer(login).contentType("application/json").header("Idempotency-Key", "place-" + UUID.randomUUID())
                .body(body).when().post(ORDERS);
    }

    private static Response change(Login login, long id, Map<String, Object> body) {
        return bearer(login).contentType("application/json").header("Idempotency-Key", "change-" + UUID.randomUUID())
                .body(body).when().post(ORDERS + "/" + id + "/changes");
    }

    private static Response cancel(Login login, long id, int baseRevision) {
        return bearer(login).contentType("application/json").header("Idempotency-Key", "cancel-" + UUID.randomUUID())
                .body(Map.of("baseRevision", baseRevision)).when().post(ORDERS + "/" + id + "/cancellation");
    }

    private static Response issue(long invoiceId) {
        return staff().contentType("application/json").body("{}").when().post(STAFF + "/" + invoiceId + "/issue");
    }

    private static JsonPath orders(Login login) {
        return bearer(login).queryParam("kind", "ORDERS").when().get(DOCUMENTS).then().statusCode(200).extract().jsonPath();
    }

    private static JsonPath invoices(Login login) {
        return bearer(login).queryParam("kind", "INVOICES").when().get(DOCUMENTS).then().statusCode(200).extract().jsonPath();
    }

    private static JsonPath detail(Login login, long id) {
        return bearer(login).when().get(DOCUMENTS + "/" + id).then().statusCode(200).extract().jsonPath();
    }

    private static void assertNoPaymentWord(String json) {
        String lower = json.toLowerCase();
        for (String word : List.of("paid", "payment", "betaal", "due", "open"))
            assertFalse(lower.contains(word), "no payment state in a customer answer: " + word + " in " + json);
    }

    private static String text(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private long webOrderRows() {
        return QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("select count(w) from SalesWebOrderEntity w", Long.class).getSingleResult());
    }

    private static String subjects(List<Mail> mails) {
        return mails.stream().map(Mail::getSubject).toList().toString();
    }

    private static RequestSpecification staff() {
        return given().auth().preemptive().basic("emre", STAFF_PASSWORD);
    }

    private static RequestSpecification bearer(Login login) {
        return given().header("Authorization", "Bearer " + login.token());
    }

    private static void settle() {
        assertTrue(ForkJoinPool.commonPool().awaitQuiescence(20, TimeUnit.SECONDS), "background work finished");
    }
}
