package be.enrosed.tica;

import java.util.Set;

/** Safe integration error: never attach browser errors, page contents or credentials. */
public final class TicaPortalException extends RuntimeException {
    private static final Set<String> CODES = Set.of("NOT_CONFIGURED", "INVALID_CONFIGURATION",
            "INVALID_DATE_RANGE", "AUTH_FAILED", "AUTH_INTERACTION_REQUIRED", "ACCOUNT_MISMATCH",
            "PORTAL_CHANGED", "DOWNLOAD_INVALID", "WORKER_PROTOCOL_INVALID", "WORKER_OUTPUT_INVALID",
            "AUTH_OPEN_PORTAL", "AUTH_OPEN_LOGIN", "AUTH_USERNAME", "AUTH_PASSWORD", "AUTH_SUBMIT", "AUTH_VERIFY",
            "FILTER_NAVIGATE", "FILTER_EXHIBITOR", "FILTER_SETTLE", "EXPORT_OPEN_DIALOG", "EXPORT_SELECT_TYPE", "EXPORT_WAIT_DOWNLOAD",
            "AUTH_MULTIPLE_INPUTS", "EXPORT_TYPE_INVALID", "FILTER_FROM_URL_INVALID", "FILTER_TO_URL_INVALID", "FILTER_FROM_VALUE_INVALID", "FILTER_TO_VALUE_INVALID",
            "AUTH_MFA_INPUT", "AUTH_CAPTCHA_FRAME", "AUTH_CAPTCHA_INPUT", "AUTH_MFA_TEXT", "AUTH_CAPTCHA_TEXT", "AUTH_TLS_ERROR", "AUTH_NETWORK_BLOCKED", "AUTH_NAVIGATION_ABORTED", "BROWSER_START_FAILED", "LOGIN_UNAVAILABLE", "DATE_FILTER_UNAVAILABLE", "EXPORT_UNAVAILABLE", "DOWNLOAD_READ_FAILED",
            "DOWNLOAD_ORIGIN_INVALID", "DOWNLOAD_FILENAME_INVALID", "DOWNLOAD_INCOMPLETE", "DOWNLOAD_HEADER_INVALID", "DOWNLOAD_TOO_LARGE", "TIMEOUT", "UNAVAILABLE", "BUSY");
    public final String code;

    public TicaPortalException(String code) {
        super(CODES.contains(code) ? code : "UNAVAILABLE", null, false, false);
        this.code = getMessage();
    }

    public String getCode() { return code; }
}
