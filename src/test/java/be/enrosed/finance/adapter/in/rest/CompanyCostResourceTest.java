package be.enrosed.finance.adapter.in.rest;

import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A cost as the screens get it: its fields at the top level, its payments
 * and what is paid and open; the payments drive "Betaald op".
 */
@QuarkusTest
@TestSecurity(user = "staff", roles = AdminIdentityProvider.ADMIN_ROLE)
class CompanyCostResourceTest {

    private static final String COSTS = "/api/costs";

    @Test
    void aCostComesBackAsAViewAndItsPaymentsDecideWhenItIsPaid() {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> google = new HashMap<>(Map.of("date", "2026-10-03", "category", "SOFTWARE",
                "description", "Google Workspace " + stamp, "party", "Google Ireland " + stamp,
                "amountExclEur", 100, "vatPct", 21, "vatRegime", "EU_SERVICES", "supplierCountry", "IE", "vatRegimeConfirmed", true));
        long costId = json().body(google).post(COSTS).then().statusCode(201)
                .body("amountExclEur", equalTo(100.00f))
                .body("amountInclEur", nullValue())
                .body("vatRegime", equalTo("EU_SERVICES"))
                .body("vatRegimeConfirmed", equalTo(true))
                .body("supplierCountry", equalTo("IE"))
                .body("expenseType", equalTo("STANDARD"))
                .body("vatDeductiblePct", equalTo(100))
                .body("taxDeductiblePct", equalTo(100))
                .body("depreciationYears", nullValue())
                .body("cashTotalEur", equalTo(100.00f))
                .body("openEur", equalTo(100.00f))
                .body("paidEur", equalTo(0.00f))
                .body("paidOn", nullValue())
                .body("payments", empty())
                .extract().jsonPath().getLong("id");
        try {
            json().body(Map.of("paidOn", "2026-10-05", "amountEur", 40.00, "account", "KBC"))
                    .post(COSTS + "/" + costId + "/payments").then().statusCode(201)
                    .body("id", equalTo((int) costId))
                    .body("openEur", equalTo(60.00f))
                    .body("paidEur", equalTo(40.00f))
                    .body("paidOn", nullValue())
                    .body("payments", hasSize(1))
                    .body("payments[0].amountEur", equalTo(40.00f))
                    .body("payments[0].account", equalTo("KBC"))
                    .body("payments[0].bankStatementLineId", nullValue())
                    .body("payments[0].companyCostId", equalTo((int) costId))
                    .body("payments[0].recordedAt", notNullValue());

            json().body("{}").post(COSTS + "/" + costId + "/paid").then().statusCode(200)
                    .body("openEur", equalTo(0.00f))
                    /* The server's day is the company's: Brussels. */
                    .body("paidOn", equalTo(LocalDate.now(ZoneId.of("Europe/Brussels")).toString()))
                    .body("payments", hasSize(2))
                    .body("payments[1].amountEur", equalTo(60.00f));
            json().get(COSTS + "/" + costId).then().statusCode(200).body("payments", hasSize(2)).body("openEur", equalTo(0.00f));
            json().get(COSTS + "?from=2026-10-03&to=2026-10-03").then().statusCode(200)
                    .body("find { it.id == " + costId + " }.payments.size()", equalTo(2));
            json().get(COSTS + "/" + costId + "/payments").then().statusCode(200).body("size()", equalTo(2));

            /* Settled: taking the date away with rows present is the switch-off, refused until the payments are gone. */
            Map<String, Object> settled = json().get(COSTS + "/" + costId).then().extract().jsonPath().getMap("$");
            settled.put("paidOn", null);
            json().body(settled).put(COSTS + "/" + costId).then().statusCode(409)
                    .body("message", equalTo("Verwijder eerst de betalingen"));

            long remainder = json().get(COSTS + "/" + costId).then().extract().jsonPath().getLong("payments[1].id");
            json().delete(COSTS + "/" + costId + "/payments/" + remainder).then().statusCode(200)
                    .body("id", equalTo((int) costId))
                    .body("openEur", equalTo(60.00f))
                    .body("paidOn", nullValue())
                    .body("payments", hasSize(1));

            /* Partly paid: the view the screen holds (no date, the extra fields) goes back as it came; the rows stay, the text changes. */
            Map<String, Object> view = json().get(COSTS + "/" + costId).then().extract().jsonPath().getMap("$");
            view.put("description", "Google Workspace oktober " + stamp);
            json().body(view).put(COSTS + "/" + costId).then().statusCode(200)
                    .body("description", equalTo("Google Workspace oktober " + stamp))
                    .body("payments[0].paidOn", equalTo("2026-10-05"))
                    .body("paidOn", nullValue())
                    .body("openEur", equalTo(60.00f));
            view.put("paidOn", "2026-10-09");
            json().body(view).put(COSTS + "/" + costId).then().statusCode(200)
                    /* The single payment, not on the bank, moves to the form's date. */
                    .body("payments[0].paidOn", equalTo("2026-10-09"))
                    .body("paidOn", nullValue())
                    .body("openEur", equalTo(60.00f));

            /* The last payment goes: the cost is open again, not a cost paid before the rows existed. */
            long only = json().get(COSTS + "/" + costId).then().extract().jsonPath().getLong("payments[0].id");
            json().delete(COSTS + "/" + costId + "/payments/" + only).then().statusCode(200)
                    .body("openEur", equalTo(100.00f))
                    .body("paidEur", equalTo(0.00f))
                    .body("paidOn", nullValue())
                    .body("payments", empty());
            json().get(COSTS + "/" + costId).then().statusCode(200).body("openEur", equalTo(100.00f)).body("paidOn", nullValue());

            json().body(Map.of("date", "2026-10-03", "category", "SOFTWARE", "description", "Zonder regeling " + stamp, "amountExclEur", 10))
                    .post(COSTS).then().statusCode(409).body("message", equalTo("Kies de btw-regeling"));
        } finally {
            json().delete(COSTS + "/" + costId).then().statusCode(204);
        }
        json().get(COSTS + "/" + costId).then().statusCode(404);
    }

    @Test
    void theSwitchWritesOneFullPayment() {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        long costId = json().body(Map.of("date", "2026-10-12", "category", "HUUR", "description", "Magazijn " + stamp,
                        "amountExclEur", 82.64, "amountInclEur", 100, "vatPct", 21, "vatRegime", "BE", "paidOn", "2026-10-12"))
                .post(COSTS).then().statusCode(201)
                .body("paidOn", equalTo("2026-10-12"))
                .body("supplierCountry", equalTo("BE"))
                .body("cashTotalEur", equalTo(100.00f))
                .body("openEur", equalTo(0.00f))
                .body("payments", hasSize(1))
                .body("payments[0].amountEur", equalTo(100.00f))
                .body("payments[0].note", equalTo("Betaald gezet"))
                .extract().jsonPath().getLong("id");
        try {
            json().body(Map.of("paidOn", "2026-10-12")).post(COSTS + "/" + costId + "/paid").then().statusCode(409)
                    .body("message", equalTo("Deze kost is al volledig betaald"));
        } finally {
            json().delete(COSTS + "/" + costId).then().statusCode(204);
        }
    }

    /** The class runs as staff; this one as a logged-in visitor without the admin role, which the resource refuses. */
    @Test
    @TestSecurity(user = "visitor", roles = "viewer")
    void callersWithoutTheAdminRoleAreRefused() {
        json().get(COSTS).then().statusCode(anyOf(is(401), is(403)));
        json().get(COSTS + "/1/payments").then().statusCode(anyOf(is(401), is(403)));
        json().body(Map.of("paidOn", "2026-10-05", "amountEur", 40.00)).post(COSTS + "/1/payments").then().statusCode(anyOf(is(401), is(403)));
    }

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }
}
