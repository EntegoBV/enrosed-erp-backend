package be.enrosed.sourcing.application;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.application.ReceiptIssues.ReceiptIssue;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A supplier's order warns about its own containers only; the product page keeps every supplier's. */
class ReceiptIssuesSupplierTest {

    private static final long A = 7L;
    private static final long B = 8L;

    @Test
    void oneSuppliersHistoryKeepsOnlyItsOwnContainersAndTheirLaterReports() {
        PurchaseOrder ofA = order(1L, "PO-A", A, LocalDate.of(2026, 8, 12),
                new PurchaseOrderLine(11L, 5L, 480, null, null, null, 480, null, 12, null, "A domes cracked"));
        PurchaseOrder ofB = order(2L, "PO-B", B, LocalDate.of(2026, 9, 2),
                new PurchaseOrderLine(21L, 5L, 470, null, null, null, 480, null, 0, null, null));
        PurchaseOrder cleanOfA = order(3L, "PO-A2", A, LocalDate.of(2026, 9, 10),
                new PurchaseOrderLine(31L, 5L, 480, null, null, null, 480, null, 0, null, null));
        List<StockMovement> book = List.of(
                new StockMovement(1L, 5L, 1L, Instant.parse("2026-09-12T10:00:00Z"), -3, 900,
                        StockMovement.Kind.DAMAGED, "PO-A2 · carton crushed", "emre", 3L),
                new StockMovement(2L, 5L, 1L, Instant.parse("2026-09-04T10:00:00Z"), -2, 903,
                        StockMovement.Kind.SHORTAGE, "PO-B · two missing", "emre", 2L));
        List<PurchaseOrder> orders = List.of(ofA, ofB, cleanOfA);

        List<ReceiptIssue> all = ReceiptIssues.forProduct(orders, book, 5L, null, null);
        assertEquals(List.of("PO-A2", "PO-B", "PO-A"), all.stream().map(ReceiptIssue::orderNumber).toList());
        assertEquals(List.of(A, B, A), all.stream().map(ReceiptIssue::supplierId).toList(),
                "each issue names its container's supplier");
        assertEquals(all, ReceiptIssues.forProduct(orders, book, 5L, null), "no supplier: every supplier");

        List<ReceiptIssue> ofSupplierA = ReceiptIssues.forProduct(orders, book, 5L, null, A);
        assertEquals(List.of("PO-A2", "PO-A"), ofSupplierA.stream().map(ReceiptIssue::orderNumber).toList());
        assertEquals(3, ofSupplierA.getFirst().laterDamaged());

        List<ReceiptIssue> ofSupplierB = ReceiptIssues.forProduct(orders, book, 5L, null, B);
        assertEquals(List.of("PO-B"), ofSupplierB.stream().map(ReceiptIssue::orderNumber).toList());
        assertEquals(12, ofSupplierB.getFirst().missing(), "10 short at receipt and 2 reported later");
        assertEquals(2, ofSupplierB.getFirst().laterMissing());

        assertEquals(List.of("PO-A2"), ReceiptIssues.forProduct(orders, book, 5L, 1L, A).stream()
                .map(ReceiptIssue::orderNumber).toList(), "the container being printed is still left out");
    }

    @Test
    void anIssueBuiltTheOldWayHasNoSupplier() {
        ReceiptIssue old = new ReceiptIssue(1L, "PO-1", LocalDate.of(2026, 8, 12), 480, 470, 0, 10, null);
        assertNull(old.supplierId());
        assertEquals(old, new ReceiptIssue(1L, "PO-1", LocalDate.of(2026, 8, 12), 480, 470, 0, 10, null,
                0, 0, null, null));
    }

    private static PurchaseOrder order(long id, String number, long supplierId, LocalDate receivedOn,
                                       PurchaseOrderLine line) {
        PurchaseOrder base = new PurchaseOrder(
                id, number, null, supplierId, LocalDate.of(2026, 8, 1),
                PurchaseOrderStatus.ONTVANGEN, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("0.91"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                new BigDecimal("5"), BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
        return base.withReceipt(PurchaseOrderStatus.ONTVANGEN, receivedOn, null, false, null, List.of(line));
    }
}
