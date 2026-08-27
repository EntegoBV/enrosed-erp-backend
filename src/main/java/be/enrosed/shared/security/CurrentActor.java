package be.enrosed.shared.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/** Who is doing this: the server-authenticated staff account, or the system outside a request. */
@ApplicationScoped
public class CurrentActor {

    @Inject
    Instance<SecurityIdentity> identity;

    public ActorRef current() {
        try {
            SecurityIdentity current = identity.get();
            if (current == null || current.isAnonymous() || current.getPrincipal() == null) {
                return ActorRef.SYSTEM;
            }
            String name = current.getPrincipal().getName();
            if (name == null || name.isBlank()) return ActorRef.SYSTEM;
            String displayName = current.getAttribute(AdminIdentityProvider.DISPLAY_NAME_ATTRIBUTE);
            return new ActorRef(name, displayName);
        } catch (RuntimeException outsideRequest) {
            return ActorRef.SYSTEM;
        }
    }

    /** Compatibility for existing audit columns that store only the canonical username. */
    public String name() {
        return current().username();
    }
}
