package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** A received bank movement. Links are owned by the invoice; timestamps retain the entered timezone. */
public record SalesPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                           String timeZone, String reference, Instant recordedAt, String actor, boolean legacy,
                           String bankAccount) {
    public SalesPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                        String timeZone, String reference, Instant recordedAt, String actor, boolean legacy) {
        this(id, salesOrderId, amountEur, receivedAt, timeZone, reference, recordedAt, actor, legacy, null);
    }
}
