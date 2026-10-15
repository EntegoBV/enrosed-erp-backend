package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An invoice that belongs to a container: number, date and amount, with the
 * file it came in. Every payment term of the payee points at it (the payee's
 * only invoice, or the one a payment names), so the commercial invoice is
 * uploaded once and compared with the Afspraak once.
 *
 * Several invoices per payee add up (an extra invoice on top); a correction
 * is made on the invoice itself, and a credit note stays a supplier credit.
 */
public record PurchaseInvoice(
        Long id,
        long orderId,
        /** The payment stream it belongs to: the supplier, the road, the inspection, other costs. */
        PurchasePayment.Payee payee,
        String number,
        LocalDate invoiceDate,
        BigDecimal amount,
        Currency currency,
        /** The euro value: as given for a foreign invoice, else at the container rate of its payee. */
        BigDecimal amountEur,
        /** The purchase document holding the file; null while none was uploaded. */
        Long documentId,
        String notes,
        String actor,
        Instant recordedAt
) {
    public PurchasePayment.Payee payee() {
        return payee == null ? PurchasePayment.Payee.SUPPLIER : payee;
    }
}
