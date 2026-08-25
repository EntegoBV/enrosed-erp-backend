package be.enrosed.shipping.domain;

/**
 * A zone: the group of postcodes that shares one price column.
 *
 * {@code postcodes} is a comma-separated list of tokens. A token is a
 * numeric prefix ("45"), a numeric range of equally wide prefixes
 * ("10-15"), or a letter prefix for countries such as the UK ("AB").
 * Matching happens on the leading characters of the customer's postcode.
 */
public record CarrierZone(
        Long id,
        String name,
        String postcodes,
        int position
) {}
