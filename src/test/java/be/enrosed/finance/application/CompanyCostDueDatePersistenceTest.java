package be.enrosed.finance.application;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

/** The due date is set on creation and by its own endpoint; every other write of the cost carries it unchanged. */
@QuarkusTest
class CompanyCostDueDatePersistenceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 5);

    @Inject CompanyCostService costs;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test @TestTransaction
    void aNewCostStoresItsDueDateAndTheCostPutKeepsIt() {
        var created = costs.create(cost("Brochures beurs", DUE));
        assertEquals(DUE, created.dueDate());
        assertEquals(DUE, stored(created.id()));

        var updated = costs.update(created.id(), withDescription(cost("Brochures beurs (herdruk)", null), DAY));
        assertEquals("Brochures beurs (herdruk)", updated.description());
        assertEquals(DUE, updated.dueDate(), "a PUT without a due date (an older screen) keeps the stored one");
        assertEquals(DUE, costs.update(created.id(), cost("Brochures", DUE.plusDays(30))).dueDate(),
                "a PUT naming another due date changes nothing either: the due date is its endpoint's");
        assertEquals(DUE, stored(created.id()));
    }

    @Test @TestTransaction
    void itsOwnEndpointChangesAndClearsIt() {
        var created = costs.create(cost("Boekhouder Q3", null));
        assertNull(created.dueDate());
        assertEquals(DUE, costs.setDueDate(created.id(), DUE).cost().dueDate());
        assertEquals(DUE, stored(created.id()));
        assertEquals(DAY, costs.setDueDate(created.id(), DAY).cost().dueDate(), "the cost date itself is allowed");
        assertNull(costs.setDueDate(created.id(), null).cost().dueDate());
        assertNull(stored(created.id()));
    }

    @Test @TestTransaction
    void aDueDateBeforeTheCostDateIsRefused() {
        var created = costs.create(cost("Huur", null));
        assertEquals("De vervaldag ligt vóór de kostdatum",
                assertThrows(BusinessRuleException.class, () -> costs.setDueDate(created.id(), DAY.minusDays(1))).getMessage());
        assertEquals("De vervaldag ligt vóór de kostdatum",
                assertThrows(BusinessRuleException.class, () -> costs.create(cost("Huur", DAY.minusDays(1)))).getMessage());
    }

    @Test @TestTransaction
    void everyPaymentWriteKeepsTheDueDate() {
        var created = costs.create(cost("Software", DUE));
        var view = costs.addPayment(created.id(), new CostPayment(null, null, DAY, new BigDecimal("50.00"), "KBC", null, null, null, null));
        assertEquals(DUE, view.cost().dueDate());
        long paymentId = view.payments().getFirst().id();
        assertEquals(DUE, costs.updatePayment(created.id(), paymentId,
                new CostPayment(null, null, DAY, new BigDecimal("60.50"), "KBC", null, "deel", null, null)).cost().dueDate());
        var paid = costs.markPaid(created.id(), DAY);
        assertNotNull(paid.paidOn());
        assertEquals(DUE, paid.dueDate());
        long last = costs.view(costs.get(created.id())).payments().getLast().id();
        assertEquals(DUE, costs.deletePayment(created.id(), last).cost().dueDate());
        assertEquals(DUE, costs.deletePayment(created.id(), paymentId).cost().dueDate());
        assertEquals(DUE, stored(created.id()));
    }

    @Test @TestTransaction
    void aCostWithoutADueDateSerialisesWithoutTheKey() throws Exception {
        var without = costs.create(cost("Zonder vervaldag", null));
        String plain = json.writeValueAsString(without);
        assertFalse(plain.contains("dueDate"), "the bank registration hash of a cost without one stays what it was");
        assertFalse(json.writeValueAsString(costs.view(without)).contains("dueDate"));
        var with = costs.create(cost("Met vervaldag", DUE));
        assertTrue(json.writeValueAsString(with).contains("\"dueDate\":\"2026-11-05\""));
        assertTrue(json.writeValueAsString(costs.view(with)).contains("\"dueDate\":\"2026-11-05\""));
        assertEquals(DUE, json.readValue("{\"dueDate\":\"2026-11-05\"}", CompanyCost.class).dueDate());
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void theEndpointsCarryTheDueDate() {
        Map<String, Object> body = new HashMap<>(Map.of("date", "2026-10-13", "category", "SOFTWARE", "description", "Due date endpoint",
                "amountExclEur", 100, "vatRegime", "OUT_OF_SCOPE", "vatRegimeConfirmed", true, "dueDate", "2026-11-05"));
        long id = given().contentType("application/json").body(body).post("/api/costs").then().statusCode(201)
                .body("dueDate", equalTo("2026-11-05")).extract().jsonPath().getLong("id");
        try {
            given().contentType("application/json").body(Map.of("dueDate", "2026-11-20")).put("/api/costs/" + id + "/due-date")
                    .then().statusCode(200).body("dueDate", equalTo("2026-11-20")).body("description", equalTo("Due date endpoint"));
            given().contentType("application/json").body(Map.of("dueDate", "2026-10-01")).put("/api/costs/" + id + "/due-date")
                    .then().statusCode(409).body("message", equalTo("De vervaldag ligt vóór de kostdatum"));
            body.remove("dueDate");
            given().contentType("application/json").body(body).put("/api/costs/" + id)
                    .then().statusCode(200).body("dueDate", equalTo("2026-11-20"));
            Map<String, Object> clear = new HashMap<>();
            clear.put("dueDate", null);
            given().contentType("application/json").body(clear).put("/api/costs/" + id + "/due-date")
                    .then().statusCode(200).body("dueDate", nullValue());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> costs.delete(id));
        }
    }

    private LocalDate stored(long id) {
        em.flush();
        em.clear();
        return em.find(CompanyCostEntity.class, id).dueDate;
    }

    private static CompanyCost cost(String description, LocalDate dueDate) {
        return new CompanyCost(null, DAY, "SOFTWARE", description, "Vandersanden Drukwerk", new BigDecimal("100.00"),
                new BigDecimal("121.00"), new BigDecimal("21"), "F-1", null, null, null, null, null, null, VatRegime.BE, true, "BE",
                null, null, null, null, null, null, dueDate);
    }

    private static CompanyCost withDescription(CompanyCost cost, LocalDate date) {
        return new CompanyCost(cost.id(), date, cost.category(), cost.description(), cost.party(), cost.amountExclEur(),
                cost.amountInclEur(), cost.vatPct(), cost.reference(), cost.paidOn(), cost.salesChannel(), cost.notes(),
                cost.createdAt(), cost.recurringCostId(), cost.bankStatementLineId(), cost.vatRegime(), cost.vatRegimeConfirmed(),
                cost.supplierCountry(), cost.costKind(), cost.expenseType(), cost.vatDeductiblePct(), cost.taxDeductiblePct(),
                cost.depreciationYears(), cost.depreciationStart(), null);
    }
}
