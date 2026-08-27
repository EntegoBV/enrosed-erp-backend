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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks staff sign-in.
 *
 * The password sits in configuration as a bcrypt hash, never as readable
 * text: getting hold of the repository or a config map yields no access.
 * Make a new hash with {@code BcryptUtil.bcryptHash("newpassword")}.
 *
 * The fixed staff accounts deliberately share one verifier: this is the
 * owner's requested transitional setup. Their canonical usernames still
 * become distinct principals, so operational actions can name who did them.
 * A shared password is not non-repudiation; separate credentials or OIDC are
 * the next step when stronger attribution or different permissions are needed.
 */
@ApplicationScoped
public class AdminIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    public static final String ADMIN_ROLE = "admin";
    public static final String DISPLAY_NAME_ATTRIBUTE = "displayName";

    @ConfigProperty(name = "enrosed.admin.accounts")
    String configuredAccounts;

    @ConfigProperty(name = "enrosed.admin.password-hash")
    String adminPasswordHash;

    private final AdminSessionTokenService sessionTokens;

    public AdminIdentityProvider(AdminSessionTokenService sessionTokens) {
        this.sessionTokens = sessionTokens;
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        String username = ActorRef.canonicalUsername(request.getUsername());
        ConfiguredAccount matchedAccount = null;

        /* Check every configured username rather than returning at the first
           match. The password check below also always runs, including for an
           unknown username, so the response does not reveal account existence. */
        for (ConfiguredAccount account : parseAccounts(configuredAccounts)) {
            boolean matches = MessageDigest.isEqual(
                    account.username().getBytes(StandardCharsets.UTF_8),
                    username.getBytes(StandardCharsets.UTF_8));
            if (matches) matchedAccount = account;
        }

        char[] supplied = request.getPassword() == null
                ? new char[0] : request.getPassword().getPassword();
        boolean passwordMatches;
        try {
            String credential = new String(supplied);
            passwordMatches = sessionTokens.isSessionToken(credential)
                    ? sessionTokens.verify(username, credential)
                    : BcryptUtil.matches(credential, adminPasswordHash);
        } finally {
            Arrays.fill(supplied, '\0');
        }

        if (matchedAccount == null || !passwordMatches) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Onjuiste aanmeldgegevens"));
        }

        ConfiguredAccount authenticated = matchedAccount;
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> authenticated.username())
                .addAttribute(DISPLAY_NAME_ATTRIBUTE, authenticated.displayName())
                .addRole(ADMIN_ROLE)
                .build());
    }

    /** Parses {@code username|Display name,username|Display name}. */
    static List<ConfiguredAccount> parseAccounts(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("At least one staff account must be configured");
        }
        List<ConfiguredAccount> accounts = new ArrayList<>();
        Set<String> usernames = new HashSet<>();
        for (String item : configured.split(",", -1)) {
            String[] parts = item.strip().split("\\|", 2);
            String username = ActorRef.canonicalUsername(parts.length == 0 ? null : parts[0]);
            String displayName = parts.length < 2 ? "" : parts[1].strip();
            if (!username.matches("[a-z0-9._-]{1,64}")) {
                throw new IllegalStateException("Invalid staff username in enrosed.admin.accounts");
            }
            if (displayName.isBlank() || displayName.length() > 100) {
                throw new IllegalStateException("Invalid staff display name in enrosed.admin.accounts");
            }
            if (!usernames.add(username)) {
                throw new IllegalStateException("Duplicate staff username in enrosed.admin.accounts");
            }
            accounts.add(new ConfiguredAccount(username, displayName));
        }
        return List.copyOf(accounts);
    }

    record ConfiguredAccount(String username, String displayName) {}
}
