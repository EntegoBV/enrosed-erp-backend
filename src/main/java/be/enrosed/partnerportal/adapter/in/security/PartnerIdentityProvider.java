package be.enrosed.partnerportal.adapter.in.security;

import be.enrosed.partnerportal.application.PartnerAuthenticator;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;

/**
 * Partner portal sign-in over the same HTTP Basic header as staff (verhoeven:password, then
 * verhoeven:epp1_...).
 *
 * Runs before the staff provider: Quarkus sorts providers by descending priority, the default
 * is 1001 (SYSTEM_FIRST + 1, verified on Quarkus 3.38) and this one is 1100. A null item hands
 * the request to the next provider; any failure ends the whole authentication. So:
 * <ol>
 *   <li>A configured staff username returns null at once, synchronously, without a database
 *       call or a worker thread: staff sign-in is exactly what it was.</li>
 *   <li>Anything else goes to a worker thread; no partner_login row returns null (the staff
 *       provider then fails as it always did for an unknown name).</li>
 *   <li>An existing partner login either signs in as role partner only, or fails with
 *       AuthenticationFailedException (a 401), whatever went wrong.</li>
 * </ol>
 */
@ApplicationScoped
public class PartnerIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    public static final int PRIORITY = 1100;

    private final AdminIdentityProvider staff;
    private final PartnerAuthenticator authenticator;

    public PartnerIdentityProvider(AdminIdentityProvider staff, PartnerAuthenticator authenticator) {
        this.staff = staff;
        this.authenticator = authenticator;
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        String username = ActorRef.canonicalUsername(request.getUsername());
        boolean staffName;
        try {
            staffName = username.isEmpty() || staff.isStaffUsername(username);
        } catch (RuntimeException misconfigured) {
            /* A broken staff account list is the staff provider's to report, as before. */
            return Uni.createFrom().nullItem();
        }
        if (staffName) return Uni.createFrom().nullItem();
        char[] supplied = request.getPassword() == null ? new char[0] : request.getPassword().getPassword();
        String credential = new String(supplied);
        Arrays.fill(supplied, '\0');
        return context.runBlocking(() -> {
            try {
                return authenticator.authenticate(username, credential);
            } catch (AuthenticationFailedException failure) {
                throw failure;
            } catch (RuntimeException unexpected) {
                throw new AuthenticationFailedException("Onjuiste aanmeldgegevens");
            }
        });
    }
}
