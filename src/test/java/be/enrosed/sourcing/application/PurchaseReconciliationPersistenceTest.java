package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchaseReconciliationPersistenceTest {
    @Inject PurchaseOrderService orders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void receiptAndPaymentCorrectionsKeepBudgetLedgerAndReconciliationLinked() {
        var supplier = suppliers.save(new Supplier(null, "Reconciliation Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var product = new ProductEntity();
        product.sku = "RECONCILIATION-LEDGER";
        product.name = "Reconciliation rose";
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        orders.update(order.id(), order.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new PurchaseOrderLine(null, product.id, 100, BigDecimal.TEN, Currency.USD, null, null)))
                .withInspectionCost(new BigDecimal("50")));

        var day = LocalDate.of(2026, 9, 1);
        var deposit = orders.addPayment(order.id(), day, new BigDecimal("300"), Currency.USD,
                "Deposit", PurchasePayment.Payee.SUPPLIER, false);
        var finalPayment = orders.addPayment(order.id(), day, new BigDecimal("600"), Currency.EUR,
                "Settlement", PurchasePayment.Payee.SUPPLIER, true);
        orders.addPayment(order.id(), day, new BigDecimal("70"), Currency.EUR,
                "Inspection", PurchasePayment.Payee.SEPARATE, true);
        var bankFee = orders.addPayment(order.id(), day, new BigDecimal("5"), Currency.EUR,
                "Bank fee", PurchasePayment.Payee.OTHER, true);

        var before = orders.reconciliation(order.id());
        assertEquals(new BigDecimal("950.00"), before.totals().plannedExternalEur());
        assertEquals(new BigDecimal("945.00"), before.totals().paidEur());
        assertEquals(new BigDecimal("-5.00"), before.totals().varianceEur());
        assertEquals(new BigDecimal("9.4500"), before.totals().forecastExternalUnitEur());
        assertTrue(before.totals().finalized());

        orders.receive(order.id(), new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(product.id, 97, 2)), false,
                new BigDecimal("945"), LocalDate.of(2026, 9, 8), null));
        entityManager.clear();
        var received = orders.reconciliation(order.id());
        assertEquals(new BigDecimal("950.00"), received.totals().plannedExternalEur(),
                "100 ordered pieces remain the budget even after only 97 arrive");
        assertEquals(95, received.totals().unitCostQuantity());
        assertEquals(new BigDecimal("9.9474"), received.totals().forecastExternalUnitEur());
        assertEquals(new BigDecimal("945.00"), received.totals().paidEur(), "legacy receipt total never doubles the ledger");
        assertEquals(4, orders.paymentsSince(null).stream().filter(row -> row.orderId() == order.id()).count());
        assertEquals(new BigDecimal("270.00"), orders.payments(order.id()).stream()
                .filter(p -> p.id().equals(deposit.id())).findFirst().orElseThrow().amountEur());

        orders.updatePayment(order.id(), finalPayment.id(), day, new BigDecimal("610"), Currency.EUR,
                "Settlement corrected", PurchasePayment.Payee.SUPPLIER, true);
        orders.deletePayment(order.id(), bankFee.id());
        var corrected = orders.reconciliation(order.id());
        var bankRows = orders.paymentsSince(null).stream().filter(row -> row.orderId() == order.id()).toList();
        assertEquals(3, bankRows.size());
        assertFalse(bankRows.stream().anyMatch(row -> row.id().equals(bankFee.id())));
        assertEquals(new BigDecimal("950.00"), bankRows.stream().map(PurchaseOrderService.PaymentRow::amountEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(new BigDecimal("950.00"), corrected.totals().forecastExternalEur());
        assertEquals(new BigDecimal("0.00"), corrected.totals().varianceEur());
        assertEquals(new BigDecimal("10.0000"), corrected.totals().forecastExternalUnitEur());
    }
}
