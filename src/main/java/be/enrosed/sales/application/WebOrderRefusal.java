package be.enrosed.sales.application;

/**
 * A customer write on a website order that cannot go through. Deliberately
 * not a BusinessRuleException: its Dutch staff sentences must never reach a
 * customer, so the account resources map this code to their own answers.
 */
public class WebOrderRefusal extends RuntimeException {
    public enum Code { NOT_FOUND, LOCKED, CHANGED, CHANGE_LIMIT }

    private final Code code;
    private final Integer currentRevision;

    public WebOrderRefusal(Code code) {
        this(code, null);
    }

    public WebOrderRefusal(Code code, Integer currentRevision) {
        super(code.name());
        this.code = code;
        this.currentRevision = currentRevision;
    }

    public Code code() { return code; }

    /** The revision the order has now; only known for CHANGED and CHANGE_LIMIT. */
    public Integer currentRevision() { return currentRevision; }
}
