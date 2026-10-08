package be.enrosed.inventory.domain;

/** Why own stock is carried below its acquisition value; the note with it is always the user's. */
public enum WriteDownReason {
    BESCHADIGD("Beschadigd"),
    VEROUDERD("Verouderd"),
    TRAAG("Traag verkopend"),
    DEMO("Demostuk"),
    MARKT("Lagere marktwaarde");

    private final String label;

    WriteDownReason(String label) {
        this.label = label;
    }

    public String code() { return name(); }

    public String label() { return label; }

    /** The reason with this code; null for an empty or unknown one. */
    public static WriteDownReason of(String code) {
        if (code == null || code.isBlank()) return null;
        for (WriteDownReason reason : values()) {
            if (reason.name().equals(code.strip())) return reason;
        }
        return null;
    }

    public static String labelOf(String code) {
        WriteDownReason reason = of(code);
        return reason == null ? null : reason.label;
    }
}
