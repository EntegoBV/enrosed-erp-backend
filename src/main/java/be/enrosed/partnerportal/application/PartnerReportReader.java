package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportCostEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerCountUnits;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * sale and buyback, the totals, the status from the open-part rule, whether it counts as confirmed
 * and whether the partner (or staff) may still edit or undo it. Everything for a page of meldingen
 * is read in a fixed number of queries (plus one customer read per distinct buyer).
 *
 * <p>Only SALE and BUYBACK rows are parts; a row of any other line_type is ignored here. A buyback
 * costs -net (round 2: its gross plus its commission and transport; round-1 rows have no costs, so
 * -net = gross).
 */
@ApplicationScoped
public class PartnerReportReader {

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 100;
    static final int RECENT_DAYS = 60;
    static final String GONE = "Klant verwijderd";

    /**
     * A melding with everything the views are built from. confirmed = staff confirmed it or a document
     * holds one of its parts; mirroredElsewhere = a buyback of another live melding mirrors one of its sales.
     */
    public record Loaded(PartnerSaleReportEntity report, List<PartnerSaleReportLineEntity> lines,
                         List<PartnerSaleReportCostEntity> costs, PartnerOpenParts.Rule rule, String status,
                         boolean confirmed, boolean mirroredElsewhere, PartnerPortalViews.Buyer buyer) {

        /** Every part open and no archive row: the shared part of every rule. */
        private boolean open() {
            return PartnerPortalRules.STATUS_OPEN.equals(status) && !report.isArchive();
        }

        /**
         * The partner's rule for Bewerken: open and not confirmed. A melding another melding's buyback mirrors
         * can still get a price, cost or note fix (the money-only path); a quantity change is refused then.
         */
        public boolean partnerCanEdit() {
            return open() && !confirmed;
        }

        /** The partner's rule for Ongedaan maken: also no buyback of another live melding mirrors it. */
        public boolean partnerCanUndo() {
            return partnerCanEdit() && !mirroredElsewhere;
        }

        /** The staff rule for Bewerken: open, also when confirmed. */
        public boolean staffCanEdit() {
            return open();
        }

        /** The staff rule for Intrekken: also no buyback of another live melding mirrors it. */
        public boolean staffCanUndo() {
            return open() && !mirroredElsewhere;
        }

        /** Round 1's name for the partner's undo flag. */
        public boolean canUndo() {
            return partnerCanUndo();
        }

        /** For the list filters: VOIDED > INVOICED (nothing open) > CONFIRMED > UNCONFIRMED. */
        public String state() {
            if (PartnerPortalRules.STATUS_VOIDED.equals(status)) return STATE_VOIDED;
            if (PartnerPortalRules.STATUS_INVOICED.equals(status)) return STATE_INVOICED;
            return confirmed ? STATE_CONFIRMED : STATE_UNCONFIRMED;
        }
    }

    public static final String STATE_VOIDED = "VOIDED";
    public static final String STATE_INVOICED = "INVOICED";
    public static final String STATE_CONFIRMED = "CONFIRMED";
    public static final String STATE_UNCONFIRMED = "UNCONFIRMED";

    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerPortalRepositories.Costs costs;
    private final PartnerOpenParts openParts;
    private final SalesRepositories.Customers customers;

    public PartnerReportReader(PartnerPortalRepositories.Reports reports, PartnerPortalRepositories.Lines lines,
                               PartnerPortalRepositories.Costs costs, PartnerOpenParts openParts,
                               SalesRepositories.Customers customers) {
        this.reports = reports;
        this.lines = lines;
        this.costs = costs;
        this.openParts = openParts;
        this.customers = customers;
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

    /** Lines, cost lines, documents, mirroring buybacks and buyers of these meldingen, in their given order. */
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
        Map<Long, List<PartnerSaleReportCostEntity>> costsByReport = new HashMap<>();
        for (PartnerSaleReportCostEntity cost : costs.byReports(ids)) {
            costsByReport.computeIfAbsent(cost.reportId, key -> new ArrayList<>()).add(cost);
        }
        /* A melding whose sale a buyback of another live melding mirrors cannot be undone. */
        Set<Long> mirroredFromElsewhere = new HashSet<>();
        for (Object[] row : lines.liveBuybacksMirroring(reportOfSalePart.keySet())) {
            Long saleReport = reportOfSalePart.get(((Number) row[0]).longValue());
            long buybackReport = ((Number) row[1]).longValue();
            if (saleReport != null && saleReport != buybackReport) mirroredFromElsewhere.add(saleReport);
        }
        Map<Long, PartnerPortalViews.Buyer> buyers = buyers(shown);
        List<Loaded> result = new ArrayList<>();
        for (PartnerSaleReportEntity report : shown) {
            List<PartnerSaleReportLineEntity> parts = byReport.getOrDefault(report.id, List.of());
            String status = status(report, parts, rule);
            boolean held = parts.stream().anyMatch(line -> (line.isSale() || line.isBuyback()) && rule.holder(line) != null);
            boolean confirmed = report.confirmedAt != null || held;
            result.add(new Loaded(report, parts, costsByReport.getOrDefault(report.id, List.of()), rule, status,
                    confirmed, mirroredFromElsewhere.contains(report.id),
                    report.buyerCustomerId == null ? null : buyers.get(report.buyerCustomerId)));
        }
        return result;
    }

    /** {customerId, company, city} of every buyer named here; a customer that is gone reads "Klant verwijderd". */
    private Map<Long, PartnerPortalViews.Buyer> buyers(List<PartnerSaleReportEntity> shown) {
        Map<Long, PartnerPortalViews.Buyer> result = new HashMap<>();
        for (PartnerSaleReportEntity report : shown) {
            Long id = report.buyerCustomerId;
            if (id == null || result.containsKey(id)) continue;
            Customer customer = customers.findById(id).orElse(null);
            result.put(id, customer == null ? new PartnerPortalViews.Buyer(id, GONE, null)
                    : new PartnerPortalViews.Buyer(id, customer.company(), blankToNull(customer.city())));
        }
        return result;
    }

    public List<PartnerPortalViews.PartnerReport> views(Collection<Loaded> loaded, PartnerProductTiles.Catalog catalog) {
        return loaded.stream().map(item -> view(item, catalog)).toList();
    }

    /** The melding as the partner sees it (canEdit / canUndo by the partner rule); staff views swap in their own. */
    public PartnerPortalViews.PartnerReport view(Loaded loaded, PartnerProductTiles.Catalog catalog) {
        PartnerSaleReportEntity report = loaded.report();
        Map<Integer, List<PartnerSaleReportLineEntity>> byEntry = new TreeMap<>();
        for (PartnerSaleReportLineEntity line : loaded.lines()) {
            if (!line.isSale() && !line.isBuyback()) continue;
            byEntry.computeIfAbsent(line.entryNo == null ? 0 : line.entryNo, key -> new ArrayList<>()).add(line);
        }
        List<PartnerPortalViews.Entry> entries = new ArrayList<>();
        List<BigDecimal> saleGrossPerEntry = new ArrayList<>();
        Sum sale = new Sum();
        Sum buyback = new Sum();
        for (List<PartnerSaleReportLineEntity> parts : byEntry.values()) {
            long productId = parts.getFirst().productId == null ? 0 : parts.getFirst().productId;
            PartnerPortalViews.ProductTile tile = catalog.tile(productId, report.customerId);
            int factor = Math.max(1, tile.piecesPerSalesUnit());
            Sum entrySale = new Sum();
            Sum entryBuyback = new Sum();
            for (PartnerSaleReportLineEntity part : parts) (part.isSale() ? entrySale : entryBuyback).add(part, factor);
            sale.add(entrySale);
            buyback.add(entryBuyback);
            if (entrySale.parts > 0) saleGrossPerEntry.add(entrySale.gross);
            Entered saleTyped = entrySale.parts == 0 ? null : entrySale.entered(factor);
            Entered buybackTyped = entryBuyback.parts == 0 ? null : entryBuyback.entered(factor);
            entries.add(new PartnerPortalViews.Entry(productId, tile.name(), tile.variant(), tile.colourHex(),
                    tile.unitOne(), tile.unitOther(), tile.hasPhoto(), tile.photoVersion(),
                    saleTyped == null ? null : new PartnerPortalViews.SalePart(entrySale.quantity,
                            PartnerNumbers.unit(entrySale.unitPrice), PartnerNumbers.money(entrySale.gross),
                            PartnerNumbers.money(entrySale.commission), PartnerNumbers.money(entrySale.transport),
                            PartnerNumbers.money(entrySale.extra), PartnerNumbers.money(entrySale.net),
                            saleTyped.unit(), saleTyped.count(), PartnerNumbers.unit(saleTyped.piecePrice()),
                            saleTyped.piecesPerUnit(), entrySale.pieces),
                    buybackTyped == null ? null : new PartnerPortalViews.BuybackPart(entryBuyback.quantity,
                            PartnerNumbers.unit(entryBuyback.unitPrice), PartnerNumbers.money(entryBuyback.gross),
                            PartnerNumbers.money(entryBuyback.commission), PartnerNumbers.money(entryBuyback.transport),
                            PartnerNumbers.money(entryBuyback.net.negate()), buybackTyped.unit(), buybackTyped.count(),
                            PartnerNumbers.unit(buybackTyped.piecePrice()), buybackTyped.piecesPerUnit(),
                            entryBuyback.pieces),
                    tile.sku(), tile.familyId(), tile.familyName(), tile.piecesPerUnit(), tile.salesUnit(),
                    tile.piecesPerSalesUnit(), tile.pieceOne(), tile.pieceOther(), tile.countUnits(),
                    tile.defaultUnit()));
        }
        BigDecimal buybackCost = buyback.net.negate();
        PartnerPortalViews.Totals totals = new PartnerPortalViews.Totals(sale.quantity, buyback.quantity,
                PartnerNumbers.money(sale.gross), PartnerNumbers.money(sale.commission),
                PartnerNumbers.money(sale.transport), PartnerNumbers.money(sale.extra),
                PartnerNumbers.money(sale.net), PartnerNumbers.money(buybackCost),
                PartnerNumbers.money(sale.net.subtract(buybackCost)), sale.pieces, buyback.pieces,
                PartnerNumbers.money(buyback.gross));
        List<PartnerPortalViews.CostLine> costLines = costLines(loaded, saleGrossPerEntry);
        return new PartnerPortalViews.PartnerReport(report.id, PartnerPortalRules.reportNumber(report.id),
                report.channel, report.soldOn, report.createdAt, report.createdByName,
                PartnerNumbers.pct(report.commissionPct), PartnerNumbers.pct(report.transportPct),
                legacyExtraCost(costLines, totals), entries, totals, loaded.status(), loaded.partnerCanUndo(),
                report.note, report.editToken(), report.updatedAt, report.updatedByName, costLines, loaded.buyer(),
                loaded.confirmed(), report.confirmedAt, report.confirmedByName, loaded.partnerCanEdit());
    }

    /**
     * The cost lines with the euros each comes to: an AMOUNT line its value, a PCT line the sum over the
     * sale entries of round2(gross x pct / 100). A round-1 melding without rows shows its single extra cost.
     */
    private static List<PartnerPortalViews.CostLine> costLines(Loaded loaded, List<BigDecimal> saleGrossPerEntry) {
        List<PartnerPortalViews.CostLine> result = new ArrayList<>();
        if (!loaded.costs().isEmpty()) {
            for (PartnerSaleReportCostEntity cost : loaded.costs()) {
                result.add(costLine(cost.description, cost.costMode, cost.costValue, saleGrossPerEntry));
            }
            return result;
        }
        PartnerSaleReportEntity report = loaded.report();
        if (report.extraCostMode != null) {
            result.add(costLine(null, report.extraCostMode, report.extraCostValue, saleGrossPerEntry));
        }
        return result;
    }

    private static PartnerPortalViews.CostLine costLine(String description, String mode, BigDecimal value,
                                                        List<BigDecimal> saleGrossPerEntry) {
        BigDecimal amount;
        if (PartnerSaleMoney.PCT.equals(mode)) {
            amount = BigDecimal.ZERO;
            for (BigDecimal gross : saleGrossPerEntry) amount = amount.add(PartnerSaleMoney.pctOf(gross, value));
        } else {
            amount = value == null ? BigDecimal.ZERO : value;
        }
        return new PartnerPortalViews.CostLine(description, mode, PartnerNumbers.unit(value),
                saleGrossPerEntry.isEmpty() ? PartnerNumbers.moneyOrZero(null) : PartnerNumbers.money(amount));
    }

    /** Round 1's extraCost for older screens: the one line, or AMOUNT of the melding's whole extra kost. */
    private static PartnerPortalViews.ExtraCost legacyExtraCost(List<PartnerPortalViews.CostLine> lines,
                                                               PartnerPortalViews.Totals totals) {
        if (lines.isEmpty()) return null;
        if (lines.size() == 1) return new PartnerPortalViews.ExtraCost(lines.getFirst().mode(), lines.getFirst().value());
        return new PartnerPortalViews.ExtraCost(PartnerSaleMoney.AMOUNT, PartnerNumbers.unit(totals.extraCostEur()));
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

    /** What one side of an entry reads as typed. */
    record Entered(String unit, int count, BigDecimal piecePrice, int piecesPerUnit) {}

    /** Running sums of parts; the unit price and the typed values are the first part's (every part of a side has the same). */
    private static final class Sum {
        int parts;
        int quantity;
        int pieces;
        BigDecimal unitPrice;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal transport = BigDecimal.ZERO;
        BigDecimal extra = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        PartnerSaleReportLineEntity first;

        void add(PartnerSaleReportLineEntity part, int factor) {
            parts++;
            quantity += part.quantityOrZero();
            pieces += part.quantityOrZero() * factor;
            if (unitPrice == null) unitPrice = part.unitPriceEur;
            if (first == null) first = part;
            gross = gross.add(nz(part.grossEur));
            commission = commission.add(nz(part.commissionEur));
            transport = transport.add(nz(part.transportEur));
            extra = extra.add(nz(part.extraCostEur));
            /* A row without a net (never written so) counts as gross for a sale and -gross for a buyback. */
            net = net.add(part.netEur != null ? part.netEur : part.isBuyback() ? nz(part.grossEur).negate() : nz(part.grossEur));
        }

        void add(Sum other) {
            parts += other.parts;
            quantity += other.quantity;
            pieces += other.pieces;
            gross = gross.add(other.gross);
            commission = commission.add(other.commission);
            transport = transport.add(other.transport);
            extra = extra.add(other.extra);
            net = net.add(other.net);
        }

        /**
         * What was typed, or (round-1 rows, or a count that no longer matches the booked pieces) the same in
         * sales units: per display on a display basis, else per stuk.
         */
        Entered entered(int factor) {
            if (first != null && first.enteredUnit != null && first.enteredCount != null
                    && first.enteredPiecesPerUnit != null && first.piecePriceEur != null
                    && (long) first.enteredCount * first.enteredPiecesPerUnit == pieces) {
                return new Entered(first.enteredUnit, first.enteredCount, first.piecePriceEur, first.enteredPiecesPerUnit);
            }
            BigDecimal piece = unitPrice == null ? null
                    : unitPrice.divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
            return new Entered(factor > 1 ? PartnerCountUnits.VERPAKKING : PartnerCountUnits.STUK, quantity, piece, factor);
        }

        private static BigDecimal nz(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
