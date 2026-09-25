package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A received bank movement. Links are owned by the invoice; timestamps retain the entered timezone.
 *
 * An offset ("verrekening") is a pair of rows that moves no bank money: a
 * negative row on a credit note and a positive row on an invoice, each
 * naming the other document and the other row.
 */
public record SalesPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                           String timeZone, String reference, Instant recordedAt, String actor, boolean legacy,
                           String bankAccount, Long offsetOrderId, Long offsetPaymentId) {
    public SalesPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                        String timeZone, String reference, Instant recordedAt, String actor, boolean legacy,
                        String bankAccount) {
        this(id, salesOrderId, amountEur, receivedAt, timeZone, reference, recordedAt, actor, legacy, bankAccount, null, null);
    }
    public SalesPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                        String timeZone, String reference, Instant recordedAt, String actor, boolean legacy) {
        this(id, salesOrderId, amountEur, receivedAt, timeZone, reference, recordedAt, actor, legacy, null);
    }

    /** One half of an offset pair; never a bank movement. */
    public boolean isOffset() {
        return offsetPaymentId != null;
    }
}
