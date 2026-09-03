package be.enrosed.sourcing.application;

import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Earlier containers on which a product arrived short or damaged. */
public final class ReceiptIssues {
    private ReceiptIssues() {}

    public record ReceiptIssue(long orderId, String orderNumber, LocalDate receivedOn, int ordered,
                               int received, int damaged, int missing, String note) {}

    public static List<ReceiptIssue> forProduct(List<PurchaseOrder> orders, long productId, Long excludeOrderId) {
        List<ReceiptIssue> issues = new ArrayList<>();
        for (PurchaseOrder order : orders) {
            if (order.status() != PurchaseOrderStatus.ONTVANGEN) continue;
            if (excludeOrderId != null && Objects.equals(order.id(), excludeOrderId)) continue;
            for (PurchaseOrderLine line : order.lines()) {
                if (line.productId() == null || line.productId() != productId) continue;
                int ordered = line.orderedQuantity() != null ? line.orderedQuantity() : line.quantity();
                int received = line.quantity();
                int damaged = line.damagedQuantity() == null ? 0 : Math.max(0, line.damagedQuantity());
                int missing = Math.max(0, ordered - received);
                if (damaged == 0 && missing == 0) continue;
                issues.add(new ReceiptIssue(order.id(), order.number(), order.receivedOn(), ordered, received,
                        damaged, missing, line.issueNote()));
            }
        }
        issues.sort(Comparator.comparing((ReceiptIssue issue) -> issue.receivedOn() == null
                ? LocalDate.MIN : issue.receivedOn()).reversed().thenComparing(ReceiptIssue::orderNumber));
        return List.copyOf(issues);
    }
}
