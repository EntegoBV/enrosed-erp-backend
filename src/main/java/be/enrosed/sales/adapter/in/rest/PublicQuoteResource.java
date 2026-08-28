package be.enrosed.sales.adapter.in.rest;

import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormBodyLimited;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.application.PublicQuoteService;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.UUID;

/** Anonymous website intake; it exposes no lookup route for submitted customer data. */
@Path("/api/v1/public/quotes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
@Blocking
@PublicFormBodyLimited
public class PublicQuoteResource {
    private final PublicQuoteService quotes;
    private final PublicFormSecurityService security;
    private final PublicFormRateLimiter rateLimiter;
    private final PublicFormIdempotencyService idempotency;
    private final ClientIdentityResolver identities;
    private final ObjectMapper json;

    @Context
    HttpServerRequest httpRequest;

    public PublicQuoteResource(PublicQuoteService quotes, PublicFormSecurityService security,
                               PublicFormRateLimiter rateLimiter,
                               PublicFormIdempotencyService idempotency,
                               ClientIdentityResolver identities, ObjectMapper json) {
        this.quotes = quotes;
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.identities = identities;
        this.json = json;
    }

    @GET
    @Path("/configuration")
    public Response configuration(@QueryParam("language") @DefaultValue("EN") String language) {
        return Response.ok(quotes.configuration(language))
                .header("Cache-Control", "public, max-age=60, stale-while-revalidate=120")
                .build();
    }

    @POST
    @Path("/preview")
    public Response preview(PublicQuoteDtos.PreviewRequest request) {
        rateLimiter.checkIp(PublicFormAction.QUOTE_PREVIEW, identities.resolve(httpRequest));
        return Response.ok(quotes.preview(request))
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/requests")
    public Response submit(PublicQuoteDtos.SubmitRequest request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        String email = request == null || request.email() == null
                ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        String fingerprint = fingerprint(request);
        PublicQuoteDtos.SubmissionResponse response;
        try {
            var replay = idempotency.replay(PublicFormPurpose.QUOTE, idempotencyKey,
                    fingerprint, PublicQuoteDtos.SubmissionResponse.class);
            if (replay.isPresent()) return created(replay.get());
            rateLimiter.checkIp(PublicFormAction.QUOTE_SUBMIT,
                    identities.resolve(httpRequest));
            if (request != null && request.website() != null && !request.website().isBlank()) {
                return created(new PublicQuoteDtos.SubmissionResponse(
                        "WEB-" + UUID.randomUUID().toString().replace("-", "")
                                .substring(0, 20).toUpperCase(Locale.ROOT),
                        "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                        "FINAL_QUOTE_FOLLOWS", null));
            }
            quotes.validateSubmission(request);
            security.verifySubmission(PublicFormPurpose.QUOTE,
                    request == null ? null : request.formToken(),
                    request == null ? null : request.challengeToken());
            response = idempotency.executeAccepted(PublicFormPurpose.QUOTE, idempotencyKey,
                    fingerprint, PublicFormAction.QUOTE_SUBMIT, email,
                    PublicQuoteDtos.SubmissionResponse.class,
                    () -> quotes.submit(request));
        } catch (BusinessRuleException exception) {
            /* Internal catalogue/order details do not belong in an anonymous response. */
            return Response.status(Response.Status.CONFLICT)
                    .entity(new PublicQuoteDtos.ErrorResponse(
                            "QUOTE_REVIEW_REQUIRED",
                            "The quote request could not be completed automatically",
                            java.util.Map.of()))
                    .header("Cache-Control", "no-store")
                    .build();
        }
        return created(response);
    }

    private static Response created(PublicQuoteDtos.SubmissionResponse response) {
        return Response.status(Response.Status.CREATED).entity(response)
                .header("Cache-Control", "no-store")
                .build();
    }

    private String fingerprint(PublicQuoteDtos.SubmitRequest request) {
        if (request == null) return "null";
        try {
            return json.writeValueAsString(new QuoteFingerprint(
                    request.language(), request.fulfillment(), request.vatNumber(),
                    request.destination(), request.items(), request.companyCountryCode(),
                    request.companyName(), request.contactName(), request.email(), request.phone(),
                    request.notes(), request.privacyAccepted(), request.website(),
                    request.pickupLocationId()));
        } catch (Exception exception) {
            throw new IllegalStateException("Quote request could not be fingerprinted", exception);
        }
    }

    private record QuoteFingerprint(
            String language, String fulfillment, String vatNumber,
            PublicQuoteDtos.Destination destination,
            java.util.List<PublicQuoteDtos.ItemRequest> items,
            String companyCountryCode, String companyName, String contactName,
            String email, String phone, String notes, Boolean privacyAccepted,
            String website, Long pickupLocationId) {}
}
