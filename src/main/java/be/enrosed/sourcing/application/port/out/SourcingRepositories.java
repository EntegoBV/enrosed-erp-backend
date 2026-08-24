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
        be.enrosed.sourcing.domain.PurchasePayment save(be.enrosed.sourcing.domain.PurchasePayment payment);
        boolean delete(long orderId, long paymentId);
    }

    interface Documents {
        List<be.enrosed.sourcing.domain.PurchaseDocument> forOrder(long orderId);
        java.util.Optional<be.enrosed.sourcing.domain.PurchaseDocument> find(long orderId, long documentId);
        be.enrosed.sourcing.domain.PurchaseDocument save(be.enrosed.sourcing.domain.PurchaseDocument document);
        java.util.Optional<be.enrosed.sourcing.domain.PurchaseDocument> rename(long orderId, long documentId, String label);
        boolean delete(long orderId, long documentId);
    }

    interface PurchaseOrders {
        List<PurchaseOrder> findAll();
        Optional<PurchaseOrder> findById(long id);
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
    }
}
