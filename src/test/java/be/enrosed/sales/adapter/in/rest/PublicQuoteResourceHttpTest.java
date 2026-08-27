package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class PublicQuoteResourceHttpTest {
    @InjectMock PublicQuoteService quotes;
    @InjectMock PublicQuoteAbuseGuard abuse;

    @BeforeEach
    void allowNormalRequests() {
        when(abuse.submitOnce(any(), anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<PublicQuoteDtos.SubmissionResponse> action = invocation.getArgument(2);
            return action.get();
        });
    }

    @Test
    void configurationIsPublicWithoutAdminAuthentication() {
        when(quotes.configuration("EN")).thenReturn(new PublicQuoteDtos.ConfigurationResponse(
                "EUR", "NET_EXCL_VAT", "FULL_CARTONS", List.of("DELIVERY", "PICKUP"),
                "ESTIMATE_NOT_BINDING", List.of(), List.of()));

        given().queryParam("language", "EN")
                .when().get("/api/v1/public/quotes/configuration")
                .then().statusCode(200)
                .body("currency", equalTo("EUR"));
    }

    @Test
    void submitReturnsCreatedAndNeverNeedsClientPrices() {
        PublicQuoteDtos.SubmissionResponse response = new PublicQuoteDtos.SubmissionResponse(
                "ENR-2026-0041", "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                "FINAL_QUOTE_FOLLOWS", null);
        when(quotes.submit(any())).thenReturn(response);

        given().contentType("application/json")
                .header("Idempotency-Key", "browser-12345678")
                .body(validSubmitJson())
                .when().post("/api/v1/public/quotes/requests")
                .then().statusCode(201)
                .body("reference", equalTo("ENR-2026-0041"))
                .body("bindingStatus", equalTo("REQUEST_RECEIVED_NOT_BINDING"));
        verify(quotes).submit(argThat(request -> request.items().getFirst().cartons() == 2));
    }

    @Test
    void previewExposesOnlyServerCalculatedDiscountBreakdown() {
        PublicQuoteDtos.EstimateResponse response = new PublicQuoteDtos.EstimateResponse(
                "EUR", "NET_EXCL_VAT", "PICKUP", null,
                "ESTIMATE_NOT_BINDING", "FINAL_QUOTE_FOLLOWS",
                List.of(new PublicQuoteDtos.LineEstimate(1L, "SKU-1", 2, 24, 12,
                        decimal("10"), decimal("5"), decimal("228"), true)),
                new PublicQuoteDtos.ShippingEstimate("PICKUP", "PICKUP",
                        decimal("0"), decimal("0"), decimal("0"), 0, 2),
                new PublicQuoteDtos.TotalsEstimate(
                        decimal("240"), decimal("12"), decimal("228"),
                        decimal("3"), decimal("6.84"), decimal("221.16"),
                        decimal("0"), decimal("221.16"), decimal("21"),
                        decimal("46.44"), decimal("267.60"), "DOMESTIC", true),
                new PublicQuoteDtos.ValidationSummary(true, false, true,
                        decimal("100"), decimal("0"), List.of()));
        when(quotes.preview(any())).thenReturn(response);

        given().contentType("application/json")
                .body("{\"language\":\"EN\",\"items\":[{\"productId\":1,\"cartons\":2}]}")
                .when().post("/api/v1/public/quotes/preview")
                .then().statusCode(200)
                .body("lines[0].discountPct", equalTo(5))
                .body("totals.goodsGrossNet", equalTo(240))
                .body("totals.lineDiscountNet", equalTo(12))
                .body("totals.orderDiscountPct", equalTo(3))
                .body("totals.orderDiscountNet", equalTo(6.84f));
    }

    @Test
    void validationAndRateLimitHaveGenericJsonContracts() {
        when(quotes.preview(any())).thenThrow(
                new PublicQuoteValidationException(Map.of("items", "REQUIRED")));
        given().contentType("application/json").body("{}")
                .when().post("/api/v1/public/quotes/preview")
                .then().statusCode(422)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.items", equalTo("REQUIRED"));

        reset(quotes, abuse);
        doThrow(new PublicQuoteRateLimitException(42)).when(abuse).checkPreview(anyString());
        given().contentType("application/json").body("{}")
                .header("Origin", "http://localhost:4334")
                .when().post("/api/v1/public/quotes/preview")
                .then().statusCode(429)
                .header("Retry-After", "42")
                .header("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsString("Retry-After"))
                .header("Cache-Control", "no-store")
                .body("code", equalTo("RATE_LIMITED"));
    }

    @Test
    void conflictingIdempotencyKeyIsAConflictAndMalformedJsonIsActionable() {
        doThrow(new PublicQuoteValidationException(Map.of("idempotencyKey", "CONFLICT")))
                .when(abuse).submitOnce(any(), anyString(), any());

        given().contentType("application/json")
                .header("Idempotency-Key", "browser-12345678")
                .body(validSubmitJson())
                .when().post("/api/v1/public/quotes/requests")
                .then().statusCode(409)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.idempotencyKey", equalTo("CONFLICT"));

        reset(quotes, abuse);
        given().contentType("application/json").body("{")
                .when().post("/api/v1/public/quotes/requests")
                .then().statusCode(400)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("INVALID_REQUEST"));
        given().contentType("application/json").body("{\"items\":\"not-a-list\"}")
                .when().post("/api/v1/public/quotes/requests")
                .then().statusCode(400)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("INVALID_REQUEST"));
        verifyNoInteractions(quotes);
    }

    @Test
    void anonymousJsonBodyHasDedicated64KbLimit() {
        String oversized = "{\"padding\":\"" + "x".repeat(65 * 1024) + "\"}";
        given().contentType("application/json").body(oversized)
                .when().post("/api/v1/public/quotes/requests")
                .then().statusCode(413)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("PAYLOAD_TOO_LARGE"));
        verifyNoInteractions(quotes);
    }

    private static String validSubmitJson() {
        return """
                {
                  "language":"EN",
                  "fulfillment":"DELIVERY",
                  "destination":{"countryCode":"BE","postalCode":"2400","city":"Mol","address":"Street 1"},
                  "items":[{"productId":1,"cartons":2}],
                  "companyCountryCode":"BE",
                  "companyName":"Buyer BV",
                  "contactName":"Ana",
                  "email":"ana@example.com",
                  "privacyAccepted":true,
                  "website":""
                }
                """;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
