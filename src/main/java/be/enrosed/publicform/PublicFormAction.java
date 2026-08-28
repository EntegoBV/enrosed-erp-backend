package be.enrosed.publicform;

/** Separate rate-limit surfaces; previews never share a submission budget. */
public enum PublicFormAction {
    QUOTE_PREVIEW(60, 60),
    QUOTE_SUBMIT(5, 3_600),
    CONTACT_SUBMIT(10, 3_600);

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
        return this == QUOTE_PREVIEW ? 0 : 3;
    }
}
