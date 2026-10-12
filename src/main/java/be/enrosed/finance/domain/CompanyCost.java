package be.enrosed.finance.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A cost the company made outside purchasing: the fair, the accountant,
 * rent, the TICA stand, software, transport. Booked by date and category,
 * excluding VAT, so the result analysis can set it against the sales.
 *
 * The VAT regime says what the cash total is (what leaves the bank) and what
 * the quarterly return shows; the arithmetic lives here once and the
 * frontend mirrors it in finance-metrics.ts. A PUT may carry the view the
 * screen was given (payments and totals): those fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyCost(
        Long id,
        LocalDate date,
        /** Free text with a suggested list on screen: HUUR, BEURS, BOEKHOUDER, ... */
        String category,
        String description,
        /** Who was paid: the fair organiser, the accountant, the landlord. */
        String party,
        BigDecimal amountExclEur,
        /**
         * The total incl. VAT as it was typed in, when the invoice was entered
         * incl.; null for a cost entered excl. Kept so € 100,00 at 21 % stays
         * € 100,00 instead of drifting to 99,99 through the rounded excl. amount.
         * For an import-VAT cost it is the VAT paid at customs.
         */
        BigDecimal amountInclEur,
        /** The VAT on top; null when the cost carries none. */
        BigDecimal vatPct,
        /** The supplier's invoice number or another reference. */
        String reference,
        /** When it was paid; derived from the payments: the last payment date once they cover the cash total, null before. */
        LocalDate paidOn,
        /** The sales channel the cost belongs to, when it does: TICA for the stand, FAIR for a fair. */
        String salesChannel,
        String notes,
        Instant createdAt,
        /** The recurring definition that booked this cost automatically; null for a hand-booked cost. */
        Long recurringCostId,
        /** The bank line that paid this cost in one go, set by the bank service when the movement is noted; null until then. */
        Long bankStatementLineId,
        /** How the VAT is treated in the return; null on rows from before the regimes (read as Belgian for cash, 'nog te bepalen' for the return). */
        VatRegime vatRegime,
        /** False for a regime derived by the backfill or from the supplier's country; the screen asks to confirm it. */
        Boolean vatRegimeConfirmed,
        /** ISO country code of the supplier: BE, IE, US. */
        String supplierCountry,
        /** Goods, services or an investment: the base grid of the return. Null reads as services (goods for MATERIAAL). */
        CostKind costKind,
        /** The deductibility preset; null reads as STANDARD. */
        ExpenseType expenseType,
        /** The deductible share of the VAT, 0–100; null reads as 100. */
        BigDecimal vatDeductiblePct,
        /** The share of the cost that counts for income tax, 0–100; null reads as 100. Reported, never changes a total. */
        BigDecimal taxDeductiblePct,
        /** Useful life of an investment in years; null unless the cost is an investment. */
        Integer depreciationYears,
        /** First day of the depreciation; defaults to the cost date. */
        LocalDate depreciationStart
) {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * A hand-booked cost entered excl. VAT, before recurring definitions, the
     * inclusive entry and the bank link existed. Every other caller names the
     * inclusive total, so a dropped one is a compile error.
     */
    public CompanyCost(Long id, LocalDate date, String category, String description, String party,
                       BigDecimal amountExclEur, BigDecimal vatPct, String reference, LocalDate paidOn,
                       String salesChannel, String notes, Instant createdAt) {
        this(id, date, category, description, party, amountExclEur, null, vatPct, reference, paidOn, salesChannel, notes,
                createdAt, null, null);
    }

    /** A cost from before the VAT regimes: regime unknown and unconfirmed, fully deductible, no depreciation. */
    public CompanyCost(Long id, LocalDate date, String category, String description, String party,
                       BigDecimal amountExclEur, BigDecimal amountInclEur, BigDecimal vatPct, String reference,
                       LocalDate paidOn, String salesChannel, String notes, Instant createdAt, Long recurringCostId,
                       Long bankStatementLineId) {
        this(id, date, category, description, party, amountExclEur, amountInclEur, vatPct, reference, paidOn, salesChannel,
                notes, createdAt, recurringCostId, bankStatementLineId, null, null, null, null, null, null, null, null, null);
    }

    public boolean recurring() {
        return recurringCostId != null;
    }

    public BigDecimal amountExclEur() {
        return amountExclEur == null ? BigDecimal.ZERO : amountExclEur;
    }

    /** Never null in the JSON: a derived or backfilled regime reads false. */
    public Boolean vatRegimeConfirmed() {
        return Boolean.TRUE.equals(vatRegimeConfirmed);
    }

    /** 100 when nothing was stored, so the JSON always carries the number. */
    public BigDecimal vatDeductiblePct() {
        return vatDeductiblePct == null ? HUNDRED : vatDeductiblePct;
    }

    /** 100 when nothing was stored, so the JSON always carries the number. */
    public BigDecimal taxDeductiblePct() {
        return taxDeductiblePct == null ? HUNDRED : taxDeductiblePct;
    }

    /** True for a supplier outside Belgium whose VAT we self-assess. */
    public boolean reverseCharge() {
        return vatRegime != null && vatRegime.reverseCharge();
    }

    /** True for a cost depreciated over its useful life. */
    public boolean investment() {
        return costKind == CostKind.INVESTMENT;
    }

    /**
     * The VAT the rate gives: for a Belgian or foreign invoice what the entered
     * inclusive total leaves above excl., else the percentage on the excl.
     * amount; for a reverse-charged invoice always the percentage (no total
     * is stored); for import VAT the amount paid; nothing for the regimes
     * without VAT.
     */
    public BigDecimal vatEur() {
        if (vatRegime == VatRegime.EXEMPT || vatRegime == VatRegime.OUT_OF_SCOPE || vatRegime == VatRegime.NO_INVOICE) {
            return zero();
        }
        if (vatRegime == VatRegime.IMPORT_VAT_PAID) {
            return amountInclEur == null ? zero() : amountInclEur.subtract(amountExclEur()).setScale(2, RoundingMode.HALF_UP);
        }
        if (!reverseCharge() && amountInclEur != null) {
            return amountInclEur.subtract(amountExclEur()).setScale(2, RoundingMode.HALF_UP);
        }
        if (vatPct == null || vatPct.signum() <= 0) return zero();
        return amountExclEur().multiply(vatPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * The cash total: what leaves the bank. The entered inclusive total or
     * excl. plus VAT for a Belgian or foreign invoice; excl. alone when the
     * VAT is reverse charged or absent; the VAT paid for an import document.
     * Deliberately not the record accessor, which stays the raw entered value:
     * the JSON then tells the screen whether the cost was entered incl.
     */
    public BigDecimal totalInclEur() {
        if (vatRegime == VatRegime.IMPORT_VAT_PAID) return amountInclEur == null ? zero() : amountInclEur;
        if (vatRegime == null || vatRegime == VatRegime.BE || vatRegime == VatRegime.FOREIGN_VAT) {
            return amountInclEur != null ? amountInclEur : amountExclEur().add(vatEur());
        }
        return amountExclEur();
    }

    /** The VAT the return shows: Belgian, reverse charged (declared and deducted) or paid at customs; foreign VAT is a cost. */
    public BigDecimal returnVatEur() {
        if (vatRegime == null || vatRegime == VatRegime.BE || reverseCharge() || vatRegime == VatRegime.IMPORT_VAT_PAID) {
            return vatEur();
        }
        return zero();
    }

    /** Grid 59: the deductible share of the return VAT; import VAT is always deducted in full. */
    public BigDecimal deductibleVatEur() {
        BigDecimal share = vatRegime == VatRegime.IMPORT_VAT_PAID ? HUNDRED : vatDeductiblePct();
        return returnVatEur().multiply(share).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** The VAT we cannot deduct: it becomes cost and joins the base in the return. */
    public BigDecimal nonDeductibleVatEur() {
        return returnVatEur().subtract(deductibleVatEur());
    }

    /**
     * What the result analysis and the cost totals count: excl. plus the
     * non-deductible VAT for a Belgian or reverse-charged invoice, the whole
     * foreign bill, the bare amount for the regimes without VAT, nothing for
     * import VAT (it is a deduction, not a cost).
     */
    public BigDecimal expenseEur() {
        if (vatRegime == VatRegime.IMPORT_VAT_PAID) return zero();
        if (vatRegime == VatRegime.FOREIGN_VAT) return totalInclEur();
        if (vatRegime == null || vatRegime == VatRegime.BE || reverseCharge()) {
            return amountExclEur().add(nonDeductibleVatEur());
        }
        return amountExclEur();
    }

    public boolean paid() {
        return paidOn != null;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
