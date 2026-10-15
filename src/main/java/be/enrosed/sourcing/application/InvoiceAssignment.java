package be.enrosed.sourcing.application;

import be.enrosed.sourcing.domain.PurchaseInvoice;
import be.enrosed.sourcing.domain.PurchasePayment;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Which invoice a payment belongs to. Pure: it reads the invoices and the
 * payments of one order and writes nothing.
 *
 *   1. the invoice the payment names, when it is one of this order;
 *   2. else the payee's only invoice (two or more: none, a choice is needed);
 *   3. else, for an inspection paid through the supplier, the supplier's
 *      primary invoice;
 *   4. else none.
 *
 * The primary invoice of a payee is its first registered one (lowest id): it
 * never changes when a later invoice is added or redated, only deleting it
 * promotes the next one.
 */
public final class InvoiceAssignment {

    private InvoiceAssignment() {}

    public static Long resolve(PurchasePayment payment, List<PurchaseInvoice> invoicesOfOrder,
                               List<PurchasePayment> paymentsOfOrder, boolean inspectionViaSupplier) {
        if (payment == null || invoicesOfOrder == null || invoicesOfOrder.isEmpty()) return null;
        if (payment.invoiceId() != null && invoicesOfOrder.stream()
                .anyMatch(invoice -> payment.invoiceId().equals(invoice.id()))) {
            return payment.invoiceId();
        }
        List<PurchaseInvoice> own = invoicesOfOrder.stream()
                .filter(invoice -> invoice.payee() == payment.payee()).toList();
        if (own.size() == 1) return own.getFirst().id();
        if (own.size() > 1) return null;
        if (viaSupplier(payment, paymentsOfOrder, inspectionViaSupplier)) {
            PurchaseInvoice primary = primary(invoicesOfOrder, PurchasePayment.Payee.SUPPLIER);
            return primary == null ? null : primary.id();
        }
        return null;
    }

    /**
     * An inspection (or another named cost) paid through the supplier: the
     * container says so, and the payment either is not on the bank yet or
     * sits on a transfer that also carries a supplier payment of this order.
     * A lab fee paid in a transfer of its own is not "via leverancier".
     */
    public static boolean viaSupplier(PurchasePayment payment, List<PurchasePayment> paymentsOfOrder,
                                      boolean inspectionViaSupplier) {
        if (!inspectionViaSupplier || payment == null || payment.payee() != PurchasePayment.Payee.SEPARATE) return false;
        if (payment.bankStatementLineId() == null) return true;
        return paymentsOfOrder != null && paymentsOfOrder.stream().anyMatch(other -> other != null
                && other.payee() == PurchasePayment.Payee.SUPPLIER
                && payment.bankStatementLineId().equals(other.bankStatementLineId()));
    }

    /** The payee's first registered invoice; null when it has none. */
    public static PurchaseInvoice primary(List<PurchaseInvoice> invoicesOfOrder, PurchasePayment.Payee payee) {
        if (invoicesOfOrder == null) return null;
        return invoicesOfOrder.stream()
                .filter(invoice -> invoice.payee() == payee && invoice.id() != null)
                .min(Comparator.comparing(PurchaseInvoice::id)).orElse(null);
    }

    public static boolean isPrimary(PurchaseInvoice invoice, List<PurchaseInvoice> invoicesOfOrder) {
        PurchaseInvoice primary = invoice == null ? null : primary(invoicesOfOrder, invoice.payee());
        return primary != null && Objects.equals(primary.id(), invoice.id());
    }

    /**
     * The supplier's primary invoice covers the inspection while it is paid
     * through the supplier and no inspection invoice of its own exists.
     */
    public static boolean coversInspection(PurchaseInvoice invoice, List<PurchaseInvoice> invoicesOfOrder,
                                           boolean inspectionViaSupplier) {
        return inspectionViaSupplier && invoice != null && invoice.payee() == PurchasePayment.Payee.SUPPLIER
                && isPrimary(invoice, invoicesOfOrder)
                && invoicesOfOrder.stream().noneMatch(other -> other.payee() == PurchasePayment.Payee.SEPARATE);
    }

    /**
     * Whether a payment of this payee may name this invoice: its own payee's,
     * or for an inspection paid through the supplier a supplier invoice.
     */
    public static boolean mayCarry(PurchaseInvoice invoice, PurchasePayment.Payee paymentPayee,
                                   boolean inspectionViaSupplier) {
        if (invoice == null) return false;
        PurchasePayment.Payee payee = paymentPayee == null ? PurchasePayment.Payee.SUPPLIER : paymentPayee;
        return invoice.payee() == payee || payee == PurchasePayment.Payee.SEPARATE
                && invoice.payee() == PurchasePayment.Payee.SUPPLIER && inspectionViaSupplier;
    }
}
