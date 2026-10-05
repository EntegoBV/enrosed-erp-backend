package be.enrosed.account;

import be.enrosed.account.CustomerSessionGuard.CustomerSession;
import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormBodyLimited;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.application.PublicQuoteService;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The quote request of a logged-in customer. Every method asks the session guard first, so
 * priced data leaves only for a valid session; the anonymous quote resource never reads a
 * credential. Prices here are the public list and do not follow the website price switch:
 * this class knows neither the switch nor the redaction of the anonymous answers.
 */
@Path("/api/v1/public/account/quotes")
@PermitAll
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class CustomerQuoteResource {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int READS_PER_ACCOUNT = 120;

    private final PublicQuoteService quotes;
    private final CustomerSessionGuard guard;
    private final PublicFormSecurityService security;
    private final PublicFormRateLimiter rateLimiter;
    private final PublicFormIdempotencyService idempotency;
    private final ClientIdentityResolver identities;
    private final ObjectMapper json;

    @Context
    HttpServerRequest httpRequest;

    public CustomerQuoteResource(PublicQuoteService quotes, CustomerSessionGuard guard,
                                 PublicFormSecurityService security,
                                 PublicFormRateLimiter rateLimiter,
                                 PublicFormIdempotencyService idempotency,
                                 ClientIdentityResolver identities, ObjectMapper json) {
        this.quotes = quotes;
        this.guard = guard;
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.identities = identities;
        this.json = json;
    }

    @GET
    @Path("/configuration")
    public Response configuration(@QueryParam("language") @DefaultValue("EN") String language) {
        CustomerSession session = guard.require(httpRequest);
        /* An approved customer can read the whole price list in one call; this only bounds how often. */
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_QUOTE_READ, "ACCOUNT",
                String.valueOf(session.accountId()), READS_PER_ACCOUNT);
        return noStore(Response.ok(quotes.configuration(language)));
    }

    @POST
    @Path("/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = PublicQuoteDtos.PreviewRequest.class)
    public Response preview(PublicQuoteDtos.PreviewRequest request) {
        guard.require(httpRequest);
        rateLimiter.checkIp(PublicFormAction.QUOTE_PREVIEW, identities.resolve(httpRequest));
        return noStore(Response.ok(quotes.preview(request)));
    }

    @POST
    @Path("/requests")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = PublicQuoteDtos.SubmitRequest.class)
    public Response submit(PublicQuoteDtos.SubmitRequest request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        /* Before the replay: a stored answer carries prices too. */
        CustomerSession session = guard.require(httpRequest);
        String fingerprint = fingerprint(session.accountId(), request);
        PublicQuoteDtos.SubmissionResponse response;
        try {
            var replay = idempotency.replay(PublicFormPurpose.ACCOUNT_QUOTE, idempotencyKey,
                    fingerprint, PublicQuoteDtos.SubmissionResponse.class);
            if (replay.isPresent()) return created(replay.get());
            rateLimiter.checkIp(PublicFormAction.ACCOUNT_QUOTE_SUBMIT,
                    identities.resolve(httpRequest));
            if (request != null && request.website() != null && !request.website().isBlank()) {
                return created(new PublicQuoteDtos.SubmissionResponse(
                        "WEB-" + UUID.randomUUID().toString().replace("-", "")
                                .substring(0, 20).toUpperCase(Locale.ROOT),
                        "RECEIVED", "REQUEST_RECEIVED_NOT_BINDING",
                        "FINAL_QUOTE_FOLLOWS", null));
            }
            quotes.validateSubmissionForCustomer(request, session.customerId());
            /* The page's own quote form token and widget are reused; ACCOUNT_QUOTE only keeps
               the stored answers of logged-in customers apart from the anonymous ones. */
            security.verifySubmission(PublicFormPurpose.QUOTE,
                    request == null ? null : request.formToken(),
                    request == null ? null : request.challengeToken());
            response = idempotency.executeAccepted(PublicFormPurpose.ACCOUNT_QUOTE, idempotencyKey,
                    fingerprint, PublicFormAction.ACCOUNT_QUOTE_SUBMIT, session.email(),
                    PublicQuoteDtos.SubmissionResponse.class,
                    () -> quotes.submitForCustomer(request, session.customerId(), session.email()));
        } catch (BusinessRuleException exception) {
            /* Internal catalogue/order details do not belong in a customer response. */
            return noStore(Response.status(Response.Status.CONFLICT)
                    .entity(new PublicQuoteDtos.ErrorResponse(
                            "QUOTE_REVIEW_REQUIRED",
                            "The quote request could not be completed automatically",
                            Map.of())));
        }
        return created(response);
    }

    private static Response created(PublicQuoteDtos.SubmissionResponse response) {
        return noStore(Response.status(Response.Status.CREATED).entity(response));
    }

    private String fingerprint(long accountId, PublicQuoteDtos.SubmitRequest request) {
        if (request == null) return "null:" + accountId;
        try {
            return json.writeValueAsString(new AccountQuoteFingerprint(
                    accountId, request.language(), request.fulfillment(), request.destination(),
                    request.items(), request.contactName(), request.phone(), request.notes(),
                    request.privacyAccepted(), request.website(), request.pickupLocationId()));
        } catch (Exception exception) {
            throw new IllegalStateException("Quote request could not be fingerprinted", exception);
        }
    }

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }

    /**
     * Company name, company country, e-mail, VAT number and the login tick box are left out on
     * purpose: the server ignores them here, so a retry that differs only there is a replay.
     */
    private record AccountQuoteFingerprint(
            long accountId, String language, String fulfillment,
            PublicQuoteDtos.Destination destination, List<PublicQuoteDtos.ItemRequest> items,
            String contactName, String phone, String notes, Boolean privacyAccepted,
            String website, Long pickupLocationId) {}
}
