package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesPaymentPlan;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sales names the container a document comes from the way purchasing does:
 * our own "Herkenbare naam" first, the purchase order number beside it, live.
 */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class SalesOrderResourceContainerNameTest {
    @Inject SalesOrderResource resource;
    @Inject SalesOrderService sales;
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject CustomerService customers;
    @Inject EntityManager em;

    @Test @TestTransaction
    void theListAndTheViewCarryTheLiveContainerNameAndNumber() {
        var customer = customers.create(new Customer(null, "Container buyer " + UUID.randomUUID(), "Buyer", null, null,
                "FR00000000000", "FR", Language.FR, "Rue 1", "75001", "Paris", "DAP", null, null, LocalDate.now()));
        var supplier = suppliers.save(new Supplier(null, "Name supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        ProductEntity product = new ProductEntity(); product.sku = "CNT-" + UUID.randomUUID(); product.name = "Container roses";
        product.supplierId = supplier.id(); product.cartonLengthCm = BigDecimal.TEN; product.cartonWidthCm = BigDecimal.TEN;
        product.cartonHeightCm = BigDecimal.TEN; product.cartonWeightKg = BigDecimal.ONE; product.piecesPerCarton = 1;
        em.persist(product); em.flush();
        var entity = em.find(PurchaseOrderEntity.class, purchase.id());
        entity.alias = "  container/2026/002 ";
        var line = new PurchaseOrderLineEntity(); line.order = entity; line.productId = product.id; line.quantity = 10;
        line.exwPrice = new BigDecimal("2"); line.exwCurrency = Currency.EUR; entity.lines.add(line); em.persist(line);
        em.flush(); em.clear();

        var quote = sales.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(purchase.id(), customer.id(),
                "CUSTOMER", null, false, null, null, false, List.of(), null, null, SalesPurpose.STANDARD, SalesPaymentPlan.FULL));
        assertTrue(quote.internalNotes().contains("inkooporder " + purchase.number() + " (container/2026/002)"), quote.internalNotes());

        var view = resource.get(quote.id());
        assertEquals("container/2026/002", view.partnerContainerName());
        assertEquals(purchase.number(), view.partnerContainerNumber());
        var listed = resource.list().stream().filter(row -> row.order().id().equals(quote.id())).findFirst().orElseThrow();
        assertEquals("container/2026/002", listed.partnerContainerName());
        assertEquals(purchase.number(), listed.partnerContainerNumber());

        /* Renamed in purchasing, renamed in sales: nothing is copied onto the document. */
        em.find(PurchaseOrderEntity.class, purchase.id()).alias = null;
        em.flush(); em.clear();
        assertEquals(purchase.number(), resource.get(quote.id()).partnerContainerName());
        assertEquals(purchase.number(), resource.list().stream().filter(row -> row.order().id().equals(quote.id()))
                .findFirst().orElseThrow().partnerContainerName());

        var plain = sales.create(customer.id(), "FR", "DAP");
        assertNull(resource.get(plain.id()).partnerContainerName(), "a document without a container names none");
        assertTrue(sales.containerNames(List.of(-1L)).isEmpty(), "an unknown container is simply absent");
    }
}
