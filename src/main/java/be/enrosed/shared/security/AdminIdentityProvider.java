package be.enrosed.shared.security;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Checks staff sign-in.
 *
 * The password sits in configuration as a bcrypt hash, never as readable
 * text: getting hold of the repository or a config map yields no access.
 * Make a new hash with {@code BcryptUtil.bcryptHash("newpassword")}.
 *
 * Deliberately kept small - one user, one role. The moment several people
 * with different rights arrive, a user table or an OIDC provider belongs
 * here; the rest of the security does not change for it, because it hangs
 * on the role and not on this class.
 */
@ApplicationScoped
public class AdminIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    public static final String ADMIN_ROLE = "admin";

    @ConfigProperty(name = "enrosed.admin.username")
    String adminUsername;

    @ConfigProperty(name = "enrosed.admin.password-hash")
    String adminPasswordHash;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        String username = request.getUsername();
        String password = new String(request.getPassword().getPassword());

        /* Compare the username in constant time, so the answer does not
           betray whether the name existed. Bcrypt already does this for the
           password by itself. */
        boolean userMatches = MessageDigest.isEqual(
                adminUsername.getBytes(StandardCharsets.UTF_8),
                username == null ? new byte[0] : username.getBytes(StandardCharsets.UTF_8));

        boolean passwordMatches = BcryptUtil.matches(password, adminPasswordHash);

        if (!userMatches || !passwordMatches) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Onjuiste aanmeldgegevens"));
        }

        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> adminUsername)
                .addRole(ADMIN_ROLE)
                .build());
    }
}
