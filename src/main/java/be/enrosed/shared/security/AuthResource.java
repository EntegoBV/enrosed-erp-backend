package be.enrosed.shared.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Sign-in status.
 *
 * No separate login endpoint is needed: the client sends its credentials as
 * HTTP Basic and uses {@code /me} to check they are right. On success the
 * app keeps the key and attaches it to every following call.
 *
 * Staff and partner logins share these two endpoints and their answer shape. A staff answer
 * is exactly what it was before partners existed; a partner answer carries the role partner,
 * a phone of null and never asks the staff contact book.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final SecurityIdentity identity;
    private final CurrentActor currentActor;
    private final AdminSessionTokenService sessionTokens;
    private final StaffContactService contacts;
    private final Instance<PartnerSessionIssuer> partnerSessions;

    public AuthResource(SecurityIdentity identity, CurrentActor currentActor,
                        AdminSessionTokenService sessionTokens, StaffContactService contacts,
                        Instance<PartnerSessionIssuer> partnerSessions) {
        this.identity = identity;
        this.currentActor = currentActor;
        this.sessionTokens = sessionTokens;
        this.contacts = contacts;
        this.partnerSessions = partnerSessions;
    }

    /** Returns 200 with the user on valid credentials, 401 without. */
    @GET
    @Path("/me")
    @RolesAllowed({AdminIdentityProvider.ADMIN_ROLE, PartnerIdentity.ROLE})
    public MeResponse me() {
        ActorRef actor = currentActor.current();
        if (!identity.hasRole(AdminIdentityProvider.ADMIN_ROLE)) {
            return new MeResponse(actor.username(), actor.displayName(),
                    identity.getRoles().stream().sorted().toList(), null);
        }
        return new MeResponse(actor.username(), actor.displayName(),
                identity.getRoles().stream().sorted().toList(), contacts.phoneFor(actor.username()));
    }

    public record MeResponse(String username, String displayName, List<String> roles, String phone) {}

    /** Exchanges the verified password for a signed, expiring browser key. */
    @POST
    @Path("/session")
    @RolesAllowed({AdminIdentityProvider.ADMIN_ROLE, PartnerIdentity.ROLE})
    public SessionResponse session() {
        ActorRef actor = currentActor.current();
        if (!identity.hasRole(AdminIdentityProvider.ADMIN_ROLE)) {
            if (!partnerSessions.isResolvable()) throw new ServiceUnavailableException();
            PartnerSessionIssuer.Issued issued = partnerSessions.get().issue(identity);
            return new SessionResponse(actor.username(), actor.displayName(),
                    identity.getRoles().stream().sorted().toList(),
                    issued.token(), issued.expiresAt().toString(), null);
        }
        AdminSessionTokenService.IssuedSession issued = sessionTokens.issue(actor.username());
        return new SessionResponse(actor.username(), actor.displayName(),
                identity.getRoles().stream().sorted().toList(),
                issued.token(), issued.expiresAt().toString(), contacts.phoneFor(actor.username()));
    }

    public record SessionResponse(String username, String displayName, List<String> roles,
                                  String token, String expiresAt, String phone) {}

    /** Public; lets the login page know the server is reachable. */
    @GET
    @Path("/ping")
    @PermitAll
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
