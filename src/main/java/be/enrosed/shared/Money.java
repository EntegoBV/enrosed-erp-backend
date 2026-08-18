package be.enrosed.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money arithmetic in one place.
 *
 * Everything goes through BigDecimal: cost prices carry three to four
 * decimals and with double they silently drift. Rounding only happens where
 * an amount goes out, never in between.
 */
public final class Money {

    /** Working scale during calculation - wide enough to avoid rounding drift. */
    public static final int CALC_SCALE = 8;
    /** Scale of an amount that appears on a document. */
    public static final int MONEY_SCALE = 2;
    /** Scale of a piece price; the purchase list works with three decimals. */
    public static final int UNIT_SCALE = 4;

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {}

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal money(BigDecimal value) {
        return nz(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal unit(BigDecimal value) {
        return nz(value).setScale(UNIT_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) return BigDecimal.ZERO;
        return nz(a).divide(b, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /** Applies a percentage: 12.5 % of 200 = 25. */
    public static BigDecimal percentOf(BigDecimal value, BigDecimal percent) {
        return divide(nz(value).multiply(nz(percent)), HUNDRED);
    }

    /** Increases by a percentage: 200 + 45 % = 290. */
    public static BigDecimal addPercent(BigDecimal value, BigDecimal percent) {
        return divide(nz(value).multiply(HUNDRED.add(nz(percent))), HUNDRED);
    }

    /** Share of a part in a whole; 0 when the whole is zero. */
    public static BigDecimal share(BigDecimal part, BigDecimal total) {
        return divide(part, total);
    }
}
