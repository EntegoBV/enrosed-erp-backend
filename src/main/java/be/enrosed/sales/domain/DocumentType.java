package be.enrosed.sales.domain;

/**
 * What kind of document a sales order is.
 *
 * A quote proposes; an invoice claims. They share the whole order model -
 * customer, lines, pricing, freight - but differ in numbering, life cycle
 * and the paper that leaves the door.
 */
public enum DocumentType {
    OFFERTE,
    FACTUUR,
    /** A credit note: positive lines whose sign the document type carries; it reverses part of an invoice. */
    CREDITNOTA
}
