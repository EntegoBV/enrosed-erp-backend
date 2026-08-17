package be.enrosed.sales.domain;

public enum TierScope {
    /** Korting per productregel, op het aantal stuks van dat product. */
    LINE,
    /** Extra korting op het ordertotaal, op het totaal aantal stuks. */
    ORDER
}
