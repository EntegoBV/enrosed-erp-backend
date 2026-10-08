package be.enrosed.inventory.domain;

/**
 * The Dutch names of the payee streams and of the allocation keys as the
 * year-end inventory shows and prints them. Payee OTHER reads "Bijkomende
 * kosten" here, like the staff screens, and not the label of the payment itself.
 */
public final class PayeeLabels {

    public static final String SUPPLIER = "Leverancier";
    public static final String LOGISTICS = "Douane & transport";
    public static final String SEPARATE = "Inspectie & andere kosten";
    public static final String OTHER = "Bijkomende kosten";

    public static final String KEY_CBM = "volume";
    public static final String KEY_VALUE = "goederenwaarde";
    public static final String KEY_PIECES = "stuks";
    public static final String KEY_SEPARATE = "apart, naar ontvangen waarde";
    public static final String KEY_UNKNOWN = "onbekend";

    private PayeeLabels() {}

    /** The label of a payee by its name (SUPPLIER, LOGISTICS, SEPARATE, OTHER); null for anything else. */
    public static String of(String payee) {
        if (payee == null) {
            return null;
        }
        return switch (payee) {
            case "SUPPLIER" -> SUPPLIER;
            case "LOGISTICS" -> LOGISTICS;
            case "SEPARATE" -> SEPARATE;
            case "OTHER" -> OTHER;
            default -> null;
        };
    }

    /** The name of one allocation key (CBM, VALUE, PIECES, MANUAL, SEPARATE); a manual key falls back to pieces. */
    public static String allocationKey(String allocation) {
        if (allocation == null) {
            return KEY_UNKNOWN;
        }
        return switch (allocation) {
            case "CBM" -> KEY_CBM;
            case "VALUE" -> KEY_VALUE;
            case "PIECES", "MANUAL" -> KEY_PIECES;
            case "SEPARATE" -> KEY_SEPARATE;
            default -> KEY_UNKNOWN;
        };
    }

    /**
     * The "Verdeelsleutels" line of a container, built from the six settings stored on its closing
     * row. Inspection that is kept out of the piece prices always reads as apart.
     */
    public static String allocationLabel(String allocOrigin, String allocFreight, String allocDestination,
                                         String allocSeparate, Boolean groupVariants, Boolean separateInPiecePrice) {
        String separateKey = Boolean.FALSE.equals(separateInPiecePrice) ? KEY_SEPARATE : allocationKey(allocSeparate);
        return "Lokale kosten bij vertrek: " + allocationKey(allocOrigin)
                + " · Zeevracht: " + allocationKey(allocFreight)
                + " · Kosten na aankomst: " + allocationKey(allocDestination)
                + " · " + SEPARATE + ": " + separateKey
                + " · Invoerrechten: per product volgens HS-code"
                + " · Varianten van één reeks gelijkgetrokken: " + (Boolean.TRUE.equals(groupVariants) ? "ja" : "nee");
    }
}
