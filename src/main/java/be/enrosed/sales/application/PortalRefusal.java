package be.enrosed.sales.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;

/**
 * A customer's quotation link that leads to a document we do not show: the
 * quotation is being updated, or it was cancelled while it was an unsent
 * draft. The Dutch message is what it always was; the code tells the
 * customer's page which of the two it is, so it can say so in the customer's
 * own language instead of "link no longer valid".
 */
public class PortalRefusal extends BusinessRuleException {
    /** Staff reopened the quotation (or adopted a proposal) and have not sent it again. */
    public static final String BEING_UPDATED = "QUOTE_BEING_UPDATED";
    /** Cancelled while it was an unsent draft: nothing of the document is shown. */
    public static final String CANCELLED = "QUOTE_CANCELLED";

    static final String BEING_UPDATED_MESSAGE = "Deze offerte wordt momenteel bijgewerkt. "
            + "De nieuwe versie is pas zichtbaar nadat Enrosed ze opnieuw heeft verstuurd.";
    static final String CANCELLED_NOTICE = "Deze offerte is geannuleerd.";

    private final String code;
    private final String cancellationMessage;
    private final String language;

    private PortalRefusal(String code, String message, String cancellationMessage, String language) {
        super(message);
        this.code = code;
        this.cancellationMessage = cancellationMessage;
        this.language = language;
    }

    /**
     * The same refusal with the language on the customer's file. The page
     * shows nothing of the quotation, so this is all it has to greet the
     * customer in their own language.
     */
    public PortalRefusal in(Language customerLanguage) {
        return new PortalRefusal(code, getMessage(), cancellationMessage,
                customerLanguage == null ? null : customerLanguage.name());
    }

    public static PortalRefusal beingUpdated() {
        return new PortalRefusal(BEING_UPDATED, BEING_UPDATED_MESSAGE, null, null);
    }

    /**
     * The message keeps the staff sentence after the notice, as before; the
     * same sentence also travels on its own, exactly as staff typed it, so
     * the page need not cut the message apart.
     */
    public static PortalRefusal cancelled(String staffMessage) {
        boolean none = staffMessage == null || staffMessage.isBlank();
        return new PortalRefusal(CANCELLED, none ? CANCELLED_NOTICE : CANCELLED_NOTICE + " " + staffMessage,
                none ? null : staffMessage, null);
    }

    public String code() { return code; }

    /** What staff wrote for the customer when cancelling; null when nothing, and always for {@link #BEING_UPDATED}. */
    public String cancellationMessage() { return cancellationMessage; }

    /** The language code of the customer's file (NL, FR, ...); null when the refusal was raised without one. */
    public String language() { return language; }
}
