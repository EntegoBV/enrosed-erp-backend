package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * Afmeting in centimeter. Wordt zowel voor het product zelf gebruikt als
 * voor de omdoos - het zijn twee verschillende dingen die vroeger door
 * elkaar liepen.
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
        if (lengthCm == null || widthCm == null || heightCm == null) return BigDecimal.ZERO;
        return lengthCm.multiply(widthCm).multiply(heightCm)
                .divide(BigDecimal.valueOf(1_000_000), 8, java.math.RoundingMode.HALF_UP);
    }

    /** "15 x 30 x 12 cm", of leeg wanneer er niets ingevuld is. */
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
