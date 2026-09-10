package be.enrosed.shared.trash;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.LandedCostCalculator;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurchaseTrashSnapshotTest {
    private final SourcingRepositories.Suppliers suppliers = mock(SourcingRepositories.Suppliers.class);
    private final SourcingRepositories.Documents documents = mock(SourcingRepositories.Documents.class);
    private final ProductService products = mock(ProductService.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final StockService stock = mock(StockService.class);
    private final LandedCostCalculator calculator = mock(LandedCostCalculator.class);
    private final PurchaseTrashSnapshot snapshots = new PurchaseTrashSnapshot(suppliers, documents, products, customers, stock, calculator);

    @Test
    void incompleteDraftPreservesStoredQuantitiesAndAttachmentsWithoutInventingAmounts() {
        when(products.list()).thenReturn(List.of());
        when(documents.forOrder(41L)).thenReturn(List.of(new PurchaseDocument(8L, 41L,
                PurchaseDocument.Kind.COMMERCIAL_INVOICE, "Factory", "original.pdf", "application/pdf", 3L,
                "private-storage-key", null, "Emre", Instant.now())));
        var snapshot = snapshots.snapshot(order());
        assertNull(snapshot.totalEur());
        assertEquals(new BigDecimal("24"), snapshot.lines().getFirst().quantity());
        assertNull(snapshot.lines().getFirst().unitPriceEur());
        assertNull(snapshot.lines().getFirst().totalEur());
        assertEquals("Product #99", snapshot.lines().getFirst().description());
        assertEquals("Mijn oorspronkelijke notitie", snapshot.notes());
        assertEquals("original.pdf", snapshot.attachments().getFirst().name());
        assertNull(snapshot.attachments().getFirst().url());
        assertFalse(snapshot.toString().contains("private-storage-key"));
        verifyNoInteractions(calculator, stock, customers);
    }

    @Test
    void restoreChecksReferencesWithoutPricingOrReplayingReceipt() {
        assertEquals("De leverancier van deze inkooporder bestaat niet meer", snapshots.validateRestore(order()));
        when(suppliers.findById(7L)).thenReturn(Optional.of(new Supplier(7L, "Supplier", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null)));
        when(products.get(99L)).thenThrow(new NotFoundException("Product", 99L));
        assertTrue(snapshots.validateRestore(order()).contains("product"));
        reset(products);
        assertNull(snapshots.validateRestore(order()));
        verifyNoInteractions(calculator, stock, customers, documents);
    }

    private PurchaseOrder order() {
        return new PurchaseOrder(41L, "PO-2026-041", null, 7L, LocalDate.of(2026, 9, 10),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ, new BigDecimal("0.14"),
                new BigDecimal("0.90"), new BigDecimal("0.90"), BigDecimal.ZERO, BigDecimal.ZERO,
                Currency.USD, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", "Mijn oorspronkelijke notitie",
                List.of(new PurchaseOrderLine(42L, 99L, 24, null, null, null, null)));
    }
}
