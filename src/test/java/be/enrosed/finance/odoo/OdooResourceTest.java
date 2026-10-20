package be.enrosed.finance.odoo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OdooResourceTest {
    @Inject OdooSyncStateStore store;
    @Test void configurationIsNotPublic() {
        given().get("/api/odoo/status").then().statusCode(401);
        given().get("/api/odoo/tica-creditnotes/status").then().statusCode(401);
        given().get("/api/odoo/linc-costs/status").then().statusCode(401);
    }
    @Test @TestSecurity(user="test-admin",roles="admin")
    void disabledConfigurationAndExplicitSyncRefusalAreHonestAndSecretFree() {
        given().get("/api/odoo/status").then().statusCode(200).body("enabled",is(false)).body("code",is("DISABLED"))
                .body("outboundEnabled",is(false)).body("apiKey",nullValue());
        given().contentType("application/json").post("/api/odoo/bank-sync").then().statusCode(503).body("code",is("DISABLED"));
        given().get("/api/odoo/tica-creditnotes/status").then().statusCode(200).body("enabled",is(false)).body("pendingCount",is(0));
        given().get("/api/odoo/linc-costs/status").then().statusCode(200).body("configured",is(false)).body("lastCode",is("DISABLED"));
    }
    @Test void persistentLeaseRejectsConcurrentOwnerAndCanBeRetriedAfterFinish() {
        String key="test-"+UUID.randomUUID(),owner=UUID.randomUUID().toString();
        store.acquire(key,owner);
        assertEquals("BUSY",assertThrows(OdooFailure.class,()->store.acquire(key,"second")).code);
        assertEquals("LEASE_LOST",assertThrows(OdooFailure.class,()->store.renew(key,"second")).code);
        store.renew(key,owner);store.finish(key,owner,"{}");
        store.acquire(key,"second");store.finish(key,"second","{\"complete\":true}");
        assertEquals("{\"complete\":true}",store.snapshot(key).lastSyncJson());
    }

    @Test void incomingImportAndSourceAreNotPublic() {
        given().get("/api/bank-statements/1/odoo-purchase-invoices").then().statusCode(401);
        given().get("/api/bank-statements/1/odoo-purchase-invoices/10/preview").then().statusCode(401);
        given().contentType("application/json").body("{}").post("/api/bank-statements/1/odoo-purchase-invoice").then().statusCode(401);
        given().get("/api/costs/1/odoo-source").then().statusCode(401);
    }
    @Test @TestSecurity(user="staff-fixture",roles="staff") void incomingImportRequiresAdmin() {
        given().get("/api/bank-statements/1/odoo-purchase-invoices").then().statusCode(403);
        given().contentType("application/json").body("{}").post("/api/bank-statements/1/odoo-purchase-invoice").then().statusCode(403);
        given().get("/api/costs/1/odoo-source").then().statusCode(403);
    }
    @Test @TestSecurity(user="test-admin",roles="admin") void importRoutesClearlyRejectUnconfiguredProvider() {
        given().get("/api/bank-statements/1/odoo-purchase-invoices").then().statusCode(503).body("code",is("DISABLED"));
        given().contentType("application/json").body("{}").post("/api/bank-statements/1/odoo-purchase-invoice").then().statusCode(503).body("code",is("DISABLED"));
    }
}
