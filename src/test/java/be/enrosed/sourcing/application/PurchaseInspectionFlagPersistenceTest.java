package be.enrosed.sourcing.application;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.in.rest.SourcingResource;
import be.enrosed.sourcing.domain.PurchaseOrder;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Inspectie via leverancier" is server-owned: its own endpoint sets it, no form save or copy does. */
@QuarkusTest
@TestSecurity(user = "fixture-admin", roles = "admin")
class PurchaseInspectionFlagPersistenceTest {
    @Inject PurchaseOrderService purchases;
    @Inject SupplierService suppliers;
    @Inject SourcingResource resource;

    @Test
    @TestTransaction
    void theEndpointTogglesTheFlagAndAFormSaveNeverDoes() {
        PurchaseOrder order = container();
        assertNull(order.inspectionViaSupplier());

        var view = resource.setInspectionViaSupplier(order.id(), new SourcingResource.FlagRequest(true));
        assertTrue(view.order().inspectionPaidViaSupplier());
        assertTrue(purchases.get(order.id()).inspectionPaidViaSupplier());

        /* An older screen saves the whole order without the flag: it stays. */
        PurchaseOrder stored = purchases.get(order.id());
        purchases.update(order.id(), stored.withInspectionViaSupplier(null).withInspectionCost(new BigDecimal("300")));
        assertTrue(purchases.get(order.id()).inspectionPaidViaSupplier());
        assertEquals(0, new BigDecimal("300").compareTo(purchases.get(order.id()).inspectionCostEur()));

        /* The preview keeps the stored flag, whatever the draft says. */
        var preview = resource.previewPurchaseOrder(order.id(), purchases.get(order.id()).withInspectionViaSupplier(false));
        assertTrue(preview.order().inspectionPaidViaSupplier());

        /* A copy is a new container: it starts without. */
        assertNull(purchases.duplicate(order.id()).inspectionViaSupplier());

        var off = resource.setInspectionViaSupplier(order.id(), new SourcingResource.FlagRequest(false));
        assertFalse(off.order().inspectionPaidViaSupplier());
        assertEquals(Boolean.FALSE, purchases.get(order.id()).inspectionViaSupplier());
        /* Unchanged is a no-op. */
        assertEquals(Boolean.FALSE, purchases.setInspectionViaSupplier(order.id(), false).inspectionViaSupplier());
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Flag Co", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }
}
