package be.enrosed.sales.domain;

import java.util.List;

/**
 * Where a customer stands commercially: new on the list, in talks, buying, gone quiet or
 * lost. Stored as a plain code (no enum, no CHECK constraint) so a status can be added
 * without a migration; null is a customer written before statuses existed.
 */
public final class CustomerCrmStatus {

    public static final String NIEUW = "NIEUW";
    public static final String IN_GESPREK = "IN_GESPREK";
    public static final String ACTIEF = "ACTIEF";
    public static final String SLAPEND = "SLAPEND";
    public static final String VERLOREN = "VERLOREN";

    public static final List<String> KNOWN = List.of(NIEUW, IN_GESPREK, ACTIEF, SLAPEND, VERLOREN);

    private CustomerCrmStatus() {}

    public static boolean isKnown(String status) {
        return status != null && KNOWN.contains(status);
    }

    /** The Dutch label for the Logboek; null stays null so "no status" never shows as a change to itself. */
    public static String label(String status) {
        if (status == null) return null;
        return switch (status) {
            case NIEUW -> "Nieuw";
            case IN_GESPREK -> "In gesprek";
            case ACTIEF -> "Actieve klant";
            case SLAPEND -> "Slapend";
            case VERLOREN -> "Verloren";
            default -> status;
        };
    }
}
