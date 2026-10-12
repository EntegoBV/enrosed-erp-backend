package be.enrosed.sourcing.domain;

import java.util.Locale;

/**
 * Where a container is cleared and what that means for the VAT return: in
 * Belgium at customs (the import VAT is paid on the customs document or, with
 * the ET 14000 licence, self-assessed in grids 87/57/59); in the Netherlands
 * through the fiscal representative and brought to Belgium (an intra-EU
 * acquisition, grids 86/55/59); or in the Netherlands and sold there (only the
 * goods in grid 81). Null on the order means "te bevestigen": the screen
 * suggests one from the port of arrival and never stores the suggestion.
 */
public enum Clearance {
    BE_CUSTOMS, NL_FISCAL_REP_TO_BE, NL_FISCAL_REP_SOLD_IN_NL;

    /** The suggestion from the port of arrival: Antwerp clears in Belgium, every other port through the representative. */
    public static Clearance suggestedFor(String destinationPort) {
        String port = destinationPort == null ? "" : destinationPort.strip().toLowerCase(Locale.ROOT);
        return port.contains("antwerp") ? BE_CUSTOMS : NL_FISCAL_REP_TO_BE;
    }

    /** The stored text back as a clearance; null for null or a text this version does not know. */
    public static Clearance parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return valueOf(text.strip());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
