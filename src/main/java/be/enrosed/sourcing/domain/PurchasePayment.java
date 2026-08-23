package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One amount paid on a purchase order - a 30% deposit at ordering, the
 * balance before shipping, a correction after receipt.
 *
 * Kept as paid: the amount in the currency it left in, with the euro
 * equivalent at the order's rate that day, so later cost management can
 * follow the money without re-deriving anything.
 */
public record PurchasePayment(
        Long id,
        long orderId,
        LocalDate paidOn,
        BigDecimal amount,
        Currency currency,
        /** What the amount was worth in euro when it was booked. */
        BigDecimal amountEur,
        /** "Aanbetaling 30%", "Saldo", "Slotbetaling" - whatever the bank line says. */
        String label,
        String actor,
        Instant recordedAt,
        /** Who got the money: the factory, or the forwarder and customs. Null reads as supplier. */
        Payee payee
) {
    /** Money goes two ways: to the supplier for the goods, and to the forwarder and customs for the road. */
    public enum Payee {
        SUPPLIER, LOGISTICS;

        public String dutchLabel() {
            return this == SUPPLIER ? "Leverancier" : "Douane & transport";
        }
    }

    /** Compatibility for callers written before the payee existed. */
    public PurchasePayment(Long id, long orderId, LocalDate paidOn, BigDecimal amount, Currency currency,
                           BigDecimal amountEur, String label, String actor, Instant recordedAt) {
        this(id, orderId, paidOn, amount, currency, amountEur, label, actor, recordedAt, null);
    }

    public Payee payee() {
        return payee == null ? Payee.SUPPLIER : payee;
    }
}
