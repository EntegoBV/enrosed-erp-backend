package be.enrosed.sourcing.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static be.enrosed.sourcing.domain.PaymentTerms.Moment.*;
import static be.enrosed.sourcing.domain.PurchasePayment.Payee.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PurchaseCifPersistenceTest {
    @Inject PurchaseOrderService orders;
    @Inject SupplierService suppliers;
    @Inject EntityManager entityManager;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final String ONLY_CIF = "Zeevracht (CIF) hoort alleen bij een betaling aan de leverancier van een CIF-container";

    @Test
    @TestTransaction
    void aSupplierQuotingCifStartsItsContainersAsCifUnlessTheClientChooses() {
        var cifSupplier = supplier("Cif Co", " cfr ");
        var fobSupplier = supplier("Fob Co", "FOB");
        assertEquals(Boolean.TRUE, orders.create(cifSupplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.ZERO).freightViaSupplier(), "a CIF or CFR supplier presets the choice once");
        assertEquals(Boolean.FALSE, orders.create(cifSupplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.ZERO, null, false).freightViaSupplier(), "the client's own choice wins");
        PurchaseOrder fob = orders.create(fobSupplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        assertNull(fob.freightViaSupplier());
        entityManager.flush(); entityManager.clear();
        assertNull(orders.get(fob.id()).freightViaSupplier(), "null is stored as null: no");
    }

    @Test
    @TestTransaction
    void theCifFreightIsTheSuppliersOwnTermAndOnlyThere() {
        var supplier = supplier("Freight Co", "FOB");
        long product = product(supplier, "CIF-TERM");
        PurchaseOrder cif = container(supplier, product, true);
        PurchaseOrder exw = container(supplier, product, null);

        var payable = orders.payable(cif, orders.calculate(cif), null);
        /* 100 pieces at US$ 10 and US$ 1.000 sea freight, both at 0,90. */
        assertEquals(new BigDecimal("1800.00"), payable.supplierEur());
        assertEquals(new BigDecimal("900.00"), payable.supplierFreightEur());
        assertEquals(new BigDecimal("0.00"), payable.logisticsEur(), "no duty and no arrival costs in this container");
        assertEquals(new BigDecimal("900.00"), orders.payable(exw, orders.calculate(exw), null).logisticsEur());

        assertRule(ONLY_CIF, () -> orders.addPayment(cif.id(), DAY, BigDecimal.TEN, Currency.EUR, "Vracht",
                LOGISTICS, false, FREIGHT));
        assertRule(ONLY_CIF, () -> orders.addPayment(exw.id(), DAY, BigDecimal.TEN, Currency.EUR, "Vracht",
                SUPPLIER, false, FREIGHT));
        orders.addPayment(cif.id(), DAY, new BigDecimal("900"), Currency.EUR, "Zeevracht", SUPPLIER, false, FREIGHT);
        assertTrue(orders.get(cif.id()).notes().contains(" · termijn zeevracht."), orders.get(cif.id()).notes());

        List<PurchaseReconciliation.SupplierInstalment> terms = orders.reconciliation(cif.id()).supplierInstalments();
        assertEquals(List.of("30% bij bestelling", "70% bij vertrek", "Zeevracht (CIF)"),
                terms.stream().map(PurchaseReconciliation.SupplierInstalment::label).toList());
        assertEquals(new BigDecimal("270.00"), terms.get(0).plannedEur(), "30 % of the goods, not of goods and freight");
        assertEquals(new BigDecimal("630.00"), terms.get(1).plannedEur());
        assertEquals(new BigDecimal("900.00"), terms.get(2).paidEur());
        assertTrue(terms.get(2).finalized());

        assertEquals(List.of("Betaling open: 30% bij bestelling (€ 270,00)"), attention(cif.id()));
        var stored = entityManager.find(PurchaseOrderEntity.class, cif.id());
        stored.status = PurchaseOrderStatus.ONDERWEG;
        stored.trackingReference = "CIF-TRACKING";
        entityManager.flush(); entityManager.clear();
        assertEquals(List.of("Betaling open: 30% bij bestelling (€ 270,00)",
                "Betaling open: 70% bij vertrek (€ 630,00)"), attention(cif.id()),
                "the freight is due at departure and already paid");
    }

    @Test
    @TestTransaction
    void cifIsNotSwitchedWhileASettleOrAFreightPaymentDependsOnIt() {
        var supplier = supplier("Toggle Co", "FOB");
        long product = product(supplier, "CIF-TOGGLE");
        PurchaseOrder cif = container(supplier, product, true);
        var freight = orders.addPayment(cif.id(), DAY, new BigDecimal("900"), Currency.EUR, "Zeevracht",
                SUPPLIER, false, FREIGHT);
        assertRule("Er zijn betalingen voor Zeevracht (CIF); zet ze eerst op een andere termijn.",
                () -> orders.update(cif.id(), orders.get(cif.id()).withFreightViaSupplier(false)));
        assertEquals(Boolean.TRUE, orders.get(cif.id()).freightViaSupplier());

        orders.deletePayment(cif.id(), freight.id());
        var settled = orders.addPayment(cif.id(), DAY, new BigDecimal("270"), Currency.EUR, "Aanbetaling",
                SUPPLIER, true, ORDERED);
        String settledFirst = "Leverancier of Douane & transport is al afgerekend. Maak die afrekening eerst ongedaan voordat je CIF aan- of uitzet.";
        assertRule(settledFirst, () -> orders.update(cif.id(), orders.get(cif.id()).withFreightViaSupplier(false)));
        orders.updatePayment(cif.id(), settled.id(), DAY, settled.amount(), Currency.EUR, settled.label(),
                SUPPLIER, false, ORDERED);
        var customs = orders.addPayment(cif.id(), DAY, BigDecimal.TEN, Currency.EUR, "Inklaring", LOGISTICS, true);
        assertRule(settledFirst, () -> orders.update(cif.id(), orders.get(cif.id()).withFreightViaSupplier(false)));
        orders.deletePayment(cif.id(), customs.id());

        PurchaseOrder exw = orders.update(cif.id(), orders.get(cif.id()).withFreightViaSupplier(false)).order();
        assertEquals(Boolean.FALSE, exw.freightViaSupplier());
        var payable = orders.payable(exw, orders.calculate(exw), null);
        assertEquals(new BigDecimal("900.00"), payable.supplierEur(), "back to the goods only");
        assertEquals(new BigDecimal("900.00"), payable.logisticsEur(), "the freight is Douane & transport's again");
        assertEquals(new BigDecimal("0.00"), payable.supplierFreightEur());
    }

    private List<String> attention(long orderId) {
        PurchaseOrder order = orders.get(orderId);
        return orders.attention(order, orders.payable(order, orders.calculate(order), null));
    }

    private static void assertRule(String message, Executable action) {
        assertEquals(message, assertThrows(BusinessRuleException.class, action).getMessage());
    }

    private Supplier supplier(String name, String incoterm) {
        return suppliers.save(new Supplier(null, name, "CN", "Yiwu", null, null, null, Currency.USD, incoterm,
                "Ningbo", 30, null));
    }

    private long product(Supplier supplier, String sku) {
        var product = new ProductEntity();
        product.sku = sku;
        product.name = "Cif rose " + sku;
        product.active = true;
        product.supplierId = supplier.id();
        product.piecesPerCarton = 10;
        product.productLengthCm = product.productWidthCm = product.productHeightCm = BigDecimal.ONE;
        product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.ONE;
        entityManager.persist(product);
        entityManager.flush();
        return product.id;
    }

    /** 100 pieces at US$ 10, US$ 1.000 sea freight, 30 % at ordering and 70 % at departure, ordered. */
    private PurchaseOrder container(Supplier supplier, long product, Boolean cif) {
        PurchaseOrder order = orders.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"),
                BigDecimal.ZERO, null, cif);
        orders.update(order.id(), order.withReceipt(PurchaseOrderStatus.BESTELD, null, null, false, null,
                List.of(new PurchaseOrderLine(null, product, 100, BigDecimal.TEN, Currency.USD, null, null))));
        var stored = entityManager.find(PurchaseOrderEntity.class, order.id());
        stored.freightUsd = new BigDecimal("1000");
        stored.paymentTerms = PaymentTerms.DEPOSIT_30_70;
        entityManager.flush(); entityManager.clear();
        return orders.get(order.id());
    }
}
