package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class PurchaseOrderCascadeDeletePersistenceTest {

    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService supplierService;
    @Inject SourcingRepositories.PurchaseOrders orderRepository;
    @Inject SourcingRepositories.Payments paymentRepository;
    @Inject SourcingRepositories.Documents documentRepository;
    @Inject SourcingRepositories.Suppliers supplierRepository;
    @Inject PhotoStorage storage;

    @Test
    void dependentRowsAndBlobsFollowTheOwningDeleteTransactionBoundary() {
        Setup setup = QuarkusTransaction.requiringNew().call(() -> {
            Supplier supplier = supplierService.save(new Supplier(null,
                    "Cascade cleanup " + System.nanoTime(), "CN", "Yiwu",
                    null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
            var order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"),
                    new BigDecimal("0.90"), BigDecimal.TEN);
            paymentRepository.save(new PurchasePayment(null, order.id(), LocalDate.of(2026, 8, 27),
                    BigDecimal.ONE, Currency.EUR, BigDecimal.ONE, "Testbetaling", "Emre",
                    Instant.parse("2026-08-27T10:00:00Z")));
            PurchaseDocument document = purchaseOrders.addDocument(order.id(),
                    PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, null,
                    "invoice.pdf", "application/pdf", new byte[] { 1, 2, 3 });
            return new Setup(supplier.id(), order.id(), document.storageKey());
        });

        assertEquals(new State(true, 1, 1, true), state(setup));

        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            purchaseOrders.delete(setup.orderId());
            throw new IllegalStateException("force rollback after delete");
        }));
        assertEquals(new State(true, 1, 1, true), state(setup),
                "a rollback must retain the order, its rows and its blob");

        QuarkusTransaction.requiringNew().run(() -> purchaseOrders.delete(setup.orderId()));
        assertEquals(new State(false, 0, 0, false), state(setup),
                "the committed delete removes dependent rows and only then its blob");

        QuarkusTransaction.requiringNew().run(() -> supplierRepository.deleteById(setup.supplierId()));
    }

    private State state(Setup setup) {
        return QuarkusTransaction.requiringNew().call(() -> new State(
                orderRepository.findById(setup.orderId()).isPresent(),
                paymentRepository.forOrder(setup.orderId()).size(),
                documentRepository.forOrder(setup.orderId()).size(),
                storage.exists(setup.storageKey())));
    }

    private record Setup(long supplierId, long orderId, String storageKey) {}

    private record State(boolean orderExists, int payments, int documents, boolean blobExists) {}
}
