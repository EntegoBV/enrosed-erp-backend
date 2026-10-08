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
     */
    public static Invoices invoices(List<SalesOrder> all, Predicate<SalesOrder> advance, int closingYear,
                                    LocalDate closingDate, Instant cutoffAt, LocalDate previousClosingDate) {
        List<SalesOrder> candidates = new ArrayList<>();
        List<SalesOrder> older = new ArrayList<>();
        for (SalesOrder order : all) {
            if (!order.isInvoice() || order.purpose() != SalesPurpose.STANDARD || !ISSUED.contains(order.status())) continue;
            if (productQuantities(order).isEmpty() || advance.test(order)) continue;
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
            boolean applied = Boolean.TRUE.equals(row.applied);
            boolean happenedBefore = listed.countAfterClosingDate() ? listed.between() && !applied : applied;
            if (happenedBefore) products.add(row.productId);
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
     * The pieces per product that left a partner container by the cut-off: the larger of what its
     * advance documents and what its settlement invoices shipped, as "Voorraad afpunten" counts them.
     */
    public static Map<Long, Integer> partnerShipped(long purchaseOrderId, List<SalesOrder> all, Instant cutoffAt) {
        Map<Long, Integer> advances = new LinkedHashMap<>();
        Map<Long, Integer> settlements = new LinkedHashMap<>();
        for (SalesOrder order : all) {
            if (!Objects.equals(order.linkedPurchaseOrderId(), purchaseOrderId) || order.goodsShippedAt() == null
                    || !order.goodsShippedAt().isBefore(cutoffAt)) continue;
            Map<Long, Integer> target = order.isPartnerAdvance() ? advances
                    : order.purpose() == SalesPurpose.PARTNER_SETTLEMENT ? settlements : null;
            if (target == null) continue;
            for (SalesOrderLine line : order.lines()) {
                if (line.productId() != null && line.quantity() > 0) target.merge(line.productId(), line.quantity(), Integer::sum);
            }
        }
        Map<Long, Integer> shipped = new LinkedHashMap<>(advances);
        settlements.forEach((productId, quantity) -> shipped.merge(productId, quantity, Math::max));
        return shipped;
    }
}
