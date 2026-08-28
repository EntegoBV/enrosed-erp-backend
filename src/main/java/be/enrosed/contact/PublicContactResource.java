package be.enrosed.contact;

import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormBodyLimited;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
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

import java.util.Locale;
import java.util.UUID;

@Path("/api/v1/public/contact")
@PermitAll
@Blocking
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@PublicFormBodyLimited
public class PublicContactResource {
    private final ContactInquiryService contacts;
    private final PublicFormSecurityService security;
    private final PublicFormRateLimiter rateLimiter;
    private final PublicFormIdempotencyService idempotency;
    private final ClientIdentityResolver identities;
    private final ObjectMapper json;

    @Context
    HttpServerRequest httpRequest;

    public PublicContactResource(ContactInquiryService contacts,
                                 PublicFormSecurityService security,
                                 PublicFormRateLimiter rateLimiter,
                                 PublicFormIdempotencyService idempotency,
                                 ClientIdentityResolver identities,
                                 ObjectMapper json) {
        this.contacts = contacts;
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.identities = identities;
        this.json = json;
    }

    @POST
    @Path("/requests")
    public Response submit(ContactDtos.Request request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        String fingerprint = fingerprint(request);
        var replay = idempotency.replay(PublicFormPurpose.CONTACT, idempotencyKey,
                fingerprint, ContactDtos.Response.class);
        if (replay.isPresent()) return accepted(replay.get());
        String email = request == null || request.email() == null ? null
                : request.email().strip().toLowerCase(Locale.ROOT);
        rateLimiter.checkIp(PublicFormAction.CONTACT_SUBMIT,
                identities.resolve(httpRequest));
        if (request != null && request.website() != null && !request.website().isBlank()) {
            /* Bots get the normal accepted envelope but create no inquiry or notification. */
            return accepted(new ContactDtos.Response("CNT-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT), "RECEIVED"));
        }
        contacts.validate(request);
        security.verifySubmission(PublicFormPurpose.CONTACT,
                request.formToken(), request.challengeToken());
        ContactDtos.Response response = idempotency.executeAccepted(PublicFormPurpose.CONTACT,
                idempotencyKey, fingerprint, PublicFormAction.CONTACT_SUBMIT, email,
                ContactDtos.Response.class,
                () -> contacts.submit(request));
        return accepted(response);
    }

    private static Response accepted(ContactDtos.Response response) {
        return Response.accepted(response).header("Cache-Control", "no-store").build();
    }

    private String fingerprint(ContactDtos.Request request) {
        if (request == null) return "null";
        try {
            return json.writeValueAsString(new Fingerprint(
                    request.language(), request.contactName(), request.email(),
                    request.companyName(), request.phone(), request.topic(), request.message(),
                    request.privacyAccepted(), request.privacyPolicyVersion(),
                    request.sourcePage(), request.website()));
        } catch (Exception exception) {
            throw new IllegalStateException("Contact request could not be fingerprinted", exception);
        }
    }

    private record Fingerprint(
            String language, String contactName, String email, String companyName,
            String phone, String topic, String message, Boolean privacyAccepted,
            String privacyPolicyVersion, String sourcePage, String website) {}
}
