package be.enrosed.catalog.domain;

/**
 * Where a photo leads. The first photo of the series is the internal lead
 * (ERP lists, purchase and sales documents); the website and the printed
 * catalogue may each open with another one.
 */
public enum PhotoRole {
    WEBSITE,
    CATALOGUE
}
