package be.enrosed.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Invitation-link and session tokens of website logins. Both carry 256 random bits, so
 * they are stored as a plain SHA-256: an HMAC with the public-form secret would log every
 * customer out when that secret rotates.
 */
public final class AccountTokens {
    private static final String INVITATION_PREFIX = "eci1_";
    private static final String SESSION_PREFIX = "ecs1_";
    private static final Pattern INVITATION = Pattern.compile("^eci1_[A-Za-z0-9_-]{43}$");
    private static final Pattern SESSION = Pattern.compile("^ecs1_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccountTokens() {}

    public static String newInvitationToken() {
        return INVITATION_PREFIX + randomPart();
    }

    public static String newSessionToken() {
        return SESSION_PREFIX + randomPart();
    }

    /** Lower-case hex SHA-256 of the full token string; the only form that is ever stored. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /* Pattern.matches would let "$" accept a trailing line break; matches() on the
       whole input does not. */
    public static boolean isInvitationToken(String token) {
        return token != null && INVITATION.matcher(token).matches();
    }

    public static boolean isSessionToken(String token) {
        return token != null && SESSION.matcher(token).matches();
    }

    private static String randomPart() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
