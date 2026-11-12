package be.enrosed.partnerportal.application;

import be.enrosed.sales.application.PartnerSettlements;
import be.enrosed.sales.application.SalesOrderService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.LongToIntFunction;

/**
 * The figures of one Factureren document, per product, taken from the plan the document is made with
 * and never computed another way: the preview, the figure snapshot written with the document
 * (partner_sale_invoice_figure), its Documentnotitie and the margin popup's breakdown all read these
 * rows. ERP only: never in a partner response.
 *
 * <p>Per row: gross - commission - transport - extra (the sale parts) - buybackCost (-net of the
 * buyback parts) = the product's own proceeds; + absorbedEur (what the q = 0 netting moved onto it,
 * negative when moved off it) = proceedsEur, the proceeds its invoice line goes on with. proceeds -
 * cost = profit; value - cost = Enrosed's profit (on a slotfactuur incl. the correction of earlier
 * deelfacturen on the line that carries it); proceeds - value = what the partner keeps of the profit
 * before advances; value - advance = what this document invoices. A container's cost, value and
 * advance are the settlement snapshot lines ({@link SalesOrderService#previewAuctionSettlementFromReports}
 * / the created document's plan); own stock's are {@link PartnerSaleInvoicing#planOwn}'s.
 */
final class PartnerInvoiceFigures {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private PartnerInvoiceFigures() {}

    /** One product of a document. onLine = it has an invoice line (false: absorbed, or an own extra line). */
    record Row(long productId, int soldQuantity, int boughtBackQuantity, int quantity, int salePieces,
               BigDecimal grossEur, BigDecimal commissionEur, BigDecimal transportEur, BigDecimal extraEur,
               BigDecimal buybackCostEur, BigDecimal proceedsEur, BigDecimal absorbedEur, BigDecimal costEur,
               BigDecimal valueEur, BigDecimal advanceEur, BigDecimal correctionEur, Set<Long> reportIds,
               LocalDate firstSoldOn, LocalDate lastSoldOn, boolean onLine) {

        BigDecimal profitEur() {
            return proceedsEur.subtract(costEur);
        }

        BigDecimal enrosedProfitEur() {
            return valueEur.subtract(costEur);
        }

        BigDecimal partnerProfitEur() {
            return proceedsEur.subtract(valueEur);
        }

        BigDecimal balanceEur() {
            return valueEur.subtract(advanceEur);
        }
    }

    /** One document: its rows (lines first, in plan order) and the percents it was made with. */
    record Group(String source, Long purchaseOrderId, List<Row> rows, BigDecimal profitSharePct,
                 BigDecimal costSharePct, boolean finalSettlement) {

        BigDecimal sum(Function<Row, BigDecimal> field) {
            return rows.stream().map(field).reduce(ZERO, BigDecimal::add);
        }

        int salePieces() {
            return rows.stream().mapToInt(Row::salePieces).sum();
        }

        Set<Long> reportIds() {
            Set<Long> ids = new TreeSet<>();
            for (Row row : rows) ids.addAll(row.reportIds());
            return ids;
        }

        LocalDate firstSoldOn() {
            return rows.stream().map(Row::firstSoldOn).filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        }

        LocalDate lastSoldOn() {
            return rows.stream().map(Row::lastSoldOn).filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        }

        Row row(long productId) {
            return rows.stream().filter(row -> row.productId() == productId).findFirst().orElse(null);
        }
    }

    /**
     * A container document: the plan's lines with the settlement's snapshot figures (cost, value incl.
     * the true-up on the last line, advance), then the absorbed q = 0 products (no line, proceeds 0).
     */
    static Group container(long containerId, PartnerSaleInvoicing.ContainerPlan plan,
                           SalesOrderService.AuctionSettlementPlan settlement, BigDecimal costSharePct,
                           LongToIntFunction piecesPerSalesUnit) {
        Map<Long, PartnerSettlements.Line> snapshot = new HashMap<>();
        for (PartnerSettlements.Line line : settlement.snapshotLines()) snapshot.put(line.productId(), line);
        List<PartnerSettlements.Line> ordered = settlement.snapshotLines();
        Long correctedProduct = ordered.isEmpty() ? null : ordered.getLast().productId();
        BigDecimal correction = money(settlement.correctionEur());
        List<Row> rows = new ArrayList<>();
        Set<Long> onLines = new java.util.HashSet<>();
        for (PartnerSaleInvoicing.Line line : plan.lines()) {
            PartnerSaleInvoicing.ProductSum sum = line.sum();
            PartnerSettlements.Line figure = snapshot.get(sum.productId);
            BigDecimal proceeds = figure != null ? money(figure.proceedsEur()) : money(line.proceeds());
            boolean corrected = correction.signum() != 0 && Long.valueOf(sum.productId).equals(correctedProduct);
            rows.add(row(sum, proceeds, figure == null ? ZERO : money(figure.costEur()),
                    figure == null ? ZERO : money(figure.revenueEur()), figure == null ? ZERO : money(figure.advanceEur()),
                    corrected ? correction : ZERO, true, piecesPerSalesUnit));
            onLines.add(sum.productId);
        }
        for (PartnerSaleInvoicing.ProductSum sum : plan.included()) {
            if (onLines.contains(sum.productId)) continue;
            rows.add(row(sum, ZERO, ZERO, ZERO, ZERO, ZERO, false, piecesPerSalesUnit));
        }
        return new Group(PartnerSaleInvoicing.SOURCE_CONTAINER, containerId, List.copyOf(rows),
                pct(settlement.profitSharePct()), pct(costSharePct), settlement.finalSettlement());
    }

    /** The own-stock invoice: per product c and v of the plan; no advance, no absorption, Enrosed paid 100 %. */
    static Group own(PartnerSaleInvoicing.OwnPlan plan, BigDecimal share, LongToIntFunction piecesPerSalesUnit) {
        List<Row> rows = new ArrayList<>();
        for (PartnerSaleInvoicing.OwnProduct product : plan.products()) {
            PartnerSaleInvoicing.ProductSum sum = product.sum();
            rows.add(row(sum, money(sum.proceeds()), money(product.costEur()), money(product.valueEur()), ZERO, ZERO,
                    product.onLine(), piecesPerSalesUnit));
        }
        return new Group(PartnerSaleInvoicing.SOURCE_OWN, null, List.copyOf(rows), pct(share), BigDecimal.ZERO, false);
    }

    private static Row row(PartnerSaleInvoicing.ProductSum sum, BigDecimal proceeds, BigDecimal cost, BigDecimal value,
                           BigDecimal advance, BigDecimal correction, boolean onLine, LongToIntFunction piecesPerSalesUnit) {
        BigDecimal own = money(sum.proceeds());
        int factor = Math.max(1, piecesPerSalesUnit.applyAsInt(sum.productId));
        return new Row(sum.productId, sum.sold, sum.boughtBack, sum.quantity(), sum.sold * factor,
                money(sum.saleGross), money(sum.saleCommission), money(sum.saleTransport), money(sum.saleExtra),
                money(sum.buyback), proceeds, proceeds.subtract(own), cost, value, advance, correction,
                Set.copyOf(sum.reportIds), sum.firstSoldOn, sum.lastSoldOn, onLine);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal value) {
        return be.enrosed.partnerportal.domain.PartnerNumbers.pct(value);
    }
}
