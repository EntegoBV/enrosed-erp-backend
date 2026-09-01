package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.DiscountTierService;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestSecurity(user = "emre", roles = AdminIdentityProvider.ADMIN_ROLE)
class DiscountTierResourceHttpTest {

    @InjectMock
    DiscountTierService tiers;

    @Test
    void productLineScheduleHasItsOwnGetEndpoint() {
        when(tiers.listForProduct(42L)).thenReturn(List.of(
                new DiscountTier(7L, TierScope.LINE, 120, new BigDecimal("4.5"), 42L)));

        given()
                .when().get("/api/discount-tiers/LINE/products/42")
                .then().statusCode(200)
                .body("[0].scope", equalTo("LINE"))
                .body("[0].productId", equalTo(42))
                .body("[0].minQuantity", equalTo(120))
                .body("[0].percent", equalTo(4.5f));
    }

    @Test
    void productLineSchedulePutUsesThePathProduct() {
        when(tiers.replaceForProduct(eq(42L), anyList())).thenReturn(List.of(
                new DiscountTier(8L, TierScope.LINE, 250, new BigDecimal("6"), 42L)));

        given().contentType("application/json")
                .body("[{\"minQuantity\":250,\"percent\":6}]")
                .when().put("/api/discount-tiers/LINE/products/42")
                .then().statusCode(200)
                .body("[0].productId", equalTo(42))
                .body("[0].minQuantity", equalTo(250));

        verify(tiers).replaceForProduct(eq(42L),
                org.mockito.ArgumentMatchers.argThat(replacement -> replacement.size() == 1
                        && replacement.getFirst().minQuantity() == 250));
    }
}
