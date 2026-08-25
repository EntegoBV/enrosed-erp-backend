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
    FACTUUR
}
