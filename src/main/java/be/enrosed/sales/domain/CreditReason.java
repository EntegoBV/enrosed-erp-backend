package be.enrosed.sales.domain;

/** Why a credit note exists; printed on the document and shown as a chip on screen. */
public enum CreditReason {
    SHORT_DELIVERY,
    DAMAGED,
    RETURN,
    PRICE_CORRECTION,
    CANCELLATION,
    /** A partner container arrived short: the advance financed more than the landed basis. */
    PARTNER_SHORTFALL,
    OTHER;

    public String dutchLabel() {
        return switch (this) {
            case SHORT_DELIVERY -> "Te weinig geleverd";
            case DAMAGED -> "Beschadigd";
            case RETURN -> "Retour";
            case PRICE_CORRECTION -> "Prijscorrectie";
            case CANCELLATION -> "Annulering";
            case PARTNER_SHORTFALL -> "Minder ontvangen dan gefinancierd";
            case OTHER -> "Andere";
        };
    }

    /** The document-text key that prints this reason in the customer's language. */
    public String documentTextKey() {
        return switch (this) {
            case SHORT_DELIVERY -> "creditReasonShortDelivery";
            case DAMAGED -> "creditReasonDamaged";
            case RETURN -> "creditReasonReturn";
            case PRICE_CORRECTION -> "creditReasonPriceCorrection";
            case CANCELLATION -> "creditReasonCancellation";
            case PARTNER_SHORTFALL -> "creditReasonPartnerShortfall";
            case OTHER -> "creditReasonOther";
        };
    }

    /** Reads a stored name leniently: an unknown value is "other", never a crash. */
    public static CreditReason of(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { return OTHER; }
    }
}
