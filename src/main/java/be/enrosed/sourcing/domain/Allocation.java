package be.enrosed.sourcing.domain;

/**
 * Verdeelsleutel voor kosten die alleen op containerniveau bekend zijn.
 * Bij een container met een product maakt de keuze niets uit.
 */
public enum Allocation {
    /** Naar volume - volume vult de container. */
    CBM,
    /** Naar goederenwaarde. */
    VALUE,
    /** Naar aantal stuks. */
    PIECES
}
