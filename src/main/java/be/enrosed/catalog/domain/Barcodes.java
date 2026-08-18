package be.enrosed.catalog.domain;

/**
 * Barcodes van een product. Allebei optioneel: niet elk artikel krijgt een
 * eigen code, en displaydozen hebben er soms alleen een op de omdoos.
 */
public record Barcodes(String inner, String outer) {

    public static Barcodes none() {
        return new Barcodes(null, null);
    }

}
