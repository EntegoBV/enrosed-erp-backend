package be.enrosed.shared.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/** Who is doing this: the signed-in user's name, or "systeem" outside a request. */
@ApplicationScoped
public class CurrentActor {

    @Inject
    Instance<SecurityIdentity> identity;

    public String name() {
        try {
            SecurityIdentity current = identity.get();
            if (current == null || current.isAnonymous() || current.getPrincipal() == null) return "systeem";
            String name = current.getPrincipal().getName();
            return name == null || name.isBlank() ? "systeem" : name;
        } catch (RuntimeException outsideRequest) {
            return "systeem";
        }
    }
}
