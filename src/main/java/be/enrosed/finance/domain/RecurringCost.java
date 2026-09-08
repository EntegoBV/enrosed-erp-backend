package be.enrosed.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A cost that returns on a fixed rhythm: the rent, the accountant's monthly
 * fee, software, insurance, the TICA stand. The booking job turns every due
 * occurrence into a {@link CompanyCost}, so the books fill themselves.
 */
public record RecurringCost(
        Long id,
        String name,
        String category,
        String party,
        BigDecimal amountExclEur,
        BigDecimal vatPct,
        String salesChannel,
        Interval interval,
        /** The first occurrence; every later one is counted from here. */
        LocalDate startDate,
        /** The last occurrence, inclusive; null runs until stopped. */
        LocalDate endDate,
        /** The next occurrence still to book; null once the schedule has ended. */
        LocalDate nextDate,
        boolean active,
        /** A direct debit or standing order: the booked cost is marked paid on its date. */
        boolean autoPaid,
        String reference,
        String notes,
        LocalDate lastBookedOn,
        Instant createdAt
) {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int OCCURRENCE_LIMIT = 20_000;

    public enum Interval {
        WEEKLY("Wekelijks"), MONTHLY("Maandelijks"), QUARTERLY("Per kwartaal"), HALF_YEARLY("Halfjaarlijks"), YEARLY("Jaarlijks");

        private final String label;

        Interval(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The n-th step from a start. Months count from the start, so a cost on the 31st never drifts to the 28th for good. */
        public LocalDate step(LocalDate start, int times) {
            return switch (this) {
                case WEEKLY -> start.plusWeeks(times);
                case MONTHLY -> start.plusMonths(times);
                case QUARTERLY -> start.plusMonths(3L * times);
                case HALF_YEARLY -> start.plusMonths(6L * times);
                case YEARLY -> start.plusYears(times);
            };
        }
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

    /** The first occurrence on or after a day; null when the schedule ends before it. */
    public LocalDate firstOccurrenceFrom(LocalDate day) {
        if (interval == null || startDate == null || day == null) return null;
        for (int index = 0; index < OCCURRENCE_LIMIT; index++) {
            LocalDate candidate = interval.step(startDate, index);
            if (endDate != null && candidate.isAfter(endDate)) return null;
            if (!candidate.isBefore(day)) return candidate;
        }
        return null;
    }

    /** The occurrence that follows a given one. */
    public LocalDate occurrenceAfter(LocalDate day) {
        return day == null ? null : firstOccurrenceFrom(day.plusDays(1));
    }

    public RecurringCost withProgress(LocalDate next, LocalDate lastBooked, boolean stillActive) {
        return new RecurringCost(id, name, category, party, amountExclEur, vatPct, salesChannel, interval, startDate, endDate,
                next, stillActive, autoPaid, reference, notes, lastBooked, createdAt);
    }
}
