package be.enrosed.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Geldrekenwerk op een plek.
 *
 * Alles gaat via BigDecimal: kostprijzen hebben drie tot vier decimalen en
 * met double lopen die stilletjes uit de pas. Er wordt pas afgerond waar een
 * bedrag naar buiten gaat, niet tussendoor.
 */
public final class Money {

    /** Werkschaal tijdens het rekenen - ruim genoeg om afrondingsdrift te vermijden. */
    public static final int CALC_SCALE = 8;
    /** Schaal van een bedrag dat op een document verschijnt. */
    public static final int MONEY_SCALE = 2;
    /** Schaal van een stukprijs; de inkooplijst werkt met drie decimalen. */
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

    /** Past een percentage toe: 12,5 % van 200 = 25. */
    public static BigDecimal percentOf(BigDecimal value, BigDecimal percent) {
        return divide(nz(value).multiply(nz(percent)), HUNDRED);
    }

    /** Verhoogt met een percentage: 200 + 45 % = 290. */
    public static BigDecimal addPercent(BigDecimal value, BigDecimal percent) {
        return divide(nz(value).multiply(HUNDRED.add(nz(percent))), HUNDRED);
    }

    /** Aandeel van een deel in een geheel; 0 als het geheel nul is. */
    public static BigDecimal share(BigDecimal part, BigDecimal total) {
        return divide(part, total);
    }
}
