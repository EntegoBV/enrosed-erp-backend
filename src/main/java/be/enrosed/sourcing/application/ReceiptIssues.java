package be.enrosed.sourcing.application;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Earlier containers on which a product arrived short or damaged: what the
 * receipt itself recorded, plus what the warehouse reported against the
 * container afterwards, while unpacking. One entry per container.
 */
public final class ReceiptIssues {
    private ReceiptIssues() {}

    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    public record ReceiptIssue(long orderId, String orderNumber, LocalDate receivedOn, int ordered,
                               int received, int damaged, int missing, String note,
                               /** Reported after receipt, while unpacking; part of {@code damaged}/{@code missing}. */
                               int laterDamaged, int laterMissing, String laterNote) {
        /** Compatibility for callers written before reports could follow the receipt. */
        public ReceiptIssue(long orderId, String orderNumber, LocalDate receivedOn, int ordered,
                            int received, int damaged, int missing, String note) {
            this(orderId, orderNumber, receivedOn, ordered, received, damaged, missing, note, 0, 0, null);
        }

        public boolean hasLaterReports() {
            return laterDamaged > 0 || laterMissing > 0;
        }
    }

    /** History from the receipts alone, as before the warehouse could report later. */
    public static List<ReceiptIssue> forProduct(List<PurchaseOrder> orders, long productId, Long excludeOrderId) {
        return forProduct(orders, List.of(), productId, excludeOrderId);
    }

    /**
     * History from the receipts and from the stock book: damage and shortage
     * lines that name a container are folded into that container's entry, so
     * a clean receipt that turned sour while unpacking still shows up.
     */
    public static List<ReceiptIssue> forProduct(List<PurchaseOrder> orders, List<StockMovement> movements,
                                                long productId, Long excludeOrderId) {
        Map<Long, Later> later = new LinkedHashMap<>();
        for (StockMovement move : movements) {
            if (move.productId() != productId || move.purchaseOrderId() == null || !move.kind().isReceiptIssue()) continue;
            if (excludeOrderId != null && Objects.equals(move.purchaseOrderId(), excludeOrderId)) continue;
            Later entry = later.computeIfAbsent(move.purchaseOrderId(), key -> new Later());
            int pieces = Math.abs(move.delta());
            if (move.kind() == StockMovement.Kind.DAMAGED) entry.damaged += pieces;
            else entry.missing += pieces;
            entry.addNote(move.reference());
        }

        List<ReceiptIssue> issues = new ArrayList<>();
        for (PurchaseOrder order : orders) {
            if (order.status() != PurchaseOrderStatus.ONTVANGEN) continue;
            if (excludeOrderId != null && Objects.equals(order.id(), excludeOrderId)) continue;
            Later extra = order.id() == null ? null : later.remove(order.id());
            for (PurchaseOrderLine line : order.lines()) {
                if (line.productId() == null || line.productId() != productId) continue;
                int ordered = line.orderedQuantity() != null ? line.orderedQuantity() : line.quantity();
                int received = line.quantity();
                int damaged = line.damagedQuantity() == null ? 0 : Math.max(0, line.damagedQuantity());
                int missing = Math.max(0, ordered - received);
                int laterDamaged = extra == null ? 0 : extra.damaged;
                int laterMissing = extra == null ? 0 : extra.missing;
                if (damaged == 0 && missing == 0 && laterDamaged == 0 && laterMissing == 0) continue;
                issues.add(new ReceiptIssue(order.id(), order.number(), order.receivedOn(), ordered, received,
                        damaged + laterDamaged, missing + laterMissing, line.issueNote(),
                        laterDamaged, laterMissing, extra == null ? null : extra.note(order.number())));
                extra = null;
            }
        }
        issues.sort(Comparator.comparing((ReceiptIssue issue) -> issue.receivedOn() == null
                ? LocalDate.MIN : issue.receivedOn()).reversed().thenComparing(ReceiptIssue::orderNumber));
        return List.copyOf(issues);
    }

    /** The day a stock line was written, in the warehouse's own time. */
    public static LocalDate dayOf(StockMovement move) {
        return move.at() == null ? null : move.at().atZone(ZONE).toLocalDate();
    }

    private static final class Later {
        int damaged;
        int missing;
        private final List<String> notes = new ArrayList<>();

        void addNote(String reference) {
            if (reference == null || reference.isBlank()) return;
            String text = reference.strip();
            if (!notes.contains(text)) notes.add(text);
        }

        /** The stock line starts with the container's number; the entry already names it. */
        String note(String number) {
            List<String> cleaned = new ArrayList<>();
            for (String text : notes) {
                String value = text;
                if (number != null && value.startsWith(number)) {
                    value = value.substring(number.length()).strip();
                    if (value.startsWith("·")) value = value.substring(1).strip();
                }
                if (!value.isEmpty() && !cleaned.contains(value)) cleaned.add(value);
            }
            return cleaned.isEmpty() ? null : String.join(" · ", cleaned);
        }
    }
}
