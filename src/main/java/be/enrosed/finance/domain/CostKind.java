package be.enrosed.finance.domain;

/**
 * Where the base of a cost lands in the return: trade goods in grid 81,
 * services and diverse goods in 82, an investment in 83 (and depreciated
 * over its useful life instead of expensed at once).
 */
public enum CostKind {
    GOODS, SERVICES, INVESTMENT;

    /** The stored text back as a kind; null for null or a text this version does not know. */
    public static CostKind parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return valueOf(text.strip());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
