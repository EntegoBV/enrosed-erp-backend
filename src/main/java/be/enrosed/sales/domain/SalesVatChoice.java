package be.enrosed.sales.domain;

/**
 * The VAT route of one sales document, as far as staff may choose it (2026-11-26).
 *
 * The customer record gives the standard: goods cleared in the Netherlands through our fiscal
 * representative, or the plain Belgian rules. A document may go the other way (the goods leave from
 * Belgium this time), and a document without product lines may be a service that has nothing to do
 * with goods. Only a choice that differs from the customer's standard is stored
 * (sales_order_vat_choice); no row means "as the customer".
 */
public enum SalesVatChoice {
    /** Supplied in the Netherlands after clearance by our fiscal representative: art. 12.3 Wet OB. */
    FISCAL_REPRESENTATIVE,
    /** The plain Belgian rules: domestic, intra-community supply from Belgium, export. */
    BELGIUM,
    /** A service, no goods: design, hosting, training. Only on a document without product lines. */
    SERVICE;

    /** An unknown stored name reads as "no choice", never as a failure. */
    public static SalesVatChoice stored(String value) {
        if (value == null || value.isBlank()) return null;
        try { return valueOf(value.strip()); } catch (IllegalArgumentException unknown) { return null; }
    }
}
