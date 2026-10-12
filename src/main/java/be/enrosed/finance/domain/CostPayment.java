package be.enrosed.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One payment on a company cost. A KBC invoice of a month is paid by several
 * transfers; the cost is paid once the payments cover its cash total, and
 * every payment links to its own bank line.
 */
public record CostPayment(
        Long id,
        Long companyCostId,
        LocalDate paidOn,
        BigDecimal amountEur,
        /** The bank account it left, when it was noted with one: KBC, ING. */
        String account,
        /** The bank line that carried this payment, set by the bank service; null until then. */
        Long bankStatementLineId,
        String note,
        Instant recordedAt,
        String actor
) {
    /** True once the bank service linked this payment to a bank line. */
    public boolean onTheBank() {
        return bankStatementLineId != null;
    }
}
