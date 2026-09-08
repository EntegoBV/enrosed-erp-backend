package be.enrosed.sourcing.application;

import be.enrosed.shared.Money;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseReconciliation.Line;
import be.enrosed.sourcing.domain.PurchaseReconciliation.Status;
import be.enrosed.sourcing.domain.PurchaseReconciliation.Stream;
import be.enrosed.sourcing.domain.PurchaseReconciliation.Totals;
import be.enrosed.sourcing.domain.PurchaseReconciliation.UnitCostBasis;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Pure calculation; it never writes stock, product costs, payments or bank entries. */
@ApplicationScoped
public class PurchaseReconciliationCalculator {
    private static final BigDecimal ZERO = Money.money(BigDecimal.ZERO);

    /**
     * costing and payable must describe ORDERED quantities. The original order
     * supplies receipt quantities, so shortages increase cost per usable piece
     * without silently reducing the budget agreed with the supplier.
     */
    public PurchaseReconciliation calculate(PurchaseOrder order, LandedCost costing,
                                            PurchaseOrderService.Payable payable,
                                            List<PurchasePayment> payments) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(costing, "costing");
        Objects.requireNonNull(costing.totals(), "costing.totals");
        Objects.requireNonNull(payable, "payable");
        List<PurchasePayment> recorded = payments == null ? List.of()
                : payments.stream().filter(Objects::nonNull).toList();
        List<String> notes = new ArrayList<>();
        boolean received = order.receivedOn() != null || order.status() == PurchaseOrderStatus.ONTVANGEN;
        UnitCostBasis unitBasis = received ? UnitCostBasis.USABLE_RECEIVED : UnitCostBasis.ORDERED;

        List<Stream> streams = new ArrayList<>();
        for (PurchasePayment.Payee payee : PurchasePayment.Payee.values()) {
            BigDecimal planned = switch (payee) {
                case SUPPLIER -> payable.supplierEur();
                case LOGISTICS -> payable.logisticsEur();
                // Do not add this to another stream when it already sits in a piece price.
                case SEPARATE -> costing.totals().separateCostsEur();
                case OTHER -> ZERO;
            };
            streams.add(stream(order, payee, Money.money(planned), recorded, notes));
        }

        BigDecimal planned = sum(streams, Stream::plannedEur);
        BigDecimal paid = sum(streams, Stream::paidEur);
        BigDecimal remaining = sum(streams, Stream::remainingEur);
        BigDecimal forecast = paid.add(remaining);
        BigDecimal markup = Money.money(payable.enrosedEur());
        boolean finalized = order.status() != PurchaseOrderStatus.CONCEPT && !order.lines().isEmpty()
                && streams.stream().allMatch(Stream::finalized);

        List<Row> rows = rows(order, costing, received, notes);
        int orderedQuantity = rows.stream().mapToInt(row -> row.ordered).sum();
        int receivedQuantity = rows.stream().mapToInt(row -> row.received).sum();
        int damagedQuantity = rows.stream().mapToInt(row -> row.damaged).sum();
        int usableQuantity = rows.stream().mapToInt(row -> row.usable).sum();
        int unitQuantity = received ? usableQuantity : orderedQuantity;

        for (Stream stream : streams) {
            List<BigDecimal> weights = weights(stream.payee(), rows, order);
            List<BigDecimal> plannedShares = allocate(stream.plannedEur(), weights);
            List<BigDecimal> forecastShares = allocate(stream.forecastEur(), weights);
            // Round the complete cost once. Independent paid/remainder rounding
            // could otherwise invent a product-level variance on a partial payment.
            // With nonnegative payments, paid <= forecast, so these weights also
            // guarantee that no product is assigned more paid than its total cost.
            List<BigDecimal> paidShares = allocate(stream.paidEur(), forecastShares);
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                row.planned = row.planned.add(plannedShares.get(i));
                row.paid = row.paid.add(paidShares.get(i));
                row.remaining = row.remaining.add(forecastShares.get(i).subtract(paidShares.get(i)));
            }
        }
        List<BigDecimal> markupShares = markupShares(markup, rows, order.allocExtra() == Allocation.MANUAL);
        String allocationBasis = "Leverancier naar goederenwaarde; douane en transport naar berekende kosten; "
                + (costing.totals().separateCostsInPiecePrice()
                        ? "inspectie en andere kosten volgens de bestaande verdeling; "
                        : "apart geboekte inspectie en andere kosten naar goederenwaarde; ")
                + "extra betalingen naar goederenwaarde. Zonder waarde: aantallen, daarna gelijk verdeeld.";
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            BigDecimal lineForecast = row.paid.add(row.remaining);
            BigDecimal lineMarkup = markupShares.get(i);
            BigDecimal linePricing = lineForecast.add(lineMarkup);
            int quantity = received ? row.usable : row.ordered;
            lines.add(new Line(row.productId, row.name, row.ordered, row.received, row.damaged,
                    row.usable, quantity, unitBasis, row.planned, row.paid, row.remaining,
                    lineForecast, lineForecast.subtract(row.planned), lineMarkup, linePricing,
                    unit(lineForecast, quantity), unit(linePricing, quantity), allocationBasis));
        }

        notes.add("Budget op bestelde aantallen en vastgelegde orderkoersen; betalingen op hun opgeslagen eurowaarde.");
        notes.add("Openstaande bedragen blijven in de verwachte kostprijs. Alleen een expliciet vereffende betaling kan een lager eindbedrag bevestigen.");
        if (streams.stream().anyMatch(s -> !s.finalized() && s.overpaidEur().signum() > 0)) {
            notes.add("Meer betaald dan begroot blijft voorlopig totdat de betaalstroom is vereffend; een terugbetaling of correctie kan nog volgen.");
        }
        if (recorded.isEmpty()) notes.add("Er zijn nog geen betalingen geregistreerd; de kostprijs is een begroting.");
        if (!finalized) notes.add("Deze nacalculatie is voorlopig: nog niet alle betaalstromen zijn afgerond.");
        if (costing.totals().separateCostsEur() != null && costing.totals().separateCostsEur().signum() != 0
                && !costing.totals().separateCostsInPiecePrice()) {
            notes.add("De analytische kostprijs per stuk bevat ook de inspectie en andere kosten die in de oorspronkelijke berekening apart staan.");
        }
        if (order.paidTotalEur() != null) {
            notes.add("Het historische totaal bij ontvangst is alleen ter referentie en wordt niet bij de geregistreerde betalingen opgeteld.");
        }
        if (unitQuantity == 0) notes.add("Geen " + (received ? "bruikbare ontvangen" : "bestelde")
                + " stuks: een kostprijs per stuk kan niet worden bepaald.");
        if (rows.isEmpty() && forecast.signum() != 0) {
            notes.add("Er zijn geen productregels; het containertotaal kan niet over producten worden verdeeld.");
        }
        notes.add(received
                ? "Kostprijs per stuk op bruikbaar ontvangen aantal: ontvangen minus beschadigd. Tekorten en schade verhogen de kost per bruikbaar stuk."
                : "Zolang ontvangst niet is geregistreerd, is de kostprijs per stuk gebaseerd op het bestelde aantal.");
        notes.add("Deze analyse wijzigt geen productkostprijzen of historische voorraadwaardering.");

        Totals totals = new Totals(planned, paid, remaining, forecast, forecast.subtract(planned),
                markup, planned.add(markup), forecast.add(markup), finalized, orderedQuantity,
                receivedQuantity, damagedQuantity, usableQuantity, unitQuantity, unitBasis,
                unit(forecast, unitQuantity), unit(forecast.add(markup), unitQuantity), received,
                order.paidTotalEur() == null ? null : Money.money(order.paidTotalEur()));
        return new PurchaseReconciliation(List.copyOf(streams), totals, List.copyOf(lines), List.copyOf(notes));
    }

    private Stream stream(PurchaseOrder order, PurchasePayment.Payee payee, BigDecimal planned,
                          List<PurchasePayment> payments, List<String> notes) {
        List<PurchasePayment> matching = payments.stream().filter(p -> p.payee() == payee).toList();
        BigDecimal paid = Money.money(matching.stream().map(PurchasePayment::amountEur)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        boolean missingAmount = matching.stream().anyMatch(p -> p.amountEur() == null);
        boolean explicitlySettled = matching.stream().anyMatch(PurchasePayment::settles);
        // OTHER records already-incurred incidental fees, not an agreed payable
        // with a balance to settle. Its actual fees are additional cost, never an
        // overpayment to be counted again alongside those fees.
        boolean finalized = !missingAmount
                && (payee == PurchasePayment.Payee.OTHER || explicitlySettled || paid.compareTo(planned) == 0);
        BigDecimal remaining = explicitlySettled && !missingAmount ? ZERO : planned.subtract(paid).max(ZERO);
        BigDecimal forecast = paid.add(remaining);
        BigDecimal saving = explicitlySettled && !missingAmount ? planned.subtract(paid).max(ZERO) : ZERO;
        BigDecimal overpaid = payee == PurchasePayment.Payee.OTHER ? ZERO : paid.subtract(planned).max(ZERO);
        Status status;
        if (paid.signum() > 0 && planned.signum() == 0) status = Status.ADDITIONAL;
        else if (overpaid.signum() > 0) status = Status.OVERPAID;
        else if (saving.signum() > 0) status = Status.SETTLED_LOWER;
        else if (planned.signum() == 0 && paid.signum() == 0) status = Status.NOT_APPLICABLE;
        else if (paid.compareTo(planned) == 0) status = Status.PAID;
        else if (paid.signum() == 0) status = order.status() == PurchaseOrderStatus.CONCEPT ? Status.PLANNED : Status.UNPAID;
        else status = Status.PARTIAL;
        if (missingAmount) notes.add(payee.dutchLabel()
                + ": een historische betaling mist de vastgelegde eurowaarde; controleer die betaling voordat de kostprijs definitief wordt.");
        return new Stream(payee, payee.dutchLabel(), status, planned, paid, remaining,
                forecast, forecast.subtract(planned), overpaid, saving,
                explicitlySettled, finalized, matching.size());
    }

    /** Aggregate duplicate product lines without losing their ordered/received quantities. */
    private List<Row> rows(PurchaseOrder order, LandedCost costing, boolean received, List<String> notes) {
        Map<Long, Row> byProduct = new LinkedHashMap<>();
        if (costing.lines() != null) {
            for (LandedCost.Line cost : costing.lines()) {
                Row row = byProduct.computeIfAbsent(cost.productId(), Row::new);
                row.name = cost.productName();
                row.calculated = true;
                row.goodsWeight = row.goodsWeight.add(positive(cost.goodsEur()));
                row.logisticsWeight = row.logisticsWeight.add(positive(cost.originEur()))
                        .add(positive(cost.freightEur())).add(positive(cost.dutyEur())).add(positive(cost.destinationEur()));
                row.separateWeight = row.separateWeight.add(positive(cost.separateEur()));
                row.markupAmount = row.markupAmount.add(Money.nz(cost.extraRevenueEur()));
                row.cbmWeight = row.cbmWeight.add(positive(cost.cbm()));
            }
        }
        for (PurchaseOrderLine line : order.lines()) {
            Row row = byProduct.computeIfAbsent(line.productId(), Row::new);
            row.ordered += line.ordered();
            if (received) {
                row.received += line.received();
                row.damaged += line.damaged();
                row.usable += line.usable();
            }
            row.nonDdp |= !line.deliveredDutyPaid();
        }
        if (received && order.lines().stream().anyMatch(line -> line.orderedQuantity() == null)) {
            notes.add("Bij oudere regels ontbreekt het oorspronkelijke bestelde aantal; voor die regels is het huidige aantal als budgetbasis gebruikt.");
        }
        if (byProduct.values().stream().anyMatch(row -> !row.calculated)) {
            notes.add("Een productregel ontbreekt in de oorspronkelijke kostberekening; de verdeling gebruikt waar nodig aantallen als terugvalbasis.");
        }
        return new ArrayList<>(byProduct.values());
    }

    private List<BigDecimal> weights(PurchasePayment.Payee payee, List<Row> rows, PurchaseOrder order) {
        List<BigDecimal> preferred = rows.stream().map(row -> switch (payee) {
            case SUPPLIER, OTHER -> row.goodsWeight;
            case LOGISTICS -> row.logisticsWeight;
            case SEPARATE -> row.separateWeight;
        }).toList();
        if (payee == PurchasePayment.Payee.SEPARATE && order.separateInPiecePrice()
                && preferred.stream().noneMatch(value -> value.signum() > 0)) {
            // An inspection first paid without a prior budget still follows its
            // chosen key; there were no nonzero component shares to reuse yet.
            preferred = switch (order.separateAllocation()) {
                case CBM -> rows.stream().map(row -> row.cbmWeight).toList();
                case PIECES, MANUAL -> rows.stream().map(row -> BigDecimal.valueOf(row.ordered)).toList();
                case VALUE -> fallback(preferred, rows, true);
                case SEPARATE -> preferred;
            };
        }
        return fallback(preferred, rows, payee == PurchasePayment.Payee.LOGISTICS);
    }

    private List<BigDecimal> markupShares(BigDecimal total, List<Row> rows, boolean manual) {
        if (!manual) return allocate(total,
                fallback(rows.stream().map(row -> positive(row.markupAmount)).toList(), rows, false));
        // A hand-spread markup may deliberately be negative for one product
        // while another carries more. Preserve that signed agreement exactly;
        // treating it as positive weights would silently reprice both products.
        List<BigDecimal> shares = rows.stream().map(row -> Money.money(row.markupAmount)).toList();
        BigDecimal remainder = total.subtract(shares.stream().reduce(ZERO, BigDecimal::add));
        List<BigDecimal> corrections = allocate(remainder,
                fallback(rows.stream().map(row -> row.goodsWeight).toList(), rows, false));
        List<BigDecimal> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) result.add(shares.get(i).add(corrections.get(i)));
        return result;
    }

    private List<BigDecimal> fallback(List<BigDecimal> preferred, List<Row> rows, boolean preferNonDdp) {
        if (preferred.stream().anyMatch(value -> value.signum() > 0)) return preferred;
        boolean excludeDdp = preferNonDdp && rows.stream().anyMatch(row -> row.nonDdp);
        List<BigDecimal> goods = rows.stream().map(row -> excludeDdp && !row.nonDdp ? ZERO : row.goodsWeight).toList();
        if (goods.stream().anyMatch(value -> value.signum() > 0)) return goods;
        List<BigDecimal> quantities = rows.stream()
                .map(row -> excludeDdp && !row.nonDdp ? ZERO : BigDecimal.valueOf(row.ordered)).toList();
        if (quantities.stream().anyMatch(value -> value.signum() > 0)) return quantities;
        return rows.stream().map(row -> excludeDdp && !row.nonDdp ? ZERO : BigDecimal.ONE).toList();
    }

    /** Largest remainders, stable product order: every source cent is assigned exactly once. */
    private List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> weights) {
        if (weights.isEmpty()) return List.of();
        BigDecimal weightTotal = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightTotal.signum() == 0) return allocate(amount, weights.stream().map(w -> BigDecimal.ONE).toList());
        BigDecimal cents = Money.money(amount).abs().movePointRight(2);
        List<BigDecimal> assigned = new ArrayList<>();
        List<BigDecimal> remainders = new ArrayList<>();
        BigDecimal assignedCents = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            // Compare exact remainder numerators; never round ratios before deciding who receives a cent.
            BigDecimal weighted = cents.multiply(weight);
            BigDecimal whole = weighted.divide(weightTotal, 0, RoundingMode.DOWN);
            assigned.add(whole);
            remainders.add(weighted.subtract(whole.multiply(weightTotal)));
            assignedCents = assignedCents.add(whole);
        }
        int extras = cents.subtract(assignedCents).intValueExact();
        List<Integer> priority = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) priority.add(i);
        priority.sort(Comparator.comparing((Integer i) -> remainders.get(i)).reversed());
        for (int i = 0; i < extras; i++) {
            int target = priority.get(i);
            assigned.set(target, assigned.get(target).add(BigDecimal.ONE));
        }
        BigDecimal sign = BigDecimal.valueOf(amount.signum());
        return assigned.stream().map(value -> value.multiply(sign).movePointLeft(2).setScale(2)).toList();
    }

    private BigDecimal positive(BigDecimal value) { return Money.nz(value).max(BigDecimal.ZERO); }

    private BigDecimal unit(BigDecimal amount, int quantity) {
        return quantity == 0 ? null : amount.divide(BigDecimal.valueOf(quantity), Money.UNIT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<Stream> streams, Function<Stream, BigDecimal> amount) {
        return streams.stream().map(amount).reduce(ZERO, BigDecimal::add);
    }

    private static class Row {
        private final Long productId;
        private String name;
        private int ordered;
        private int received;
        private int damaged;
        private int usable;
        private boolean nonDdp;
        private boolean calculated;
        private BigDecimal goodsWeight = ZERO;
        private BigDecimal logisticsWeight = ZERO;
        private BigDecimal separateWeight = ZERO;
        private BigDecimal markupAmount = ZERO;
        private BigDecimal cbmWeight = ZERO;
        private BigDecimal planned = ZERO;
        private BigDecimal paid = ZERO;
        private BigDecimal remaining = ZERO;

        private Row(Long productId) {
            this.productId = productId;
            this.name = "Product #" + productId;
        }
    }
}
