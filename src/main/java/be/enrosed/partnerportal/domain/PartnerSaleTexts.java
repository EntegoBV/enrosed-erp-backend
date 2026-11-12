package be.enrosed.partnerportal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** The Dutch words a melding is told with in the bell, the push message and the activity log. */
public final class PartnerSaleTexts {

    private static final Locale NL = Locale.forLanguageTag("nl-NL");

    private PartnerSaleTexts() {}

    /** "Veiling verkocht", "Direct verkocht", or "Teruggekocht op de klok" for a melding with only buybacks. */
    public static String title(String channel, boolean buybackOnly) {
        if (buybackOnly) return "Teruggekocht op de klok";
        return PartnerPortalRules.CHANNEL_DIRECT.equals(channel) ? "Direct verkocht" : "Veiling verkocht";
    }

    /** The short kind on a lock screen: Veiling, Direct or Terugkoop. */
    public static String kind(String channel, boolean buybackOnly) {
        if (buybackOnly) return "Terugkoop";
        return PartnerPortalRules.CHANNEL_DIRECT.equals(channel) ? "Direct" : "Veiling";
    }

    /** "€ 1.234,50": two decimals, Dutch separators. */
    public static String euro(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        return "€ " + format("#,##0.00").format(amount);
    }

    /** A unit price: two decimals, up to four when it has them ("€ 0,355"). */
    public static String unitEuro(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(4, RoundingMode.HALF_UP);
        return "€ " + format("#,##0.00##").format(amount);
    }

    /** "1 stuk" / "3 stuks" with the product's own unit words. */
    public static String quantity(int quantity, String unitOne, String unitOther) {
        return quantity + " " + (quantity == 1 ? unitOne : unitOther);
    }

    private static DecimalFormat format(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(NL));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }
}
