package be.enrosed.catalog.domain;

/**
 * Productcategorie uit een vaste lijst - "Preserved", "Glas", "Acryl".
 * Vrij tekstveld werd te snel een verzameling spelfouten.
 */
public record Category(Long id, String code, String name, String description, int position) {
}
