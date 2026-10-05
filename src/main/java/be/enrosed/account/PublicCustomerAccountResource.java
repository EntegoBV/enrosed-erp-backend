package be.enrosed.account;

import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormBodyLimited;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormValidationException;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The customer's own side of a website login: log in, restore, log out and choose a
 * password with the one-time link. The session travels as a Bearer token, never as
 * HTTP Basic, which belongs to staff.
 */
@Path("/api/v1/public/account")
@PermitAll
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class PublicCustomerAccountResource {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_LOGOUT_BODY_BYTES = 1024;
    private static final int LOGINS_PER_EMAIL = 10;

    private final CustomerAccountService accounts;
    private final CustomerSessionGuard guard;
    private final AccountHousekeeping housekeeping;
    private final PublicFormSecurityService security;
    private final PublicFormRateLimiter rateLimiter;
    private final ClientIdentityResolver identities;

    @Context
    HttpServerRequest httpRequest;

    public PublicCustomerAccountResource(CustomerAccountService accounts,
                                         CustomerSessionGuard guard,
                                         AccountHousekeeping housekeeping,
                                         PublicFormSecurityService security,
                                         PublicFormRateLimiter rateLimiter,
                                         ClientIdentityResolver identities) {
        this.accounts = accounts;
        this.guard = guard;
        this.housekeeping = housekeeping;
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.identities = identities;
    }

    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = AccountDtos.LoginSubmit.class)
    public Response login(AccountDtos.LoginSubmit request) {
        rateLimiter.checkIp(PublicFormAction.ACCOUNT_LOGIN, identities.resolve(httpRequest));
        validate(request);
        security.verifySubmission(PublicFormPurpose.ACCOUNT,
                request.formToken(), request.challengeToken());
        challengeOnce(request.challengeToken());
        /* Charged only after the checks above: knowing a customer's address is not enough to
           keep that login at its limit. An address that does not normalise has no bucket and
           no account; it still costs one bcrypt and gets the same 401. */
        String email = AccountEmails.normalize(request.email());
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_LOGIN, "EMAIL", email, LOGINS_PER_EMAIL);
        AccountDtos.SessionResponse session = accounts.login(email, request.password());
        housekeeping.purge();
        return noStore(Response.ok(session));
    }

    @GET
    @Path("/session")
    public Response session() {
        return noStore(Response.ok(accounts.sessionInfo(guard.require(httpRequest))));
    }

    /** No entity parameter: a POST without body or Content-Type, and one with {}, both log out. */
    @POST
    @Path("/session/logout")
    @Consumes(MediaType.WILDCARD)
    @PublicFormBodyLimited(maxBytes = MAX_LOGOUT_BODY_BYTES)
    public Response logout() {
        String token = CustomerSessionGuard.bearerToken(httpRequest);
        if (token != null) accounts.endSession(token);
        return noStore(Response.noContent());
    }

    @POST
    @Path("/activation/inspect")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = AccountDtos.TokenSubmit.class)
    public Response inspect(AccountDtos.TokenSubmit request) {
        rateLimiter.checkIp(PublicFormAction.ACCOUNT_ACTIVATE, identities.resolve(httpRequest));
        return noStore(Response.ok(accounts.inspect(request == null ? null : request.token())));
    }

    /** No form token and no challenge: the link carries 256 random bits and redeeming needs a POST. */
    @POST
    @Path("/activation")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = AccountDtos.ActivationSubmit.class)
    public Response activate(AccountDtos.ActivationSubmit request) {
        rateLimiter.checkIp(PublicFormAction.ACCOUNT_ACTIVATE, identities.resolve(httpRequest));
        return noStore(Response.ok(accounts.activate(
                request == null ? null : request.token(),
                request == null ? null : request.password())));
    }

    private static void validate(AccountDtos.LoginSubmit request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String email = request == null ? null : request.email();
        String password = request == null ? null : request.password();
        if (email == null || email.isBlank()) errors.put("email", "REQUIRED");
        else if (email.length() > 254) errors.put("email", "TOO_LONG");
        if (password == null || password.isBlank()) errors.put("password", "REQUIRED");
        else if (password.getBytes(StandardCharsets.UTF_8).length > 72) errors.put("password", "TOO_LONG");
        if (!errors.isEmpty()) throw new PublicFormValidationException(errors);
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

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }
}
