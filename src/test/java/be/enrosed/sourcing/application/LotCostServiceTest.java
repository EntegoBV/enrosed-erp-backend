package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseSupplierCreditService.CreditRequest;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.LotCost.CreditTreatment;
import be.enrosed.sourcing.domain.LotCost.PaymentUse;
import be.enrosed.sourcing.domain.LotCost.State;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Reason;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static be.enrosed.sourcing.domain.PurchasePayment.Payee.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class LotCostServiceTest {
    @Inject LotCostService lotCosts;
    @Inject PurchaseOrderService orders;
    @Inject PurchaseSupplierCreditService credits;
    @Inject SupplierService suppliers;
    @Inject ProductService products;
    @Inject EntityManager entityManager;

    @Test
    @TestTransaction
    void aContainerBuiltThroughTheServicesGivesWhatThePureCalculationGivesOnHandBuiltInput() {
        LocalDate today = LocalDate.now();
        LocalDate bought = today.minusDays(10);
        var supplier = suppliers.save(new Supplier(null, "Lot Cost Co", "CN", "Yiwu", null, null, null, Currency.USD,
                "FOB", "Ningbo", 30, null));
        long a = product(supplier, "LOT-A");
        long b = product(supplier, "LOT-B");

        /* 1.000 x US$ 2 and 500 x US$ 4 at 0,92; US$ 1.000 freight, € 530 at arrival, € 180 inspection apart. */
        PurchaseOrder created = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.92"), BigDecimal.ZERO);
        orders.update(created.id(), created.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null, List.of(
                new PurchaseOrderLine(null, a, 1000, new BigDecimal("2.00"), Currency.USD, null, null),
                new PurchaseOrderLine(null, b, 500, new BigDecimal("4.00"), Currency.USD, null, null))));
        var stored = entityManager.find(PurchaseOrderEntity.class, created.id());
        stored.freightUsd = new BigDecimal("1000");
        stored.destinationCostsEur = new BigDecimal("530");
        stored.inspectionCostEur = new BigDecimal("180");
        stored.extraRevenueEur = new BigDecimal("2000");
        stored.allocFreight = stored.allocOrigin = stored.allocDestination = Allocation.PIECES;
        entityManager.flush(); entityManager.clear();
        orders.receive(created.id(), new PurchaseOrderService.Receipt(List.of(
                new PurchaseOrderService.ReceivedLine(a, 950, 20),
                new PurchaseOrderService.ReceivedLine(b, 500, 0)), true, null, today, null));

        var deposit = orders.addPayment(created.id(), today.minusDays(30), new BigDecimal("1200"), Currency.USD,
                "Aanbetaling", SUPPLIER, false, null, new BigDecimal("1116.00"));
        var balance = orders.addPayment(created.id(), today.minusDays(5), new BigDecimal("2800"), Currency.USD,
                "Saldo", SUPPLIER, false, null, new BigDecimal("2604.00"));
        var forwarder = orders.addPayment(created.id(), today, new BigDecimal("1000"), Currency.EUR, "Expediteur", LOGISTICS);
        var inspection = orders.addPayment(created.id(), today, new BigDecimal("180"), Currency.EUR, "Inspectie", SEPARATE);
        var bankFee = orders.addPayment(created.id(), today, new BigDecimal("35"), Currency.EUR, "Bankkost", OTHER);
        var priceCredit = credits.add(created.id(),
                new CreditRequest(today, new BigDecimal("100"), Currency.USD, null, Reason.PRICE, null));
        var shortageCredit = credits.add(created.id(),
                new CreditRequest(today, new BigDecimal("100"), Currency.USD, null, Reason.SHORTAGE, null));
        orders.reportAfterReceipt(created.id(), new PurchaseOrderService.AfterReceiptReport(
                a, null, 6, StockMovement.Kind.DAMAGED, "barst in de stolp"));
        entityManager.flush(); entityManager.clear();

        PurchaseOrder order = orders.get(created.id());
        Map<Long, Product> productsById = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        var options = new LotCost.Options(bought, LotCost.QuantityBasis.ONTVANGEN, null,
                Instant.now().plusSeconds(3600), LotCost.BilledBasis.BESTELD, Map.of(), Map.of());
        PurchaseReconciliation before = orders.reconciliation(order.id());
        List<PurchasePayment> paymentsBefore = orders.payments(order.id());

        LotCost.Container loaded = lotCosts.forContainer(order, options, productsById);

        /* The calculation on received quantities, as the hand-built input below repeats it. */
        LandedCost calculated = orders.calculate(order);
        assertCost(calculated, a, "0.00", "602.76", "0.00", "347.24", "0.00");
        assertCost(calculated, b, "0.00", "317.24", "0.00", "182.76", "0.00");
        LandedCost byHand = new LandedCost(List.of(cost(a, "602.76", "347.24"), cost(b, "317.24", "182.76")),
                totals("2000.00"), null);
        List<PurchasePayment> paymentsByHand = List.of(
                payment(deposit.id(), order.id(), today.minusDays(30), "1200.00", Currency.USD, "1116.00", "Aanbetaling", SUPPLIER),
                payment(balance.id(), order.id(), today.minusDays(5), "2800.00", Currency.USD, "2604.00", "Saldo", SUPPLIER),
                payment(forwarder.id(), order.id(), today, "1000.00", Currency.EUR, "1000.00", "Expediteur", LOGISTICS),
                payment(inspection.id(), order.id(), today, "180.00", Currency.EUR, "180.00", "Inspectie", SEPARATE),
                payment(bankFee.id(), order.id(), today, "35.00", Currency.EUR, "35.00", "Bankkost", OTHER));
        List<PurchaseSupplierCredit> creditsByHand = List.of(
                credit(priceCredit.id(), order.id(), today, Reason.PRICE),
                credit(shortageCredit.id(), order.id(), today, Reason.SHORTAGE));
        List<PurchaseReconciliation.Stream> streamsByHand = List.of(
                stream(SUPPLIER, PurchaseReconciliation.Status.PAID, "3680.00", "3680.00", "0.00"),
                stream(LOGISTICS, PurchaseReconciliation.Status.PARTIAL, "1450.00", "1000.00", "450.00"),
                stream(SEPARATE, PurchaseReconciliation.Status.PAID, "180.00", "180.00", "0.00"));
        List<StockMovement> lossByHand = List.of(new StockMovement(null, a, null, Instant.now(), -6, 924,
                StockMovement.Kind.DAMAGED, order.number(), "test", order.id()));
        LotCost.Container expected = new LotCostCalculator().calculate(new LotCost.Input(order, options, productsById,
                byHand, new BigDecimal("3680.00"), new BigDecimal("0.00"), paymentsByHand, creditsByHand,
                streamsByHand, lossByHand));

        assertEquals(expected.lots(), loaded.lots());
        assertEquals(expected.streams(), loaded.streams());
        assertEquals(expected.notes(), loaded.notes());
        assertEquals(expected.supplierGoodsEur(), loaded.supplierGoodsEur());
        assertEquals(expected.supplierTransportEur(), loaded.supplierTransportEur());
        assertEquals(expected.otherExcludedEur(), loaded.otherExcludedEur());
        assertEquals(expected.priceCreditEur(), loaded.priceCreditEur());
        assertEquals(expected.lossCreditEur(), loaded.lossCreditEur());
        assertEquals(expected.exchangeDifferenceEur(), loaded.exchangeDifferenceEur());
        assertEquals(expected.enrosedCostExcludedEur(), loaded.enrosedCostExcludedEur());
        assertEquals(expected.acquisitionEur(), loaded.acquisitionEur());
        assertEquals(expected.estimatedEur(), loaded.estimatedEur());
        assertEquals(expected.missingAndDamagedCostEur(), loaded.missingAndDamagedCostEur());
        assertEquals(expected.payments().stream().map(use -> use.paymentId() + " " + use.countedEur() + " " + use.rule()).toList(),
                loaded.payments().stream().map(use -> use.paymentId() + " " + use.countedEur() + " " + use.rule()).toList());
        assertEquals(expected.credits().stream().map(use -> use.creditId() + " " + use.countedEur() + " " + use.treatmentCode()).toList(),
                loaded.credits().stream().map(use -> use.creditId() + " " + use.countedEur() + " " + use.treatmentCode()).toList());

        /* And the figures themselves, so both sides cannot be wrong together. */
        var supplierStream = loaded.stream(SUPPLIER);
        assertEquals("PAID", supplierStream.status());
        assertEquals(new BigDecimal("3692.00"), supplierStream.includedEur(), "1.116,00 kept, 2.800 x 0,92 counted");
        assertEquals(State.WERKELIJK, supplierStream.state());
        assertEquals(new BigDecimal("450.00"), loaded.stream(LOGISTICS).estimatedEur());
        assertEquals(State.GESCHAT, loaded.stream(LOGISTICS).state());
        assertEquals(new BigDecimal("28.00"), loaded.exchangeDifferenceEur());
        assertEquals(new BigDecimal("35.00"), loaded.otherExcludedEur());
        assertEquals(new BigDecimal("2000.00"), loaded.enrosedCostExcludedEur());
        assertEquals(new BigDecimal("5230.00"), loaded.acquisitionEur());
        assertFalse(loaded.cif());
        assertEquals("PIECES", loaded.allocFreight());
        assertEquals("SEPARATE", loaded.allocSeparate());
        assertEquals(List.of(), loaded.notes());
        var lotA = loaded.lot(a);
        assertEquals("LOT-A", lotA.sku());
        assertEquals(930, lotA.capacity());
        assertEquals(6, lotA.laterLostQuantity(), "reported against the container after receipt");
        assertEquals(new BigDecimal("950.00"), lotA.logisticsKey());
        assertEquals(new BigDecimal("1748.00"), lotA.separateKey());
        assertEquals(new BigDecimal("1.8000"), lotA.unitGoodsEur());
        assertEquals(new BigDecimal("1.0000"), lotA.unitLogisticsEur());
        assertEquals(new BigDecimal("0.0923"), lotA.unitSeparateEur());
        assertEquals(new BigDecimal("2.8923"), lotA.unitValueEur());
        assertEquals(new BigDecimal("90.00"), lotA.missingCostEur());
        assertEquals(new BigDecimal("75.20"), lotA.damagedCostEur(), "(20 + 6) x 2,8923");
        var lotB = loaded.lot(b);
        assertEquals(500, lotB.capacity());
        assertEquals(new BigDecimal("4.7846"), lotB.unitValueEur());
        assertEquals(PaymentUse.RULE_GOODS_RATE, loaded.payments().stream()
                .filter(use -> use.paymentId().equals(balance.id())).findFirst().orElseThrow().rule());
        assertEquals(CreditTreatment.BUITEN, loaded.credits().stream()
                .filter(use -> use.creditId().equals(shortageCredit.id())).findFirst().orElseThrow().treatment());

        /* Reading the lot cost leaves the container as it was. */
        assertEquals(before, orders.reconciliation(order.id()), "the nacalculatie is unchanged");
        assertEquals(paymentsBefore, orders.payments(order.id()));
        entityManager.flush(); entityManager.clear();
        assertEquals(order, orders.get(order.id()));
        assertEquals(before, orders.reconciliation(order.id()));
    }

    private static void assertCost(LandedCost costing, long product, String origin, String freight, String duty,
                                   String destination, String separate) {
        LandedCost.Line line = costing.lines().stream().filter(cost -> cost.productId() == product).findFirst().orElseThrow();
        assertEquals(new BigDecimal(origin), line.originEur());
        assertEquals(new BigDecimal(freight), line.freightEur());
        assertEquals(new BigDecimal(duty), line.dutyEur());
        assertEquals(new BigDecimal(destination), line.destinationEur());
        assertEquals(new BigDecimal(separate), line.separateEur());
        assertEquals(0, BigDecimal.ZERO.compareTo(line.dutyRatePct()));
    }

    /** Only what the lot cost reads of a calculation line: the four container costs and the duty rate. */
    private static LandedCost.Line cost(long product, String freight, String destination) {
        BigDecimal zero = new BigDecimal("0.00");
        return new LandedCost.Line(product, "Product " + product, 0, 0, zero, zero, zero, zero,
                new BigDecimal(freight), zero, BigDecimal.ZERO, "standaardtarief order", zero,
                new BigDecimal(destination), zero, zero, zero, zero, zero, zero, zero);
    }

    private static LandedCost.Totals totals(String enrosed) {
        BigDecimal zero = new BigDecimal("0.00");
        return new LandedCost.Totals(0, 0, zero, zero, zero, zero, zero, zero, zero, zero, new BigDecimal(enrosed),
                zero, zero, zero);
    }

    private static PurchasePayment payment(Long id, long order, LocalDate on, String amount, Currency currency,
                                           String bankEuro, String label, PurchasePayment.Payee payee) {
        return new PurchasePayment(id, order, on, new BigDecimal(amount), currency, new BigDecimal(bankEuro),
                label, "test", null, payee, false, null);
    }

    /** US$ 100; the stored euro is deliberately not the order rate's 92,00. */
    private static PurchaseSupplierCredit credit(Long id, long order, LocalDate on, Reason reason) {
        return new PurchaseSupplierCredit(id, order, on, new BigDecimal("100.00"), Currency.USD, new BigDecimal("80.00"),
                reason, null, PurchaseSupplierCredit.Status.OPEN, null, null, null, "test", null);
    }

    private static PurchaseReconciliation.Stream stream(PurchasePayment.Payee payee, PurchaseReconciliation.Status status,
                                                       String planned, String paid, String remaining) {
        BigDecimal zero = new BigDecimal("0.00");
        return new PurchaseReconciliation.Stream(payee, payee.dutchLabel(), status, new BigDecimal(planned),
                new BigDecimal(paid), new BigDecimal(remaining), zero, zero, zero, zero, false, false, 0);
    }

    private long product(Supplier supplier, String sku) {
        var product = new ProductEntity();
        product.sku = sku;
        product.name = "Lot rose " + sku;
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        return product.id;
    }
}
