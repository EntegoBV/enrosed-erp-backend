package be.enrosed.shared.security;

/**
 * The role and identity attributes of a partner portal login (be.enrosed.partnerportal).
 *
 * A partner identity carries the role {@link #ROLE} and never {@code admin}. Staff resources stay
 * {@code @RolesAllowed("admin")}: never {@code @Authenticated}, {@code "**"} or this role on them,
 * or every partner login can read the whole ERP. Only {@code /api/auth/me}, {@code /api/auth/session}
 * and {@code /api/partner-portal/*} admit it.
 */
public final class PartnerIdentity {

    public static final String ROLE = "partner";

    /** Long: the partner customer this login belongs to; the only source of the customer id. */
    public static final String CUSTOMER_ID = "partnerCustomerId";
    /** Long: the partner_login row. */
    public static final String LOGIN_ID = "partnerLoginId";
    /** String: {@link #AUTH_PASSWORD} or {@link #AUTH_SESSION}. */
    public static final String AUTH = "partnerAuth";
    /** Long: the partner_session row on the session path; absent on the password path. */
    public static final String SESSION_ID = "partnerSessionId";
    /**
     * String, password path only: a fingerprint of the password hash the sign-in was checked against, so a session
     * is only issued while that is still the login's password (a reset in between refuses it).
     */
    public static final String PASSWORD_STAMP = "partnerPasswordStamp";

    public static final String AUTH_PASSWORD = "PASSWORD";
    public static final String AUTH_SESSION = "SESSION";

    private PartnerIdentity() {}
}
