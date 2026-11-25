package be.enrosed.sales.application;

import be.enrosed.shared.BusinessRuleException;

import java.math.BigDecimal;

/**
 * An invoice under the country's minimum order leaves only once staff said
 * "Toch versturen". Without that answer the screen gets this code and the two
 * figures it asks with; the sentence stays the one older screens show.
 */
public class MinimumOrderNotMetException extends BusinessRuleException {
    public static final String CODE = "MINIMUM_NOT_MET";

    private final BigDecimal minimumEur;
    private final BigDecimal shortfallEur;

    public MinimumOrderNotMetException(BigDecimal minimumEur, BigDecimal shortfallEur) {
        super("De factuur haalt de minimum orderwaarde niet - er ontbreekt nog " + shortfallEur + " EUR");
        this.minimumEur = minimumEur;
        this.shortfallEur = shortfallEur;
    }

    public BigDecimal minimumEur() { return minimumEur; }

    public BigDecimal shortfallEur() { return shortfallEur; }
}
