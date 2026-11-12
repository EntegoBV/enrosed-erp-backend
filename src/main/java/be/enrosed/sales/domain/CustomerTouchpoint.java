package be.enrosed.sales.domain;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One kind of contact we had with a customer: mailed, called, spoken to, a catalogue
 * or a sample sent. A kind is on or off per customer, so there is at most one row per
 * kind and the moment is the first time it was ticked; ticking it off and on again
 * gives a fresh moment.
 *
 * The kind is an upper-case code rather than an enum so the screen can grow a kind
 * without a database change; the five we know are named here for the labels.
 */
public record CustomerTouchpoint(String kind, Instant at, String by) {

    public static final String GEMAILD = "GEMAILD";
    public static final String GEBELD = "GEBELD";
    public static final String GESPROKEN = "GESPROKEN";
    public static final String CATALOGUS_GESTUURD = "CATALOGUS_GESTUURD";
    public static final String STAAL_GESTUURD = "STAAL_GESTUURD";

    public static final List<String> KNOWN_KINDS = List.of(GEMAILD, GEBELD, GESPROKEN, CATALOGUS_GESTUURD, STAAL_GESTUURD);

    /** What a stored kind looks like: a code, not free text, and it fits the 32-character column. */
    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z_]{1,31}$");

    public static boolean isValidKind(String kind) {
        return kind != null && KIND.matcher(kind).matches();
    }

    /** The Dutch label for the Logboek; an unknown kind shows as its raw code. */
    public static String label(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case GEMAILD -> "Gemaild";
            case GEBELD -> "Gebeld";
            case GESPROKEN -> "Gesproken";
            case CATALOGUS_GESTUURD -> "Catalogus gestuurd";
            case STAAL_GESTUURD -> "Staal gestuurd";
            default -> kind;
        };
    }
}
