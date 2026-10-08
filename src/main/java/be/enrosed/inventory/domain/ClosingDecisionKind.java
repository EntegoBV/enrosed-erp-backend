package be.enrosed.inventory.domain;

/**
 * What the user decides in a closing. These are the inputs the law leaves to
 * the company and its accountant; the ERP stores the choice with its reason
 * and gives no advice.
 */
public enum ClosingDecisionKind {
    ACCRUAL("Nog verschuldigd bedrag", true),
    SUPPLIER_BILLED("Aangerekende stuks", true),
    CREDIT_TREATMENT("Tegoed leverancier", true),
    OWNERSHIP_DATE("Eigendom of risico vanaf", true),
    TRANSIT("Goederen onderweg", true),
    PARTNER_CONTAINER("Partnercontainer", true),
    PARTNER_QUANTITY("Aantal partnercontainer", true),
    /** The reason is asked only for "Blijft eigen voorraad" and "Stuks waren al weg". */
    INVOICED("Gefactureerd, nog niet afgepunt", false),
    THIRD_PARTY("Goederen van derden", true),
    WRITE_DOWN("Waardevermindering", true),
    MOVEMENT("Beweging rond de afsluitdatum", true),
    VAT_CONFIRMATION("Bevestiging btw", false);

    private final String label;
    private final boolean reasonRequired;

    ClosingDecisionKind(String label, boolean reasonRequired) {
        this.label = label;
        this.reasonRequired = reasonRequired;
    }

    public String label() { return label; }

    public boolean reasonRequired() { return reasonRequired; }

    /** A third-party quantity and a waardevermindering always add; every other kind replaces the one with its key. */
    public boolean alwaysAdds() {
        return this == THIRD_PARTY || this == WRITE_DOWN;
    }

    /** The kind with this name; null for an empty or unknown one. */
    public static ClosingDecisionKind of(String name) {
        if (name == null || name.isBlank()) return null;
        for (ClosingDecisionKind kind : values()) {
            if (kind.name().equals(name.strip())) return kind;
        }
        return null;
    }

    public static String labelOf(String name) {
        ClosingDecisionKind kind = of(name);
        return kind == null ? name : kind.label;
    }
}
