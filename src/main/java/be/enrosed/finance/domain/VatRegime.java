package be.enrosed.finance.domain;

/**
 * How the VAT of a cost is treated in the quarterly return: a Belgian
 * invoice carries deductible VAT, an EU or non-EU supplier's invoice is
 * reverse charged (we self-assess the VAT and deduct it in the same return),
 * foreign VAT on a hotel bill is a cost, insurance and postage are exempt,
 * bank charges and wages stay out of the return, a till receipt without an
 * invoice gives no deduction, and the VAT paid at customs on an import
 * document is a deduction with no base of its own. One regime per cost.
 */
public enum VatRegime {
    BE, EU_GOODS, EU_SERVICES, NON_EU_SERVICES, FOREIGN_VAT, EXEMPT, OUT_OF_SCOPE, NO_INVOICE, IMPORT_VAT_PAID;

    /** The VAT is ours to declare (grids 55/56) and to deduct (grid 59), nothing goes to the supplier. */
    public boolean reverseCharge() {
        return this == EU_GOODS || this == EU_SERVICES || this == NON_EU_SERVICES;
    }

    /** The cost carries a VAT rate: Belgian, reverse charged or foreign. */
    public boolean carriesVat() {
        return this == BE || reverseCharge() || this == FOREIGN_VAT;
    }

    /** Whether the cost appears in the return at all; bank charges, fines, taxes and wages do not. */
    public boolean inReturn() {
        return this != OUT_OF_SCOPE;
    }

    /** The stored text back as a regime; null for null or a text this version does not know. */
    public static VatRegime parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return valueOf(text.strip());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
