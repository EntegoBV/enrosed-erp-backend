package be.enrosed.inventory.domain;

/**
 * Why a counted figure differs from the book.
 *
 * There is deliberately no reason "sold, not yet afgepunt" and none "received,
 * not yet bijgeboekt": a difference that an invoice or a container explains
 * is solved in that document, because afpunten and bijboeken would book the
 * same pieces a second time.
 */
public enum CountReason {
    BESCHADIGD("Beschadigd of stuk", false),
    NIET_GEVONDEN("Niet gevonden", false),
    TELFOUT("Eerdere tel- of boekfout", false),
    ANDERE_LOCATIE("Lag op een andere locatie", false),
    DEMO("Als demo of staal weggegeven", false),
    TERUGGEVONDEN("Teruggevonden", false),
    ANDERS("Andere reden", true);

    private final String label;
    private final boolean noteRequired;

    CountReason(String label, boolean noteRequired) {
        this.label = label;
        this.noteRequired = noteRequired;
    }

    public String code() { return name(); }

    public String label() { return label; }

    /** Whether the reason says nothing without a note of the counter. */
    public boolean noteRequired() { return noteRequired; }

    /** The reason with this code; null for a blank or unknown code. */
    public static CountReason of(String code) {
        if (code == null || code.isBlank()) return null;
        for (CountReason reason : values()) {
            if (reason.name().equals(code.strip())) return reason;
        }
        return null;
    }

    /** The label of a stored code; null when the line has no reason. */
    public static String labelOf(String code) {
        CountReason reason = of(code);
        return reason == null ? null : reason.label;
    }
}
