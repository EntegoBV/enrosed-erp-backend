package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads meldingen back as PartnerReport: the parts of an entry (one per source) merged into its
 * sale and buyback, the totals, the status from the open-part rule and whether the partner may
 * still undo it. Everything for a page of meldingen is read in a fixed number of queries.
 *
 * Only SALE and BUYBACK rows are parts; a row of any other line_type is ignored here.
 */
@ApplicationScoped
public class PartnerReportReader {

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 100;
    static final int RECENT_DAYS = 60;

    /** A melding with everything the views are built from; staff views add their own fields to it. */
    public record Loaded(PartnerSaleReportEntity report, List<PartnerSaleReportLineEntity> lines,
                         PartnerOpenParts.Rule rule, String status, boolean canUndo) {}

    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerOpenParts openParts;

    public PartnerReportReader(PartnerPortalRepositories.Reports reports, PartnerPortalRepositories.Lines lines,
                               PartnerOpenParts openParts) {
        this.reports = reports;
        this.lines = lines;
        this.openParts = openParts;
    }

    /** Newest first, {@code before} an exclusive id cursor, limit 1..100 (default 50). */
    public PartnerPortalViews.ReportPage page(long customerId, Long before, Integer limit,
                                              PartnerProductTiles.Catalog catalog) {
        int size = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        List<PartnerSaleReportEntity> found = reports.page(customerId, before, size + 1);
        boolean more = found.size() > size;
        List<PartnerSaleReportEntity> shown = more ? found.subList(0, size) : found;
        return new PartnerPortalViews.ReportPage(views(load(shown), catalog), more);
    }

    public List<PartnerPortalViews.PartnerReport> newest(long customerId, int count,
                                                         PartnerProductTiles.Catalog catalog) {
        return views(load(reports.page(customerId, null, count)), catalog);
    }

    /** Lines, documents and mirroring buybacks of these meldingen, in their given order. */
    public List<Loaded> load(List<PartnerSaleReportEntity> shown) {
        if (shown.isEmpty()) return List.of();
        List<Long> ids = shown.stream().map(report -> report.id).toList();
        List<PartnerSaleReportLineEntity> all = lines.byReports(ids);
        PartnerOpenParts.Rule rule = openParts.forLines(all);
        Map<Long, List<PartnerSaleReportLineEntity>> byReport = new HashMap<>();
        Map<Long, Long> reportOfSalePart = new HashMap<>();
        for (PartnerSaleReportLineEntity line : all) {
            byReport.computeIfAbsent(line.reportId, key -> new ArrayList<>()).add(line);
            if (line.isSale()) reportOfSalePart.put(line.id, line.reportId);
        }
        /* A melding whose sale a buyback of another live melding mirrors cannot be undone. */
        Set<Long> mirroredFromElsewhere = new HashSet<>();
        for (Object[] row : lines.liveBuybacksMirroring(reportOfSalePart.keySet())) {
            Long saleReport = reportOfSalePart.get(((Number) row[0]).longValue());
            long buybackReport = ((Number) row[1]).longValue();
            if (saleReport != null && saleReport != buybackReport) mirroredFromElsewhere.add(saleReport);
        }
        List<Loaded> result = new ArrayList<>();
        for (PartnerSaleReportEntity report : shown) {
            List<PartnerSaleReportLineEntity> parts = byReport.getOrDefault(report.id, List.of());
            String status = status(report, parts, rule);
            boolean canUndo = PartnerPortalRules.STATUS_OPEN.equals(status) && !mirroredFromElsewhere.contains(report.id);
            result.add(new Loaded(report, parts, rule, status, canUndo));
        }
        return result;
    }

    public List<PartnerPortalViews.PartnerReport> views(Collection<Loaded> loaded, PartnerProductTiles.Catalog catalog) {
        return loaded.stream().map(item -> view(item, catalog)).toList();
    }

    public PartnerPortalViews.PartnerReport view(Loaded loaded, PartnerProductTiles.Catalog catalog) {
        PartnerSaleReportEntity report = loaded.report();
        Map<Integer, List<PartnerSaleReportLineEntity>> byEntry = new TreeMap<>();
        for (PartnerSaleReportLineEntity line : loaded.lines()) {
            if (!line.isSale() && !line.isBuyback()) continue;
            byEntry.computeIfAbsent(line.entryNo == null ? 0 : line.entryNo, key -> new ArrayList<>()).add(line);
        }
        List<PartnerPortalViews.Entry> entries = new ArrayList<>();
        Sum sale = new Sum();
        Sum buyback = new Sum();
        for (List<PartnerSaleReportLineEntity> parts : byEntry.values()) {
            Sum entrySale = new Sum();
            Sum entryBuyback = new Sum();
            for (PartnerSaleReportLineEntity part : parts) (part.isSale() ? entrySale : entryBuyback).add(part);
            sale.add(entrySale);
            buyback.add(entryBuyback);
            long productId = parts.getFirst().productId == null ? 0 : parts.getFirst().productId;
            PartnerPortalViews.ProductTile tile = catalog.tile(productId);
            entries.add(new PartnerPortalViews.Entry(productId, tile.name(), tile.variant(), tile.colourHex(),
                    tile.unitOne(), tile.unitOther(), tile.hasPhoto(), tile.photoVersion(),
                    entrySale.parts == 0 ? null : new PartnerPortalViews.SalePart(entrySale.quantity,
                            PartnerNumbers.unit(entrySale.unitPrice), PartnerNumbers.money(entrySale.gross),
                            PartnerNumbers.money(entrySale.commission), PartnerNumbers.money(entrySale.transport),
                            PartnerNumbers.money(entrySale.extra), PartnerNumbers.money(entrySale.net)),
                    entryBuyback.parts == 0 ? null : new PartnerPortalViews.BuybackPart(entryBuyback.quantity,
                            PartnerNumbers.unit(entryBuyback.unitPrice), PartnerNumbers.money(entryBuyback.gross))));
        }
        PartnerPortalViews.Totals totals = new PartnerPortalViews.Totals(sale.quantity, buyback.quantity,
                PartnerNumbers.money(sale.gross), PartnerNumbers.money(sale.commission),
                PartnerNumbers.money(sale.transport), PartnerNumbers.money(sale.extra),
                PartnerNumbers.money(sale.net), PartnerNumbers.money(buyback.gross),
                PartnerNumbers.money(sale.net.subtract(buyback.gross)));
        PartnerPortalViews.ExtraCost extra = report.extraCostMode == null ? null
                : new PartnerPortalViews.ExtraCost(report.extraCostMode, PartnerNumbers.unit(report.extraCostValue));
        return new PartnerPortalViews.PartnerReport(report.id, PartnerPortalRules.reportNumber(report.id),
                report.channel, report.soldOn, report.createdAt, report.createdByName,
                PartnerNumbers.pct(report.commissionPct), PartnerNumbers.pct(report.transportPct), extra,
                entries, totals, loaded.status(), loaded.canUndo());
    }

    /**
     * Products this partner sold (non-voided meldingen) in the last 60 days, newest first: the
     * quantity sold in that window, the last day and the average of the newest entry.
     */
    public List<PartnerPortalViews.RecentSaleTile> recentSales(long customerId, PartnerProductTiles.Catalog catalog) {
        LocalDate from = PartnerPortalRules.today().minusDays(RECENT_DAYS);
        Map<Long, int[]> quantities = new LinkedHashMap<>();
        Map<Long, LocalDate> lastSoldOn = new HashMap<>();
        Map<Long, BigDecimal> lastAverage = new HashMap<>();
        for (Object[] row : lines.saleParts(customerId, from)) {
            PartnerSaleReportLineEntity line = (PartnerSaleReportLineEntity) row[0];
            if (line.productId == null) continue;
            quantities.computeIfAbsent(line.productId, key -> new int[1])[0] += line.quantityOrZero();
            lastSoldOn.putIfAbsent(line.productId, (LocalDate) row[1]);
            if (line.unitPriceEur != null) lastAverage.putIfAbsent(line.productId, line.unitPriceEur);
        }
        List<PartnerPortalViews.RecentSaleTile> result = new ArrayList<>();
        quantities.forEach((productId, quantity) -> result.add(PartnerPortalViews.RecentSaleTile.of(
                catalog.tile(productId), quantity[0], lastSoldOn.get(productId),
                PartnerNumbers.unit(lastAverage.get(productId)))));
        return result;
    }

    static String status(PartnerSaleReportEntity report, List<PartnerSaleReportLineEntity> parts,
                         PartnerOpenParts.Rule rule) {
        if (report.isVoided()) return PartnerPortalRules.STATUS_VOIDED;
        int open = 0;
        int counted = 0;
        for (PartnerSaleReportLineEntity part : parts) {
            if (!part.isSale() && !part.isBuyback()) continue;
            counted++;
            if (rule.isOpen(part, false)) open++;
        }
        if (open == counted) return PartnerPortalRules.STATUS_OPEN;
        if (open == 0) return PartnerPortalRules.STATUS_INVOICED;
        return PartnerPortalRules.STATUS_PARTLY_INVOICED;
    }

    /** Running sums of parts; the unit price is the first part's (every part of an entry has the same). */
    private static final class Sum {
        int parts;
        int quantity;
        BigDecimal unitPrice;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal transport = BigDecimal.ZERO;
        BigDecimal extra = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;

        void add(PartnerSaleReportLineEntity part) {
            parts++;
            quantity += part.quantityOrZero();
            if (unitPrice == null) unitPrice = part.unitPriceEur;
            gross = gross.add(nz(part.grossEur));
            commission = commission.add(nz(part.commissionEur));
            transport = transport.add(nz(part.transportEur));
            extra = extra.add(nz(part.extraCostEur));
            net = net.add(nz(part.netEur));
        }

        void add(Sum other) {
            parts += other.parts;
            quantity += other.quantity;
            gross = gross.add(other.gross);
            commission = commission.add(other.commission);
            transport = transport.add(other.transport);
            extra = extra.add(other.extra);
            net = net.add(other.net);
        }

        private static BigDecimal nz(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
