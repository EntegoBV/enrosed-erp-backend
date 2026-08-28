package be.enrosed.contact;

import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ContactInquiryResourceHttpTest {
    @InjectMock ContactInquiryService contacts;

    @Test
    void staffEndpointsAreClosedToAnonymousUsers() {
        given().when().get("/api/contact-inquiries")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
        given().contentType("application/json").body("{\"status\":\"READ\"}")
                .when().patch("/api/contact-inquiries/42/status")
                .then().statusCode(anyOf(equalTo(401), equalTo(403)));
    }

    @Test
    @TestSecurity(user = "staff", roles = AdminIdentityProvider.ADMIN_ROLE)
    void adminListIsBoundedAndNeverCacheable() {
        when(contacts.list("NEW", 1, 25)).thenReturn(List.of(view("NEW")));

        given().queryParam("status", "NEW").queryParam("page", 1).queryParam("size", 25)
                .when().get("/api/contact-inquiries")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("[0].reference", equalTo("CNT-0123456789ABCDEF0123"))
                .body("[0].notificationStatus", equalTo("PENDING"));
        verify(contacts).list("NEW", 1, 25);
    }

    @Test
    @TestSecurity(user = "staff", roles = AdminIdentityProvider.ADMIN_ROLE)
    void adminCanPatchStatusAndResponseIsNeverCacheable() {
        when(contacts.updateStatus(org.mockito.ArgumentMatchers.eq(42L), any()))
                .thenReturn(view("READ"));

        given().contentType("application/json").body("{\"status\":\"READ\"}")
                .when().patch("/api/contact-inquiries/42/status")
                .then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("status", equalTo("READ"));
    }

    private static ContactDtos.View view(String status) {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        return new ContactDtos.View(42L, "CNT-0123456789ABCDEF0123", status,
                "EN", "GENERAL", "Ana", "ana@example.com", "Buyer BV", null,
                "A useful message", "/contact", now, "2026-08-28", now, now,
                "PENDING", 0, null, null);
    }
}
