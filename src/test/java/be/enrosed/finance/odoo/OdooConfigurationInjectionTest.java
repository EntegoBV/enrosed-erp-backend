package be.enrosed.finance.odoo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real CDI injection must preserve fields used by the connector; this profile never makes a remote request. */
@QuarkusTest
@TestProfile(OdooConfigurationInjectionTest.EnabledFixture.class)
class OdooConfigurationInjectionTest {
    @Inject OdooConfiguration configuration;
    @Inject OdooOutboundConfiguration outbound;

    public static class EnabledFixture implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("enrosed.odoo.enabled","true"),
                    Map.entry("enrosed.odoo.base-url","https://odoo-fixture.invalid"),
                    Map.entry("enrosed.odoo.database","fixture-database"),
                    Map.entry("enrosed.odoo.api-key","not-a-real-api-key"),
                    Map.entry("enrosed.odoo.company-id","7"),
                    Map.entry("enrosed.odoo.journal-ids","8,9"),
                    Map.entry("enrosed.odoo.outbound-enabled","true"),
                    Map.entry("enrosed.odoo.send-enabled","true"),
                    Map.entry("enrosed.odoo.sales-journal-id","10"),
                    Map.entry("enrosed.odoo.income-account-id","11"),
                    Map.entry("enrosed.odoo.vat-tax-ids","{\"BINNENLAND\":12}"));
        }
    }

    @Test void injectedConfigurationFieldsAgreeWithValidatedConfiguration() {
        assertNull(configuration.problem());
        assertDoesNotThrow(()->outbound.requireMapping("BINNENLAND"));
        assertAll(
                ()->assertTrue(configuration.enabled),
                ()->assertEquals("https://odoo-fixture.invalid",configuration.baseUrl),
                ()->assertEquals("fixture-database",configuration.database),
                ()->assertEquals("not-a-real-api-key",configuration.apiKey),
                ()->assertEquals(7L,configuration.companyId),
                ()->assertEquals(Set.of(8L,9L),configuration.journalIds),
                ()->assertTrue(outbound.enabled),
                ()->assertTrue(outbound.sendEnabled),
                ()->assertEquals(10L,outbound.journalId),
                ()->assertEquals(11L,outbound.incomeAccountId),
                ()->assertEquals(Map.of("BINNENLAND",12L),outbound.taxIds),
                ()->assertTrue(outbound.validTaxMap));
    }

    @Test @TestSecurity(user="test-admin",roles="admin")
    void statusReportsEnabledConfigurationWithoutCheckingTheRemoteConnection() {
        given().get("/api/odoo/status").then().statusCode(200)
                .body("enabled",is(true)).body("configured",is(true)).body("outboundEnabled",is(true))
                .body("code",is("NOT_CHECKED")).body("apiKey",nullValue());
    }
}
