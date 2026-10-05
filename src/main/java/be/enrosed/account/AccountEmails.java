package be.enrosed.account;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one normal form of an e-mail address for website logins: storage, lookup and
 * rate-limit keys all use it. strip() on purpose, never trim(): trim() also removes
 * control characters, so an address with a trailing control character would reach the
 * victim's login from a rate bucket of its own.
 */
public final class AccountEmails {
    private static final int MAX_LENGTH = 254;
    /* Same expression as the website quote form (PublicQuoteService). */
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);

    private AccountEmails() {}

    /** The normalised address, or null when the input is not a usable e-mail address. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String email = raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_LENGTH) return null;
        if (email.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f)) return null;
        return EMAIL.matcher(email).matches() ? email : null;
    }
}
