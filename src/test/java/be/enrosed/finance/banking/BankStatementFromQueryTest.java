package be.enrosed.finance.banking;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/** ?from= keeps the recent lines and every line still to link, older ones included; the Kasprognose needs both. */
@QuarkusTest
class BankStatementFromQueryTest {
    private static final String ACCOUNT = "BE55000011112222";
    @Inject BankStatementService bank;
    @Inject BankLineClassificationService classifications;
    @Inject CompanyCostService costs;

    @Test @TestTransaction
    void keepsEveryOpenOlderLineAndDropsTheLinkedAndBookedOnes() {
        long olderOpen = line("-100.00", "2026-05-02T10:00:00Z");
        long olderLinked = line("-121.00", "2026-06-10T10:00:00Z");
        bank.recordCostPayment(olderLinked, new BankStatementService.CostAllocation(cost("121.00")));
        long olderBooked = line("-50.00", "2026-06-20T10:00:00Z");
        classifications.classify(olderBooked, new BankLineClassificationService.Request("PRIVATE", null, null, null));
        long firstHour = line("-80.00", "2026-06-30T22:30:00Z");
        bank.recordCostPayment(firstHour, new BankStatementService.CostAllocation(cost("80.00")));
        long recentOpen = line("200.00", "2026-07-15T10:00:00Z");

        var all = ids(bank.list(null));
        assertTrue(all.containsAll(Set.of(olderOpen, olderLinked, olderBooked, firstHour, recentOpen)), "without from every line");
        var listed = bank.list("2026-07-01").stream().filter(row -> Set.of(olderOpen, olderLinked, olderBooked, firstHour, recentOpen)
                .contains(row.id)).toList();
        assertEquals(List.of(recentOpen, firstHour, olderOpen), listed.stream().map(row -> row.id).toList(),
                "newest first; 00:30 in Brussels on the first counts as that day; the old open line stays");
        assertTrue(listed.get(1).allocated, "the lines keep their described allocation");
        assertFalse(listed.get(2).allocated);
    }

    @Test @TestTransaction
    void anInvalidDayIsRefused() {
        line("-1.00", "2026-07-15T10:00:00Z");
        for (String invalid : List.of("2026-13-01", "01/07/2026", "gisteren")) {
            assertEquals("Ongeldige datum voor from (JJJJ-MM-DD)",
                    assertThrows(BusinessRuleException.class, () -> bank.list(invalid)).getMessage());
        }
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void theQueryParameterReachesTheList() {
        given().get("/api/bank-statements?from=juli").then().statusCode(409)
                .body("message", equalTo("Ongeldige datum voor from (JJJJ-MM-DD)"));
        given().get("/api/bank-statements?from=2099-01-01").then().statusCode(200);
    }

    private static Set<Long> ids(List<BankStatementLineEntity> rows) {
        return Set.copyOf(rows.stream().map(row -> row.id).toList());
    }

    private long line(String amount, String at) {
        return bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(), ACCOUNT,
                new BigDecimal(amount), Instant.parse(at), "Europe/Brussels", "From query", "Party", Instant.parse(at), false)).id();
    }

    private long cost(String amount) {
        return costs.create(new CompanyCost(null, LocalDate.of(2026, 5, 1), "SOFTWARE", "From fixture", "Party",
                new BigDecimal(amount), null, null, null, null, null, null, null, null, null, VatRegime.OUT_OF_SCOPE, true, null,
                null, null, null, null, null, null, null)).id();
    }
}
