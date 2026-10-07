package be.enrosed.account;

import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormBodyLimited;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The two website forms that ask staff for something: a login, and a new link for a login
 * that exists. Both always answer "received" in the same shape and in the same time, so
 * neither tells whether an address is known. Nobody gets access here; staff decide.
 */
@Path("/api/v1/public/account")
@PermitAll
@Blocking
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PublicLoginRequestResource {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int LINK_REQUESTS_PER_EMAIL = 3;
    private static final String RECEIVED = "RECEIVED";

    private final LoginRequestService requests;
    private final LoginRequestNotifier notifier;
    private final PublicFormSecurityService security;
    private final PublicFormRateLimiter rateLimiter;
    private final PublicFormIdempotencyService idempotency;
    private final ClientIdentityResolver identities;
    private final ObjectMapper json;

    @Context
    HttpServerRequest httpRequest;

    public PublicLoginRequestResource(LoginRequestService requests,
                                      LoginRequestNotifier notifier,
                                      PublicFormSecurityService security,
                                      PublicFormRateLimiter rateLimiter,
                                      PublicFormIdempotencyService idempotency,
                                      ClientIdentityResolver identities,
                                      ObjectMapper json) {
        this.requests = requests;
        this.notifier = notifier;
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.identities = identities;
        this.json = json;
    }

    @POST
    @Path("/requests")
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = LoginRequestDtos.Submit.class)
    public Response submit(LoginRequestDtos.Submit request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        String fingerprint = fingerprint(request);
        var replay = idempotency.replay(PublicFormPurpose.ACCOUNT, idempotencyKey,
                fingerprint, LoginRequestDtos.Accepted.class);
        if (replay.isPresent()) return accepted(replay.get());
        rateLimiter.checkIp(PublicFormAction.ACCOUNT_REQUEST_SUBMIT,
                identities.resolve(httpRequest));
        if (request != null && request.website() != null && !request.website().isBlank()) {
            /* Bots get the normal accepted envelope but create no request and no notice. */
            return accepted(new LoginRequestDtos.Accepted(LoginRequestWriter.newReference(), RECEIVED));
        }
        requests.validate(request);
        security.verifySubmission(PublicFormPurpose.ACCOUNT,
                request.formToken(), request.challengeToken());
        challengeOnce(request.challengeToken());
        String email = AccountEmails.normalize(request.email());
        LoginRequestDtos.Accepted response;
        try {
            response = store(idempotencyKey, fingerprint, email, request);
        } catch (RuntimeException exception) {
            /* A background route stored a request for the same address at the same moment. The
               whole transaction rolled back; the second run finds that request and counts a
               repeat. A second failure is not caught. */
            if (!LoginRequestWriter.isUniqueViolation(exception)) throw exception;
            response = store(idempotencyKey, fingerprint, email, request);
        }
        return accepted(response);
    }

    private LoginRequestDtos.Accepted store(String idempotencyKey, String fingerprint, String email,
                                            LoginRequestDtos.Submit request) {
        return idempotency.executeAccepted(PublicFormPurpose.ACCOUNT, idempotencyKey, fingerprint,
                PublicFormAction.ACCOUNT_REQUEST_SUBMIT, email, LoginRequestDtos.Accepted.class,
                () -> requests.submit(request));
    }

    /**
     * The request thread does the same for every address: the lookup, the insert and
     * everything after it run in the background.
     */
    @POST
    @Path("/link-requests")
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = LoginRequestDtos.LinkSubmit.class)
    public Response requestLink(LoginRequestDtos.LinkSubmit request) {
        rateLimiter.checkIp(PublicFormAction.ACCOUNT_LINK_REQUEST, identities.resolve(httpRequest));
        if (request != null && request.website() != null && !request.website().isBlank()) {
            return accepted(new LoginRequestDtos.LinkAccepted(RECEIVED));
        }
        String raw = request == null ? null : request.email();
        if (raw == null || raw.isBlank()) {
            throw new PublicFormValidationException(Map.of("email", "REQUIRED"));
        }
        String email = AccountEmails.normalize(raw);
        if (email == null) throw new PublicFormValidationException(Map.of("email", "INVALID"));
        security.verifySubmission(PublicFormPurpose.ACCOUNT,
                request.formToken(), request.challengeToken());
        challengeOnce(request.challengeToken());
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_LINK_REQUEST, "EMAIL", email,
                LINK_REQUESTS_PER_EMAIL);
        String language = request.language();
        notifier.runLater(() -> requests.requestNewLink(email, language));
        return accepted(new LoginRequestDtos.LinkAccepted(RECEIVED));
    }

    /**
     * One solved challenge buys one guarded call on the three account forms together. The
     * verification helper would accept the same response again for minutes, from any address.
     * A reuse answers like a failed challenge, so the website resets its widget.
     */
    private void challengeOnce(String challengeToken) {
        if (challengeToken == null || challengeToken.isBlank()) return;
        try {
            rateLimiter.checkKey(PublicFormAction.ACCOUNT_CHALLENGE, "CHALLENGE",
                    AccountTokens.hash(challengeToken.strip()), 1);
        } catch (PublicFormRateLimitException reused) {
            throw new PublicFormValidationException(Map.of("challengeToken", "INVALID"));
        }
    }

    private static Response accepted(Object response) {
        return Response.accepted(response).header("Cache-Control", "no-store").build();
    }

    private String fingerprint(LoginRequestDtos.Submit request) {
        if (request == null) return "null";
        try {
            return json.writeValueAsString(new Fingerprint(
                    request.language(), request.companyName(), request.companyCountryCode(),
                    request.vatNumber(), request.contactName(), request.email(), request.phone(),
                    request.message(), request.privacyAccepted(), request.website()));
        } catch (Exception exception) {
            throw new IllegalStateException("Login request could not be fingerprinted", exception);
        }
    }

    private record Fingerprint(
            String language, String companyName, String companyCountryCode, String vatNumber,
            String contactName, String email, String phone, String message,
            Boolean privacyAccepted, String website) {}
}
