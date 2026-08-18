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
 * Sign-in status.
 *
 * No separate login endpoint is needed: the client sends its credentials as
 * HTTP Basic and uses {@code /me} to check they are right. On success the
 * app keeps the key and attaches it to every following call.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final SecurityIdentity identity;

    public AuthResource(SecurityIdentity identity) {
        this.identity = identity;
    }

    /** Returns 200 with the user on valid credentials, 401 without. */
    @GET
    @Path("/me")
    @RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
    public Map<String, Object> me() {
        return Map.of(
                "username", identity.getPrincipal().getName(),
                "roles", List.copyOf(identity.getRoles()));
    }

    /** Public; lets the login page know the server is reachable. */
    @GET
    @Path("/ping")
    @PermitAll
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
