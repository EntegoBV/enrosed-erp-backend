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
        Long recurringCostId
) {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** A hand-booked cost, before recurring definitions existed. */
    public CompanyCost(Long id, LocalDate date, String category, String description, String party,
                       BigDecimal amountExclEur, BigDecimal vatPct, String reference, LocalDate paidOn,
                       String salesChannel, String notes, Instant createdAt) {
        this(id, date, category, description, party, amountExclEur, vatPct, reference, paidOn, salesChannel, notes, createdAt, null);
    }

    public boolean recurring() {
        return recurringCostId != null;
    }

    public BigDecimal amountExclEur() {
        return amountExclEur == null ? BigDecimal.ZERO : amountExclEur;
    }

    public BigDecimal vatEur() {
        if (vatPct == null || vatPct.signum() <= 0) return BigDecimal.ZERO;
        return amountExclEur().multiply(vatPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public BigDecimal amountInclEur() {
        return amountExclEur().add(vatEur());
    }

    public boolean paid() {
        return paidOn != null;
    }
}
