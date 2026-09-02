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
        boolean includeTerms
) {
    public static SalesPdfOptions defaults() {
        return new SalesPdfOptions(true, true, true, true);
    }
}
