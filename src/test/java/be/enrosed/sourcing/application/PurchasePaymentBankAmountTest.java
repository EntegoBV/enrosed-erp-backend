package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The euro value the bank charged for a foreign transfer may be given with
 * the payment; without it the order's frozen rates convert as before.
 */
@QuarkusTest
class PurchasePaymentBankAmountTest {
    @Inject PurchaseOrderService orders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entityManager;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    private PurchaseOrder orderedContainer() {
        var supplier = suppliers.save(new Supplier(null, "Bank Amount Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var product = new ProductEntity();
        product.sku = "BANK-AMOUNT-" + order.id();
        product.name = "Bank amount rose";
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        orders.update(order.id(), order.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new PurchaseOrderLine(null, product.id, 100, BigDecimal.TEN, Currency.USD, null, null))));
        return orders.get(order.id());
    }

    private static PurchaseReconciliation.Stream supplierStream(PurchaseReconciliation reconciliation) {
        return reconciliation.streams().stream()
                .filter(stream -> stream.payee() == PurchasePayment.Payee.SUPPLIER).findFirst().orElseThrow();
    }

    @Test
    @TestTransaction
    void aUsdPaymentKeepsTheBankEuroAmountAndTheReconciliationReadsIt() {
        var order = orderedContainer();
        var payment = orders.addPayment(order.id(), DAY, new BigDecimal("300"), Currency.USD, "Deposit",
                PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("281.456"));
        assertEquals(new BigDecimal("281.46"), payment.amountEur(), "the bank figure, rounded to cents");
        assertEquals(new BigDecimal("281.46"), orders.payments(order.id()).get(0).amountEur());
        assertEquals(new BigDecimal("281.46"), supplierStream(orders.reconciliation(order.id())).paidEur());
    }

    @Test
    @TestTransaction
    void aUsdPaymentWithoutBankAmountConvertsAtTheOrderRate() {
        var order = orderedContainer();
        var payment = orders.addPayment(order.id(), DAY, new BigDecimal("300"), Currency.USD, "Deposit",
                PurchasePayment.Payee.SUPPLIER, false, null, null);
        assertEquals(new BigDecimal("270.00"), payment.amountEur(), "300 USD at 0.90");
    }

    @Test
    @TestTransaction
    void aCnyPaymentKeepsTheBankEuroAmount() {
        var order = orderedContainer();
        var payment = orders.addPayment(order.id(), DAY, new BigDecimal("2000"), Currency.CNY, "Sample",
                PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("260.10"));
        assertEquals(new BigDecimal("260.10"), payment.amountEur(), "not 2000 × 0.14 × 0.90 = 252.00");
    }

    @Test
    @TestTransaction
    void aEuroPaymentRefusesADifferentBankAmount() {
        var order = orderedContainer();
        var refused = assertThrows(BusinessRuleException.class, () -> orders.addPayment(order.id(), DAY,
                new BigDecimal("500"), Currency.EUR, null, PurchasePayment.Payee.SUPPLIER, false, null,
                new BigDecimal("480")));
        assertEquals("Voor een betaling in euro is het eurobedrag gelijk aan het bedrag", refused.getMessage());
        var same = orders.addPayment(order.id(), DAY, new BigDecimal("500"), Currency.EUR, null,
                PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("500.00"));
        assertEquals(new BigDecimal("500.00"), same.amountEur());
    }

    @Test
    @TestTransaction
    void aBankAmountMustBePositive() {
        var order = orderedContainer();
        var zero = assertThrows(BusinessRuleException.class, () -> orders.addPayment(order.id(), DAY,
                new BigDecimal("300"), Currency.USD, null, PurchasePayment.Payee.SUPPLIER, false, null,
                BigDecimal.ZERO));
        assertEquals("Geef een eurobedrag groter dan nul op", zero.getMessage());
        var negative = assertThrows(BusinessRuleException.class, () -> orders.addPayment(order.id(), DAY,
                new BigDecimal("300"), Currency.USD, null, PurchasePayment.Payee.SUPPLIER, false, null,
                new BigDecimal("-1")));
        assertEquals("Geef een eurobedrag groter dan nul op", negative.getMessage());
    }

    @Test
    @TestTransaction
    void correctionsKeepTheBankAmountUntilTheForeignMoneyChanges() {
        var order = orderedContainer();
        var payment = orders.addPayment(order.id(), DAY, new BigDecimal("300"), Currency.USD, "Deposit",
                PurchasePayment.Payee.SUPPLIER, false, null, new BigDecimal("281.46"));

        /* Settling the stream sends no bank amount and must not revalue the transfer. */
        var settled = orders.updatePayment(order.id(), payment.id(), DAY, new BigDecimal("300"), Currency.USD,
                "Deposit", PurchasePayment.Payee.SUPPLIER, true, null, false, null);
        assertEquals(new BigDecimal("281.46"), settled.amountEur(), "settle keeps the stored bank amount");

        /* A new foreign amount without a bank figure goes back to the order rate. */
        var reconverted = orders.updatePayment(order.id(), payment.id(), DAY, new BigDecimal("400"), Currency.USD,
                "Deposit", PurchasePayment.Payee.SUPPLIER, true, null, false, null);
        assertEquals(new BigDecimal("360.00"), reconverted.amountEur(), "400 USD at 0.90");

        /* A bank figure on PUT overrides the unchanged-money rule. */
        var corrected = orders.updatePayment(order.id(), payment.id(), DAY, new BigDecimal("400"), Currency.USD,
                "Deposit", PurchasePayment.Payee.SUPPLIER, true, null, false, new BigDecimal("372.90"));
        assertEquals(new BigDecimal("372.90"), corrected.amountEur());
        assertEquals(new BigDecimal("372.90"), supplierStream(orders.reconciliation(order.id())).paidEur());
    }
}
