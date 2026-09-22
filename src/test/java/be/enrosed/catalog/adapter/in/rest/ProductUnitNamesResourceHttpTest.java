package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class ProductUnitNamesResourceHttpTest {

    @Test
    void theErpPickListComesInDutchInFileOrderAndIsNotMistakenForAProductId() {
        given()
                .when().get("/api/products/unit-names")
                .then().statusCode(200)
                .body("", hasSize(8))
                .body("[0].key", equalTo("stuk"))
                .body("[0].one", equalTo("stuk"))
                .body("[0].other", equalTo("stuks"))
                .body("[0].per", equalTo("per stuk"))
                .body("[0].short", equalTo("st."))
                .body("[1].key", equalTo("bowl"))
                .body("[1].one", equalTo("bowl"))
                .body("[1].other", equalTo("bowls"))
                .body("[1].per", equalTo("per bowl"))
                .body("[1].short", equalTo("bowls"))
                .body("[2].per", equalTo("per stolp"))
                .body("[1].shortForm", nullValue());
    }
}
