package be.enrosed.sourcing.application.port.out;

import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.Supplier;

import java.util.List;
import java.util.Optional;

public interface SourcingRepositories {

    interface Suppliers {
        List<Supplier> findAll();
        Optional<Supplier> findById(long id);
        Supplier save(Supplier supplier);
        void deleteById(long id);
    }

    interface Payments {
        List<be.enrosed.sourcing.domain.PurchasePayment> forOrder(long orderId);
        /** Every payment on every order, oldest first; from a day when given. */
        List<be.enrosed.sourcing.domain.PurchasePayment> since(java.time.LocalDate from);
        be.enrosed.sourcing.domain.PurchasePayment save(be.enrosed.sourcing.domain.PurchasePayment payment);
        boolean delete(long orderId, long paymentId);
        /** Every member of a group (the root included), by day then id; empty for a payment never split. */
        default List<be.enrosed.sourcing.domain.PurchasePayment> inGroup(long rootId) {
            return since(null).stream().filter(payment -> Long.valueOf(rootId).equals(payment.groupId())).toList();
        }
        /** Every payment of the order that names this invoice goes back to automatic. Column-only. */
        default void clearInvoice(long orderId, long invoiceId) {
            throw new UnsupportedOperationException("invoices not supported by this adapter");
        }
        /** Names (or, with null, stops naming) an invoice on these payments. Column-only: no other field changes. */
        default void setInvoice(java.util.Collection<Long> paymentIds, Long invoiceId) {
            throw new UnsupportedOperationException("invoices not supported by this adapter");
        }
        /** Puts these payments in a group (or, with null, out of it). Column-only: no other field changes. */
        default void setGroup(java.util.Collection<Long> paymentIds, Long groupId) {
            throw new UnsupportedOperationException("groups not supported by this adapter");
        }
        default long deleteForOrder(long orderId) {
            long deleted = 0;
            for (be.enrosed.sourcing.domain.PurchasePayment payment : List.copyOf(forOrder(orderId))) {
                if (delete(orderId, payment.id())) deleted++;
            }
            return deleted;
        }
    }

    /**
     * What payments were before they first followed their bank transaction. The first adjustment wins; only a hand
     * edit of the amount or date and deleting the payment remove a row.
     */
    interface PaymentBankAdjustments {
        /** The rows of these payments, by payment id; payments without one are absent. */
        java.util.Map<Long, be.enrosed.sourcing.domain.PurchasePaymentBankAdjustment> find(java.util.Collection<Long> paymentIds);
        /** Writes the row unless the payment already has one; true when it was written. */
        boolean insertIfAbsent(be.enrosed.sourcing.domain.PurchasePaymentBankAdjustment row);
        void delete(long paymentId);
    }

    /** Credits the supplier owes on a container ("Tegoed leverancier"). */
    interface SupplierCredits {
        /** The credits noted on this order, oldest first. */
        List<be.enrosed.sourcing.domain.PurchaseSupplierCredit> forOrder(long orderId);
        /** The credits of other orders that were offset as a payment on this one. */
        List<be.enrosed.sourcing.domain.PurchaseSupplierCredit> offsetOnto(long orderId);
        Optional<be.enrosed.sourcing.domain.PurchaseSupplierCredit> find(long orderId, long creditId);
        /** The credit an offset payment settles, if any. */
        Optional<be.enrosed.sourcing.domain.PurchaseSupplierCredit> forOffsetPayment(long paymentId);
        /** Every payment that is a credit offset, on any order: no bank line, no proof needed. */
        default java.util.Set<Long> offsetPaymentIds() {
            return java.util.Set.of();
        }
        be.enrosed.sourcing.domain.PurchaseSupplierCredit save(be.enrosed.sourcing.domain.PurchaseSupplierCredit credit);
        boolean delete(long orderId, long creditId);
    }

    interface Documents {
        List<be.enrosed.sourcing.domain.PurchaseDocument> forOrder(long orderId);
        java.util.Optional<be.enrosed.sourcing.domain.PurchaseDocument> find(long orderId, long documentId);
        be.enrosed.sourcing.domain.PurchaseDocument save(be.enrosed.sourcing.domain.PurchaseDocument document);
        java.util.Optional<be.enrosed.sourcing.domain.PurchaseDocument> rename(long orderId, long documentId, String label);
        boolean delete(long orderId, long documentId);
        /** The files of one payment go to another (null: to the container itself). Column-only. */
        default void reassignPayment(long orderId, long fromPaymentId, Long toPaymentId) {
            throw new UnsupportedOperationException("reassigning documents not supported by this adapter");
        }
        /** Every document of these kinds on every order, in one read. */
        default List<be.enrosed.sourcing.domain.PurchaseDocument> ofKinds(
                java.util.Collection<be.enrosed.sourcing.domain.PurchaseDocument.Kind> kinds) {
            return List.of();
        }
        /** These documents, whatever their order or kind, in one read; unknown ids are absent. */
        default List<be.enrosed.sourcing.domain.PurchaseDocument> byIds(java.util.Collection<Long> ids) {
            return List.of();
        }
        default long deleteForOrder(long orderId) {
            long deleted = 0;
            for (be.enrosed.sourcing.domain.PurchaseDocument document : List.copyOf(forOrder(orderId))) {
                if (delete(orderId, document.id())) deleted++;
            }
            return deleted;
        }
    }

    /** The invoices of the containers: number, date, amount and the file they came in. */
    interface Invoices {
        /** The invoices of one order, by invoice date then id. */
        List<be.enrosed.sourcing.domain.PurchaseInvoice> forOrder(long orderId);
        /** Every invoice of every order, by order, invoice date then id. */
        List<be.enrosed.sourcing.domain.PurchaseInvoice> all();
        Optional<be.enrosed.sourcing.domain.PurchaseInvoice> find(long orderId, long invoiceId);
        be.enrosed.sourcing.domain.PurchaseInvoice save(be.enrosed.sourcing.domain.PurchaseInvoice invoice);
        boolean delete(long orderId, long invoiceId);
        /** The file is gone: the invoices that held it keep their data without a file. */
        void clearDocument(long orderId, long documentId);
    }

    interface PurchaseOrders {
        List<PurchaseOrder> findAll();
        /** Deleted documents still reserve their original number for a safe restore. */
        default List<String> numbersIncludingDeleted() {
            return findAll().stream().map(PurchaseOrder::number).filter(java.util.Objects::nonNull).toList();
        }
        default boolean referencesPartnerIncludingDeleted(long customerId) {
            return findAll().stream().anyMatch(order -> Long.valueOf(customerId).equals(order.partnerCustomerId()));
        }
        default boolean referencesSupplierIncludingDeleted(long supplierId) {
            return findAll().stream().anyMatch(order -> Long.valueOf(supplierId).equals(order.supplierId()));
        }
        /**
         * Purchase lines that name this supplier row, or carry its product on a container of its supplier,
         * trashed containers included (the persistent adapter counts them; this fallback sees live ones only).
         */
        default long linesUsingProductSupplierIncludingDeleted(long rowId, long productId, long supplierId) {
            return findAll().stream().mapToLong(order -> order.lines().stream()
                    .filter(line -> Long.valueOf(rowId).equals(line.productSupplierId())
                            || (Long.valueOf(productId).equals(line.productId())
                                && Long.valueOf(supplierId).equals(order.supplierId())))
                    .count()).sum();
        }
        Optional<PurchaseOrder> findById(long id);
        /**
         * Number and "Herkenbare naam" of many orders at once, without their
         * lines; unknown or deleted ids are simply absent. Persistent adapters
         * read a projection; this fallback serves the in-memory ones.
         */
        default java.util.Map<Long, be.enrosed.sourcing.domain.PurchaseOrderName> names(java.util.Collection<Long> ids) {
            java.util.Map<Long, be.enrosed.sourcing.domain.PurchaseOrderName> names = new java.util.HashMap<>();
            if (ids == null) return names;
            for (Long id : ids) {
                if (id == null || names.containsKey(id)) continue;
                findById(id).ifPresent(order -> names.put(id,
                        new be.enrosed.sourcing.domain.PurchaseOrderName(id, order.number(), order.alias())));
            }
            return names;
        }
        /**
         * Locks one order for a lifecycle-changing transaction.
         *
         * The fallback keeps in-memory adapters simple; persistent adapters
         * override this with a database row lock so two receipt requests
         * cannot both observe the order as not yet received.
         */
        default Optional<PurchaseOrder> findByIdForUpdate(long id) {
            return findById(id);
        }
        PurchaseOrder save(PurchaseOrder order);
        void deleteById(long id);
        /**
         * Puts an order in the archive (a moment) or back on the working
         * list (null). Kept apart from {@link #save} so no ordinary edit can
         * archive or unarchive by accident.
         */
        default void setArchivedAt(long id, java.time.Instant at) {
            throw new UnsupportedOperationException("archive not supported by this adapter");
        }
    }
}
