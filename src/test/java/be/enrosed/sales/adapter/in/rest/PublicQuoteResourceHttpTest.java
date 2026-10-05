package be.enrosed.sales.adapter.in.rest;

import be.enrosed.account.CustomerAccountService;
import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.sales.application.*;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.Language;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class PublicQuoteResourceHttpTest {
    @InjectMock PublicQuoteService quotes;
    @InjectMock PublicFormSecurityService security;
    @InjectMock PublicFormRateLimiter rateLimiter;
    @InjectMock PublicFormIdempotencyService idempotency;
    @InjectMock ClientIdentityResolver identities;
    @Inject WebsiteQuoteSettingsService quoteSettings;
    @Inject CustomerAccountService accounts;
    @Inject CustomerService customers;

    @AfterEach
    void restorePriceVisibility() {
        quoteSettings.update(true);
    }

    @BeforeEach
    void allowNormalRequests() {
        quoteSettings.update(true);
        when(identities.resolve(any())).thenReturn("127.0.0.1");
        when(idempotency.replay(any(), nullable(String.class), anyString(), any()))
                .thenReturn(Optional.empty());
        when(idempotency.executeAccepted(any(), nullable(String.class), anyString(), any(),
                anyString(), any(), any()))
                .thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<PublicQuoteDtos.SubmissionResponse> action = invocation.getArgument(6);
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
                .header("Cache-Control", "no-store")
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
    void hidingPricesProtectsConfigurationPreviewAndNewSubmission() {
        quoteSettings.update(false);
        when(quotes.configuration("EN")).thenReturn(new PublicQuoteDtos.ConfigurationResponse(
                "EUR", "NET_EXCL_VAT", "FULL_CARTONS", List.of("DELIVERY"), "ESTIMATE_NOT_BINDING",
                List.of(new PublicQuoteDtos.CountryOption("BE", "Belgium", decimal("100"), 2)),
                List.of(new PublicQuoteDtos.ProductPrice(1L, decimal("10"), true, 12))));
        when(quotes.preview(any())).thenReturn(PublicQuotePriceVisibilityTest.pricedEstimate());
        when(quotes.submit(any())).thenReturn(new PublicQuoteDtos.SubmissionResponse("WEB-123", "RECEIVED",
                "REQUEST_RECEIVED_NOT_BINDING", "FINAL_QUOTE_FOLLOWS", PublicQuotePriceVisibilityTest.pricedEstimate()));
        given().get("/api/v1/public/quotes/configuration").then().statusCode(200)
                .body("pricesVisible", equalTo(false))
                .body("countries[0].minimumOrderNet", org.hamcrest.Matchers.nullValue())
                .body("products[0].unitPriceNet", org.hamcrest.Matchers.nullValue())
                .body("products[0].piecesPerCarton", equalTo(12));
        given().contentType("application/json").body("{}").post("/api/v1/public/quotes/preview")
                .then().statusCode(200).body("pricesVisible", equalTo(false))
                .body("lines[0].unitPriceNet", org.hamcrest.Matchers.nullValue())
                .body("shipping.totalNet", org.hamcrest.Matchers.nullValue())
                .body("totals.totalNet", org.hamcrest.Matchers.nullValue())
                .body("validation.canSubmit", equalTo(true));
        given().contentType("application/json").body(validSubmitJson()).post("/api/v1/public/quotes/requests")
                .then().statusCode(201).body("reference", equalTo("WEB-123"))
                .body("estimate.pricesVisible", equalTo(false))
                .body("estimate.lines[0].unitPriceNet", org.hamcrest.Matchers.nullValue())
                .body("estimate.validation.minimumShortfallNet", org.hamcrest.Matchers.nullValue());
        verify(quotes).submit(any());
    }

    @Test
    void hidingAfterSubmissionRedactsAnExistingPricedIdempotencyReplay() {
        var saved = new PublicQuoteDtos.SubmissionResponse("WEB-OLD", "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                "FINAL_QUOTE_FOLLOWS", PublicQuotePriceVisibilityTest.pricedEstimate());
        when(idempotency.replay(any(), nullable(String.class), anyString(), any())).thenReturn(Optional.of(saved));
        quoteSettings.update(false);
        given().contentType("application/json").header("Idempotency-Key", "existing-submission-123")
                .body(validSubmitJson()).post("/api/v1/public/quotes/requests")
                .then().statusCode(201).body("reference", equalTo("WEB-OLD"))
                .body("estimate.pricesVisible", equalTo(false))
                .body("estimate.lines[0].unitPriceNet", org.hamcrest.Matchers.nullValue())
                .body("estimate.shipping.totalNet", org.hamcrest.Matchers.nullValue())
                .body("estimate.totals.totalInclVat", org.hamcrest.Matchers.nullValue())
                .body("estimate.validation.meetsMinimum", org.hamcrest.Matchers.nullValue());
        verifyNoInteractions(quotes, security);
        quoteSettings.update(true);
        given().contentType("application/json").header("Idempotency-Key", "existing-submission-123")
                .body(validSubmitJson()).post("/api/v1/public/quotes/requests")
                .then().statusCode(201).body("estimate.pricesVisible", equalTo(true))
                .body("estimate.lines[0].unitPriceNet", equalTo(123.45f));
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

        reset(quotes);
        doThrow(new PublicQuoteRateLimitException(42)).when(rateLimiter)
                .checkIp(eq(PublicFormAction.QUOTE_PREVIEW), anyString());
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
                .when(idempotency).replay(eq(PublicFormPurpose.QUOTE), anyString(),
                        anyString(), eq(PublicQuoteDtos.SubmissionResponse.class));

        given().contentType("application/json")
                .header("Idempotency-Key", "browser-12345678")
                .body(validSubmitJson())
                .when().post("/api/v1/public/quotes/requests")
                .then().statusCode(409)
                .header("Cache-Control", "no-store")
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.idempotencyKey", equalTo("CONFLICT"));

        reset(quotes);
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

    @Test
    void theLoginTickBoxIsPartOfTheFingerprint() {
        when(quotes.submit(any())).thenReturn(new PublicQuoteDtos.SubmissionResponse(
                "ENR-2026-0041", "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                "FINAL_QUOTE_FOLLOWS", null));
        String plain = validSubmitJson();
        String ticked = plain.replace("\"website\":\"\"", "\"website\":\"\",\"loginRequested\":true");
        String unticked = plain.replace("\"website\":\"\"", "\"website\":\"\",\"loginRequested\":false");
        assertNotEquals(plain, ticked);

        for (String body : List.of(plain, ticked, ticked, unticked)) {
            given().contentType("application/json").header("Idempotency-Key", "browser-12345678")
                    .body(body).when().post("/api/v1/public/quotes/requests")
                    .then().statusCode(201);
        }

        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        verify(idempotency, times(4)).replay(eq(PublicFormPurpose.QUOTE), eq("browser-12345678"),
                fingerprints.capture(), eq(PublicQuoteDtos.SubmissionResponse.class));
        List<String> seen = fingerprints.getAllValues();
        assertNotEquals(seen.get(0), seen.get(1), "a retry with the box ticked is another request");
        assertEquals(seen.get(1), seen.get(2), "the same request has the same fingerprint");
        assertNotEquals(seen.get(1), seen.get(3));
        assertNotEquals(seen.get(0), seen.get(3));
        verify(quotes).submit(argThat(request -> request.loginRequested() == null));
        verify(quotes, times(2)).submit(argThat(request -> Boolean.TRUE.equals(request.loginRequested())));
        verify(quotes).submit(argThat(request -> Boolean.FALSE.equals(request.loginRequested())));
    }

    @Test
    void theAnonymousEndpointsIgnoreAValidCustomerSession() {
        Customer customer = customers.create(new Customer(null, "Bloemen Peeters BV", "An Peeters",
                "info-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com", null,
                "BE0123456789", "BE", Language.NL, null, null, null, null, null, null, null));
        try {
            var grant = accounts.grant(customer.id(),
                    "buyer-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com",
                    "An Peeters", Language.NL);
            String bearer = "Bearer " + accounts.activate(grant.rawToken(), "roses-in-a-dome").sessionToken();
            given().header("Authorization", bearer).get("/api/v1/public/account/session")
                    .then().statusCode(200);
            quoteSettings.update(false);
            when(quotes.configuration("EN")).thenReturn(new PublicQuoteDtos.ConfigurationResponse(
                    "EUR", "NET_EXCL_VAT", "FULL_CARTONS", List.of("DELIVERY"), "ESTIMATE_NOT_BINDING",
                    List.of(new PublicQuoteDtos.CountryOption("BE", "Belgium", decimal("100"), 2)),
                    List.of(new PublicQuoteDtos.ProductPrice(1L, decimal("10"), true, 12))));
            when(quotes.preview(any())).thenReturn(PublicQuotePriceVisibilityTest.pricedEstimate());
            when(quotes.submit(any())).thenReturn(new PublicQuoteDtos.SubmissionResponse("WEB-123",
                    "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING", "FINAL_QUOTE_FOLLOWS",
                    PublicQuotePriceVisibilityTest.pricedEstimate()));

            given().header("Authorization", bearer).get("/api/v1/public/quotes/configuration")
                    .then().statusCode(200)
                    .body("pricesVisible", equalTo(false))
                    .body("countries[0].minimumOrderNet", org.hamcrest.Matchers.nullValue())
                    .body("products[0].unitPriceNet", org.hamcrest.Matchers.nullValue());
            given().header("Authorization", bearer).contentType("application/json").body("{}")
                    .post("/api/v1/public/quotes/preview")
                    .then().statusCode(200).body("pricesVisible", equalTo(false))
                    .body("lines[0].unitPriceNet", org.hamcrest.Matchers.nullValue())
                    .body("totals.totalNet", org.hamcrest.Matchers.nullValue());
            given().header("Authorization", bearer).contentType("application/json")
                    .body(validSubmitJson()).post("/api/v1/public/quotes/requests")
                    .then().statusCode(201)
                    .body("estimate.pricesVisible", equalTo(false))
                    .body("estimate.lines[0].unitPriceNet", org.hamcrest.Matchers.nullValue());
            /* The anonymous route stays the anonymous route: a new customer, never the login's. */
            verify(quotes).submit(any());
            verify(quotes, never()).submitForCustomer(any(), anyLong(), anyString());
        } finally {
            customers.delete(customer.id());
        }
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
