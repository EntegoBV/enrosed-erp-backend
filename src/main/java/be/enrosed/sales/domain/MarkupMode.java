package be.enrosed.sales.domain;

/** Waar de opslag op de kostprijs vandaan komt. */
public enum MarkupMode {
    /** Elk product gebruikt zijn eigen opslag uit de catalogus. */
    PRODUCT,
    /** Een percentage over de hele order. */
    ORDER
}
