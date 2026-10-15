package be.enrosed.finance.forecast;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.company.CompanyProfileEntity;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CashForecastSettingsResourceTest {
    @Inject CashForecastSettingsService settings;
    @Inject CompanyProfileService company;
    @Inject EntityManager em;

    @Test @TestTransaction
    void theDefaultIsTenThousandUntilABufferIsStoredAndNullGoesBack() {
        clear();
        assertEquals(new CashForecastSettingsService.Settings(new BigDecimal("10000.00"), false), settings.get());
        assertEquals(new CashForecastSettingsService.Settings(new BigDecimal("12500.00"), true),
                settings.save(new CashForecastSettingsService.Request(new BigDecimal("12500"))));
        assertEquals(new CashForecastSettingsService.Settings(new BigDecimal("12500.00"), true), settings.get());
        assertEquals(new CashForecastSettingsService.Settings(new BigDecimal("0.00"), true),
                settings.save(new CashForecastSettingsService.Request(BigDecimal.ZERO)), "zero is a buffer of its own");
        assertEquals(new CashForecastSettingsService.Settings(new BigDecimal("10000.00"), false),
                settings.save(new CashForecastSettingsService.Request(null)));
        assertNull(em.find(CompanyProfileEntity.class, 1L).cashBufferEur);
    }

    @Test @TestTransaction
    void refusesAnAmountOutsideTheRangeOrWithFractionsOfCents() {
        assertEquals(new BigDecimal("10000000.00"), settings.save(new CashForecastSettingsService.Request(new BigDecimal("10000000")))
                .cashBufferEur(), "the upper bound itself is allowed");
        for (String amount : new String[] {"-0.01", "10000000.01"}) {
            var refusal = assertThrows(BusinessRuleException.class,
                    () -> settings.save(new CashForecastSettingsService.Request(new BigDecimal(amount))));
            assertEquals("Geef een buffer tussen € 0 en € 10.000.000", refusal.getMessage());
        }
        var refusal = assertThrows(BusinessRuleException.class,
                () -> settings.save(new CashForecastSettingsService.Request(new BigDecimal("12500.005"))));
        assertEquals("Een bedrag heeft maximaal twee decimalen", refusal.getMessage());
    }

    @Test @TestTransaction
    void theCompanyProfileSaveKeepsTheBuffer() {
        settings.save(new CashForecastSettingsService.Request(new BigDecimal("7500")));
        company.save(company.get());
        em.flush(); em.clear();
        assertEquals(new CashForecastSettingsService.Settings(new BigDecimal("7500.00"), true), settings.get());
    }

    @Test @TestSecurity(user = "emre", roles = "admin")
    void theEndpointAnswersTheSettingsShape() {
        BigDecimal before = QuarkusTransaction.requiringNew().call(() -> {
            var row = em.find(CompanyProfileEntity.class, 1L);
            return row == null ? null : row.cashBufferEur;
        });
        try {
            given().contentType("application/json").body(Map.of("cashBufferEur", 12500.00)).put("/api/cash-forecast/settings")
                    .then().statusCode(200).body("cashBufferEur", equalTo(12500.00f)).body("cashBufferStored", equalTo(true));
            given().get("/api/cash-forecast/settings")
                    .then().statusCode(200).body("cashBufferEur", equalTo(12500.00f)).body("cashBufferStored", equalTo(true));
            given().contentType("application/json").body(Map.of("cashBufferEur", -5)).put("/api/cash-forecast/settings")
                    .then().statusCode(409).body("message", equalTo("Geef een buffer tussen € 0 en € 10.000.000"));
            Map<String, Object> cleared = new HashMap<>();
            cleared.put("cashBufferEur", null);
            given().contentType("application/json").body(cleared).put("/api/cash-forecast/settings")
                    .then().statusCode(200).body("cashBufferEur", equalTo(10000.00f)).body("cashBufferStored", equalTo(false));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                var row = em.find(CompanyProfileEntity.class, 1L);
                if (row != null) row.cashBufferEur = before;
            });
        }
    }

    @Test
    void anonymousCannotReadTheSettings() {
        given().get("/api/cash-forecast/settings").then().statusCode(401);
    }

    private void clear() {
        var row = em.find(CompanyProfileEntity.class, 1L);
        if (row != null) row.cashBufferEur = null;
        em.flush();
    }
}
