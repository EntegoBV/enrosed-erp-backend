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
        assertTrue(notes.contains("Betaald 01/08/2026: US$ 3.000,00 (≈ € 2.700,00) aan de leverancier · Aanbetaling 30%."), notes);

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

    @Inject jakarta.persistence.EntityManager entityManager;

    /** Paid is paid: the third instalment cannot be noted twice, nor "the rest" on top of it. */
    @Test
    @TestTransaction
    void aPaymentCannotGoBeyondWhatIsStillOpen() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Cap Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);
        var product = new be.enrosed.catalog.adapter.out.persistence.ProductEntity();
        product.sku = "SKU-CAP-1";
        product.name = "Capped rose";
        product.active = true;
        product.piecesPerCarton = 10;
        product.productLengthCm = BigDecimal.ONE;
        product.productWidthCm = BigDecimal.ONE;
        product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = BigDecimal.ONE;
        product.cartonWidthCm = BigDecimal.ONE;
        product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        /* 100 pieces at US$ 10 = US$ 1000 = € 900 to the factory. */
        purchaseOrders.update(order.id(), order.withReceipt(order.status(), null, null, false, null,
                List.of(new be.enrosed.sourcing.domain.PurchaseOrderLine(null, product.id, 100, BigDecimal.TEN,
                        Currency.USD, null, null))));

        /* Nothing to do on a concept; once ordered, the first third is open. */
        assertEquals(List.of(), attention(order.id()));
        purchaseOrders.update(order.id(), purchaseOrders.get(order.id()).withReceipt(
                be.enrosed.sourcing.domain.PurchaseOrderStatus.BESTELD, null, null, false, null,
                purchaseOrders.get(order.id()).lines()));
        assertEquals(List.of("Betaling open: 1/3 bij bestelling (€ 300,00)"), attention(order.id()));

        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 8, 1), new BigDecimal("600"), Currency.EUR, "2/3");
        assertEquals(List.of(), attention(order.id()), "two thirds paid, the third is not due yet");
        /* On the water without a tracking reference: that is the open point now. */
        purchaseOrders.update(order.id(), purchaseOrders.get(order.id()).withReceipt(
                be.enrosed.sourcing.domain.PurchaseOrderStatus.ONDERWEG, null, null, false, null,
                purchaseOrders.get(order.id()).lines()));
        assertEquals(List.of("Track & trace ontbreekt"), attention(order.id()));
        var tooMuch = assertThrows(be.enrosed.shared.BusinessRuleException.class, () -> purchaseOrders.addPayment(
                order.id(), LocalDate.of(2026, 8, 2), new BigDecimal("600"), Currency.EUR, "2/3 nog eens"));
        assertTrue(tooMuch.getMessage().startsWith("Er staat nog € 300,00 open aan de leverancier"), tooMuch.getMessage());
        /* Paid between two instalments: € 750 of € 900 leaves € 150. */
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 8, 3), new BigDecimal("150"), Currency.EUR, "Deel");
        /* The other stream has its own ceiling and is untouched by the factory's. */
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("50"), Currency.EUR,
                "Inklaring", PurchasePayment.Payee.LOGISTICS);

        /* The container comes in with one broken piece; the usable 99 go on the shelf. */
        purchaseOrders.receive(order.id(), new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(product.id, 100, 1)), true, null,
                LocalDate.of(2026, 9, 20), null));
        entityManager.flush(); entityManager.refresh(product);
        assertEquals(99, product.stockQuantity);

        /* The last third would be € 300, but only € 150 is open: the nag asks
           for what is genuinely left, never more. */
        assertEquals(List.of("Betaling open: 1/3 bij aankomst (€ 150,00)"), attention(order.id()));
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 21), new BigDecimal("150"), Currency.EUR, "Rest");
        assertEquals(List.of(), attention(order.id()));
        var nothingLeft = assertThrows(be.enrosed.shared.BusinessRuleException.class, () -> purchaseOrders.addPayment(
                order.id(), LocalDate.of(2026, 9, 22), BigDecimal.ONE, Currency.EUR, null));
        assertTrue(nothingLeft.getMessage().startsWith("Alles is al betaald aan de leverancier"), nothingLeft.getMessage());

        /* Weeks later two more turn out broken: editing the received order books them out. */
        PurchaseOrder received = purchaseOrders.get(order.id());
        var line = received.lines().get(0);
        purchaseOrders.update(order.id(), received.withReceipt(received.status(), received.receivedOn(),
                received.paidTotalEur(), received.stockBooked(), received.notes(),
                List.of(new be.enrosed.sourcing.domain.PurchaseOrderLine(line.id(), product.id, 100, BigDecimal.TEN,
                        Currency.USD, null, line.orderedQuantity(), null, 3))));
        entityManager.flush(); entityManager.refresh(product);
        assertEquals(97, product.stockQuantity);
        assertTrue(purchaseOrders.get(order.id()).notes().contains("Ontvangst gecorrigeerd"),
                purchaseOrders.get(order.id()).notes());

        /* A box turns out three short: the count correction follows into stock. */
        PurchaseOrder counted = purchaseOrders.get(order.id());
        var countedLine = counted.lines().get(0);
        purchaseOrders.update(order.id(), counted.withReceipt(counted.status(), counted.receivedOn(),
                counted.paidTotalEur(), counted.stockBooked(), counted.notes(),
                List.of(new be.enrosed.sourcing.domain.PurchaseOrderLine(countedLine.id(), product.id, 97,
                        BigDecimal.TEN, Currency.USD, null, countedLine.orderedQuantity(), null, 3))));
        entityManager.flush(); entityManager.refresh(product);
        assertEquals(94, product.stockQuantity, "97 received of which 3 broken: 94 usable on the shelf");

        /* Pieces do not unbreak. */
        PurchaseOrder again = purchaseOrders.get(order.id());
        var lineAgain = again.lines().get(0);
        assertThrows(be.enrosed.shared.BusinessRuleException.class, () -> purchaseOrders.update(order.id(),
                again.withReceipt(again.status(), again.receivedOn(), again.paidTotalEur(), again.stockBooked(),
                        again.notes(), List.of(new be.enrosed.sourcing.domain.PurchaseOrderLine(lineAgain.id(),
                                product.id, 97, BigDecimal.TEN, Currency.USD, null, lineAgain.orderedQuantity(),
                                null, 1)))));
    }

    private List<String> attention(long orderId) {
        PurchaseOrder order = purchaseOrders.get(orderId);
        return purchaseOrders.attention(order, purchaseOrders.payable(order, purchaseOrders.calculate(order), "FOB"));
    }
}
