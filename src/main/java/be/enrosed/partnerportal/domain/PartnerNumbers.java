package be.enrosed.partnerportal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How the portal writes numbers in JSON: money at 2 decimals, unit prices and percents as short as exact. */
public final class PartnerNumbers {

    private PartnerNumbers() {}

    /** 2 decimals; null stays null. */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 2 decimals; null reads as 0. */
    public static BigDecimal moneyOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** At most 4 decimals without trailing zeros (3.2, not 3.2000); null stays null. */
    public static BigDecimal unit(BigDecimal value) {
        return value == null ? null : plain(value.setScale(4, RoundingMode.HALF_UP));
    }

    /** A percent as a short number (4.5); null stays null. */
    public static BigDecimal pct(BigDecimal value) {
        return value == null ? null : plain(value.setScale(4, RoundingMode.HALF_UP));
    }

    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
