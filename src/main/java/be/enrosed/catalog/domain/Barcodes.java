package be.enrosed.catalog.domain;

/**
 * A product's barcodes. Both optional: not every article gets its own code,
 * and display boxes sometimes only carry one on the outer carton.
 */
public record Barcodes(String inner, String outer) {

    public static Barcodes none() {
        return new Barcodes(null, null);
    }

}
