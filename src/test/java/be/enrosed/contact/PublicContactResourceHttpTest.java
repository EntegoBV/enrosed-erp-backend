package be.enrosed.contact;

import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormServiceUnavailableException;
import be.enrosed.publicform.PublicFormValidationException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class PublicContactResourceHttpTest {
    @InjectMock ContactInquiryService contacts;
    @InjectMock PublicFormSecurityService security;
    @InjectMock PublicFormRateLimiter rateLimiter;
    @InjectMock PublicFormIdempotencyService idempotency;
    @InjectMock ClientIdentityResolver identities;

    @BeforeEach
    void allowNormalRequests() {
        when(identities.resolve(any())).thenReturn("127.0.0.1");
        when(idempotency.replay(eq(PublicFormPurpose.CONTACT), nullable(String.class),
                anyString(), eq(ContactDtos.Response.class))).thenReturn(Optional.empty());
        when(idempotency.executeAccepted(eq(PublicFormPurpose.CONTACT), nullable(String.class),
                anyString(), any(), anyString(), eq(ContactDtos.Response.class), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ContactDtos.Response> action = invocation.getArgument(6);
                    return action.get();
                });
        when(contacts.submit(any())).thenReturn(new ContactDtos.Response(
                "CNT-0123456789ABCDEF0123", "RECEIVED"));
    }

    @Test
    void anonymousContactSubmissionReturnsOpaqueAcceptedReference() {
        given().contentType("application/json")
                .header("Idempotency-Key", "contact-browser-1234")
                .body(validRequestJson(""))
                .when().post("/api/v1/public/contact/requests")
                .then().statusCode(202)
                .header("Cache-Control", "no-store")
                .body("reference", equalTo("CNT-0123456789ABCDEF0123"))
                .body("status", equalTo("RECEIVED"));

        verify(contacts).validate(any());
        verify(security).verifySubmission(eq(PublicFormPurpose.CONTACT),
                eq("form-token"), eq("challenge-token"));
        verify(contacts).submit(any());
    }

    @Test
    void filledHoneypotSilentlyAcceptsWithoutPersistenceOrChallenge() {
        given().contentType("application/json")
                .body("{\"website\":\"https://bot.example\"}")
                .when().post("/api/v1/public/contact/requests")
                .then().statusCode(202)
                .header("Cache-Control", "no-store")
                .body("reference", startsWith("CNT-"))
                .body("status", equalTo("RECEIVED"));

        verify(contacts, never()).validate(any());
        verify(contacts, never()).submit(any());
        verifyNoInteractions(security);
        verify(idempotency, never()).executeAccepted(any(), any(), anyString(), any(),
                any(), any(), any());
    }

    @Test
    void invalidChallengesCannotConsumeVictimEmailQuotaAndLaterValidRequestWorks() {
        doThrow(new PublicFormValidationException(Map.of("challengeToken", "INVALID")))
                .when(security).verifySubmission(any(), anyString(), anyString());

        for (int attempt = 0; attempt < 3; attempt++) {
            given().contentType("application/json")
                    .header("Idempotency-Key", "invalid-challenge-" + attempt)
                    .body(validRequestJson(""))
                    .when().post("/api/v1/public/contact/requests")
                    .then().statusCode(422)
                    .header("Cache-Control", "no-store")
                    .body("fieldErrors.challengeToken", equalTo("INVALID"));
        }
        verify(idempotency, never()).executeAccepted(any(), any(), anyString(), any(),
                any(), any(), any());

        reset(security);
        given().contentType("application/json")
                .header("Idempotency-Key", "valid-after-attacks")
                .body(validRequestJson(""))
                .when().post("/api/v1/public/contact/requests")
                .then().statusCode(202);
        verify(idempotency).executeAccepted(any(), any(), anyString(), any(),
                eq("victim@example.com"), any(), any());
    }

    @Test
    void challengeProviderOutageFailsClosedWithoutPersistence() {
        doThrow(new PublicFormServiceUnavailableException()).when(security)
                .verifySubmission(any(), anyString(), anyString());

        given().contentType("application/json")
                .body(validRequestJson(""))
                .when().post("/api/v1/public/contact/requests")
                .then().statusCode(503)
                .header("Retry-After", "30")
                .header("Cache-Control", "no-store")
                .body("code", equalTo("SERVICE_UNAVAILABLE"));

        verify(contacts, never()).submit(any());
        verify(idempotency, never()).executeAccepted(any(), any(), anyString(), any(),
                any(), any(), any());
    }

    @Test
    void contactBodyLimitCannotBeBypassedByTrailingSlashOrMatrixParameter() {
        String oversized = "{\"message\":\"" + "x".repeat(17 * 1024) + "\"}";
        for (String path : new String[]{"/api/v1/public/contact/requests/",
                "/api/v1/public/contact/requests;v=1"}) {
            given().urlEncodingEnabled(false).contentType("application/json").body(oversized)
                    .when().post(path)
                    .then().statusCode(413)
                    .header("Cache-Control", "no-store")
                    .body("code", equalTo("PAYLOAD_TOO_LARGE"));
        }
        verifyNoInteractions(contacts);
    }

    private static String validRequestJson(String website) {
        return """
                {
                  "language":"EN",
                  "contactName":"Ana",
                  "email":"victim@example.com",
                  "companyName":"Buyer BV",
                  "topic":"GENERAL",
                  "message":"A useful contact message",
                  "privacyAccepted":true,
                  "sourcePage":"/contact",
                  "website":"%s",
                  "formToken":"form-token",
                  "challengeToken":"challenge-token"
                }
                """.formatted(website);
    }
}
