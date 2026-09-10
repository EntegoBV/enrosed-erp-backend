package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderLineEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.SupplierEntity;
import be.enrosed.sourcing.application.SupplierService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RetainedOrderDependenciesTest {
    @Inject EntityManager entities;
    @Inject CustomerService customers;
    @Inject SupplierService suppliers;
    @Inject PartnerAdvanceQuotes quotes;
    @Inject SalesDocumentLocks documentLocks;
    @Inject SalesLineCostBackfill costs;

    @Test @TestTransaction
    void startupCostSnapshotLeavesRetainedLinesIntactWhileFillingActiveLines() {
        var product = new ProductEntity(); product.landedCostEur = new BigDecimal("4.2500");
        entities.persist(product);
        var active = new SalesOrderEntity(); entities.persist(active);
        var retained = new SalesOrderEntity(); entities.persist(retained);
        var activeLine = new SalesOrderLineEntity(); activeLine.order = active; activeLine.productId = product.id;
        var retainedLine = new SalesOrderLineEntity(); retainedLine.order = retained; retainedLine.productId = product.id;
        entities.persist(activeLine); entities.persist(retainedLine); entities.flush();
        tombstone("sales_order", retained.id);
        costs.onStart(null);
        entities.flush();
        assertEquals(new BigDecimal("4.2500"), activeLine.unitCostEur);
        assertNull(retainedLine.unitCostEur, "trash snapshots must not change during startup backfill");
    }

    @Test @TestTransaction
    void mutationEntryRejectsTheCachedParentAfterItsTrashMarkerChangesInTheDatabase() {
        var order = new SalesOrderEntity();
        entities.persist(order); entities.flush();
        tombstone("sales_order", order.id);
        assertNull(order.deletedAt, "the managed parent deliberately predates the trash operation");
        assertThrows(NotFoundException.class, () -> documentLocks.lock(order.id));
        assertFalse(entities.createNativeQuery("select id from sales_order where id=:id and deleted_at is not null")
                .setParameter("id", order.id).getResultList().isEmpty());
    }

    @Test @TestTransaction
    void trashedQuoteKeepsCustomerAndSnapshotWithoutGoverningTheActivePlan() {
        var customer = customer();
        var order = new SalesOrderEntity(); order.customerId = customer.id;
        entities.persist(order); entities.flush();
        var snapshot = new PartnerAdvanceQuotes.Snapshot(981234L, new BigDecimal("100"),
                new BigDecimal("300.00"), new BigDecimal("50"), List.of(
                new PartnerAdvanceQuotes.Row(991234L, "Voorschot", null, new BigDecimal("300.00"), null)));
        quotes.save(order.id, snapshot);
        assertEquals(List.of(order.id), quotes.quoteIds(snapshot.purchaseOrderId()));
        tombstone("sales_order", order.id);
        assertTrue(quotes.quoteIds(snapshot.purchaseOrderId()).isEmpty());
        assertEquals(snapshot, quotes.find(order.id));
        assertEquals(0, customers.orderCount(customer.id), "active UI counts exclude trash");
        assertThrows(BusinessRuleException.class, () -> customers.delete(customer.id));
        assertNotNull(entities.find(CustomerEntity.class, customer.id));
        entities.createNativeQuery("update sales_order set deleted_at=null where id=:id")
                .setParameter("id", order.id).executeUpdate();
        assertEquals(List.of(order.id), quotes.quoteIds(snapshot.purchaseOrderId()));
        assertEquals(snapshot, quotes.find(order.id));
    }

    @Test @TestTransaction
    void trashedPurchaseKeepsItsPartnerCustomerAndSupplierEvenWithoutAnyProducts() {
        var customer = customer();
        var supplier = new SupplierEntity(); supplier.name = "Retained supplier";
        entities.persist(supplier);
        var purchase = new PurchaseOrderEntity();
        purchase.partnerCustomerId = customer.id; purchase.supplierId = supplier.id;
        entities.persist(purchase); entities.flush();
        tombstone("purchase_order", purchase.id);
        assertEquals(0, suppliers.productCount(supplier.id));
        assertThrows(BusinessRuleException.class, () -> customers.delete(customer.id));
        assertThrows(BusinessRuleException.class, () -> suppliers.delete(supplier.id));
        assertNotNull(entities.find(CustomerEntity.class, customer.id));
        assertNotNull(entities.find(SupplierEntity.class, supplier.id));
    }

    private CustomerEntity customer() {
        var customer = new CustomerEntity(); customer.company = "Retained customer";
        customer.countryCode = "NL"; entities.persist(customer); return customer;
    }

    private void tombstone(String table, long id) {
        entities.createNativeQuery("update " + table + " set deleted_at=current_timestamp where id=:id")
                .setParameter("id", id).executeUpdate();
    }
}
