package be.enrosed.sales.application;

import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.application.WebOrders.Row;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A staff member and the customer on the same website order at the same
 * moment. Both wait for the document lock; whoever commits first decides, and
 * the other is told so and writes nothing. Staff act through the endpoints
 * the ERP calls, with the revision their screen shows; the customer side is
 * played with the primitives the customer endpoints use. The races run on
 * two threads; the outcomes that depend on who was first are also walked in
 * both orders.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class WebOrderConcurrencyTest {
    private static final String BASE = "/api/sales-orders";
    private static final String STAFF_REFUSED = "WEB_ORDER_CHANGED";
    private static final String CUSTOMER_REFUSED = "ORDER_LOCKED";
    /** How long the customer side waits in each round: from a dead heat to a clear head start for staff. */
    private static final int[] CUSTOMER_DELAYS_MS = {0, 10, 30, 80, 200, 500};

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject WebOrders webOrders;

    private final Shop shop = new Shop();

    @AfterEach
    void removeRows() {
        shop.remove();
    }

    // ------------------------------------------------------------------------------------------ two threads

    @Test
    void aStaffSaveAndACustomerChangeNeverOverwriteEachOther() throws Exception {
        for (int delay : CUSTOMER_DELAYS_MS) {
            Shop.Placed order = shop.place();
            Map<String, Object> screen = screen(order.id());
            line(screen).put("quantity", 132);

            Outcome outcome = race(delay, () -> code(put(order.id(), 1, screen)), () -> customer(() -> shop.customerChanges(order.id(), 144)));

            Row row = row(order.id());
            int quantity = order(order.id()).lines().getFirst().quantity();
            if (outcome.staff().equals("OK")) {
                /* Row 2 of the table: the staff save committed first and took the order. */
                assertEquals(CUSTOMER_REFUSED, outcome.customer(), outcome.toString());
                assertEquals(1, row.revision());
                assertNotNull(row.processingStartedAt());
                assertEquals(132, quantity);
            } else {
                /* Row 1: the customer was first; the screen is refused and nothing of it is written. */
                assertEquals(STAFF_REFUSED, outcome.staff(), outcome.toString());
                assertEquals("OK", outcome.customer(), outcome.toString());
                assertEquals(2, row.revision());
                assertNull(row.processingStartedAt());
                assertEquals(144, quantity);
            }
        }
    }

    @Test
    void takingAnOrderAndTheCustomerCancellingItHaveOneWinner() throws Exception {
        for (int delay : CUSTOMER_DELAYS_MS) {
            Shop.Placed order = shop.place();

            Outcome outcome = race(delay, () -> code(given().queryParam("webOrderRevision", 1)
                            .post(BASE + "/{id}/take-into-processing", order.id())),
                    () -> customer(() -> shop.customerCancels(order.id())));

            Row row = row(order.id());
            if (outcome.staff().equals("OK")) {
                assertEquals(CUSTOMER_REFUSED, outcome.customer(), outcome.toString());
                assertNotNull(row.processingStartedAt());
                assertNull(row.customerCancelledAt());
                assertEquals(QuoteStatus.CONCEPT, order(order.id()).status());
            } else {
                /* Row 5: the revision moved; the reloaded screen shows "Door de klant geannuleerd". */
                assertEquals(STAFF_REFUSED, outcome.staff(), outcome.toString());
                assertEquals("OK", outcome.customer(), outcome.toString());
                assertNull(row.processingStartedAt());
                assertNotNull(row.customerCancelledAt());
                assertEquals(2, row.revision());
                assertEquals(QuoteStatus.GEANNULEERD, order(order.id()).status());
            }
        }
    }

    @Test
    void aCancellationByStaffAndOneByTheCustomerHaveOneWinner() throws Exception {
        for (int delay : CUSTOMER_DELAYS_MS) {
            Shop.Placed order = shop.place();

            Outcome outcome = race(delay, () -> code(given().queryParam("webOrderRevision", 1).contentType("application/json")
                            .body(Map.of("message", "Niet leverbaar", "notifyCustomer", false))
                            .post(BASE + "/{id}/cancel", order.id())),
                    () -> customer(() -> shop.customerCancels(order.id())));

            Row row = row(order.id());
            assertEquals(QuoteStatus.GEANNULEERD, order(order.id()).status());
            long cancellations = shop.inTransaction(() -> quotes.history(order.id())).stream()
                    .filter(event -> event.type() == be.enrosed.sales.domain.QuoteEvent.Type.GEANNULEERD).count();
            assertEquals(1, cancellations, "cancelled once: " + outcome);
            if (outcome.staff().equals("OK")) {
                assertEquals(CUSTOMER_REFUSED, outcome.customer(), outcome.toString());
                assertNull(row.customerCancelledAt(), "cancelled by Enrosed");
                assertNotNull(row.processingStartedAt());
            } else {
                /* Row 6. */
                assertEquals(STAFF_REFUSED, outcome.staff(), outcome.toString());
                assertEquals("OK", outcome.customer(), outcome.toString());
                assertNotNull(row.customerCancelledAt(), "cancelled by the customer");
                assertNull(row.processingStartedAt());
            }
        }
    }

    // ------------------------------------------------------------------------------------------ both orders, one after the other

    @Test
    void sendingAndInvoicingSerialiseWithACustomerChangeInBothOrders() {
        for (String action : List.of("send", "invoice")) {
            /* Rows 3 and 4, the customer first: staff are refused with the revision their screen must load. */
            Shop.Placed customerFirst = shop.place();
            shop.customerChanges(customerFirst.id(), 144);
            assertEquals(STAFF_REFUSED, code(post(customerFirst.id(), 1, action)));
            assertNull(row(customerFirst.id()).processingStartedAt());
            assertEquals(QuoteStatus.CONCEPT, order(customerFirst.id()).status());
            assertEquals(144, order(customerFirst.id()).lines().getFirst().quantity());
            assertEquals("OK", code(post(customerFirst.id(), 2, action)), "after loading the latest version");

            /* Staff first: what was mailed or invoiced is the version that was saved, and the order is closed. */
            Shop.Placed staffFirst = shop.place();
            assertEquals("OK", code(post(staffFirst.id(), 1, action)));
            assertEquals(CUSTOMER_REFUSED, customer(() -> shop.customerChanges(staffFirst.id(), 144)));
            assertEquals(CUSTOMER_REFUSED, customer(() -> shop.customerCancels(staffFirst.id())));
            assertEquals(1, row(staffFirst.id()).revision());
            assertNotNull(row(staffFirst.id()).processingStartedAt());
            assertEquals(120, order(staffFirst.id()).lines().getFirst().quantity());
        }
    }

    @Test
    void anApprovalThroughTheLinkLandsBeforeOrAfterAReopenNeverOnIt() {
        /* Row 10, the approval first: the reopen finds an accepted quote and refuses. */
        Shop.Placed approvedFirst = shop.place();
        String token = given().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .post(BASE + "/{id}/send", approvedFirst.id()).then().statusCode(200).extract().path("order.portalToken");
        assertEquals(QuoteStatus.GEACCEPTEERD, quotes.acceptByCustomer(token, "An Peeters", null).status());
        given().queryParam("webOrderRevision", 1).contentType("application/json").post(BASE + "/{id}/reopen", approvedFirst.id())
                .then().statusCode(409);
        assertEquals(QuoteStatus.GEACCEPTEERD, order(approvedFirst.id()).status());

        /* The reopen first: the link answers its "being updated" refusal, also after the edit, until the resend. */
        Shop.Placed reopenedFirst = shop.place();
        String link = given().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .post(BASE + "/{id}/send", reopenedFirst.id()).then().statusCode(200).extract().path("order.portalToken");
        assertEquals("OK", code(post(reopenedFirst.id(), 1, "reopen")));
        assertThrows(RuntimeException.class, () -> quotes.acceptByCustomer(link, "An Peeters", null));
        assertEquals(QuoteStatus.CONCEPT, order(reopenedFirst.id()).status());
        assertNull(row(reopenedFirst.id()).acceptedTerms());
        assertEquals("OK", code(post(reopenedFirst.id(), 1, "send")));
        assertEquals(QuoteStatus.GEACCEPTEERD, quotes.acceptByCustomer(link, "An Peeters", null).status());
        assertNotNull(row(reopenedFirst.id()).acceptedTerms());
    }

    @Test
    void aScreenFromBeforeTheCustomersChangeStaysRefusedAfterAColleagueTookTheOrder() {
        /* Row 12: A's screen shows revision 1, the customer changes, B takes the order, A saves and cancels. */
        Shop.Placed order = shop.place();
        Map<String, Object> screenOfA = screen(order.id());
        line(screenOfA).put("quantity", 132);
        shop.customerChanges(order.id(), 144);
        assertEquals("OK", code(given().queryParam("webOrderRevision", 2).post(BASE + "/{id}/take-into-processing", order.id())));

        assertEquals(STAFF_REFUSED, code(put(order.id(), 1, screenOfA)));
        assertEquals(STAFF_REFUSED, code(given().contentType("application/json").body(screenOfA).put(BASE + "/{id}", order.id())),
                "a screen that names no revision at all");
        assertEquals(STAFF_REFUSED, code(given().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("notifyCustomer", false)).post(BASE + "/{id}/cancel", order.id())));
        assertEquals(144, order(order.id()).lines().getFirst().quantity(), "revision 2 is what staff process");
        assertEquals(QuoteStatus.CONCEPT, order(order.id()).status());

        Map<String, Object> reloaded = screen(order.id());
        line(reloaded).put("quantity", 156);
        assertEquals("OK", code(put(order.id(), 2, reloaded)));
        assertEquals(156, order(order.id()).lines().getFirst().quantity());
    }

    @Test
    void freightChangedOnASentOrderIsNotApprovedUntilItIsSentAgain() {
        /* Row 13. */
        Shop.Placed order = shop.place();
        String token = given().queryParam("webOrderRevision", 1).contentType("application/json").body("{}")
                .post(BASE + "/{id}/send", order.id()).then().statusCode(200).extract().path("order.portalToken");
        given().queryParam("webOrderRevision", 1).contentType("application/json")
                .body(Map.of("state", "BEREKEND", "manualFreightEur", 150, "freightPricingStrategy", "FIXED"))
                .put(BASE + "/{id}/freight", order.id()).then().statusCode(200)
                .body("webOrder.termsState", org.hamcrest.Matchers.equalTo("RESEND_REQUIRED"));

        assertEquals("Deze offerte wordt momenteel bijgewerkt. De nieuwe versie is pas zichtbaar nadat Enrosed ze opnieuw heeft verstuurd.",
                assertThrows(BusinessRuleException.class, () -> quotes.acceptByCustomer(token, "An Peeters", null)).getMessage());
        assertEquals(QuoteStatus.VERZONDEN, order(order.id()).status());
        assertNull(row(order.id()).acceptedTerms(), "no approval is recorded");

        assertEquals("OK", code(post(order.id(), 1, "send")));
        assertEquals(QuoteStatus.GEACCEPTEERD, quotes.acceptByCustomer(token, "An Peeters", null).status());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private record Outcome(String staff, String customer) {}

    /** Both sides start together, the customer after its delay; each answers what its caller would be told. */
    private static Outcome race(int customerDelayMs, Callable<String> staff, Callable<String> customer) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var staffSide = threads.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return staff.call();
            });
            var customerSide = threads.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                if (customerDelayMs > 0) Thread.sleep(customerDelayMs);
                return customer.call();
            });
            start.countDown();
            return new Outcome(staffSide.get(30, TimeUnit.SECONDS), customerSide.get(30, TimeUnit.SECONDS));
        }
    }

    /** What the customer endpoint answers: fine, or the code of the refusal. */
    private static String customer(Runnable write) {
        try {
            write.run();
            return "OK";
        } catch (WebOrderRefusal refusal) {
            return refusal.code() == WebOrderRefusal.Code.LOCKED ? CUSTOMER_REFUSED : refusal.code().name();
        }
    }

    /** What the ERP is told: fine, or the code of the conflict (the message when there is none). */
    private static String code(Response response) {
        if (response.statusCode() < 300) return "OK";
        String code = response.jsonPath().getString("code");
        return code != null ? code : response.statusCode() + " " + response.jsonPath().getString("message");
    }

    private static Response put(long id, int revision, Map<String, Object> order) {
        return given().queryParam("webOrderRevision", revision).contentType("application/json").body(order)
                .put(BASE + "/{id}", id);
    }

    private static Response post(long id, int revision, String action) {
        return given().queryParam("webOrderRevision", revision).contentType("application/json").body("{}")
                .post(BASE + "/{id}/" + action, id);
    }

    /** The document as the editor of the ERP holds it. */
    private static Map<String, Object> screen(long id) {
        return new HashMap<>(given().get(BASE + "/{id}", id).then().statusCode(200).extract().jsonPath().getMap("order"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("lines")).getFirst();
    }

    private Row row(long id) {
        return shop.inTransaction(() -> webOrders.find(id).orElseThrow());
    }

    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }
}
