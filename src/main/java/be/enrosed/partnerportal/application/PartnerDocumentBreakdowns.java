package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleInvoiceFigureEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleInvoicePartEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.PartnerSettlements;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The margin popup's breakdown of a document Factureren made (GET
 * /api/partner-sales/documents/{salesOrderId}/breakdown, staff only): per product what the meldingen
 * sold and bought back, the costs, the netto opbrengst, the inkoopkost and its split, the profit and
 * its split, our value and the advance, read from the figure snapshot written with the document
 * (partner_sale_invoice_figure), so it shows exactly what the preview showed and the note says.
 * The meldingen come from the document's links (partner_sale_invoice_part). A report-backed document
 * from before round 2 has no snapshot: its lines are rebuilt from the linked parts and the document's
 * own figures (settlement snapshot, or the invoice lines for own stock) and labelled as such. Any other
 * document answers 404. ERP only: never reachable by the partner.
 */
@ApplicationScoped
public class PartnerDocumentBreakdowns {

    public static final String FALLBACK_NOTE = "Opbouw uit de factuurregels; geen detail bewaard";
    static final String NOT_REPORT_BACKED = "Dit document komt niet uit Veilingverkopen";
    static final String MIXED = "MIXED";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final PartnerPortalRepositories.InvoiceParts invoiceParts;
    private final PartnerPortalRepositories.InvoiceFigures figureRows;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerProductTiles tiles;
    private final PartnerSettlements settlements;
    private final SalesRepositories.Orders orders;

    public PartnerDocumentBreakdowns(PartnerPortalRepositories.InvoiceParts invoiceParts,
                                     PartnerPortalRepositories.InvoiceFigures figureRows,
                                     PartnerPortalRepositories.Lines lines, PartnerPortalRepositories.Reports reports,
                                     PartnerProductTiles tiles, PartnerSettlements settlements,
                                     SalesRepositories.Orders orders) {
        this.invoiceParts = invoiceParts;
        this.figureRows = figureRows;
        this.lines = lines;
        this.reports = reports;
        this.tiles = tiles;
        this.settlements = settlements;
        this.orders = orders;
    }

    @Transactional
    public PartnerPortalViews.DocumentBreakdown of(long salesOrderId) {
        List<PartnerSaleInvoicePartEntity> links = invoiceParts.byOrder(salesOrderId);
        if (links.isEmpty()) throw new NotFoundException(NOT_REPORT_BACKED);
        List<Long> lineIds = links.stream().map(link -> link.lineId).filter(Objects::nonNull).distinct().toList();
        List<PartnerSaleReportLineEntity> parts = lineIds.isEmpty() ? List.of()
                : lines.list("id in ?1", io.quarkus.panache.common.Sort.ascending("id"), lineIds);
        Long containerId = parts.stream().map(part -> part.purchaseOrderId).filter(Objects::nonNull).findFirst().orElse(null);
        String source = containerId == null ? PartnerSaleInvoicing.SOURCE_OWN : PartnerSaleInvoicing.SOURCE_CONTAINER;
        PartnerSettlements.Snapshot snapshot = containerId == null ? null : settlements.find(salesOrderId);
        List<PartnerPortalViews.BreakdownMelding> meldingen = meldingen(parts);
        String channel = meldingen.stream().map(PartnerPortalViews.BreakdownMelding::channel).distinct().count() > 1 ? MIXED
                : meldingen.stream().map(PartnerPortalViews.BreakdownMelding::channel).findFirst().orElse(null);
        PartnerProductTiles.Catalog catalog = tiles.load();
        List<PartnerSaleInvoiceFigureEntity> rows = figureRows.byOrder(salesOrderId);
        List<PartnerPortalViews.BreakdownLine> result = rows.isEmpty()
                ? fallback(salesOrderId, parts, snapshot, catalog)
                : fromSnapshot(rows, containerId != null, catalog);
        BigDecimal value = total(result, PartnerPortalViews.BreakdownLine::valueEur);
        BigDecimal advance = total(result, PartnerPortalViews.BreakdownLine::advanceEur);
        BigDecimal cost = total(result, PartnerPortalViews.BreakdownLine::costEur);
        BigDecimal proceeds = total(result, PartnerPortalViews.BreakdownLine::proceedsEur);
        PartnerPortalViews.BreakdownLine first = result.isEmpty() ? null : result.getFirst();
        return new PartnerPortalViews.DocumentBreakdown(salesOrderId, source, containerId,
                snapshot != null && snapshot.finalSettlement(), channel, meldingen, result,
                total(result, PartnerPortalViews.BreakdownLine::grossEur),
                total(result, PartnerPortalViews.BreakdownLine::commissionEur),
                total(result, PartnerPortalViews.BreakdownLine::transportEur),
                total(result, PartnerPortalViews.BreakdownLine::extraEur),
                total(result, PartnerPortalViews.BreakdownLine::buybackCostEur), proceeds, cost,
                subtract(proceeds, cost), subtract(value, cost), subtract(proceeds, value), value, advance,
                subtract(value, advance), total(result, PartnerPortalViews.BreakdownLine::correctionEur),
                first == null ? null : first.costSharePct(), first == null ? null : first.profitSharePct(),
                rows.isEmpty(), rows.isEmpty() ? FALLBACK_NOTE : null);
    }

    /**
     * The meldingen of these parts: an archive row (the old version an edit kept after this document
     * was trashed) counts as its melding, with the day and channel it was invoiced with.
     */
    private List<PartnerPortalViews.BreakdownMelding> meldingen(List<PartnerSaleReportLineEntity> parts) {
        Set<Long> ids = new TreeSet<>();
        for (PartnerSaleReportLineEntity part : parts) if (part.reportId != null) ids.add(part.reportId);
        Map<Long, PartnerPortalViews.BreakdownMelding> byMelding = new TreeMap<>();
        if (ids.isEmpty()) return List.of();
        for (PartnerSaleReportEntity report : reports.list("id in ?1", ids)) {
            long melding = report.revisionOf != null ? report.revisionOf : report.id;
            byMelding.putIfAbsent(melding, new PartnerPortalViews.BreakdownMelding(melding,
                    PartnerPortalRules.reportNumber(melding), report.soldOn, report.channel));
        }
        return List.copyOf(byMelding.values());
    }

    private static List<PartnerPortalViews.BreakdownLine> fromSnapshot(List<PartnerSaleInvoiceFigureEntity> rows,
                                                                      boolean container,
                                                                      PartnerProductTiles.Catalog catalog) {
        Map<Long, BigDecimal> absorbed = new LinkedHashMap<>();
        for (PartnerSaleInvoiceFigureEntity row : rows) if (row.productId != null) absorbed.put(row.productId, money(row.absorbedEur));
        List<PartnerPortalViews.BreakdownLine> result = new ArrayList<>();
        for (PartnerSaleInvoiceFigureEntity row : rows) {
            long productId = row.productId == null ? 0 : row.productId;
            PartnerPortalViews.ProductTile tile = catalog.tile(productId);
            int sold = nz(row.soldQuantity);
            int quantity = nz(row.quantity);
            int factor = sold > 0 && row.salePieces != null && row.salePieces % sold == 0 ? Math.max(1, row.salePieces / sold)
                    : Math.max(1, tile.piecesPerSalesUnit());
            BigDecimal proceeds = money(row.proceedsEur);
            BigDecimal cost = money(row.costEur);
            BigDecimal value = money(row.valueEur);
            BigDecimal moved = money(row.absorbedEur);
            result.add(new PartnerPortalViews.BreakdownLine(productId, tile.name(), tile.variant(), sold,
                    nz(row.boughtBackQuantity), quantity, quantity * factor, row.salePieces == null ? sold * factor : row.salePieces,
                    money(row.grossEur), money(row.commissionEur), money(row.transportEur), money(row.extraEur),
                    money(row.buybackCostEur), proceeds, moved,
                    PartnerSaleInvoicing.absorbedFrom(productId, moved, absorbed, catalog), cost,
                    PartnerNumbers.pct(row.costSharePct), proceeds.subtract(cost), PartnerNumbers.pct(row.profitSharePct),
                    value.subtract(cost), proceeds.subtract(value), value, money(row.advanceEur), money(row.correctionEur),
                    /* A container line has q > 0; an own product without a positive value went on an extra line. */
                    quantity > 0 && (container || value.signum() > 0)));
        }
        return result;
    }

    /**
     * A document from before round 2: per product the linked parts' sums, with the settlement's snapshot
     * lines (container) or the invoice lines (own stock: value = q x unit price, cost = q x unit cost) for
     * what the document made of them. A product the own invoice put on an extra line has no value here.
     */
    private List<PartnerPortalViews.BreakdownLine> fallback(long salesOrderId, List<PartnerSaleReportLineEntity> parts,
                                                            PartnerSettlements.Snapshot snapshot,
                                                            PartnerProductTiles.Catalog catalog) {
        Map<Long, Sums> byProduct = new TreeMap<>();
        for (PartnerSaleReportLineEntity part : parts) {
            if (part.productId == null || !(part.isSale() || part.isBuyback())) continue;
            byProduct.computeIfAbsent(part.productId, key -> new Sums()).add(part);
        }
        SalesOrder order = orders.findById(salesOrderId).orElse(null);
        BigDecimal share = order == null ? null : order.partnerSharePct();
        Map<Long, Figures> figures = new LinkedHashMap<>();
        if (snapshot != null) {
            for (PartnerSettlements.Line line : snapshot.lines()) {
                if (line.productId() == null) continue;
                figures.put(line.productId(), new Figures(money(line.proceedsEur()), money(line.costEur()),
                        money(line.revenueEur()), money(line.advanceEur())));
            }
        } else if (order != null) {
            for (SalesOrderLine line : order.lines()) {
                if (line.productId() == null || line.quantity() <= 0) continue;
                BigDecimal quantity = BigDecimal.valueOf(line.quantity());
                Sums sums = byProduct.get(line.productId());
                figures.put(line.productId(), new Figures(sums == null ? null : money(sums.proceeds()),
                        line.unitCostEur() == null ? null : money(line.unitCostEur().multiply(quantity)),
                        line.unitPriceEur() == null ? null : money(line.unitPriceEur().multiply(quantity)), ZERO));
            }
        }
        Set<Long> products = new TreeSet<>(byProduct.keySet());
        products.addAll(figures.keySet());
        Map<Long, BigDecimal> absorbed = new HashMap<>();
        for (Long productId : products) {
            Sums sums = byProduct.getOrDefault(productId, new Sums());
            Figures figure = figures.get(productId);
            BigDecimal own = money(sums.proceeds());
            if (snapshot != null) absorbed.put(productId, (figure == null ? ZERO : figure.proceeds()).subtract(own));
        }
        List<PartnerPortalViews.BreakdownLine> result = new ArrayList<>();
        for (Long productId : products) {
            Sums sums = byProduct.getOrDefault(productId, new Sums());
            Figures figure = figures.get(productId);
            PartnerPortalViews.ProductTile tile = catalog.tile(productId);
            int factor = Math.max(1, tile.piecesPerSalesUnit());
            int quantity = sums.sold - sums.boughtBack;
            BigDecimal proceeds = snapshot != null ? (figure == null ? ZERO : figure.proceeds()) : money(sums.proceeds());
            BigDecimal cost = figure == null ? (snapshot != null ? ZERO : null) : figure.cost();
            BigDecimal value = figure == null ? (snapshot != null ? ZERO : null) : figure.value();
            BigDecimal advance = figure == null ? ZERO : figure.advance();
            BigDecimal moved = snapshot == null ? ZERO : absorbed.getOrDefault(productId, ZERO);
            result.add(new PartnerPortalViews.BreakdownLine(productId, tile.name(), tile.variant(), sums.sold,
                    sums.boughtBack, quantity, quantity * factor, sums.sold * factor, money(sums.gross),
                    money(sums.commission), money(sums.transport), money(sums.extra), money(sums.buyback), proceeds,
                    moved, PartnerSaleInvoicing.absorbedFrom(productId, moved, absorbed, catalog), cost,
                    snapshot == null ? BigDecimal.ZERO : null, subtract(proceeds, cost),
                    PartnerNumbers.pct(share), subtract(value, cost), subtract(proceeds, value), value, advance, null,
                    figure != null));
        }
        return result;
    }

    private record Figures(BigDecimal proceeds, BigDecimal cost, BigDecimal value, BigDecimal advance) {}

    /** The linked parts of one product: sale gross and costs, buyback cost (-net). */
    private static final class Sums {
        int sold;
        int boughtBack;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal transport = BigDecimal.ZERO;
        BigDecimal extra = BigDecimal.ZERO;
        BigDecimal saleNet = BigDecimal.ZERO;
        BigDecimal buyback = BigDecimal.ZERO;

        void add(PartnerSaleReportLineEntity part) {
            if (part.isSale()) {
                sold += part.quantityOrZero();
                gross = gross.add(nz(part.grossEur));
                commission = commission.add(nz(part.commissionEur));
                transport = transport.add(nz(part.transportEur));
                extra = extra.add(nz(part.extraCostEur));
                saleNet = saleNet.add(nz(part.netEur));
            } else {
                boughtBack += part.quantityOrZero();
                buyback = buyback.add(PartnerSaleMoney.buybackCost(part.grossEur, part.netEur));
            }
        }

        BigDecimal proceeds() {
            return saleNet.subtract(buyback);
        }
    }

    private static BigDecimal total(List<PartnerPortalViews.BreakdownLine> rows,
                                    Function<PartnerPortalViews.BreakdownLine, BigDecimal> field) {
        BigDecimal sum = null;
        for (PartnerPortalViews.BreakdownLine row : rows) {
            BigDecimal value = field.apply(row);
            if (value != null) sum = (sum == null ? BigDecimal.ZERO : sum).add(value);
        }
        return sum == null ? null : money(sum);
    }

    private static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : money(left.subtract(right));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
