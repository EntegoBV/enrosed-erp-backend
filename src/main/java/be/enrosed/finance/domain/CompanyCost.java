package be.enrosed.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A cost the company made outside purchasing: the fair, the accountant,
 * rent, the TICA stand, software, transport. Booked by date and category,
 * excluding VAT, so the result analysis can set it against the sales.
 */
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
         */
        BigDecimal amountInclEur,
        /** The VAT on top; null when the cost carries none. */
        BigDecimal vatPct,
        /** The supplier's invoice number or another reference. */
        String reference,
        /** When it was paid; null while it is still open. */
        LocalDate paidOn,
        /** The sales channel the cost belongs to, when it does: TICA for the stand, FAIR for a fair. */
        String salesChannel,
        String notes,
        Instant createdAt,
        /** The recurring definition that booked this cost automatically; null for a hand-booked cost. */
        Long recurringCostId,
        /** The bank line that paid this cost, set by the bank service when the movement is noted; null until then. */
        Long bankStatementLineId
) {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** A hand-booked cost, before recurring definitions existed. */
    public CompanyCost(Long id, LocalDate date, String category, String description, String party,
                       BigDecimal amountExclEur, BigDecimal vatPct, String reference, LocalDate paidOn,
                       String salesChannel, String notes, Instant createdAt) {
        this(id, date, category, description, party, amountExclEur, vatPct, reference, paidOn, salesChannel, notes, createdAt, null);
    }

    /** A cost entered excl. VAT, as every cost was before the inclusive entry existed. */
    public CompanyCost(Long id, LocalDate date, String category, String description, String party,
                       BigDecimal amountExclEur, BigDecimal vatPct, String reference, LocalDate paidOn,
                       String salesChannel, String notes, Instant createdAt, Long recurringCostId) {
        this(id, date, category, description, party, amountExclEur, null, vatPct, reference, paidOn, salesChannel, notes,
                createdAt, recurringCostId, null);
    }

    public boolean recurring() {
        return recurringCostId != null;
    }

    public BigDecimal amountExclEur() {
        return amountExclEur == null ? BigDecimal.ZERO : amountExclEur;
    }

    /** The VAT: what the entered inclusive total leaves above excl., else the percentage on the excl. amount. */
    public BigDecimal vatEur() {
        if (amountInclEur != null) return amountInclEur.subtract(amountExclEur()).setScale(2, RoundingMode.HALF_UP);
        if (vatPct == null || vatPct.signum() <= 0) return BigDecimal.ZERO;
        return amountExclEur().multiply(vatPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * The total incl. VAT: the entered one when the cost was entered incl.,
     * else excl. plus VAT. Deliberately not the record accessor, which stays
     * the raw entered value: the JSON then tells the screen whether the cost
     * was entered incl. (a number) or excl. (null).
     */
    public BigDecimal totalInclEur() {
        return amountInclEur != null ? amountInclEur : amountExclEur().add(vatEur());
    }

    public boolean paid() {
        return paidOn != null;
    }
}
