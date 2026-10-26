package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooInvoiceSearch;
import java.math.BigDecimal;
import java.util.*;

/**
 * Pure: whether an Odoo move is a copy of a document ENROSED already shows, by number (+ euro amount for incoming).
 * Never a guess: incoming needs the same separator-blind number, the same euro total within a cent and the same
 * credit-ness; outgoing needs our own invoice number in the move's name, ref or origin and the same kind.
 */
public final class InvoiceMatch {
    public record CostRef(long costId, boolean credit, String reference, BigDecimal amountEur /* |cashTotalEur| */) {}
    public record ContainerInvoiceRef(long invoiceId, String number, BigDecimal amountEur) {}  // EUR invoices of live orders only
    public record SalesRef(long orderId, boolean credit, String number) {}                     // issued claim documents only

    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** blind number → refs, each list sorted by id so the lowest id wins. */
    private final Map<String, List<CostRef>> costs;
    private final Map<String, List<ContainerInvoiceRef>> invoices;
    private final Map<String, List<SalesRef>> sales;

    private InvoiceMatch(Map<String, List<CostRef>> costs, Map<String, List<ContainerInvoiceRef>> invoices,
                         Map<String, List<SalesRef>> sales) {
        this.costs = costs;
        this.invoices = invoices;
        this.sales = sales;
    }

    public static InvoiceMatch of(List<CostRef> costs, List<ContainerInvoiceRef> invoices, List<SalesRef> sales) {
        Map<String, List<CostRef>> costIndex = new HashMap<>();
        for (CostRef cost : costs == null ? List.<CostRef>of() : costs) {
            String number = InvoiceSearchText.blind(cost.reference());
            if (number.length() >= 3 && cost.amountEur() != null) costIndex.computeIfAbsent(number, key -> new ArrayList<>()).add(cost);
        }
        costIndex.values().forEach(list -> list.sort(Comparator.comparingLong(CostRef::costId)));
        Map<String, List<ContainerInvoiceRef>> invoiceIndex = new HashMap<>();
        for (ContainerInvoiceRef invoice : invoices == null ? List.<ContainerInvoiceRef>of() : invoices) {
            String number = InvoiceSearchText.blind(invoice.number());
            if (number.length() >= 3 && invoice.amountEur() != null)
                invoiceIndex.computeIfAbsent(number, key -> new ArrayList<>()).add(invoice);
        }
        invoiceIndex.values().forEach(list -> list.sort(Comparator.comparingLong(ContainerInvoiceRef::invoiceId)));
        Map<String, List<SalesRef>> salesIndex = new HashMap<>();
        for (SalesRef order : sales == null ? List.<SalesRef>of() : sales) {
            String number = InvoiceSearchText.blind(order.number());
            if (number.length() >= 4) salesIndex.computeIfAbsent(number, key -> new ArrayList<>()).add(order);
        }
        salesIndex.values().forEach(list -> list.sort(Comparator.comparingLong(SalesRef::orderId)));
        return new InvoiceMatch(costIndex, invoiceIndex, salesIndex);
    }

    /** Key of the ENROSED row that already stands for this move ("cost:12", "pi:4", "so:9"), or null. */
    public String keyFor(OdooInvoiceSearch.Move move) {
        if (move == null || move.moveType() == null) return null;
        return switch (move.moveType()) {
            case "in_invoice", "in_refund" -> incoming(move);
            case "out_invoice", "out_refund" -> outgoing(move);
            default -> null;
        };
    }

    private String incoming(OdooInvoiceSearch.Move move) {
        String number = InvoiceSearchText.blind(move.ref() != null && !move.ref().isBlank() ? move.ref() : move.name());
        if (number.length() < 3) return null;
        BigDecimal euro = "EUR".equals(move.currency()) ? move.amountTotal() : move.amountTotalEur();
        if (euro == null) return null;
        boolean credit = "in_refund".equals(move.moveType());
        for (CostRef cost : costs.getOrDefault(number, List.of()))
            if (cost.credit() == credit && close(cost.amountEur(), euro)) return "cost:" + cost.costId();
        if (!credit)
            for (ContainerInvoiceRef invoice : invoices.getOrDefault(number, List.of()))
                if (close(invoice.amountEur(), euro)) return "pi:" + invoice.invoiceId();
        return null;
    }

    private String outgoing(OdooInvoiceSearch.Move move) {
        boolean credit = "out_refund".equals(move.moveType());
        Long best = null;
        for (String candidate : new String[] {move.name(), move.ref(), move.origin()}) {
            String number = InvoiceSearchText.blind(candidate);
            if (number.length() < 4) continue;
            for (SalesRef order : sales.getOrDefault(number, List.of())) {
                if (order.credit() != credit) continue;
                if (best == null || order.orderId() < best) best = order.orderId();
                break;
            }
        }
        return best == null ? null : "so:" + best;
    }

    private static boolean close(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.subtract(right).abs().compareTo(CENT) <= 0;
    }
}
