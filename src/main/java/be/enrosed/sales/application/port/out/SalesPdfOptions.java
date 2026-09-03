package be.enrosed.sales.application.port.out;

/**
 * Optional presentation choices for an explicitly downloaded sales PDF.
 *
 * <p>The canonical document sent to a customer always uses {@link #defaults()}.
 * These switches only remove supporting presentation detail; invoice identity,
 * quantities, prices, VAT, totals and payment details remain mandatory.</p>
 */
public record SalesPdfOptions(
        boolean includePhotos,
        boolean includeProductDetails,
        boolean includeLogistics,
        boolean includeTerms,
        boolean showOuterCarton,
        boolean showBarcode
) {
    /** Compatibility for callers written before printable master-data choices. */
    public SalesPdfOptions(boolean includePhotos, boolean includeProductDetails,
                           boolean includeLogistics, boolean includeTerms) {
        this(includePhotos, includeProductDetails, includeLogistics, includeTerms,
                false, false);
    }

    public static SalesPdfOptions defaults() {
        return new SalesPdfOptions(true, true, true, true, false, false);
    }

    /** The packing slip only consumes the two price-free product-data switches. */
    public static SalesPdfOptions forPackingSlip(boolean showOuterCarton,
                                                  boolean showBarcode) {
        return new SalesPdfOptions(false, false, false, false,
                showOuterCarton, showBarcode);
    }
}
