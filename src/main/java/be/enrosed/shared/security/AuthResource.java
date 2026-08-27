package be.enrosed.shared.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
    private final CurrentActor currentActor;
    private final AdminSessionTokenService sessionTokens;

    public AuthResource(SecurityIdentity identity, CurrentActor currentActor,
                        AdminSessionTokenService sessionTokens) {
        this.identity = identity;
        this.currentActor = currentActor;
        this.sessionTokens = sessionTokens;
    }

    /** Returns 200 with the user on valid credentials, 401 without. */
    @GET
    @Path("/me")
    @RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
    public MeResponse me() {
        ActorRef actor = currentActor.current();
        return new MeResponse(actor.username(), actor.displayName(),
                identity.getRoles().stream().sorted().toList());
    }

    public record MeResponse(String username, String displayName, List<String> roles) {}

    /** Exchanges the verified password for a signed, expiring browser key. */
    @POST
    @Path("/session")
    @RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
    public SessionResponse session() {
        ActorRef actor = currentActor.current();
        AdminSessionTokenService.IssuedSession issued = sessionTokens.issue(actor.username());
        return new SessionResponse(actor.username(), actor.displayName(),
                identity.getRoles().stream().sorted().toList(),
                issued.token(), issued.expiresAt().toString());
    }

    public record SessionResponse(String username, String displayName, List<String> roles,
                                  String token, String expiresAt) {}

    /** Public; lets the login page know the server is reachable. */
    @GET
    @Path("/ping")
    @PermitAll
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
