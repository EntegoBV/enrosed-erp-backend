package be.enrosed.inventory.application;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds what a closing shows apart from the own stock: invoices that were
 * sent and not afgepunt, containers that were on the water, and the pieces
 * that already left a partner container. Only the candidates are found here;
 * whether each one is in the value is the user's decision.
 */
public final class ClosingSeparations {

    private static final Set<QuoteStatus> ISSUED = Set.of(QuoteStatus.VERZONDEN, QuoteStatus.UITGEREIKT, QuoteStatus.BETAALD);

    private ClosingSeparations() {}

    /**
     * @param candidates the invoices of the period whose goods had not left by the cut-off, by date then id
     * @param older      invoices from before the period that were never afgepunt: listed, never processed
     */
    public record Invoices(List<SalesOrder> candidates, List<SalesOrder> older) {}

    /**
     * @param advance             whether an invoice is an advance invoice, which ships nothing
     * @param previousClosingDate null in a first closing: the period then starts on 1 January of the year
     * @param reportBacked        invoices made from partner meldingen: their pieces left through the meldingen'
     *                            own stock lines (the roll lists those), so they are never a candidate
     */
    public static Invoices invoices(List<SalesOrder> all, Predicate<SalesOrder> advance, int closingYear,
                                    LocalDate closingDate, Instant cutoffAt, LocalDate previousClosingDate,
                                    Set<Long> reportBacked) {
        List<SalesOrder> candidates = new ArrayList<>();
        List<SalesOrder> older = new ArrayList<>();
        for (SalesOrder order : all) {
            if (!order.isInvoice() || order.purpose() != SalesPurpose.STANDARD || !ISSUED.contains(order.status())) continue;
            if (productQuantities(order).isEmpty() || advance.test(order)) continue;
            if (order.id() != null && reportBacked.contains(order.id())) continue;
            boolean before = order.orderDate() == null || (previousClosingDate == null
                    ? order.orderDate().isBefore(LocalDate.of(closingYear, 1, 1))
                    : !order.orderDate().isAfter(previousClosingDate));
            if (before) {
                if (order.goodsShippedAt() == null) older.add(order);
                continue;
            }
            if (order.orderDate().isAfter(closingDate)) continue;
            if (order.goodsShippedAt() == null || !order.goodsShippedAt().isBefore(cutoffAt)) candidates.add(order);
        }
        Comparator<SalesOrder> byDate = Comparator.comparing(SalesOrder::orderDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(SalesOrder::id);
        candidates.sort(byDate);
        older.sort(byDate.thenComparing(SalesOrder::number, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Invoices(candidates, older);
    }

    /** The pieces per product on a sales document: lines that are available and hold a quantity. */
    public static Map<Long, Integer> productQuantities(SalesOrder order) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (SalesOrderLine line : order.lines()) {
            if (line.productId() != null && !line.isUnavailable() && line.quantity() > 0) {
                quantities.merge(line.productId(), line.quantity(), Integer::sum);
            }
        }
        return quantities;
    }

    /**
     * The products of an invoice whose sale the movements of step 2 already took out of the closing
     * quantity: the SALE line that names the invoice was treated as having happened on or before the
     * closing date. Carving those pieces again would let them leave twice.
     */
    public static Set<Long> alreadyOut(SalesOrder invoice, List<StockRoll.Row> movements) {
        Set<Long> products = new LinkedHashSet<>();
        if (invoice.number() == null) return products;
        for (StockRoll.Row listed : movements) {
            var row = listed.row();
            if (Boolean.TRUE.equals(row.removed) || !StockMovement.Kind.SALE.name().equals(row.kind)
                    || !invoice.number().equals(row.refText)) continue;
            if (happenedBefore(listed)) products.add(row.productId);
        }
        return products;
    }

    /**
     * Whether a container was on the water on the closing date: ordered by then, not received by
     * then, and either shipped or paid for by then. A received container without a receipt date is
     * no candidate; it only blocks.
     */
    public static boolean inTransit(PurchaseOrder order, List<PurchasePayment> payments, LocalDate closingDate) {
        if (order.status() == PurchaseOrderStatus.CONCEPT || order.orderDate() == null
                || order.orderDate().isAfter(closingDate)) return false;
        if (order.status() == PurchaseOrderStatus.ONTVANGEN) {
            if (order.receivedOn() == null || !order.receivedOn().isAfter(closingDate)) return false;
        }
        boolean shipped = order.shippedOn() != null && !order.shippedOn().isAfter(closingDate);
        boolean paid = payments.stream().anyMatch(payment -> payment.paidOn() != null && !payment.paidOn().isAfter(closingDate));
        return shipped || paid;
    }

    /**
     * The pieces per product (sales units) that left a partner container by the cut-off: the larger of
     * what its advance documents and what its settlement invoices shipped, as "Voorraad afpunten"
     * counts them, plus what partner meldingen took out of it (sold minus bought back, withdrawals
     * included). Settlements made from meldingen are left out of the settlement sum: their pieces
     * left through the meldingen' stock lines, which {@code reportedOut} already counts.
     *
     * @param reportBacked the documents made from partner meldingen
     * @param reportedOut  per product, the net sales units the meldingen' stock lines of this container
     *                     took out before the cut-off (the caller decides "before": the closing by the
     *                     roll's answer per line, the batch start by booking time)
     */
    public static Map<Long, Integer> partnerShipped(long purchaseOrderId, List<SalesOrder> all, Instant cutoffAt,
                                                    Set<Long> reportBacked, Map<Long, Integer> reportedOut) {
        Map<Long, Integer> advances = new LinkedHashMap<>();
        Map<Long, Integer> settlements = new LinkedHashMap<>();
        for (SalesOrder order : all) {
            if (!Objects.equals(order.linkedPurchaseOrderId(), purchaseOrderId) || order.goodsShippedAt() == null
                    || !order.goodsShippedAt().isBefore(cutoffAt)) continue;
            Map<Long, Integer> target = order.isPartnerAdvance() ? advances
                    : order.purpose() == SalesPurpose.PARTNER_SETTLEMENT
                    && (order.id() == null || !reportBacked.contains(order.id())) ? settlements : null;
            if (target == null) continue;
            for (SalesOrderLine line : order.lines()) {
                if (line.productId() != null && line.quantity() > 0) target.merge(line.productId(), line.quantity(), Integer::sum);
            }
        }
        Map<Long, Integer> shipped = new LinkedHashMap<>(advances);
        settlements.forEach((productId, quantity) -> shipped.merge(productId, quantity, Math::max));
        reportedOut.forEach((productId, quantity) -> shipped.merge(productId, quantity, Integer::sum));
        shipped.replaceAll((productId, quantity) -> Math.max(0, quantity));
        shipped.values().removeIf(quantity -> quantity == 0);
        return shipped;
    }

    /**
     * Whether a listed line of the roll happened on or before the closing date, the way
     * {@link #alreadyOut} reads it: with a count after the closing date a line between the two counts
     * as before when the user took it out of the roll; otherwise when it counts towards the closing.
     */
    public static boolean happenedBefore(StockRoll.Row listed) {
        boolean applied = Boolean.TRUE.equals(listed.row().applied);
        return listed.countAfterClosingDate() ? listed.between() && !applied : applied;
    }

    /**
     * Per container and product: the net sales units partner meldingen' stock lines took out by the
     * closing date. A line the roll lists counts by the roll's answer ({@link #happenedBefore}); a
     * line it does not list by its booking time against the cut-off.
     *
     * @param movements the PARTNER_SALE / PARTNER_BUYBACK lines that name a container, in sales units
     * @param listed    the roll's rows
     */
    public static Map<Long, Map<Long, Integer>> partnerReportedOut(List<StockMovement> movements, List<StockRoll.Row> listed,
                                                                   Instant cutoffAt) {
        Map<Long, Boolean> decided = new java.util.HashMap<>();
        for (StockRoll.Row row : listed) {
            if (row.row().movementId == null || Boolean.TRUE.equals(row.row().removed)) continue;
            decided.put(row.row().movementId, happenedBefore(row));
        }
        Map<Long, Map<Long, Integer>> out = new LinkedHashMap<>();
        for (StockMovement movement : movements) {
            if (!movement.kind().isPartnerReport() || movement.purchaseOrderId() == null || movement.at() == null) continue;
            boolean before = movement.id() != null && decided.containsKey(movement.id()) ? decided.get(movement.id())
                    : movement.at().isBefore(cutoffAt);
            if (!before) continue;
            out.computeIfAbsent(movement.purchaseOrderId(), key -> new LinkedHashMap<>())
                    .merge(movement.productId(), -movement.delta(), Integer::sum);
        }
        return out;
    }
}
