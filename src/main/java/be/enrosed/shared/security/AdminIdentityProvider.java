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
 * Controleert de aanmelding van het personeel.
 *
 * Het wachtwoord staat als bcrypt-hash in de configuratie, nooit als leesbare
 * tekst: wie de repository of een configmap in handen krijgt heeft daarmee nog
 * geen toegang. De hash maak je met
 * {@code BcryptUtil.bcryptHash("nieuwwachtwoord")}.
 *
 * Dit is bewust klein gehouden - een gebruiker, een rol. Zodra er meerdere
 * mensen met verschillende rechten bijkomen hoort hier een gebruikerstabel of
 * een OIDC-provider te staan; de rest van de beveiliging verandert daar niet
 * van, want die hangt aan de rol en niet aan deze klasse.
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

        /* De gebruikersnaam in constante tijd vergelijken, zodat het antwoord
           niet verraadt of de naam bestond. Bcrypt doet dat voor het wachtwoord
           al uit zichzelf. */
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
