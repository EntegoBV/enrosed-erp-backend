package be.enrosed.finance.domain;

import java.math.BigDecimal;

/**
 * The deductibility presets of Belgian practice: how much of the VAT may be
 * deducted and how much of the cost counts for income tax. A preset fills
 * both percentages; CUSTOM keeps what was typed.
 */
public enum ExpenseType {
    /** Fully deductible, the default. */
    STANDARD("100", "100"),
    /** Restaurant: no VAT deduction, 69 % for income tax. */
    RESTAURANT("0", "69"),
    /** Reception costs: no VAT deduction, 50 % for income tax. */
    RECEPTION("0", "50"),
    /** Business gifts under € 50 excl.: VAT deductible, 50 % for income tax. */
    GIFT("100", "50"),
    /** Passenger car and fuel: VAT capped at 50 %, income tax by the CO₂ formula (reported as 100 here). */
    CAR("50", "100"),
    /** Mixed use of movable goods (phone, laptop): 75 % VAT. */
    MIXED("75", "100"),
    /** Percentages of one's own. */
    CUSTOM(null, null);

    private final BigDecimal vatPct;
    private final BigDecimal taxPct;

    ExpenseType(String vatPct, String taxPct) {
        this.vatPct = vatPct == null ? null : new BigDecimal(vatPct);
        this.taxPct = taxPct == null ? null : new BigDecimal(taxPct);
    }

    /** The deductible share of the VAT, in percent; null for CUSTOM. */
    public BigDecimal vatPct() {
        return vatPct;
    }

    /** The share of the cost that counts for income tax, in percent; null for CUSTOM. */
    public BigDecimal taxPct() {
        return taxPct;
    }

    /** The stored text back as a preset; null for null or a text this version does not know. */
    public static ExpenseType parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return valueOf(text.strip());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
