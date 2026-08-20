package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * Dimensions in centimetres. Used both for the product itself and for the
 * outer carton - two different things that used to blur together.
 */
public record Dimensions(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {

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

    /** "15 x 30 x 12 cm", or empty when nothing is filled in. */
    public String label() {
        if (isBlank()) return "";
        return strip(lengthCm) + " x " + strip(widthCm) + " x " + strip(heightCm) + " cm";
    }

    private static int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }

    private static String strip(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
