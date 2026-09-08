package be.enrosed.sales.application;

import be.enrosed.sales.domain.*;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

/** Remaining capacity includes draft reservations; issued snapshots never change with later costs. */
public final class PartnerSettlementLedger {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    public record Line(Long productId, String productName, int totalQuantity, int settledQuantity, int remainingQuantity,
                       BigDecimal totalCostEur, BigDecimal settledCostEur, BigDecimal remainingCostEur) {}
    public record Document(Long invoiceId, String number, QuoteStatus status, boolean finalSettlement,
                           int quantity, BigDecimal revenueEur, BigDecimal costEur, BigDecimal advanceEur,
                           BigDecimal invoiceTotalEur) {}
    public record Availability(long purchaseOrderId, Long partnerCustomerId, BigDecimal externalCostEur,
                               BigDecimal issuedAdvanceEur, BigDecimal creditedAdvanceEur, BigDecimal remainingAdvanceEur,
                               List<Line> lines, List<Document> settlements) {}

    public static Availability calculate(long purchaseId, Long partnerId, PurchaseReconciliation reconciliation,
                                         List<SalesOrder> orders, Function<SalesOrder, PricedOrder> pricing,
                                         Function<SalesOrder, PartnerSettlements.Snapshot> snapshots) {
        Map<Long, Integer> quantities = new HashMap<>();
        Map<Long, BigDecimal> costs = new HashMap<>();
        List<Document> documents = new ArrayList<>();
        BigDecimal issued = ZERO, credited = ZERO;
        for (var order : orders) {
            if (!order.isInvoice() || !PartnerFinancingService.live(order)) continue;
            if (order.isPartnerAdvance() && PartnerFinancingService.issued(order))
                issued = issued.add(pricing.apply(order).totals().total());
            if (order.purpose() != SalesPurpose.PARTNER_SETTLEMENT) continue;
            var priced = pricing.apply(order);
            var snapshot = normalizedSnapshot(order, priced, snapshots.apply(order));
            for (var line : snapshot.lines()) {
                quantities.merge(line.productId(), line.quantity(), Integer::sum);
                costs.merge(line.productId(), line.costEur(), BigDecimal::add);
            }
            credited = credited.add(snapshot.advanceEur());
            documents.add(new Document(order.id(), order.number(), order.status(), snapshot.finalSettlement(),
                    snapshot.lines().stream().mapToInt(PartnerSettlements.Line::quantity).sum(), snapshot.revenueEur(),
                    snapshot.costEur(), snapshot.advanceEur(), priced.totals().total()));
        }
        List<Line> lines = reconciliation.lines().stream().map(row -> {
            int settled = quantities.getOrDefault(row.productId(), 0);
            BigDecimal cost = costs.getOrDefault(row.productId(), ZERO);
            return new Line(row.productId(), row.productName(), row.unitCostQuantity(), settled,
                    Math.max(0, row.unitCostQuantity() - settled), row.forecastExternalEur(), cost,
                    Money.money(row.forecastExternalEur().subtract(cost)));
        }).toList();
        return new Availability(purchaseId, partnerId, reconciliation.totals().forecastExternalEur(), Money.money(issued),
                Money.money(credited), Money.money(issued.subtract(credited).max(ZERO)), lines, List.copyOf(documents));
    }

    /** Existing full settlements predate line snapshots. Allocate their frozen total once over their saved lines. */
    public static PartnerSettlements.Snapshot normalizedSnapshot(SalesOrder order, PricedOrder priced, PartnerSettlements.Snapshot saved) {
        if (saved != null && !saved.lines().isEmpty()) return saved;
        BigDecimal advance = saved == null ? order.extraLines().stream().filter(line -> line.description() != null
                && line.description().startsWith("Voorschot verrekend")).map(SalesExtraLine::total)
                .reduce(ZERO, BigDecimal::add).negate().max(ZERO) : saved.advanceEur();
        BigDecimal revenue = saved == null ? priced.totals().total().add(advance) : saved.revenueEur();
        BigDecimal cost = saved == null ? priced.totals().costTotal().add(advance) : saved.costEur();
        List<SalesOrderLine> rows = order.lines().stream().filter(line -> line.quantity() > 0).toList();
        List<BigDecimal> weights = rows.stream().map(line -> BigDecimal.valueOf(line.quantity())
                .multiply(line.unitCostEur() == null ? BigDecimal.ONE : line.unitCostEur())).toList();
        if (weights.stream().reduce(ZERO, BigDecimal::add).signum() == 0)
            weights = rows.stream().map(line -> BigDecimal.valueOf(line.quantity())).toList();
        List<BigDecimal> costs = allocate(cost, weights), revenues = allocate(revenue, weights), advances = allocate(advance, weights);
        List<PartnerSettlements.Line> details = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) details.add(new PartnerSettlements.Line(rows.get(i).productId(),
                rows.get(i).quantity(), ZERO, costs.get(i), revenues.get(i), advances.get(i)));
        return new PartnerSettlements.Snapshot(revenue, cost, advance, saved == null || saved.finalSettlement(), List.copyOf(details));
    }

    /** Round each allocation and give the last nonzero weight the exact residual. */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        if (weights.isEmpty()) return List.of();
        BigDecimal denominator = weights.stream().reduce(ZERO, BigDecimal::add);
        if (denominator.signum() == 0) weights = Collections.nCopies(weights.size(), BigDecimal.ONE);
        denominator = weights.stream().reduce(ZERO, BigDecimal::add);
        List<BigDecimal> amounts = new ArrayList<>();
        BigDecimal remaining = Money.money(total);
        BigDecimal weightRemaining = denominator;
        for (var weight : weights) {
            BigDecimal amount = weight.signum() == 0 ? ZERO : weight.compareTo(weightRemaining) == 0 ? remaining
                    : remaining.multiply(weight).divide(weightRemaining, 2, RoundingMode.HALF_UP);
            amounts.add(amount); remaining = remaining.subtract(amount); weightRemaining = weightRemaining.subtract(weight);
        }
        return List.copyOf(amounts);
    }
}
