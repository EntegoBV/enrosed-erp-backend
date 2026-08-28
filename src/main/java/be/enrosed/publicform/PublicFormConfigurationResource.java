package be.enrosed.publicform;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.Map;

@Path("/api/v1/public/forms")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class PublicFormConfigurationResource {
    private final PublicFormTokenService tokens;
    private final TurnstileVerificationService turnstile;

    public PublicFormConfigurationResource(PublicFormTokenService tokens,
                                           TurnstileVerificationService turnstile) {
        this.tokens = tokens;
        this.turnstile = turnstile;
    }

    @GET
    @Path("/configuration")
    public Response configuration(
            @QueryParam("purpose") @DefaultValue("QUOTE") String requestedPurpose) {
        PublicFormPurpose purpose;
        try {
            purpose = PublicFormPurpose.valueOf(requestedPurpose.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new PublicFormValidationException(Map.of("purpose", "UNSUPPORTED"));
        }
        return Response.ok(tokens.issue(purpose, turnstile.siteKey()))
                .header("Cache-Control", "no-store")
                .build();
    }
}
