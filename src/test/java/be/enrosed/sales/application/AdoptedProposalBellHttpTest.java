package be.enrosed.sales.application;

import be.enrosed.sales.application.WebOrderStaffGateTest.Shop;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bell and the sales list agree on "opnieuw versturen", over HTTP and
 * against the real tables: an adopted proposal is one action item from the
 * moment it was adopted until the quote goes out again.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class AdoptedProposalBellHttpTest {

    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;

    private final Shop shop = new Shop();

    @AfterEach
    void removeRows() {
        shop.remove();
    }

    @Test
    void anAdoptedProposalRingsTheBellUntilTheQuoteGoesOutAgain() {
        long customerId = shop.customer("Bloemen Peeters BV", "Bloemenlaan 5", "2000", "Antwerpen");
        long productId = shop.product();
        SalesOrder created = sales.create(customerId, "BE", "DAP");
        sales.update(created.id(), shop.edited(order(created.id()), productId, 120, "10.00", "120.00"));
        SalesOrder sent = quotes.send(created.id(), null);
        long id = sent.id();
        assertEquals(200, given().when().get("/api/portal/" + sent.portalToken()).statusCode());
        Response proposed = given().contentType("application/json")
                .body(Map.of("proposedBy", "An Peeters", "message", "Graag 240 stuks", "lines", List.of()))
                .when().post("/api/portal/" + sent.portalToken() + "/propose");
        assertEquals(200, proposed.statusCode(), proposed.asString());
        assertTrue(bell(id).stream().noneMatch(item -> "OPNIEUW_VERSTUREN".equals(item.get("kind"))),
                "a proposal still to review is VOORSTEL: " + bell(id));

        quotes.approveRevision(quotes.revisionsFor(id).getFirst().id(), "emre", null);
        assertEquals(QuoteStatus.CONCEPT, order(id).status());
        QuoteRevision adopted = shop.inTransaction(() -> quotes.revisionsFor(id).getFirst());

        List<Map<String, Object>> items = bell(id);
        assertEquals(1, items.size(), "one item for the document: " + items);
        Map<String, Object> item = items.getFirst();
        assertEquals("OPNIEUW_VERSTUREN", item.get("kind"));
        assertEquals("Wijziging nog versturen", item.get("title"));
        assertEquals("Je nam de wijziging van de klant over; de klant wacht op de nieuwe versie.", item.get("detail"));
        assertEquals(Boolean.TRUE, item.get("actionNeeded"));
        assertEquals(adopted.handledAt(), Instant.parse((String) item.get("at")));
        assertTrue(listedAwaitingResend(id), "the sales list says the same");

        quotes.send(id, null);
        assertTrue(bell(id).stream().noneMatch(entry -> "OPNIEUW_VERSTUREN".equals(entry.get("kind"))),
                "after the resend the item is gone: " + bell(id));
        assertFalse(listedAwaitingResend(id), "and the sales list no longer asks for it");
    }

    private List<Map<String, Object>> bell(long orderId) {
        Response feed = given().when().get("/api/notifications");
        assertEquals(200, feed.statusCode(), feed.asString());
        List<Map<String, Object>> items = feed.jsonPath().getList("items");
        long actions = items.stream().filter(entry -> Boolean.TRUE.equals(entry.get("actionNeeded"))).count();
        assertEquals(actions, feed.jsonPath().getLong("actionCount"), "the badge counts every action item");
        return items.stream()
                .filter(entry -> entry.get("orderId") != null && ((Number) entry.get("orderId")).longValue() == orderId)
                .toList();
    }

    private boolean listedAwaitingResend(long orderId) {
        Response list = given().when().get("/api/sales-orders");
        assertEquals(200, list.statusCode(), list.asString());
        List<Map<String, Object>> rows = list.jsonPath().getList("$");
        return rows.stream()
                .filter(row -> ((Number) ((Map<?, ?>) row.get("order")).get("id")).longValue() == orderId)
                .map(row -> Boolean.TRUE.equals(row.get("awaitingResend")))
                .findFirst().orElseThrow();
    }

    /** A fresh read: outside a transaction this test's own request would answer from what it read before. */
    private SalesOrder order(long id) {
        return shop.inTransaction(() -> sales.get(id));
    }
}
