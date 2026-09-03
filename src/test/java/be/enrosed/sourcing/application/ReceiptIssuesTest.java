package be.enrosed.sourcing.application;

import be.enrosed.sourcing.application.ReceiptIssues.ReceiptIssue;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A product's history of short or damaged arrivals, as the product page and the supplier order read it. */
class ReceiptIssuesTest {

    @Test
    void onlyReceivedOrdersWithAShortOrDamagedLineCount() {
        PurchaseOrder damaged = order(1L, "PO-1", PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 8, 12),
                new PurchaseOrderLine(11L, 8L, 480, null, null, null, 480, null, 12, null, "glass domes cracked"));
        PurchaseOrder shortOne = order(2L, "PO-2", PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 9, 1),
                new PurchaseOrderLine(21L, 8L, 470, null, null, null, 480, null, 0, null, null));
        PurchaseOrder clean = order(3L, "PO-3", PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 9, 2),
                new PurchaseOrderLine(31L, 8L, 480, null, null, null, 480, null, 0, null, null));
        PurchaseOrder open = order(4L, "PO-4", PurchaseOrderStatus.ONDERWEG, null,
                new PurchaseOrderLine(41L, 8L, 400, null, null, null, 480, null, 50, null, "not yet"));
        PurchaseOrder other = order(5L, "PO-5", PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 9, 3),
                new PurchaseOrderLine(51L, 9L, 10, null, null, null, 20, null, 0, null, null));

        List<ReceiptIssue> issues = ReceiptIssues.forProduct(List.of(damaged, shortOne, clean, open, other), 8L, null);

        assertEquals(List.of("PO-2", "PO-1"), issues.stream().map(ReceiptIssue::orderNumber).toList(), "newest first");
        assertEquals(10, issues.get(0).missing());
        assertEquals(12, issues.get(1).damaged());
        assertEquals("glass domes cracked", issues.get(1).note());
        assertTrue(ReceiptIssues.forProduct(List.of(damaged), 8L, 1L).isEmpty(), "the order being printed is not its own history");
    }

    private static PurchaseOrder order(long id, String number, PurchaseOrderStatus status, LocalDate receivedOn,
                                       PurchaseOrderLine line) {
        PurchaseOrder base = new PurchaseOrder(
                id, number, null, 7L, LocalDate.of(2026, 8, 1),
                status, ContainerType.FORTY_HQ,
                new BigDecimal("0.14"), new BigDecimal("0.91"), new BigDecimal("0.91"),
                BigDecimal.ZERO, BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                new BigDecimal("5"), BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Ningbo", "Rotterdam", null, List.of());
        return base.withReceipt(status, receivedOn, null, false, null, List.of(line));
    }
}
