package be.enrosed.catalog.application.port.out;

/** Outbound port making a finished catalogue PDF small enough to mail (about 10 MB for the whole range). */
public interface CatalogPdfCompression {

    /** The same PDF with lighter photos; the original when that would not be smaller or fails. */
    byte[] compress(byte[] pdf);
}
