package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerLoginEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.PartnerIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * The blocking half of the partner sign-in (PartnerIdentityProvider runs it on a worker thread).
 *
 * Returns null when no partner login has this username, so the staff provider answers as before.
 * For an existing login every problem - wrong password or key, inactive login, customer gone or no
 * longer a partner, the rate limit, a database error - ends in AuthenticationFailedException (a
 * 401), never in another exception that would turn into a server error. The password path counts
 * against PARTNER_LOGIN per username (20 per 15 minutes); the session path is not counted. bcrypt
 * runs outside any transaction.
 */
@ApplicationScoped
public class PartnerAuthenticator {

    private static final Logger LOG = Logger.getLogger(PartnerAuthenticator.class);
    static final int PASSWORD_ATTEMPTS_PER_WINDOW = 20;

    /** What the sign-in needs of the login row, read in one short transaction. */
    private record Snapshot(long id, long customerId, String username, String displayName,
                            String passwordHash, boolean usable) {
        @Override
        public String toString() {
            return "Snapshot[id=" + id + ", username=" + username + ", usable=" + usable + "]";
        }
    }

    private final PartnerPortalRepositories.Logins logins;
    private final PartnerSessionService sessions;
    private final PartnerPasswords passwords;
    private final PublicFormRateLimiter rateLimiter;
    private final SalesRepositories.Customers customers;

    public PartnerAuthenticator(PartnerPortalRepositories.Logins logins, PartnerSessionService sessions,
                                PartnerPasswords passwords, PublicFormRateLimiter rateLimiter,
                                SalesRepositories.Customers customers) {
        this.logins = logins;
        this.sessions = sessions;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.customers = customers;
    }

    /** @param username canonical (trimmed, lower case) and not a staff name */
    public SecurityIdentity authenticate(String username, String credential) {
        try {
            Snapshot login = QuarkusTransaction.requiringNew().call(() -> snapshot(username));
            if (login == null) return null;
            if (!login.usable()) throw failed();
            if (PartnerSessionService.looksLikeToken(credential)) {
                Long sessionId = QuarkusTransaction.requiringNew().call(() -> sessions.verify(login.id(), credential));
                if (sessionId == null) throw failed();
                return identity(login, PartnerIdentity.AUTH_SESSION, sessionId);
            }
            rateLimiter.checkKey(PublicFormAction.PARTNER_LOGIN, "USERNAME", login.username(),
                    PASSWORD_ATTEMPTS_PER_WINDOW);
            if (!passwords.matches(credential, login.passwordHash())) throw failed();
            QuarkusTransaction.requiringNew().run(() -> touchLogin(login.id()));
            return identity(login, PartnerIdentity.AUTH_PASSWORD, null);
        } catch (AuthenticationFailedException failure) {
            throw failure;
        } catch (RuntimeException problem) {
            /* Never the credential: only what kind of problem stopped this sign-in. */
            LOG.warnf("Partneraanmelding geweigerd door %s", problem.getClass().getSimpleName());
            throw failed();
        }
    }

    private Snapshot snapshot(String username) {
        PartnerLoginEntity login = logins.findByUsername(username).orElse(null);
        if (login == null) return null;
        Customer customer = login.customerId == null ? null : customers.findById(login.customerId).orElse(null);
        boolean usable = login.isActive() && customer != null && customer.partner()
                && login.passwordHash != null && !login.passwordHash.isBlank();
        return new Snapshot(login.id, login.customerId == null ? 0 : login.customerId, login.username,
                login.displayName, login.passwordHash, usable);
    }

    /* Only this column: a whole-entity update could write back a password a reset replaced meanwhile. */
    private void touchLogin(long loginId) {
        logins.update("lastLoginAt = ?1 where id = ?2", Instant.now(), loginId);
    }

    private static SecurityIdentity identity(Snapshot login, String auth, Long sessionId) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(login.username()))
                .addRole(PartnerIdentity.ROLE)
                .addAttribute(AdminIdentityProvider.DISPLAY_NAME_ATTRIBUTE,
                        login.displayName() == null || login.displayName().isBlank()
                                ? login.username() : login.displayName())
                .addAttribute(PartnerIdentity.CUSTOMER_ID, login.customerId())
                .addAttribute(PartnerIdentity.LOGIN_ID, login.id())
                .addAttribute(PartnerIdentity.AUTH, auth);
        if (sessionId != null) builder.addAttribute(PartnerIdentity.SESSION_ID, sessionId);
        if (PartnerIdentity.AUTH_PASSWORD.equals(auth)) {
            builder.addAttribute(PartnerIdentity.PASSWORD_STAMP, PartnerSessionService.passwordStamp(login.passwordHash()));
        }
        return builder.build();
    }

    private static AuthenticationFailedException failed() {
        return new AuthenticationFailedException("Onjuiste aanmeldgegevens");
    }
}
