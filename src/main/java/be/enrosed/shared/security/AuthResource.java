package be.enrosed.shared.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Aanmeldstatus.
 *
 * Er is geen apart login-endpoint nodig: de client stuurt zijn gegevens mee als
 * HTTP Basic en gebruikt {@code /me} om te controleren of ze kloppen. Lukt dat,
 * dan bewaart de app de sleutel en zet hij hem op elke volgende oproep.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final SecurityIdentity identity;

    public AuthResource(SecurityIdentity identity) {
        this.identity = identity;
    }

    /** Geeft 200 met de gebruiker bij geldige gegevens, 401 zonder. */
    @GET
    @Path("/me")
    @RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
    public Map<String, Object> me() {
        return Map.of(
                "username", identity.getPrincipal().getName(),
                "roles", List.copyOf(identity.getRoles()));
    }

    /** Openbaar; laat de aanmeldpagina weten dat de server bereikbaar is. */
    @GET
    @Path("/ping")
    @PermitAll
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
