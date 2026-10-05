package be.enrosed.publicform;

/** Separate rate-limit surfaces; previews never share a submission budget. */
public enum PublicFormAction {
    QUOTE_PREVIEW(60, 60),
    QUOTE_SUBMIT(5, 3_600),
    CONTACT_SUBMIT(10, 3_600),
    ACCOUNT_REQUEST_SUBMIT(5, 3_600),
    ACCOUNT_LINK_REQUEST(5, 3_600),
    ACCOUNT_LOGIN(20, 900),
    /** Shared by inspecting an invitation link and activating it. */
    ACCOUNT_ACTIVATE(20, 3_600),
    /** Counted per account through checkKey, never per IP. */
    ACCOUNT_QUOTE_READ(120, 3_600),
    ACCOUNT_QUOTE_SUBMIT(10, 3_600),
    /** Staff notices about login requests, one global key per window (checkKey). */
    ACCOUNT_NOTICE_HOUR(6, 3_600),
    ACCOUNT_NOTICE_DAY(20, 86_400),
    /** One solved challenge buys one call on the account forms together (checkKey). */
    ACCOUNT_CHALLENGE(1, 86_400);

    private final int ipLimit;
    private final long windowSeconds;

    PublicFormAction(int ipLimit, long windowSeconds) {
        this.ipLimit = ipLimit;
        this.windowSeconds = windowSeconds;
    }

    public int ipLimit() {
        return ipLimit;
    }

    public long windowSeconds() {
        return windowSeconds;
    }

    public int emailLimit() {
        return switch (this) {
            case QUOTE_PREVIEW, ACCOUNT_LINK_REQUEST, ACCOUNT_LOGIN, ACCOUNT_ACTIVATE,
                 ACCOUNT_QUOTE_READ, ACCOUNT_NOTICE_HOUR, ACCOUNT_NOTICE_DAY, ACCOUNT_CHALLENGE -> 0;
            case ACCOUNT_QUOTE_SUBMIT -> 10;
            default -> 3;
        };
    }
}
