package be.enrosed.sourcing.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        /* The diary knows about it. */
        String notes = purchaseOrders.get(order.id()).notes();
        assertTrue(notes.contains("Betaald 01/08/2026: US$ 3.000,00 (≈ € 2.700,00) aan de leverancier · Aanbetaling 30%."), notes);

        /* Deleting a payment takes its diary line with it; the rest stays. */
        purchaseOrders.deletePayment(order.id(), deposit.id());
        assertEquals(1, purchaseOrders.payments(order.id()).size());
        String cleaned = purchaseOrders.get(order.id()).notes();
        assertFalse(cleaned.contains("Aanbetaling 30%"), cleaned);
        assertTrue(cleaned.contains("Betaald 20/08/2026: € 1.000,00 aan de leverancier."), cleaned);

        /* A payment to the forwarder is its own stream. */
        PurchasePayment road = purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("500"),
                Currency.EUR, "Inklaring", PurchasePayment.Payee.LOGISTICS);
        assertEquals(PurchasePayment.Payee.LOGISTICS, road.payee());
        assertTrue(purchaseOrders.get(order.id()).notes().contains("aan douane & transport · Inklaring."));

        /* Documents live in the photo store, with a cap on proofs per payment. */
        var proof = purchaseOrders.addDocument(order.id(), be.enrosed.sourcing.domain.PurchaseDocument.Kind.PAYMENT_PROOF,
                "KBC", road.id(), "afschrift.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        for (int i = 2; i <= 5; i++) {
            purchaseOrders.addDocument(order.id(), be.enrosed.sourcing.domain.PurchaseDocument.Kind.PAYMENT_PROOF,
                    null, road.id(), "afschrift-" + i + ".pdf", "application/pdf", ("%PDF-1.4 test " + i).getBytes());
        }
        assertThrows(be.enrosed.shared.BusinessRuleException.class, () -> purchaseOrders.addDocument(order.id(),
                be.enrosed.sourcing.domain.PurchaseDocument.Kind.PAYMENT_PROOF, null, road.id(), "6.pdf", "application/pdf", "x".getBytes()),
                "five proofs per payment, not six");
        assertEquals(5, purchaseOrders.documents(order.id()).size());
        /* Loose documents get the same ceiling per category. */
        for (int i = 1; i <= 5; i++) {
            purchaseOrders.addDocument(order.id(), be.enrosed.sourcing.domain.PurchaseDocument.Kind.CUSTOMS,
                    null, null, "douane-" + i + ".pdf", "application/pdf", ("doc " + i).getBytes());
        }
        assertThrows(be.enrosed.shared.BusinessRuleException.class, () -> purchaseOrders.addDocument(order.id(),
                be.enrosed.sourcing.domain.PurchaseDocument.Kind.CUSTOMS, null, null, "douane-6.pdf", "application/pdf", "x".getBytes()),
                "five customs files, not six");
        assertEquals(10, purchaseOrders.documents(order.id()).size());
        assertEquals("%PDF-1.4 test", new String(purchaseOrders.documentData(proof).readAllBytes()));
        assertEquals("ING mei", purchaseOrders.renameDocument(order.id(), proof.id(), " ING mei ").label(),
                "the title stays editable after the upload");
        purchaseOrders.deleteDocument(order.id(), proof.id());
        assertEquals(9, purchaseOrders.documents(order.id()).size());
    }

    @Inject jakarta.persistence.EntityManager entityManager;

    @Test
    @TestTransaction
    void editingAndDeletingAMultilinePaymentRemoveOnlyItsExactDiaryEntry() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Diary Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var manual = "Afspraak douane: eerst controleren.\nFactuur 250,00 nog vergelijken met exportdocument.";
        var stored = entityManager.find(PurchaseOrderEntity.class, order.id());
        stored.notes = manual;
        entityManager.flush(); entityManager.clear();
        var first = purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("250"),
                Currency.EUR, "Douane inklaring\nExportdocument 123", PurchasePayment.Payee.LOGISTICS);
        var second = purchaseOrders.addPayment(order.id(), first.paidOn(), first.amount(), first.currency(),
                first.label(), PurchasePayment.Payee.LOGISTICS);
        entityManager.flush(); entityManager.clear();

        purchaseOrders.updatePayment(order.id(), first.id(), first.paidOn(), first.amount(), first.currency(),
                "Douane gecorrigeerd\nExportdocument 456", PurchasePayment.Payee.LOGISTICS, false);
        entityManager.flush(); entityManager.clear();
        var edited = purchaseOrders.get(order.id()).notes();
        assertEquals(1, edited.split("Exportdocument 123", -1).length - 1, "The identical sibling retains exactly one entry");
        assertEquals(1, edited.split("Exportdocument 456", -1).length - 1);
        assertTrue(edited.startsWith(manual));

        purchaseOrders.deletePayment(order.id(), first.id());
        entityManager.flush(); entityManager.clear();
        var remaining = purchaseOrders.get(order.id()).notes();
        assertFalse(remaining.contains("Exportdocument 456"));
        assertTrue(remaining.contains("Exportdocument 123"));
        assertTrue(remaining.startsWith(manual));
        assertEquals(List.of(second.id()), purchaseOrders.payments(order.id()).stream().map(PurchasePayment::id).toList());

        purchaseOrders.deletePayment(order.id(), second.id());
        entityManager.flush(); entityManager.clear();
        assertEquals(manual, purchaseOrders.get(order.id()).notes());
    }

    @Test
    @TestTransaction
    void logisticsPaymentsCanBeEditedAboveTheRemainingBudgetWithoutChangingTheirSiblings() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Separate transfers Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var day = LocalDate.of(2026, 9, 1);
        var customs = purchaseOrders.addPayment(order.id(), day, new BigDecimal("1046.95"), Currency.EUR,
                "Douane", PurchasePayment.Payee.LOGISTICS, false);
        var transport = purchaseOrders.addPayment(order.id(), day, new BigDecimal("789.50"), Currency.EUR,
                "Transport", PurchasePayment.Payee.LOGISTICS, false);
        var inspection = purchaseOrders.addPayment(order.id(), day, new BigDecimal("125"), Currency.USD,
                "Inspectie", PurchasePayment.Payee.SEPARATE, false);
        var corrected = purchaseOrders.updatePayment(order.id(), customs.id(), day.plusDays(1),
                new BigDecimal("1200.25"), Currency.EUR, "Douane en exportdocumenten", PurchasePayment.Payee.LOGISTICS, false);
        entityManager.flush(); entityManager.clear();
        var recorded = purchaseOrders.payments(order.id());
        assertEquals(3, recorded.size());
        assertEquals(new BigDecimal("1200.25"), corrected.amountEur());
        assertEquals(transport.amountEur(), recorded.stream().filter(p -> p.id().equals(transport.id())).findFirst().orElseThrow().amountEur());
        assertEquals(inspection.amountEur(), recorded.stream().filter(p -> p.id().equals(inspection.id())).findFirst().orElseThrow().amountEur());
        assertTrue(purchaseOrders.get(order.id()).notes().contains("€ 1.200,25 aan douane & transport · Douane en exportdocumenten."));
        assertFalse(purchaseOrders.get(order.id()).notes().contains("€ 1.046,95"));
    }

    @Test
    @TestTransaction
    void metadataCorrectionsPreserveHistoricalEuroAmountsAfterOrderRatesChange() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Pinned FX Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        var order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        var day = LocalDate.of(2026, 8, 1);
        var usd = purchaseOrders.addPayment(order.id(), day, new BigDecimal("300"), Currency.USD, "Deposit");
        var cny = purchaseOrders.addPayment(order.id(), day, new BigDecimal("1000"), Currency.CNY, "Inspection",
                PurchasePayment.Payee.SEPARATE, false);
        assertEquals(new BigDecimal("270.00"), usd.amountEur());
        assertEquals(new BigDecimal("126.00"), cny.amountEur());

        var stored = entityManager.find(PurchaseOrderEntity.class, order.id());
        stored.usdToEurGoods = new BigDecimal("0.95");
        stored.cnyToUsd = new BigDecimal("0.15");
        entityManager.flush();
        entityManager.clear();

        // The rounded monetary amount is unchanged; date, label, recipient and
        // final-payment marker may all change without rewriting historical FX.
        var corrected = purchaseOrders.updatePayment(order.id(), usd.id(), day.plusDays(1), new BigDecimal("300.004"),
                Currency.USD, "Corrected recipient and final marker", PurchasePayment.Payee.LOGISTICS, true);
        assertEquals(new BigDecimal("300.00"), corrected.amount());
        assertEquals(new BigDecimal("270.00"), corrected.amountEur());
        assertEquals(PurchasePayment.Payee.LOGISTICS, corrected.payee());
        assertTrue(corrected.settles());
        assertEquals(day.plusDays(1), corrected.paidOn());
        var renamed = purchaseOrders.updatePayment(order.id(), cny.id(), day, new BigDecimal("1000.00"),
                Currency.CNY, "Inspection renamed", PurchasePayment.Payee.SEPARATE, true);
        assertEquals(new BigDecimal("126.00"), renamed.amountEur());
        entityManager.clear();
        assertEquals(new BigDecimal("396.00"), purchaseOrders.paymentsSince(null).stream()
                .filter(row -> row.orderId() == order.id()).map(PurchaseOrderService.PaymentRow::amountEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add), "bank projection retains the original euro movements");

        // Changing a monetary value is an actual correction and uses the saved
        // order rate. Conversion uses the amount rounded to its stored cents.
        var changedAmount = purchaseOrders.updatePayment(order.id(), usd.id(), day, new BigDecimal("310.006"),
                Currency.USD, "Corrected transfer amount", PurchasePayment.Payee.LOGISTICS, true);
        assertEquals(new BigDecimal("310.01"), changedAmount.amount());
        assertEquals(new BigDecimal("294.51"), changedAmount.amountEur());
        var changedCurrency = purchaseOrders.updatePayment(order.id(), cny.id(), day, new BigDecimal("1000"),
                Currency.EUR, "Actually paid in EUR", PurchasePayment.Payee.SEPARATE, true);
        assertEquals(new BigDecimal("1000.00"), changedCurrency.amountEur());
    }

    @Test
    @TestTransaction
    void supplierSettlementClosesAttentionUntilItIsRevokedOrDeleted() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Settlement Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);
        var stored = entityManager.find(PurchaseOrderEntity.class, order.id());
        stored.status = PurchaseOrderStatus.ONDERWEG;
        stored.paymentTerms = PaymentTerms.THIRD_TWO_THIRDS_SHIPPED;
        stored.trackingReference = "SETTLEMENT-TRACKING";
        entityManager.flush();

        // Reproduce the reported balance using the actual recorded supplier payments.
        var payable = new PurchaseOrderService.Payable(new BigDecimal("34504.94"), new BigDecimal("11534.86"),
                BigDecimal.ZERO, false, false);
        var paidOn = LocalDate.of(2026, 7, 10);
        var balance = new BigDecimal("23942.73");
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 5, 13), new BigDecimal("10007.44"),
                Currency.EUR, "1/3 bij bestelling");
        PurchasePayment payment = purchaseOrders.addPayment(order.id(), paidOn, balance, Currency.EUR,
                "2/3 bij vertrek");
        List<String> open = List.of("Betaling open: 2/3 bij vertrek (€ 554,77)");
        assertEquals(open, purchaseOrders.attention(purchaseOrders.get(order.id()), payable));

        // Settling another payee must never discharge the supplier's balance.
        for (PurchasePayment.Payee payee : List.of(PurchasePayment.Payee.LOGISTICS,
                PurchasePayment.Payee.SEPARATE, PurchasePayment.Payee.OTHER)) {
            purchaseOrders.addPayment(order.id(), paidOn, new BigDecimal("400"), Currency.EUR,
                    "Settled separately", payee, true);
        }
        assertEquals(open, purchaseOrders.attention(purchaseOrders.get(order.id()), payable));

        purchaseOrders.updatePayment(order.id(), payment.id(), paidOn, balance, Currency.EUR,
                payment.label(), PurchasePayment.Payee.SUPPLIER, true);
        entityManager.clear();
        assertTrue(purchaseOrders.payments(order.id()).stream()
                .anyMatch(p -> p.id().equals(payment.id()) && p.settles()), "settlement survives reloading");
        assertEquals(List.of(), purchaseOrders.attention(purchaseOrders.get(order.id()), payable));
        assertEquals(new BigDecimal("33950.17"), purchaseOrders.payments(order.id()).stream()
                .filter(p -> p.payee() == PurchasePayment.Payee.SUPPLIER)
                .map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add),
                "settlement does not change the amounts actually paid");

        stored = entityManager.find(PurchaseOrderEntity.class, order.id());
        stored.trackingReference = null;
        entityManager.flush();
        assertEquals(List.of("Track & trace ontbreekt"),
                purchaseOrders.attention(purchaseOrders.get(order.id()), payable));
        stored.trackingReference = "SETTLEMENT-TRACKING";
        entityManager.flush();

        purchaseOrders.updatePayment(order.id(), payment.id(), paidOn, balance, Currency.EUR,
                payment.label(), PurchasePayment.Payee.SUPPLIER, false);
        assertEquals(open, purchaseOrders.attention(purchaseOrders.get(order.id()), payable));

        // Creating and then deleting a settlement must also restore the reminder.
        PurchasePayment finalPayment = purchaseOrders.addPayment(order.id(), paidOn, BigDecimal.ONE, Currency.EUR,
                "Slotbetaling", PurchasePayment.Payee.SUPPLIER, true);
        assertEquals(List.of(), purchaseOrders.attention(purchaseOrders.get(order.id()), payable));
        purchaseOrders.deletePayment(order.id(), finalPayment.id());
        assertEquals(open, purchaseOrders.attention(purchaseOrders.get(order.id()), payable));
    }

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
        product.supplierId = supplier.id();
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
        /* Paid between two instalments: € 750 of € 900 leaves € 150. */
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 8, 3), new BigDecimal("150"), Currency.EUR, "Deel");
        /* The other stream has its own ceiling and is untouched by the factory's. */
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("50"), Currency.EUR,
                "Inklaring", PurchasePayment.Payee.LOGISTICS);

        /* The container comes in with one broken piece; the usable 99 go on the shelf. */
        purchaseOrders.receive(order.id(), new PurchaseOrderService.Receipt(
                List.of(new PurchaseOrderService.ReceivedLine(product.id, 100, 1)), true, null,
                /* The order was created today, and a receipt cannot predate its order. */
                LocalDate.now(), null));
        entityManager.flush(); entityManager.refresh(product);
        assertEquals(99, product.stockQuantity);

        /* The last third would be € 300, but only € 150 is open: the nag asks
           for what is genuinely left, never more. */
        assertEquals(List.of("Betaling open: 1/3 bij aankomst (€ 150,00)"), attention(order.id()));
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 21), new BigDecimal("150"), Currency.EUR, "Rest");
        assertEquals(List.of(), attention(order.id()));
        /* Bank costs, a rate difference: paying past the agreed amount is
           allowed and simply recorded; nothing reopens. */
        purchaseOrders.addPayment(order.id(), LocalDate.of(2026, 9, 22), new BigDecimal("12.50"), Currency.EUR, "Bankkosten");
        assertEquals(List.of(), attention(order.id()));

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

    private static final String NO_CONCEPT_PAID = "Terug naar Concept kan niet: er zijn al betalingen geboekt op deze "
            + "container. Besteld is de laagste status die nog kan.";

    /** Vertrokken by mistake: back to Besteld keeps every payment where it was; money that moved rules out Concept. */
    @Test
    @TestTransaction
    void aContainerSetToVertrokkenByMistakeGoesBackToBesteldWithItsPaymentsButNotToConcept() {
        long id = placedContainer("Step back Co", "SKU-BACK-1").id();
        purchaseOrders.addPayment(id, LocalDate.of(2026, 8, 1), new BigDecimal("300"), Currency.EUR, "1/3",
                PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);

        PurchaseOrder departed = moveTo(id, PurchaseOrderStatus.ONDERWEG);
        assertEquals(LocalDate.now(PurchaseOrderService.BRUSSELS), departed.shippedOn());
        assertEquals(List.of("Track & trace ontbreekt", "Betaling open: 1/3 bij vertrek (€ 300,00)"), attention(id));

        /* It did not leave after all: no tracking nag, and the departure third is not due yet. */
        PurchaseOrder back = stepBack(id, PurchaseOrderStatus.BESTELD);
        assertEquals(PurchaseOrderStatus.BESTELD, back.status());
        assertNull(back.shippedOn());
        assertEquals(List.of(), attention(id));

        /* Departed again and the departure third paid; then the status turns out wrong once more. */
        moveTo(id, PurchaseOrderStatus.ONDERWEG);
        PurchasePayment shipped = purchaseOrders.addPayment(id, LocalDate.of(2026, 8, 20), new BigDecimal("300"),
                Currency.EUR, "1/3 bij vertrek", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
        assertEquals(List.of("Track & trace ontbreekt"), attention(id));
        stepBack(id, PurchaseOrderStatus.BESTELD);
        entityManager.flush(); entityManager.clear();

        assertEquals(PurchaseOrderStatus.BESTELD, purchaseOrders.get(id).status());
        assertNull(purchaseOrders.get(id).shippedOn());
        assertEquals(PaymentTerms.Moment.SHIPPED, purchaseOrders.payments(id).stream()
                        .filter(payment -> payment.id().equals(shipped.id())).findFirst().orElseThrow().instalmentDue(),
                "a paid departure term stays paid, simply early");
        assertEquals(List.of(), attention(id));

        assertEquals(NO_CONCEPT_PAID, assertThrows(BusinessRuleException.class,
                () -> stepBack(id, PurchaseOrderStatus.CONCEPT)).getMessage());
        assertEquals(2, purchaseOrders.payments(id).size());
    }

    @Test
    @TestTransaction
    void anUnpaidContainerGoesBackToConceptButALegacyPaidTotalKeepsItOrdered() {
        long id = placedContainer("Legacy paid Co", "SKU-BACK-2").id();
        moveTo(id, PurchaseOrderStatus.ONDERWEG);

        /* Nothing paid, nothing credited: never ordered after all. */
        PurchaseOrder concept = stepBack(id, PurchaseOrderStatus.CONCEPT);
        assertEquals(PurchaseOrderStatus.CONCEPT, concept.status());
        assertNull(concept.shippedOn());
        assertNull(concept.lines().getFirst().orderedQuantity(), "not ordered: no snapshot to keep");
        assertEquals(List.of(), attention(id));
        assertEquals(100, moveTo(id, PurchaseOrderStatus.BESTELD).lines().getFirst().orderedQuantity(),
                "placing again takes a new snapshot");

        /* A paid total from before the payment ledger counts as money that moved. */
        entityManager.find(PurchaseOrderEntity.class, id).paidTotalEur = new BigDecimal("250.00");
        entityManager.flush(); entityManager.clear();
        assertEquals(NO_CONCEPT_PAID, assertThrows(BusinessRuleException.class,
                () -> stepBack(id, PurchaseOrderStatus.CONCEPT)).getMessage());
    }

    /**
     * The desk tab still shows Besteld when the phone marks the container
     * departed; a later Opslaan there must not undo the departure or its day.
     */
    @Test
    @TestTransaction
    void anOutdatedFormSaveDoesNotUndoADepartureMadeElsewhere() {
        long id = placedContainer("Stale tab Co", "SKU-BACK-3").id();
        PurchaseOrder deskCopy = purchaseOrders.get(id);
        LocalDate departedOn = moveTo(id, PurchaseOrderStatus.ONDERWEG).shippedOn();
        entityManager.flush(); entityManager.clear();

        assertEquals("Deze container staat intussen op Vertrokken; laad hem opnieuw voor je opslaat.",
                assertThrows(BusinessRuleException.class, () -> purchaseOrders.update(id,
                        deskCopy.withReceipt(deskCopy.status(), deskCopy.receivedOn(), deskCopy.paidTotalEur(),
                                deskCopy.stockBooked(), "typo fixed", deskCopy.lines()))).getMessage());
        entityManager.clear();

        PurchaseOrder stored = purchaseOrders.get(id);
        assertEquals(PurchaseOrderStatus.ONDERWEG, stored.status());
        assertEquals(departedOn, stored.shippedOn());
        assertEquals(100, stored.lines().getFirst().orderedQuantity());
    }

    /** One container of 100 pieces at US$ 10 (€ 900 to the factory), ordered. */
    private PurchaseOrder placedContainer(String supplierName, String sku) {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, supplierName, "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        PurchaseOrder order = purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.TEN);
        var product = new be.enrosed.catalog.adapter.out.persistence.ProductEntity();
        product.sku = sku;
        product.name = "Step back rose " + sku;
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        return purchaseOrders.update(order.id(), order.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new be.enrosed.sourcing.domain.PurchaseOrderLine(null, product.id, 100, BigDecimal.TEN,
                        Currency.USD, null, null)))).order();
    }

    /* ---- payments paid in several transfers, and their invoice ---- */

    @Inject PurchaseInvoiceService invoices;

    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    private static final LocalDate SEP_24 = LocalDate.of(2026, 9, 24);

    @Test
    @TestTransaction
    void aGroupEditKeepsTheGroupAndTheInvoiceAndAppliesToEveryTransfer() {
        PurchaseOrder order = groupOrder();
        var first = invoices.add(order.id(), invoice(PurchasePayment.Payee.SUPPLIER, "CI-1"));
        var second = invoices.add(order.id(), invoice(PurchasePayment.Payee.SUPPLIER, "CI-2"));
        PurchasePayment balance = purchaseOrders.addPayment(order.id(), SEP_10, new BigDecimal("21733.63"), Currency.EUR,
                "Saldo 70%", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED, null, first.id(), false);
        var parts = purchaseOrders.splitPayment(order.id(), balance.id(), List.of(
                new PurchaseOrderService.PaymentPart(new BigDecimal("10000.00"), new BigDecimal("10000.00"), SEP_10),
                new PurchaseOrderService.PaymentPart(new BigDecimal("11733.63"), new BigDecimal("11733.63"), SEP_24)));
        long other = parts.get(1).id();

        /* Its own description only: the group and the invoice stay. */
        purchaseOrders.updatePayment(order.id(), other, SEP_24, new BigDecimal("11733.63"), Currency.EUR, "Saldo deel 2",
                PurchasePayment.Payee.SUPPLIER, false);
        PurchasePayment edited = payment(order, other);
        assertEquals("Saldo deel 2", edited.label());
        assertEquals(balance.id(), edited.groupId());
        assertEquals(first.id(), edited.invoiceId());

        /* A settle on the root with applyToGroup reaches both transfers, each with its own day and money. */
        purchaseOrders.updatePayment(order.id(), balance.id(), SEP_10, new BigDecimal("10000.00"), Currency.EUR,
                "Saldo 70%", PurchasePayment.Payee.SUPPLIER, true, PaymentTerms.Moment.SHIPPED, true, null,
                second.id(), true, true);
        for (PurchasePayment part : purchaseOrders.payments(order.id())) {
            assertTrue(part.settles());
            assertEquals("Saldo 70%", part.label());
            assertEquals(second.id(), part.invoiceId());
            assertEquals(balance.id(), part.groupId());
        }
        assertEquals(SEP_24, payment(order, other).paidOn());
        assertEquals(new BigDecimal("11733.63"), payment(order, other).amountEur());
        String notes = purchaseOrders.get(order.id()).notes();
        assertEquals(1, notes.split("Betaald 24/09/2026: € 11.733,63", -1).length - 1, notes);
        assertTrue(notes.contains("Betaald 24/09/2026: € 11.733,63 aan de leverancier · Saldo 70% · termijn bij vertrek · slotbetaling, deze termijn vereffend."), notes);

        /* A move to another payee with applyToGroup: an invoice it may no longer carry goes back to automatic. */
        purchaseOrders.updatePayment(order.id(), balance.id(), SEP_10, new BigDecimal("10000.00"), Currency.EUR,
                "Saldo 70%", PurchasePayment.Payee.LOGISTICS, false, null, true, null, null, false, true);
        for (PurchasePayment part : purchaseOrders.payments(order.id())) {
            assertEquals(PurchasePayment.Payee.LOGISTICS, part.payee());
            assertNull(part.invoiceId(), "a supplier invoice cannot carry a transport payment");
            assertEquals(balance.id(), part.groupId());
        }
    }

    @Test
    @TestTransaction
    void oneTransferOfAGroupCannotDriftFromTheOthers() {
        PurchaseOrder order = groupOrder();
        var invoice = invoices.add(order.id(), invoice(PurchasePayment.Payee.SUPPLIER, "CI-1"));
        PurchasePayment balance = purchaseOrders.addPayment(order.id(), SEP_10, new BigDecimal("300"), Currency.EUR,
                "Saldo", PurchasePayment.Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
        var parts = purchaseOrders.splitPayment(order.id(), balance.id(), List.of(
                new PurchaseOrderService.PaymentPart(new BigDecimal("100.00"), new BigDecimal("100.00"), SEP_10),
                new PurchaseOrderService.PaymentPart(new BigDecimal("200.00"), new BigDecimal("200.00"), SEP_24)));
        long other = parts.get(1).id();
        String refusal = "Deze betaling is verdeeld over 2 transacties; pas de transacties samen aan";
        assertEquals(refusal, assertThrows(BusinessRuleException.class, () -> purchaseOrders.updatePayment(order.id(), other,
                SEP_24, new BigDecimal("200"), Currency.EUR, "Saldo", PurchasePayment.Payee.LOGISTICS, false)).getMessage());
        assertEquals(refusal, assertThrows(BusinessRuleException.class, () -> purchaseOrders.updatePayment(order.id(), other,
                SEP_24, new BigDecimal("200"), Currency.EUR, "Saldo", PurchasePayment.Payee.SUPPLIER, false,
                PaymentTerms.Moment.ORDERED)).getMessage());
        assertEquals(refusal, assertThrows(BusinessRuleException.class, () -> purchaseOrders.updatePayment(order.id(), other,
                SEP_24, new BigDecimal("200"), Currency.EUR, "Saldo", PurchasePayment.Payee.SUPPLIER, true)).getMessage());
        assertEquals(refusal, assertThrows(BusinessRuleException.class, () -> purchaseOrders.updatePayment(order.id(), other,
                SEP_24, new BigDecimal("200"), Currency.EUR, "Saldo", PurchasePayment.Payee.SUPPLIER, false,
                PaymentTerms.Moment.SHIPPED, true, null, invoice.id(), true, false)).getMessage());

        /* The per-part correction: its own amount and day. */
        PurchasePayment corrected = purchaseOrders.updatePayment(order.id(), other, SEP_24.plusDays(1),
                new BigDecimal("210"), Currency.EUR, "Saldo", PurchasePayment.Payee.SUPPLIER, false);
        assertEquals(new BigDecimal("210.00"), corrected.amountEur());
        assertEquals(balance.id(), corrected.groupId());
        assertEquals(PaymentTerms.Moment.SHIPPED, corrected.instalmentDue());
    }

    @Test
    @TestTransaction
    void deletingTransfersKeepsTheGroupNamedAfterAMemberAndMovesTheFiles() {
        PurchaseOrder order = groupOrder();
        PurchasePayment balance = purchaseOrders.addPayment(order.id(), SEP_10, new BigDecimal("300"), Currency.EUR,
                "Saldo", PurchasePayment.Payee.SUPPLIER);
        var proof = purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.PAYMENT_PROOF, null, balance.id(),
                "afschrift.pdf", "application/pdf", "%PDF-1.4 proof".getBytes());
        var parts = purchaseOrders.splitPayment(order.id(), balance.id(), List.of(
                new PurchaseOrderService.PaymentPart(new BigDecimal("100.00"), new BigDecimal("100.00"), SEP_10),
                new PurchaseOrderService.PaymentPart(new BigDecimal("100.00"), new BigDecimal("100.00"), SEP_24),
                new PurchaseOrderService.PaymentPart(new BigDecimal("100.00"), new BigDecimal("100.00"), SEP_24.plusDays(1))));
        long second = parts.get(1).id();
        long third = parts.get(2).id();

        /* The root goes: the other two are re-rooted on the lowest remaining id, the proof follows. */
        purchaseOrders.deletePayment(order.id(), balance.id());
        assertEquals(second, payment(order, second).groupId());
        assertEquals(second, payment(order, third).groupId());
        assertEquals(second, purchaseOrders.documents(order.id()).stream()
                .filter(document -> document.id().equals(proof.id())).findFirst().orElseThrow().paymentId());

        /* One transfer left: a plain payment again, and the proof goes to it. */
        purchaseOrders.deletePayment(order.id(), second);
        assertNull(payment(order, third).groupId());
        assertEquals(third, purchaseOrders.documents(order.id()).getFirst().paymentId());

        /* No transfer left: the proof belongs to the container. */
        purchaseOrders.deletePayment(order.id(), third);
        assertNull(purchaseOrders.documents(order.id()).getFirst().paymentId());
    }

    @Test
    @TestTransaction
    void aPaymentNamesOnlyAnInvoiceItMayCarry() {
        PurchaseOrder order = groupOrder();
        PurchaseOrder other = groupOrder();
        var own = invoices.add(order.id(), invoice(PurchasePayment.Payee.SUPPLIER, "CI-1"));
        var elsewhere = invoices.add(other.id(), invoice(PurchasePayment.Payee.SUPPLIER, "CI-9"));
        assertEquals("Deze factuur hoort niet bij deze inkooporder", assertThrows(BusinessRuleException.class, () ->
                purchaseOrders.addPayment(order.id(), SEP_10, BigDecimal.TEN, Currency.EUR, null,
                        PurchasePayment.Payee.SUPPLIER, false, null, null, elsewhere.id(), false)).getMessage());
        assertEquals("Deze factuur hoort bij Leverancier, niet bij Douane & transport", assertThrows(BusinessRuleException.class, () ->
                purchaseOrders.addPayment(order.id(), SEP_10, BigDecimal.TEN, Currency.EUR, null,
                        PurchasePayment.Payee.LOGISTICS, false, null, null, own.id(), false)).getMessage());
        assertEquals("Deze factuur hoort bij Leverancier, niet bij Inspectie & andere kosten", assertThrows(BusinessRuleException.class, () ->
                purchaseOrders.addPayment(order.id(), SEP_10, BigDecimal.TEN, Currency.EUR, null,
                        PurchasePayment.Payee.SEPARATE, false, null, null, own.id(), false)).getMessage(),
                "a supplier invoice carries the inspection only while it is paid through the supplier");
        assertFalse(purchaseOrders.get(order.id()).inspectionPaidViaSupplier());

        /* The inspection part that says "via leverancier" sets the flag first, so the supplier invoice may carry it. */
        PurchasePayment inspection = purchaseOrders.addPayment(order.id(), SEP_10, BigDecimal.TEN, Currency.EUR,
                "Inspectie", PurchasePayment.Payee.SEPARATE, false, null, null, own.id(), true);
        assertEquals(own.id(), inspection.invoiceId());
        assertTrue(purchaseOrders.get(order.id()).inspectionPaidViaSupplier());
        assertTrue(purchaseOrders.get(order.id()).notes().contains("aan inspectie & andere kosten · Inspectie."));
        assertEquals("Inspectiebetalingen verwijzen naar een factuur van de leverancier; kies bij die betalingen eerst een andere factuur",
                assertThrows(BusinessRuleException.class, () -> purchaseOrders.setInspectionViaSupplier(order.id(), false)).getMessage());

        /* A false or a supplier payment never touches the flag. */
        purchaseOrders.addPayment(other.id(), SEP_10, BigDecimal.TEN, Currency.EUR, null,
                PurchasePayment.Payee.SUPPLIER, false, null, null, null, true);
        assertFalse(purchaseOrders.get(other.id()).inspectionPaidViaSupplier());
    }

    @Test
    @TestTransaction
    void aFileCanOnlyHangOnAPaymentOfItsOwnContainer() {
        PurchaseOrder order = groupOrder();
        PurchaseOrder other = groupOrder();
        PurchasePayment foreign = purchaseOrders.addPayment(other.id(), SEP_10, BigDecimal.TEN, Currency.EUR, "Elders");
        assertEquals("Deze betaling hoort niet bij deze inkooporder", assertThrows(BusinessRuleException.class, () ->
                purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.PAYMENT_PROOF, null, foreign.id(),
                        "afschrift.pdf", "application/pdf", "%PDF-1.4 x".getBytes())).getMessage());
        /* Invoices get room for ten files, the other kinds keep five. */
        for (int i = 1; i <= 10; i++) {
            purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, null,
                    "ci-" + i + ".pdf", "application/pdf", ("%PDF-1.4 " + i).getBytes());
        }
        assertEquals("Per container horen hoogstens tien factuurbestanden", assertThrows(BusinessRuleException.class, () ->
                purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, null,
                        "ci-11.pdf", "application/pdf", "%PDF-1.4 11".getBytes())).getMessage());
        assertEquals(PurchaseDocument.Kind.INVOICE, purchaseOrders.addDocument(order.id(), PurchaseDocument.Kind.INVOICE,
                null, null, "inspectie.pdf", "application/pdf", "%PDF-1.4 ins".getBytes()).kind());
        assertEquals("Factuur", PurchaseDocument.Kind.INVOICE.dutchLabel());
        assertEquals("Betaalbewijs", PurchaseDocument.Kind.PAYMENT_PROOF.dutchLabel());
    }

    private PurchaseOrder groupOrder() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Group Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        return purchaseOrders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private PurchasePayment payment(PurchaseOrder order, long id) {
        return purchaseOrders.payments(order.id()).stream().filter(payment -> payment.id() == id).findFirst().orElseThrow();
    }

    private static PurchaseInvoiceService.InvoiceRequest invoice(PurchasePayment.Payee payee, String number) {
        return new PurchaseInvoiceService.InvoiceRequest(payee, number, LocalDate.of(2026, 8, 20),
                new BigDecimal("31048.04"), Currency.EUR, null, null, null);
    }

    /** The form save with only the status changed, as the ERP sends it. */
    private PurchaseOrder moveTo(long orderId, PurchaseOrderStatus status) {
        return save(orderId, status, false);
    }

    /** The explicit "Status terugzetten" step: the same save, flagged as a step back. */
    private PurchaseOrder stepBack(long orderId, PurchaseOrderStatus status) {
        return save(orderId, status, true);
    }

    private PurchaseOrder save(long orderId, PurchaseOrderStatus status, boolean stepBack) {
        PurchaseOrder current = purchaseOrders.get(orderId);
        return purchaseOrders.update(orderId, current.withReceipt(status, current.receivedOn(), current.paidTotalEur(),
                current.stockBooked(), current.notes(), current.lines()), stepBack).order();
    }

    private List<String> attention(long orderId) {
        PurchaseOrder order = purchaseOrders.get(orderId);
        return purchaseOrders.attention(order, purchaseOrders.payable(order, purchaseOrders.calculate(order), "FOB"));
    }
}
