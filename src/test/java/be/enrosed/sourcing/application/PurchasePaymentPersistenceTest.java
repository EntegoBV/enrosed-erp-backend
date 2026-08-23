package be.enrosed.sourcing.application;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PurchasePaymentPersistenceTest {
    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService suppliers;

    @Test
    @TestTransaction
    void paymentsAreKeptAsPaidWithTheirEuroValueAtTheOrdersRate() throws Exception {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Pay Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);

        PurchasePayment deposit = purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 8, 1),
                new BigDecimal("3000"), Currency.USD, "Aanbetaling 30%");
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 8, 20), new BigDecimal("1000"), Currency.EUR, null);

        List<PurchasePayment> all = purchaseOrders.payments(order.id());
        assertEquals(2, all.size());
        assertEquals(new BigDecimal("3000.00"), all.get(0).amount());
        assertEquals(Currency.USD, all.get(0).currency());
        assertEquals(new BigDecimal("2700.00"), all.get(0).amountEur(), "3000 USD at 0.90");
        assertEquals("Aanbetaling 30%", all.get(0).label());
        assertEquals(new BigDecimal("1000.00"), all.get(1).amountEur());
        assertTrue(all.get(0).actor() != null);

        purchaseOrders.deletePayment(order.id(), deposit.id());
        assertEquals(1, purchaseOrders.payments(order.id()).size());

        /* The diary knows about it. */
        String notes = purchaseOrders.get(order.id()).notes();
        assertTrue(notes.contains("Betaald 01/08/2026: US$ 3000,00 (≈ € 2700,00) aan de leverancier · Aanbetaling 30%."), notes);

        /* A payment to the forwarder is its own stream. */
        PurchasePayment road = purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("500"),
                Currency.EUR, "Inklaring", PurchasePayment.Payee.LOGISTICS);
        assertEquals(PurchasePayment.Payee.LOGISTICS, road.payee());
        assertTrue(purchaseOrders.get(order.id()).notes().contains("aan douane & transport · Inklaring."));

        /* Documents live in the photo store, with a cap on proofs per payment. */
        var proof = purchaseOrders.addDocument(order.id(), be.enrosed.sourcing.domain.PurchaseDocument.Kind.PAYMENT_PROOF,
                "KBC", road.id(), "afschrift.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        purchaseOrders.addDocument(order.id(), be.enrosed.sourcing.domain.PurchaseDocument.Kind.PAYMENT_PROOF,
                null, road.id(), "afschrift-2.pdf", "application/pdf", "%PDF-1.4 test 2".getBytes());
        assertThrows(be.enrosed.shared.BusinessRuleException.class, () -> purchaseOrders.addDocument(order.id(),
                be.enrosed.sourcing.domain.PurchaseDocument.Kind.PAYMENT_PROOF, null, road.id(), "3.pdf", "application/pdf", "x".getBytes()),
                "two proofs per payment, not three");
        assertEquals(2, purchaseOrders.documents(order.id()).size());
        assertEquals("%PDF-1.4 test", new String(purchaseOrders.documentData(proof).readAllBytes()));
        purchaseOrders.deleteDocument(order.id(), proof.id());
        assertEquals(1, purchaseOrders.documents(order.id()).size());
    }
}
