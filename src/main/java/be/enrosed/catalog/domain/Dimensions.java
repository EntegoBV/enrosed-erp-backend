package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * Dimensions in centimetres. Used for the product itself, for the gift box
 * or display around it, and for the outer carton - three different things
 * that used to blur together.
 *
 * The component names are a legacy storage/API contract and must not move:
 * {@code lengthCm} is displayed as Breedte (B), {@code widthCm} as Diepte (D),
 * and {@code heightCm} as Hoogte (H).
 */
public record Dimensions(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {

    public static final String AXIS_ORDER_SHORT = "B × D × H";
    public static final String AXIS_ORDER_LONG = "Breedte × Diepte × Hoogte";

    public static Dimensions empty() {
        return new Dimensions(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public boolean isBlank() {
        return signum(lengthCm) == 0 && signum(widthCm) == 0 && signum(heightCm) == 0;
    }

    /** Volume in kubieke meter. */
    public BigDecimal cbm() {
        if (signum(lengthCm) <= 0 || signum(widthCm) <= 0 || signum(heightCm) <= 0) {
            return BigDecimal.ZERO;
        }
        return lengthCm.multiply(widthCm).multiply(heightCm)
                .divide(BigDecimal.valueOf(1_000_000), 8, java.math.RoundingMode.HALF_UP);
    }

    /** "B × D × H: 15 × 30 × 12 cm", or empty when nothing is filled in. */
    public String label() {
        if (isBlank()) return "";
        return AXIS_ORDER_SHORT + ": " + strip(lengthCm) + " × " + strip(widthCm)
                + " × " + strip(heightCm) + " cm";
    }

    private static int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }

    private static String strip(BigDecimal value) {
        return value == null || value.signum() <= 0
                ? "—"
                : value.stripTrailingZeros().toPlainString();
    }
}
