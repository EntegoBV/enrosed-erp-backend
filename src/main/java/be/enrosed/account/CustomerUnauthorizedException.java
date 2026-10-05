package be.enrosed.account;

/** A customer credential that grants nothing: wrong login or a session that is no longer valid. */
public class CustomerUnauthorizedException extends RuntimeException {
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String SESSION_INVALID = "SESSION_INVALID";

    private final String code;

    public CustomerUnauthorizedException(String code) {
        super(INVALID_CREDENTIALS.equals(code)
                ? "E-mail address or password is incorrect"
                : "The session is no longer valid");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
