package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Money the supplier still owes us on a container: pieces that never came,
 * pieces that came broken, a price that was wrong ("Tegoed leverancier").
 *
 * Deliberately not a negative {@link PurchasePayment}: every reader of the
 * payment ledger counts a payment as money that went out, and a credit is
 * money that comes back. A credit lowers the container's supplier cost from
 * the moment it is noted; how it is settled only says where the money went:
 * still to receive, refunded to the bank, or offset as a payment on another
 * container of the same supplier.
 */
public record PurchaseSupplierCredit(
        Long id,
        long orderId,
        LocalDate notedOn,
        /** In the currency it was agreed in with the supplier. */
        BigDecimal amount,
        Currency currency,
        /** What the credit is worth in euro: the order's rate, or the bank's euro once refunded. */
        BigDecimal amountEur,
        Reason reason,
        String note,
        Status status,
        /** The day it was refunded or offset; null while open. */
        LocalDate settledOn,
        /** The container whose supplier payment took this credit; null unless offset. */
        Long offsetOrderId,
        /** That payment; null unless offset. */
        Long offsetPaymentId,
        String actor,
        Instant recordedAt
) {
    public enum Reason {
        SHORTAGE, DAMAGE, PRICE, OTHER;

        public String dutchLabel() {
            return switch (this) {
                case SHORTAGE -> "Tekort";
                case DAMAGE -> "Schade";
                case PRICE -> "Prijsverschil";
                case OTHER -> "Andere";
            };
        }
    }

    public enum Status {
        OPEN, OFFSET, REFUNDED;

        public String dutchLabel() {
            return switch (this) {
                case OPEN -> "Tegoed open";
                case OFFSET -> "Verrekend";
                case REFUNDED -> "Terugbetaald";
            };
        }
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    /** The same credit with other terms, settlement or offset. */
    public PurchaseSupplierCredit with(BigDecimal amount, Currency currency, BigDecimal amountEur, Reason reason,
                                       String note, Status status, LocalDate settledOn,
                                       Long offsetOrderId, Long offsetPaymentId) {
        return new PurchaseSupplierCredit(id, orderId, notedOn, amount, currency, amountEur, reason, note, status,
                settledOn, offsetOrderId, offsetPaymentId, actor, recordedAt);
    }

    /** Back to "te ontvangen": no settlement day, no offset. */
    public PurchaseSupplierCredit reopened() {
        return with(amount, currency, amountEur, reason, note, Status.OPEN, null, null, null);
    }
}
