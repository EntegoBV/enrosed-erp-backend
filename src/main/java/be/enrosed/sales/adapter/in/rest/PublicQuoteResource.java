package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.PublicQuoteAbuseGuard;
import be.enrosed.sales.application.PublicQuoteService;
import be.enrosed.shared.BusinessRuleException;
import io.vertx.core.http.HttpServerRequest;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;

/** Anonymous website intake; it exposes no lookup route for submitted customer data. */
@Path("/api/v1/public/quotes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
@Blocking
public class PublicQuoteResource {
    private final PublicQuoteService quotes;
    private final PublicQuoteAbuseGuard abuse;

    @Context
    HttpServerRequest httpRequest;

    public PublicQuoteResource(PublicQuoteService quotes, PublicQuoteAbuseGuard abuse) {
        this.quotes = quotes;
        this.abuse = abuse;
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
        abuse.checkPreview(remoteKey());
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
        PublicQuoteDtos.SubmissionResponse response;
        try {
            response = abuse.submitOnce(idempotencyKey, fingerprint(request), () -> {
                abuse.checkSubmit(remoteKey(), email);
                return quotes.submit(request);
            });
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
        return Response.status(Response.Status.CREATED).entity(response)
                .header("Cache-Control", "no-store")
                .build();
    }

    private String remoteKey() {
        try {
            return httpRequest == null || httpRequest.remoteAddress() == null
                    ? "unknown" : httpRequest.remoteAddress().hostAddress();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String fingerprint(PublicQuoteDtos.SubmitRequest request) {
        if (request == null) return "null";
        return String.join("|",
                String.valueOf(request.language()),
                String.valueOf(request.companyCountryCode()), String.valueOf(request.companyName()),
                String.valueOf(request.contactName()),
                String.valueOf(request.email()), String.valueOf(request.phone()),
                String.valueOf(request.vatNumber()),
                String.valueOf(request.fulfillment()), String.valueOf(request.destination()),
                String.valueOf(request.items()), String.valueOf(request.notes()),
                String.valueOf(request.privacyAccepted()), String.valueOf(request.website()));
    }
}
