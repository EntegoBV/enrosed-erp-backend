package be.enrosed.sales.application.port.out;

/**
 * Optional presentation choices for an explicitly downloaded sales PDF.
 *
 * <p>The canonical document sent to a customer always uses {@link #defaults()}.
 * These switches only remove supporting presentation detail; invoice identity,
 * quantities and the amount due remain mandatory. Invoice prices, VAT and
 * totals remain mandatory. An advance-agreement quotation carries its frozen
 * instalments, or just its agreed advance amount when payment details are hidden.</p>
 *
 * <p>Without {@code includeReceipts} an invoice or credit note prints as it was
 * issued: its own amount, and nothing about what was received, offset or
 * refunded since. That is the copy a customer downloads from their account.</p>
 */
public record SalesPdfOptions(
        boolean includePhotos,
        boolean includeProductDetails,
        boolean includeLogistics,
        boolean includeTerms,
        boolean showOuterCarton,
        boolean showBarcode,
        boolean includePaymentDetails,
        boolean includeReceipts
) {
    /** Every download from before the account copy keeps showing how the document stands today. */
    public SalesPdfOptions(boolean includePhotos, boolean includeProductDetails,
                           boolean includeLogistics, boolean includeTerms,
                           boolean showOuterCarton, boolean showBarcode,
                           boolean includePaymentDetails) {
        this(includePhotos, includeProductDetails, includeLogistics, includeTerms,
                showOuterCarton, showBarcode, includePaymentDetails, true);
    }

    /** Existing downloads retain payment details unless explicitly hidden. */
    public SalesPdfOptions(boolean includePhotos, boolean includeProductDetails,
                           boolean includeLogistics, boolean includeTerms,
                           boolean showOuterCarton, boolean showBarcode) {
        this(includePhotos, includeProductDetails, includeLogistics, includeTerms,
                showOuterCarton, showBarcode, true);
    }

    /** Compatibility for callers written before printable master-data choices. */
    public SalesPdfOptions(boolean includePhotos, boolean includeProductDetails,
                           boolean includeLogistics, boolean includeTerms) {
        this(includePhotos, includeProductDetails, includeLogistics, includeTerms,
                false, false);
    }

    public static SalesPdfOptions defaults() {
        return new SalesPdfOptions(true, true, true, true, false, false);
    }

    /** The customer's own download: the document as issued, without payment sentences or receipts. */
    public static SalesPdfOptions accountCopy() {
        return new SalesPdfOptions(true, true, true, true, false, false, false, false);
    }

    /** The packing slip only consumes the two price-free product-data switches. */
    public static SalesPdfOptions forPackingSlip(boolean showOuterCarton,
                                                  boolean showBarcode) {
        return new SalesPdfOptions(false, false, false, false,
                showOuterCarton, showBarcode);
    }
}
