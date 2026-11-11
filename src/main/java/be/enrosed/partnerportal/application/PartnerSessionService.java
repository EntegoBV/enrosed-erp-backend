package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerLoginEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSessionEntity;
import be.enrosed.shared.security.PartnerIdentity;
import be.enrosed.shared.security.PartnerSessionIssuer;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server-side sessions of partner logins. The browser key is {@code epp1_} plus 256 random bits
 * (base64url); only its SHA-256 is stored, so a database copy holds no usable key. At most five
 * per login (the oldest goes), 90 days by default ({@code enrosed.partner.session-ttl-days}).
 * Unlike the staff key (enr1, stateless) every session can be revoked on its own, and all of a
 * login's sessions go when its password, username or active flag changes.
 */
@ApplicationScoped
public class PartnerSessionService implements PartnerSessionIssuer {

    public static final String TOKEN_PREFIX = "epp1_";
    static final int MAX_SESSIONS = 5;
    private static final Pattern TOKEN = Pattern.compile("^epp1_[A-Za-z0-9_-]{43}$");
    private static final Duration LAST_SEEN_STEP = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PartnerPortalRepositories.Sessions sessions;
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Settings settingsRows;
    private final int ttlDays;

    public PartnerSessionService(PartnerPortalRepositories.Sessions sessions, PartnerPortalRepositories.Logins logins,
                                 PartnerPortalRepositories.Settings settingsRows,
                                 @ConfigProperty(name = "enrosed.partner.session-ttl-days", defaultValue = "90")
                                 int ttlDays) {
        this.sessions = sessions;
        this.logins = logins;
        this.settingsRows = settingsRows;
        this.ttlDays = Math.max(1, Math.min(365, ttlDays));
    }

    /** A fingerprint of a stored password hash (never the hash itself in the identity). */
    static String passwordStamp(String passwordHash) {
        return passwordHash == null ? "" : hash(passwordHash);
    }

    public static boolean looksLikeToken(String credential) {
        return credential != null && credential.startsWith(TOKEN_PREFIX);
    }

    @Override
    @Transactional
    public Issued issue(SecurityIdentity identity) {
        if (!PartnerIdentity.AUTH_PASSWORD.equals(identity.getAttribute(PartnerIdentity.AUTH))) {
            throw new WebApplicationException(Response.status(403).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("status", 403, "message", "Meld opnieuw aan met je wachtwoord",
                            "timestamp", Instant.now().toString()))
                    .build());
        }
        Long loginId = identity.getAttribute(PartnerIdentity.LOGIN_ID);
        Long customerId = identity.getAttribute(PartnerIdentity.CUSTOMER_ID);
        if (loginId == null || customerId == null) throw new IllegalStateException("Partner identity without a login id");
        /* Under the partner lock a password change takes too: a reset that committed after this sign-in checked the
           old password is seen here and refuses the session; one that comes later deletes it. */
        settingsRows.lockOrCreate(customerId);
        PartnerLoginEntity login = logins.findById(loginId);
        String stamp = identity.getAttribute(PartnerIdentity.PASSWORD_STAMP);
        if (login == null || !login.isActive() || login.passwordHash == null || stamp == null
                || !stamp.equals(passwordStamp(login.passwordHash))) {
            throw new WebApplicationException(Response.status(401).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("status", 401, "message", "Onjuiste aanmeldgegevens",
                            "timestamp", Instant.now().toString()))
                    .build());
        }
        Instant now = Instant.now();
        String token = TOKEN_PREFIX + randomPart();
        sessions.deleteExpired(loginId, now);
        PartnerSessionEntity session = new PartnerSessionEntity();
        session.loginId = loginId;
        session.tokenHash = hash(token);
        session.createdAt = now;
        session.lastSeenAt = now;
        session.expiresAt = now.plus(Duration.ofDays(ttlDays));
        sessions.persist(session);
        sessions.flush();
        sessions.keepNewest(loginId, MAX_SESSIONS);
        return new Issued(token, session.expiresAt);
    }

    /**
     * The live session of this login behind the key, or null. last_seen_at is written at most
     * every 15 minutes. Joins the caller's transaction.
     */
    @Transactional
    public Long verify(long loginId, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return null;
        Instant now = Instant.now();
        PartnerSessionEntity session = sessions.findLive(loginId, hash(token), now).orElse(null);
        if (session == null) return null;
        if (session.lastSeenAt == null || session.lastSeenAt.plus(LAST_SEEN_STEP).isBefore(now)) {
            session.lastSeenAt = now;
        }
        return session.id;
    }

    /** Sign-out of one device: the session the key belongs to. */
    @Transactional
    public void delete(long sessionId) {
        sessions.deleteById(sessionId);
    }

    /** Every device of the login signs out (new password, other username, deactivated, no partner). */
    @Transactional
    public long deleteAll(long loginId) {
        return sessions.deleteByLogin(loginId);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String randomPart() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
