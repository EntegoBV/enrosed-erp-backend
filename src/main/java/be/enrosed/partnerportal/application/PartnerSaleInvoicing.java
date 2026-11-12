package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleInvoiceFigureEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleInvoicePartEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.partnerportal.domain.PartnerSaleTexts;
import be.enrosed.sales.application.PartnerAdvanceSchedules;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesExtraLine;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Factureren (contract sections 1 and 5b): turns the open parts of chosen meldingen into invoices,
 * one per source. A partner container's parts become one partner settlement through
 * {@link SalesOrderService#createAuctionSettlementFromReports} (its own cost, value, advance, final
 * and true-up arithmetic, which the preview reads through
 * {@link SalesOrderService#previewAuctionSettlementFromReports}); the own-stock parts become one
 * concept invoice built by {@link SalesOrderService#createPartnerSalesInvoice}.
 *
 * <p>Per source and product: S sold, N sale net, B bought back, K buyback cost; quantity q = S - B,
 * proceeds p = N - K. A product with q > 0 is a line; q = 0 (a buyback and a resale cancel out in
 * pieces) adds its p to the source's largest line (the value is linear in the proceeds; own stock
 * puts it on an extra line instead); a line with p < 0 goes on at 0 and its deficit is taken from
 * the largest lines. q < 0, or a source whose proceeds together stay below 0, leaves those parts open
 * ("skipped") for a later run.
 *
 * <p>The invoice run is not transactional: every source runs in its own
 * {@code QuarkusTransaction.requiringNew()} that takes the section 3 locks (partner row, the
 * partner's containers ascending, the products ascending), reads the parts again after them,
 * re-checks that each is still open and not withdrawn, makes the document and links the parts
 * (invoice_id, invoiced_at, and an append-only partner_sale_invoice_part row each). A refusal blocks
 * that source only.
 */
@ApplicationScoped
public class PartnerSaleInvoicing {

    private static final Logger LOG = Logger.getLogger(PartnerSaleInvoicing.class);

    public static final String SOURCE_CONTAINER = "CONTAINER";
    public static final String SOURCE_OWN = "OWN";

    static final String WAIT = "Wacht op een volgende verkoop van dit product";
    static final String BUYBACK_ABOVE_SALE = "Terugkoop groter dan verkoop: wacht op een volgende verkoop";
    static final String BUYBACK_ABOVE_NET = "Terugkoopkost groter dan de netto opbrengst";
    static final String ROUNDING = "Afronding veilingverkopen";
    static final String NOT_PARTNERS = "Deze container hoort niet (meer) bij deze partner; koppel hem opnieuw of trek de meldingen in";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    /** report_ids of a figure row holds at most this many characters. */
    private static final int REPORT_IDS_LENGTH = 500;

    private final PartnerPortalRepositories.Settings settingsRows;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerPortalRepositories.InvoiceParts invoiceParts;
    private final PartnerOpenParts openParts;
    private final PartnerContainerCapacity capacity;
    private final PartnerProductTiles tiles;
    private final StockService stock;
    private final SalesOrderService sales;
    private final SalesRepositories.Customers customers;
    private final PurchaseOrderService purchaseOrders;
    private final PartnerPortalQueries queries;
    private final PartnerPortalRepositories.InvoiceFigures figureRows;
    private final PartnerAdvanceSchedules advanceRows;

    public PartnerSaleInvoicing(PartnerPortalRepositories.Settings settingsRows, PartnerPortalRepositories.Reports reports,
                                PartnerPortalRepositories.Lines lines, PartnerPortalRepositories.InvoiceParts invoiceParts,
                                PartnerOpenParts openParts, PartnerContainerCapacity capacity, PartnerProductTiles tiles,
                                StockService stock, SalesOrderService sales, SalesRepositories.Customers customers,
                                PurchaseOrderService purchaseOrders, PartnerPortalQueries queries,
                                PartnerPortalRepositories.InvoiceFigures figureRows, PartnerAdvanceSchedules advanceRows) {
        this.figureRows = figureRows;
        this.advanceRows = advanceRows;
        this.settingsRows = settingsRows;
        this.reports = reports;
        this.lines = lines;
        this.invoiceParts = invoiceParts;
        this.openParts = openParts;
        this.capacity = capacity;
        this.tiles = tiles;
        this.stock = stock;
        this.sales = sales;
        this.customers = customers;
        this.purchaseOrders = purchaseOrders;
        this.queries = queries;
    }

    /* =============================================================== preview */

    /**
     * What Factureren would make now, from confirmed meldingen only (his answer: "eerst bevestigen").
     * reportIds null = every confirmed melding of the partner with an open part (sold on or before
     * {@code until} when given); an unconfirmed melding among explicit reportIds is refused ("VV-n is nog
     * niet bevestigd: bevestig of trek in"). Nothing is written or locked; a container group is planned
     * with createAuctionSettlement's own arithmetic in a transaction of its own, so its refusal becomes
     * {@code blocked} and touches nothing else. Every figure comes from {@link PartnerInvoiceFigures}.
     * includeUnconfirmed (with explicit reportIds only): those meldingen count as confirmed for this read
     * (Veilingverkopen's Inkoop en winst of one melding before Bevestigen: the figures Factureren will make
     * once it is confirmed). Meldingen that come along by a mirror link still need their own confirmation,
     * as in Factureren; invoice() never takes this path.
     */
    @Transactional
    public PartnerPortalViews.InvoicePreview preview(Long customerId, List<Long> reportIds, LocalDate until,
                                                     boolean includeUnconfirmed) {
        Customer partner = requirePartner(customerId);
        Selection selection = select(partner.id(), reportIds, until, includeUnconfirmed && reportIds != null);
        List<OpenPart> parts = selection.parts();
        PartnerProductTiles.Catalog catalog = tiles.load();
        Map<Long, Map<Long, ProductSum>> bySource = group(parts);
        Map<Long, PurchaseOrderName> containers = purchaseOrders.names(bySource.keySet().stream().filter(Objects::nonNull).toList());
        List<PartnerPortalViews.InvoicePreviewGroup> groups = new ArrayList<>();
        List<PartnerPortalViews.InvoiceSkipped> skipped = new ArrayList<>();
        BigDecimal share = partner.partnerSharePctOrDefault();
        Set<Long> partnerContainers = Set.copyOf(queries.partnerContainerIds(partner.id()));
        for (Map.Entry<Long, Map<Long, ProductSum>> source : bySource.entrySet()) {
            Long containerId = source.getKey();
            if (containerId != null) {
                ContainerPlan plan = planContainer(source.getValue(), catalog);
                skipped.addAll(skippedRows(plan.skipped(), SOURCE_CONTAINER, containerId, catalog));
                if (plan.lines().isEmpty()) continue;
                groups.add(previewContainer(partner.id(), containerId, containerName(containers, containerId), plan,
                        internalHead(plan.included()), catalog, partnerContainers));
            } else {
                OwnPlan plan = planOwn(source.getValue(), share, catalog);
                skipped.addAll(skippedRows(plan.skipped(), SOURCE_OWN, null, catalog));
                if (plan.included().isEmpty()) continue;
                groups.add(previewOwn(plan, share, catalog));
            }
        }
        List<Long> chosen = parts.stream().map(part -> part.report().id).distinct().sorted().toList();
        List<String> warnings = new ArrayList<>(selection.joined().values());
        warnings.addAll(mirrorWarnings(parts));
        return new PartnerPortalViews.InvoicePreview(groups, skipped, chosen, PartnerNumbers.pct(share), warnings);
    }

    private PartnerPortalViews.InvoicePreviewGroup previewContainer(long customerId, long containerId, String containerName,
                                                                   ContainerPlan plan, String note,
                                                                   PartnerProductTiles.Catalog catalog,
                                                                   Set<Long> partnerContainers) {
        SalesOrderService.AuctionSettlementRequest request = settlementRequest(customerId, containerId, plan, note);
        SalesOrderService.AuctionSettlementPlan settlement = null;
        String blocked = null;
        if (!partnerContainers.contains(containerId)) blocked = NOT_PARTNERS;
        else try {
            settlement = QuarkusTransaction.requiringNew().call(() -> sales.previewAuctionSettlementFromReports(request));
        } catch (BusinessRuleException | NotFoundException refusal) {
            blocked = refusal.getMessage();
        }
        if (settlement != null && settlement.finalSettlement()) {
            blocked = leftBehind(containerId, plan);
            if (blocked != null) settlement = null;
        }
        PartnerInvoiceFigures.Group figures = settlement == null ? null
                : PartnerInvoiceFigures.container(containerId, plan, settlement, costSharePct(settlement), factor(catalog));
        Map<Long, BigDecimal> absorbed = absorbed(plan);
        List<PartnerPortalViews.InvoicePreviewLine> rows = new ArrayList<>();
        for (Line line : plan.lines()) {
            rows.add(previewLine(line.sum(), line.proceeds(), absorbed, figures, catalog));
        }
        BigDecimal proceeds = figures != null ? figures.sum(PartnerInvoiceFigures.Row::proceedsEur)
                : plan.lines().stream().map(Line::proceeds).reduce(BigDecimal.ZERO, BigDecimal::add);
        return previewGroup(SOURCE_CONTAINER, containerId, containerName, rows, figures, plan.included(), proceeds,
                settlement != null && settlement.finalSettlement(), blocked, plan.warnings(), catalog);
    }

    private PartnerPortalViews.InvoicePreviewGroup previewOwn(OwnPlan plan, BigDecimal share, PartnerProductTiles.Catalog catalog) {
        PartnerInvoiceFigures.Group figures = PartnerInvoiceFigures.own(plan, share, factor(catalog));
        List<PartnerPortalViews.InvoicePreviewLine> rows = new ArrayList<>();
        for (OwnProduct product : plan.products()) {
            rows.add(previewLine(product.sum(), product.sum().proceeds(), Map.of(), figures, catalog));
        }
        return previewGroup(SOURCE_OWN, null, null, rows, figures, plan.included(),
                figures.sum(PartnerInvoiceFigures.Row::proceedsEur), false, null, plan.warnings(), catalog);
    }

    /**
     * One preview line: the open parts' sums, and (unless the group is blocked) the document's own figures
     * of this product. absorbedEur = what the q = 0 netting moved onto (or off) this line.
     */
    private static PartnerPortalViews.InvoicePreviewLine previewLine(ProductSum sum, BigDecimal proceeds,
                                                                     Map<Long, BigDecimal> absorbed,
                                                                     PartnerInvoiceFigures.Group figures,
                                                                     PartnerProductTiles.Catalog catalog) {
        PartnerPortalViews.ProductTile tile = catalog.tile(sum.productId);
        PartnerInvoiceFigures.Row row = figures == null ? null : figures.row(sum.productId);
        BigDecimal moved = row != null ? row.absorbedEur() : absorbed.getOrDefault(sum.productId, BigDecimal.ZERO);
        BigDecimal lineProceeds = row != null ? row.proceedsEur() : proceeds;
        return new PartnerPortalViews.InvoicePreviewLine(sum.productId, tile.name(), tile.variant(), sum.sold,
                sum.boughtBack, sum.quantity(), PartnerNumbers.money(sum.saleNet), PartnerNumbers.money(sum.buyback),
                PartnerNumbers.money(lineProceeds), row == null ? null : row.costEur(), row == null ? null : row.valueEur(),
                PartnerNumbers.money(sum.buybackGross), sum.sold * Math.max(1, tile.piecesPerSalesUnit()),
                PartnerNumbers.money(sum.saleGross), PartnerNumbers.money(sum.saleCommission),
                PartnerNumbers.money(sum.saleTransport), PartnerNumbers.money(sum.saleExtra), PartnerNumbers.money(sum.buyback),
                PartnerNumbers.money(moved), absorbedFrom(sum.productId, moved, absorbedOf(figures, absorbed), catalog),
                row == null ? null : PartnerNumbers.money(row.profitEur()),
                row == null ? null : PartnerNumbers.money(row.enrosedProfitEur()),
                row == null ? null : PartnerNumbers.money(row.partnerProfitEur()),
                row == null ? null : row.advanceEur(), row == null ? null : row.correctionEur(),
                figures == null ? null : figures.costSharePct(), figures == null ? null : figures.profitSharePct());
    }

    /** A group with the document's totals (null while blocked) and the sums of every included product. */
    private static PartnerPortalViews.InvoicePreviewGroup previewGroup(String source, Long containerId, String containerName,
                                                                       List<PartnerPortalViews.InvoicePreviewLine> rows,
                                                                       PartnerInvoiceFigures.Group figures,
                                                                       List<ProductSum> included, BigDecimal proceeds,
                                                                       boolean finalSettlement, String blocked,
                                                                       List<String> warnings, PartnerProductTiles.Catalog catalog) {
        BigDecimal value = figures == null ? null : figures.sum(PartnerInvoiceFigures.Row::valueEur);
        BigDecimal advance = figures == null ? null : figures.sum(PartnerInvoiceFigures.Row::advanceEur);
        BigDecimal cost = figures == null ? null : figures.sum(PartnerInvoiceFigures.Row::costEur);
        BigDecimal money = PartnerNumbers.money(proceeds);
        int pieces = 0;
        for (ProductSum sum : included) pieces += sum.sold * Math.max(1, catalog.tile(sum.productId).piecesPerSalesUnit());
        return new PartnerPortalViews.InvoicePreviewGroup(source, containerId, containerName, rows, value, advance,
                value == null ? null : value.subtract(advance), finalSettlement, blocked, warnings,
                figures == null ? null : figures.profitSharePct(), figures == null ? null : figures.costSharePct(),
                money, cost, cost == null ? null : money.subtract(cost), value == null ? null : value.subtract(cost),
                value == null ? null : money.subtract(value),
                figures == null ? null : figures.sum(PartnerInvoiceFigures.Row::correctionEur),
                total(included, sum -> sum.saleGross), total(included, sum -> sum.saleCommission),
                total(included, sum -> sum.saleTransport), total(included, sum -> sum.saleExtra),
                total(included, sum -> sum.buyback), pieces);
    }

    private static BigDecimal total(List<ProductSum> sums, java.util.function.Function<ProductSum, BigDecimal> field) {
        return PartnerNumbers.money(sums.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Per product of a container plan: line proceeds minus its own (q = 0 products: minus their own). */
    private static Map<Long, BigDecimal> absorbed(ContainerPlan plan) {
        Map<Long, BigDecimal> result = new HashMap<>();
        Set<Long> onLines = new java.util.HashSet<>();
        for (Line line : plan.lines()) {
            result.put(line.sum().productId, line.proceeds().subtract(line.sum().proceeds()));
            onLines.add(line.sum().productId);
        }
        for (ProductSum sum : plan.included()) if (!onLines.contains(sum.productId)) result.put(sum.productId, sum.proceeds().negate());
        return result;
    }

    private static Map<Long, BigDecimal> absorbedOf(PartnerInvoiceFigures.Group figures, Map<Long, BigDecimal> fallback) {
        if (figures == null) return fallback;
        Map<Long, BigDecimal> result = new HashMap<>();
        for (PartnerInvoiceFigures.Row row : figures.rows()) result.put(row.productId(), row.absorbedEur());
        return result;
    }

    /** The products the netting moved proceeds between with this one: those with the opposite sign. */
    static List<String> absorbedFrom(long productId, BigDecimal moved, Map<Long, BigDecimal> absorbed,
                                     PartnerProductTiles.Catalog catalog) {
        if (moved == null || moved.signum() == 0) return List.of();
        List<String> names = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> other : new TreeMap<>(absorbed).entrySet()) {
            if (other.getKey() == productId || other.getValue() == null) continue;
            if (other.getValue().signum() == -moved.signum()) names.add(label(catalog, other.getKey()));
        }
        return names;
    }

    /**
     * A buyback of another melding that mirrors a chosen sale, or the sale a chosen buyback mirrors, stays out of
     * this run while that melding is not confirmed (a confirmed one comes along, see {@link #select}); the preview
     * says so, so the one is not invoiced without knowing about the other.
     */
    private List<String> mirrorWarnings(List<OpenPart> parts) {
        Set<Long> chosen = new java.util.HashSet<>();
        List<Long> saleIds = new ArrayList<>();
        Set<Long> mirroredIds = new TreeSet<>();
        for (OpenPart part : parts) {
            chosen.add(part.report().id);
            if (part.line().isSale()) saleIds.add(part.line().id);
            else if (part.line().mirrorsLineId != null) mirroredIds.add(part.line().mirrorsLineId);
        }
        Set<Long> buybacks = new TreeSet<>();
        for (Object[] row : lines.liveBuybacksMirroring(saleIds)) {
            long reportId = ((Number) row[1]).longValue();
            if (!chosen.contains(reportId)) buybacks.add(reportId);
        }
        Set<Long> sales = new TreeSet<>();
        if (!mirroredIds.isEmpty()) {
            for (PartnerSaleReportLineEntity sale : lines.list("id in ?1", mirroredIds)) {
                if (sale.isSale() && !chosen.contains(sale.reportId)) sales.add(sale.reportId);
            }
        }
        List<String> warnings = new ArrayList<>();
        for (Long reportId : unconfirmed(buybacks)) {
            warnings.add("Terugkoop " + PartnerPortalRules.reportNumber(reportId)
                    + " hoort bij deze verkopen maar is nog niet bevestigd; ze gaat niet mee");
        }
        for (Long reportId : unconfirmed(sales)) {
            warnings.add("Verkoop " + PartnerPortalRules.reportNumber(reportId)
                    + " hoort bij deze terugkopen maar is nog niet bevestigd; ze gaat niet mee");
        }
        return warnings;
    }

    /** The partner's financed part of the cost: the advance agreement's percent, else the container's. */
    private BigDecimal costSharePct(SalesOrderService.AuctionSettlementPlan settlement) {
        PartnerAdvanceSchedules.Agreement agreement = advanceRows.find(settlement.container().id());
        return agreement != null && agreement.financingPct() != null ? agreement.financingPct()
                : settlement.container().partnerCostPctOrDefault();
    }

    private static java.util.function.LongToIntFunction factor(PartnerProductTiles.Catalog catalog) {
        return productId -> catalog.tile(productId).piecesPerSalesUnit();
    }

    /* =============================================================== invoice */

    /**
     * Makes the invoices for the open parts of these meldingen (the preview's reportIds), one
     * transaction per source: the partner's containers ascending, then own stock. Only confirmed
     * meldingen: an unconfirmed one among the ids refuses the whole run before anything is made.
     */
    public PartnerPortalViews.InvoiceResult invoice(Long customerId, List<Long> reportIds) {
        long partnerId = QuarkusTransaction.requiringNew().call(() -> requirePartner(customerId).id());
        if (reportIds == null || reportIds.isEmpty()) throw new BusinessRuleException("Kies de meldingen om te factureren");
        List<Long> chosen = reportIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        /* Which sources there are, read once without locks; each group reads its parts again under them. */
        List<Long> sources = QuarkusTransaction.requiringNew().call(() -> {
            TreeSet<Long> containers = new TreeSet<>();
            boolean own = false;
            for (OpenPart part : selectOpen(partnerId, chosen, null)) {
                if (part.line().purchaseOrderId == null) own = true;
                else containers.add(part.line().purchaseOrderId);
            }
            List<Long> ordered = new ArrayList<>(containers);
            if (own) ordered.add(null);
            return ordered;
        });
        List<PartnerPortalViews.CreatedInvoice> invoices = new ArrayList<>();
        List<PartnerPortalViews.BlockedGroup> blocked = new ArrayList<>();
        List<PartnerPortalViews.InvoiceSkipped> skipped = new ArrayList<>();
        for (Long containerId : sources) {
            try {
                GroupOutcome outcome = QuarkusTransaction.requiringNew().call(() -> runGroup(partnerId, chosen, containerId));
                if (outcome.invoice() != null) invoices.add(outcome.invoice());
                skipped.addAll(outcome.skipped());
            } catch (BusinessRuleException | NotFoundException refusal) {
                blocked.add(new PartnerPortalViews.BlockedGroup(containerId, refusal.getMessage()));
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "Factureren van veilingverkopen (klant %d, container %s) mislukt", partnerId, containerId);
                blocked.add(new PartnerPortalViews.BlockedGroup(containerId, "Dit deel kon niet gefactureerd worden; probeer opnieuw"));
            }
        }
        return new PartnerPortalViews.InvoiceResult(invoices, blocked, skipped);
    }

    /**
     * One source, in the caller's new transaction: locks, a fresh read (confirmed meldingen only, checked
     * again after the partner lock, which confirm / unconfirm take too), the document with its compact
     * note, the figure snapshot (partner_sale_invoice_figure, the same figures the preview showed) and
     * the links.
     */
    private GroupOutcome runGroup(long customerId, List<Long> reportIds, Long containerId) {
        settingsRows.lockOrCreate(customerId);
        List<be.enrosed.sourcing.domain.PurchaseOrder> containers = capacity.lockContainers(customerId);
        if (containerId != null && containers.stream().noneMatch(order -> containerId.equals(order.id()))) {
            throw new BusinessRuleException(NOT_PARTNERS);
        }
        TreeSet<Long> products = new TreeSet<>();
        for (OpenPart part : selectOpen(customerId, reportIds, null)) {
            if (Objects.equals(part.line().purchaseOrderId, containerId)) products.add(part.line().productId);
        }
        for (Long productId : products) stock.lockProduct(productId);
        /* After the locks: still open, not withdrawn, confirmed, of this source. */
        List<OpenPart> parts = selectOpen(customerId, reportIds, null).stream()
                .filter(part -> Objects.equals(part.line().purchaseOrderId, containerId)).toList();
        PartnerProductTiles.Catalog catalog = tiles.load();
        Map<Long, ProductSum> sums = group(parts).getOrDefault(containerId, Map.of());
        Customer partner = requirePartner(customerId);
        if (containerId != null) {
            ContainerPlan plan = planContainer(sums, catalog);
            List<PartnerPortalViews.InvoiceSkipped> skipped = skippedRows(plan.skipped(), SOURCE_CONTAINER, containerId, catalog);
            if (plan.lines().isEmpty()) return new GroupOutcome(null, skipped);
            String head = internalHead(plan.included());
            SalesOrderService.AuctionSettlementRequest request = settlementRequest(customerId, containerId, plan, head);
            String behind = leftBehind(containerId, plan);
            if (behind != null && sales.previewAuctionSettlementFromReports(request).finalSettlement()) {
                throw new BusinessRuleException(behind);
            }
            String partnerName = PartnerSaleTexts.shortName(partner.company(), "partner");
            SalesOrderService.SettlementFromReports made = sales.createAuctionSettlementFromReports(request,
                    new SalesOrderService.ReportTexts() {
                        @Override
                        public String notes(SalesOrderService.AuctionSettlementPlan settlement) {
                            return PartnerInvoiceNotes.container(figuresOf(containerId, plan, settlement, catalog),
                                    settlement.container().displayName(), partnerName, catalog);
                        }

                        @Override
                        public String internalNotes(SalesOrderService.AuctionSettlementPlan settlement) {
                            return head + "\n" + settlement.detail().strip();
                        }
                    });
            SalesOrder created = made.order();
            writeFigures(created.id(), figuresOf(containerId, plan, made.plan(), catalog));
            link(created, plan.included());
            return new GroupOutcome(new PartnerPortalViews.CreatedInvoice(created.id(), created.number(), SOURCE_CONTAINER,
                    containerId), skipped);
        }
        BigDecimal share = partner.partnerSharePctOrDefault();
        OwnPlan plan = planOwn(sums, share, catalog);
        List<PartnerPortalViews.InvoiceSkipped> skipped = skippedRows(plan.skipped(), SOURCE_OWN, null, catalog);
        if (plan.included().isEmpty()) return new GroupOutcome(null, skipped);
        PartnerInvoiceFigures.Group figures = PartnerInvoiceFigures.own(plan, share, factor(catalog));
        SalesOrder created = sales.createPartnerSalesInvoice(new SalesOrderService.PartnerSalesInvoiceRequest(customerId,
                plan.invoiceLines(), plan.extras(), PartnerInvoiceNotes.own(figures, catalog),
                internalHead(plan.included()) + "\n" + ownDetail(figures, catalog)));
        writeFigures(created.id(), figures);
        link(created, plan.included());
        return new GroupOutcome(new PartnerPortalViews.CreatedInvoice(created.id(), created.number(), SOURCE_OWN, null), skipped);
    }

    private PartnerInvoiceFigures.Group figuresOf(long containerId, ContainerPlan plan,
                                                  SalesOrderService.AuctionSettlementPlan settlement,
                                                  PartnerProductTiles.Catalog catalog) {
        return PartnerInvoiceFigures.container(containerId, plan, settlement, costSharePct(settlement), factor(catalog));
    }

    /** The figure snapshot of a document: one row per product it holds (lines, absorbed and extra-line ones). */
    private void writeFigures(long salesOrderId, PartnerInvoiceFigures.Group figures) {
        Instant now = Instant.now();
        for (PartnerInvoiceFigures.Row row : figures.rows()) {
            PartnerSaleInvoiceFigureEntity entity = new PartnerSaleInvoiceFigureEntity();
            entity.salesOrderId = salesOrderId;
            entity.productId = row.productId();
            entity.soldQuantity = row.soldQuantity();
            entity.boughtBackQuantity = row.boughtBackQuantity();
            entity.quantity = row.quantity();
            entity.salePieces = row.salePieces();
            entity.grossEur = row.grossEur();
            entity.commissionEur = row.commissionEur();
            entity.transportEur = row.transportEur();
            entity.extraEur = row.extraEur();
            entity.buybackCostEur = row.buybackCostEur();
            entity.proceedsEur = row.proceedsEur();
            entity.absorbedEur = row.absorbedEur();
            entity.costEur = row.costEur();
            entity.valueEur = row.valueEur();
            entity.advanceEur = row.advanceEur();
            entity.correctionEur = row.correctionEur();
            entity.profitSharePct = figures.profitSharePct();
            entity.costSharePct = figures.costSharePct();
            entity.reportIds = reportIdsText(row.reportIds());
            entity.createdAt = now;
            figureRows.persist(entity);
        }
    }

    /** "12,14,17": the melding ids of a row, as many as fit the column (the breakdown reads the links, not this). */
    private static String reportIdsText(Set<Long> ids) {
        StringBuilder text = new StringBuilder();
        for (Long id : new TreeSet<>(ids)) {
            String next = (text.isEmpty() ? "" : ",") + id;
            if (text.length() + next.length() > REPORT_IDS_LENGTH) break;
            text.append(next);
        }
        return text.toString();
    }

    /** Every part of these products goes on the document: invoice_id, invoiced_at and an append-only row. */
    private void link(SalesOrder created, List<ProductSum> included) {
        Instant now = Instant.now();
        for (ProductSum sum : included) {
            for (OpenPart part : sum.parts) {
                PartnerSaleReportLineEntity line = part.line();
                line.invoiceId = created.id();
                line.invoicedAt = now;
                PartnerSaleInvoicePartEntity row = new PartnerSaleInvoicePartEntity();
                row.salesOrderId = created.id();
                row.lineId = line.id;
                row.quantity = line.quantityOrZero();
                row.createdAt = now;
                invoiceParts.persist(row);
            }
        }
        lines.flush();
    }

    /**
     * A slotfactuur must take every open part of its container: after it no settlement line can come on the
     * container, so a part it leaves open (a buyback waiting for a resale, a melding outside this run, or one
     * not confirmed yet) could never be invoiced. The refusal text when such parts exist, else null.
     */
    private String leftBehind(long containerId, ContainerPlan plan) {
        Set<Long> included = new java.util.HashSet<>();
        for (ProductSum sum : plan.included()) for (OpenPart part : sum.parts) included.add(part.line().id);
        List<PartnerSaleReportLineEntity> onContainer = lines.partsOnContainer(containerId);
        PartnerOpenParts.Rule rule = openParts.forLines(onContainer);
        TreeSet<Long> waiting = new TreeSet<>();
        for (PartnerSaleReportLineEntity line : onContainer) {
            if (rule.isOpen(line, false) && !included.contains(line.id)) waiting.add(line.reportId);
        }
        if (waiting.isEmpty()) return null;
        Set<Long> unconfirmed = unconfirmed(waiting);
        List<Long> open = waiting.stream().filter(id -> !unconfirmed.contains(id)).toList();
        String start = "Dit wordt de slotfactuur van deze container, maar ";
        String notConfirmed = PartnerSaleTexts.numbers(unconfirmed) + (unconfirmed.size() == 1 ? " is" : " zijn")
                + " nog niet bevestigd";
        String stillOpen = String.join(", ", open.stream().map(PartnerPortalRules::reportNumber).toList())
                + (open.size() == 1 ? " heeft" : " hebben") + " er nog een open deel";
        if (open.isEmpty()) return start + notConfirmed + ": bevestig of trek in";
        if (unconfirmed.isEmpty()) return start + stillOpen + "; factureer dat mee of wacht op een volgende verkoop";
        return start + notConfirmed + " (bevestig of trek in) en " + stillOpen
                + " (factureer dat mee of wacht op een volgende verkoop)";
    }

    private static SalesOrderService.AuctionSettlementRequest settlementRequest(long customerId, long containerId,
                                                                               ContainerPlan plan, String note) {
        List<SalesOrderService.AuctionLine> auction = plan.lines().stream()
                .map(line -> new SalesOrderService.AuctionLine(line.sum().productId, line.sum().quantity(), line.proceeds(), null))
                .toList();
        /* The server derives share, cost share and final from the container, as the manual sheet leaves them. */
        return new SalesOrderService.AuctionSettlementRequest(customerId, containerId, null, null, null, null, auction, note, null);
    }

    /* ============================================================= selection */

    /** One open part with its melding. */
    record OpenPart(PartnerSaleReportLineEntity line, PartnerSaleReportEntity report) {}

    /**
     * The open SALE/BUYBACK parts of these meldingen of this partner (reportIds null = every melding
     * with an open part, sold on or before {@code until}). Withdrawn meldingen and other partners'
     * are left out; the open-part rule decides the rest. Confirmed meldingen only (his answer 3): with
     * reportIds null an unconfirmed melding is left out, among explicit reportIds it is refused ("VV-n
     * is nog niet bevestigd: bevestig of trek in"). Confirmed = staff confirmed it, or a document holds
     * one of its parts (the reader's rule). A confirmed melding tied to a chosen one by a buyback comes
     * along ({@link #select}). runGroup calls this again after the partner lock.
     */
    private List<OpenPart> selectOpen(long customerId, Collection<Long> reportIds, LocalDate until) {
        return select(customerId, reportIds, until, false).parts();
    }

    /**
     * The open parts {@link #selectOpen} takes, and the meldingen that came along with the chosen ones: a sale and
     * a confirmed buyback of another melding that mirrors it are invoiced together (either way round, also
     * transitively), so a subset never invoices a sale without the pieces that came back from it, or a buyback
     * without its sale. joined: melding id -> the warning that says why it came along.
     */
    record Selection(List<OpenPart> parts, Map<Long, String> joined) {}

    /** asConfirmed: the explicit reportIds count as confirmed (the read-only preview of one melding, never an invoice). */
    private Selection select(long customerId, Collection<Long> reportIds, LocalDate until, boolean asConfirmed) {
        List<Long> ids = reportIds == null
                ? lines.reportsWithOpenParts(customerId, openParts.releasing(lines.liveInvoiceIdsOf(customerId)), until)
                : reportIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) return new Selection(List.of(), Map.of());
        Map<Long, PartnerSaleReportEntity> chosen = new HashMap<>();
        for (PartnerSaleReportEntity report : reports.list("id in ?1 and revisionOf is null", ids)) {
            if (!Objects.equals(report.customerId, customerId) || report.isVoided()) continue;
            if (until != null && report.soldOn != null && report.soldOn.isAfter(until)) continue;
            chosen.put(report.id, report);
        }
        if (chosen.isEmpty()) return new Selection(List.of(), Map.of());
        Set<Long> unconfirmed = new TreeSet<>();
        Set<Long> taken = asConfirmed ? Set.copyOf(chosen.keySet()) : Set.of();
        List<OpenPart> open = openOf(chosen, unconfirmed, taken);
        if (reportIds != null && !unconfirmed.isEmpty()) throw new BusinessRuleException(notConfirmed(unconfirmed));
        Map<Long, String> joined = new LinkedHashMap<>();
        for (Map<Long, String> more = linked(customerId, open, chosen.keySet()); !more.isEmpty();
             more = linked(customerId, open, chosen.keySet())) {
            for (PartnerSaleReportEntity report : reports.list("id in ?1", more.keySet())) chosen.put(report.id, report);
            joined.putAll(more);
            open = openOf(chosen, new TreeSet<>(), taken);
        }
        return new Selection(open, joined);
    }

    /**
     * The open parts of these meldingen that are confirmed (or in {@code taken}: counted as confirmed for a
     * read-only preview); the open ones of other unconfirmed meldingen go to unconfirmed.
     */
    private List<OpenPart> openOf(Map<Long, PartnerSaleReportEntity> chosen, Set<Long> unconfirmed, Set<Long> taken) {
        List<PartnerSaleReportLineEntity> found = lines.byReports(chosen.keySet()).stream()
                .filter(line -> line.isSale() || line.isBuyback()).toList();
        PartnerOpenParts.Rule rule = openParts.forLines(found);
        Set<Long> held = new java.util.HashSet<>();
        for (PartnerSaleReportLineEntity line : found) if (rule.holder(line) != null) held.add(line.reportId);
        List<OpenPart> open = new ArrayList<>();
        for (PartnerSaleReportLineEntity line : found) {
            PartnerSaleReportEntity report = chosen.get(line.reportId);
            if (report == null || line.productId == null || !rule.isOpen(line, report.isVoided())) continue;
            if (report.confirmedAt == null && !held.contains(report.id) && !taken.contains(report.id)) {
                unconfirmed.add(report.id);
                continue;
            }
            open.add(new OpenPart(line, report));
        }
        return open;
    }

    /**
     * The confirmed, live meldingen of this partner outside {@code chosen} whose open part is tied to one of these
     * open parts by a buyback: a buyback that mirrors a chosen sale, or the sale a chosen buyback mirrors. Each
     * with its warning ("VV-2 gaat mee: die terugkoop hoort bij de verkoop van VV-1").
     */
    private Map<Long, String> linked(long customerId, List<OpenPart> open, Set<Long> chosen) {
        Map<Long, PartnerSaleReportLineEntity> sales = new HashMap<>();
        Map<Long, Long> mirroredBy = new HashMap<>();
        for (OpenPart part : open) {
            if (part.line().isSale()) sales.put(part.line().id, part.line());
            else if (part.line().mirrorsLineId != null) mirroredBy.put(part.line().mirrorsLineId, part.line().reportId);
        }
        /* candidate line -> the chosen melding it ties to; a buyback is "terugkoop", a sale "verkoop". */
        Map<PartnerSaleReportLineEntity, Long> candidates = new LinkedHashMap<>();
        for (PartnerSaleReportLineEntity buyback : lines.liveBuybackPartsMirroring(sales.keySet())) {
            if (!chosen.contains(buyback.reportId)) candidates.put(buyback, sales.get(buyback.mirrorsLineId).reportId);
        }
        if (!mirroredBy.isEmpty()) {
            for (PartnerSaleReportLineEntity sale : lines.list("id in ?1", mirroredBy.keySet())) {
                if (sale.isSale() && !chosen.contains(sale.reportId)) candidates.put(sale, mirroredBy.get(sale.id));
            }
        }
        if (candidates.isEmpty()) return Map.of();
        Set<Long> reportIds = new TreeSet<>();
        for (PartnerSaleReportLineEntity line : candidates.keySet()) reportIds.add(line.reportId);
        Map<Long, PartnerSaleReportEntity> live = new HashMap<>();
        for (PartnerSaleReportEntity report : reports.list("id in ?1 and revisionOf is null", reportIds)) {
            if (Objects.equals(report.customerId, customerId) && !report.isVoided()) live.put(report.id, report);
        }
        List<PartnerSaleReportLineEntity> theirs = lines.byReports(live.keySet()).stream()
                .filter(line -> line.isSale() || line.isBuyback()).toList();
        PartnerOpenParts.Rule rule = openParts.forLines(theirs);
        Set<Long> held = new java.util.HashSet<>();
        for (PartnerSaleReportLineEntity line : theirs) if (rule.holder(line) != null) held.add(line.reportId);
        Map<Long, String> result = new TreeMap<>();
        for (Map.Entry<PartnerSaleReportLineEntity, Long> candidate : candidates.entrySet()) {
            PartnerSaleReportLineEntity line = candidate.getKey();
            PartnerSaleReportEntity report = live.get(line.reportId);
            if (report == null || result.containsKey(report.id)) continue;
            if (report.confirmedAt == null && !held.contains(report.id)) continue;
            if (!rule.isOpen(line, false)) continue;
            String own = PartnerPortalRules.reportNumber(report.id);
            String other = PartnerPortalRules.reportNumber(candidate.getValue());
            result.put(report.id, line.isBuyback()
                    ? own + " gaat mee: die terugkoop hoort bij de verkoop van " + other
                    : own + " gaat mee: die verkoop hoort bij de terugkoop van " + other);
        }
        return result;
    }

    /** "VV-3 is nog niet bevestigd: bevestig of trek in" (several: "VV-3, VV-5 zijn ..."). */
    static String notConfirmed(Collection<Long> reportIds) {
        return PartnerSaleTexts.numbers(reportIds) + (reportIds.size() == 1 ? " is" : " zijn")
                + " nog niet bevestigd: bevestig of trek in";
    }

    /** Among these meldingen, the live ones nobody confirmed and no document holds a part of. */
    private Set<Long> unconfirmed(Collection<Long> reportIds) {
        if (reportIds.isEmpty()) return Set.of();
        List<Long> ids = reportIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        List<PartnerSaleReportLineEntity> found = lines.byReports(ids).stream()
                .filter(line -> line.isSale() || line.isBuyback()).toList();
        PartnerOpenParts.Rule rule = openParts.forLines(found);
        Set<Long> held = new java.util.HashSet<>();
        for (PartnerSaleReportLineEntity line : found) if (rule.holder(line) != null) held.add(line.reportId);
        Set<Long> result = new TreeSet<>();
        for (PartnerSaleReportEntity report : reports.list("id in ?1 and revisionOf is null", ids)) {
            if (!report.isVoided() && report.confirmedAt == null && !held.contains(report.id)) result.add(report.id);
        }
        return result;
    }

    /** Per source (container id, null = own stock last) and product: the sums of the open parts. */
    private static Map<Long, Map<Long, ProductSum>> group(List<OpenPart> parts) {
        Map<Long, Map<Long, ProductSum>> result = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (OpenPart part : parts) {
            PartnerSaleReportLineEntity line = part.line();
            ProductSum sum = result.computeIfAbsent(line.purchaseOrderId, key -> new TreeMap<>())
                    .computeIfAbsent(line.productId, ProductSum::new);
            sum.add(part);
        }
        return result;
    }

    /** What the open parts of one source come to for one product. Own cost per part: unit_cost_eur x quantity. */
    static final class ProductSum {
        final long productId;
        int sold;
        int boughtBack;
        BigDecimal saleNet = BigDecimal.ZERO;
        /** The sale parts' gross and costs (saleNet = gross - commission - transport - extra). */
        BigDecimal saleGross = BigDecimal.ZERO;
        BigDecimal saleCommission = BigDecimal.ZERO;
        BigDecimal saleTransport = BigDecimal.ZERO;
        BigDecimal saleExtra = BigDecimal.ZERO;
        /** What the buybacks cost (-net); buybackGross the clock price totals alone. */
        BigDecimal buyback = BigDecimal.ZERO;
        BigDecimal buybackGross = BigDecimal.ZERO;
        BigDecimal ownCost = BigDecimal.ZERO;
        boolean costUnknown;
        /** Some own pieces had no known cost and count at the average of the known ones. */
        boolean costEstimated;
        final List<OpenPart> parts = new ArrayList<>();
        final Set<Long> reportIds = new TreeSet<>();
        LocalDate firstSoldOn;
        LocalDate lastSoldOn;

        ProductSum(long productId) {
            this.productId = productId;
        }

        void add(OpenPart part) {
            PartnerSaleReportLineEntity line = part.line();
            int quantity = line.quantityOrZero();
            BigDecimal unitCost = line.unitCostEur == null ? BigDecimal.ZERO : line.unitCostEur;
            if (line.isSale()) {
                sold += quantity;
                saleNet = saleNet.add(line.netEur == null ? BigDecimal.ZERO : line.netEur);
                saleGross = saleGross.add(line.grossEur == null ? BigDecimal.ZERO : line.grossEur);
                saleCommission = saleCommission.add(line.commissionEur == null ? BigDecimal.ZERO : line.commissionEur);
                saleTransport = saleTransport.add(line.transportEur == null ? BigDecimal.ZERO : line.transportEur);
                saleExtra = saleExtra.add(line.extraCostEur == null ? BigDecimal.ZERO : line.extraCostEur);
                ownCost = ownCost.add(unitCost.multiply(BigDecimal.valueOf(quantity)));
                if (line.purchaseOrderId == null && unitCost.signum() <= 0) costUnknown = true;
            } else {
                boughtBack += quantity;
                /* The terugkoopkost is -net: round 2 adds the buyback's commission and transport to its gross. */
                buyback = buyback.add(PartnerSaleMoney.buybackCost(line.grossEur, line.netEur));
                buybackGross = buybackGross.add(line.grossEur == null ? BigDecimal.ZERO : line.grossEur);
                ownCost = ownCost.subtract(unitCost.multiply(BigDecimal.valueOf(quantity)));
            }
            if (line.purchaseOrderId == null && Boolean.TRUE.equals(line.unitCostEstimated)) costEstimated = true;
            parts.add(part);
            reportIds.add(part.report().id);
            LocalDate soldOn = part.report().soldOn;
            if (soldOn != null && (firstSoldOn == null || soldOn.isBefore(firstSoldOn))) firstSoldOn = soldOn;
            if (soldOn != null && (lastSoldOn == null || soldOn.isAfter(lastSoldOn))) lastSoldOn = soldOn;
        }

        int quantity() {
            return sold - boughtBack;
        }

        BigDecimal proceeds() {
            return saleNet.subtract(buyback);
        }

        BigDecimal cost() {
            return ownCost.setScale(2, RoundingMode.HALF_UP);
        }
    }

    record Skip(ProductSum sum, String reason) {}

    private static List<PartnerPortalViews.InvoiceSkipped> skippedRows(List<Skip> skips, String source, Long containerId,
                                                                      PartnerProductTiles.Catalog catalog) {
        return skips.stream().map(skip -> new PartnerPortalViews.InvoiceSkipped(skip.sum().productId,
                label(catalog, skip.sum().productId), source, containerId, skip.reason())).toList();
    }

    /* ======================================================= container groups */

    /** A settlement line: the product and the net proceeds it goes on with (after absorption). */
    record Line(ProductSum sum, BigDecimal proceeds) {}

    /** lines = the settlement's lines; included = every product whose parts go on it (lines + absorbed). */
    record ContainerPlan(List<Line> lines, List<ProductSum> included, List<Skip> skipped, List<String> warnings) {}

    /**
     * The lines of one container. Products with q > 0 are lines at max(p, 0); what q = 0 products
     * bring (their p) and what lines below 0 lack is moved onto the largest lines, largest first, so
     * every line stays at 0 or more and the proceeds add up to the source's total. When that total
     * is below 0 the products that pull it down wait.
     */
    static ContainerPlan planContainer(Map<Long, ProductSum> sums, PartnerProductTiles.Catalog catalog) {
        List<Skip> skipped = new ArrayList<>();
        List<ProductSum> candidates = new ArrayList<>();
        List<ProductSum> zeros = new ArrayList<>();
        for (ProductSum sum : sums.values()) {
            if (sum.quantity() < 0) skipped.add(new Skip(sum, BUYBACK_ABOVE_SALE));
            else if (sum.quantity() == 0) zeros.add(sum);
            else candidates.add(sum);
        }
        BigDecimal total = sum(candidates).add(sum(zeros));
        if (!candidates.isEmpty() && total.signum() < 0) {
            for (ProductSum sum : new ArrayList<>(candidates)) {
                if (sum.proceeds().signum() < 0) {
                    candidates.remove(sum);
                    skipped.add(new Skip(sum, BUYBACK_ABOVE_NET));
                }
            }
            for (ProductSum sum : new ArrayList<>(zeros)) {
                if (sum.proceeds().signum() < 0) {
                    zeros.remove(sum);
                    skipped.add(new Skip(sum, BUYBACK_ABOVE_NET));
                }
            }
        }
        if (candidates.isEmpty()) {
            for (ProductSum sum : zeros) skipped.add(new Skip(sum, WAIT));
            return new ContainerPlan(List.of(), List.of(), skipped, List.of());
        }
        Map<Long, BigDecimal> proceeds = new LinkedHashMap<>();
        BigDecimal shift = BigDecimal.ZERO;
        for (ProductSum sum : candidates) {
            proceeds.put(sum.productId, sum.proceeds().max(BigDecimal.ZERO));
            if (sum.proceeds().signum() < 0) shift = shift.add(sum.proceeds());
        }
        for (ProductSum sum : zeros) shift = shift.add(sum.proceeds());
        /* Largest proceeds first, then the larger quantity, then the lower product id. */
        List<ProductSum> largestFirst = candidates.stream().sorted(Comparator
                .comparing((ProductSum sum) -> proceeds.get(sum.productId)).reversed()
                .thenComparing(Comparator.comparingInt(ProductSum::quantity).reversed())
                .thenComparingLong(sum -> sum.productId)).toList();
        ProductSum target = largestFirst.getFirst();
        if (shift.signum() > 0) {
            proceeds.merge(target.productId, shift, BigDecimal::add);
        } else {
            BigDecimal missing = shift.negate();
            for (ProductSum sum : largestFirst) {
                if (missing.signum() <= 0) break;
                BigDecimal taken = proceeds.get(sum.productId).min(missing);
                proceeds.put(sum.productId, proceeds.get(sum.productId).subtract(taken));
                missing = missing.subtract(taken);
            }
        }
        List<String> warnings = new ArrayList<>();
        String into = label(catalog, target.productId);
        for (ProductSum sum : zeros) {
            warnings.add(label(catalog, sum.productId) + ": " + sum.sold + " verkocht en " + sum.boughtBack
                    + " teruggekocht; netto " + PartnerSaleTexts.euro(sum.proceeds()) + " verrekend bij " + into);
        }
        for (ProductSum sum : candidates) {
            if (sum.proceeds().signum() < 0) {
                warnings.add(label(catalog, sum.productId) + ": terugkoopkost " + PartnerSaleTexts.euro(sum.proceeds().negate())
                        + " boven de opbrengst, verrekend bij " + into);
            }
        }
        List<Line> lines = candidates.stream().map(sum -> new Line(sum, proceeds.get(sum.productId))).toList();
        List<ProductSum> included = new ArrayList<>(candidates);
        included.addAll(zeros);
        return new ContainerPlan(lines, included, skipped, warnings);
    }

    private static BigDecimal sum(List<ProductSum> sums) {
        return sums.stream().map(ProductSum::proceeds).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /* ============================================================== own stock */

    /** One own-stock product: cost c, our value v = c + (p - c) x share. onLine = an invoice line, else an extra line. */
    record OwnProduct(ProductSum sum, BigDecimal costEur, BigDecimal valueEur, boolean onLine) {}

    record OwnPlan(List<OwnProduct> products, List<ProductSum> included, List<Skip> skipped, List<String> warnings,
                   List<SalesOrderService.PartnerSalesInvoiceLine> invoiceLines, List<SalesExtraLine> extras,
                   BigDecimal valueEur) {}

    /**
     * The own-stock invoice: per product our value v = c + (p - c) x share / 100 with c the external
     * cost of its parts; a product with q > 0 and v > 0 is a line at v / q (unit cost c / q), any other
     * one (v <= 0, or q = 0) an extra line with its value, and "Afronding veilingverkopen" closes the
     * cent gap so the invoice excl. VAT is exactly the sum of v. q < 0 waits; when the values together
     * stay below 0 the products that pull them down wait; without a product with q > 0 everything waits.
     */
    static OwnPlan planOwn(Map<Long, ProductSum> sums, BigDecimal share, PartnerProductTiles.Catalog catalog) {
        List<Skip> skipped = new ArrayList<>();
        List<OwnProduct> products = new ArrayList<>();
        for (ProductSum sum : sums.values()) {
            if (sum.quantity() < 0) skipped.add(new Skip(sum, BUYBACK_ABOVE_SALE));
            else products.add(value(sum, share));
        }
        BigDecimal total = products.stream().map(OwnProduct::valueEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() < 0) {
            for (OwnProduct product : new ArrayList<>(products)) {
                if (product.valueEur().signum() < 0) {
                    products.remove(product);
                    skipped.add(new Skip(product.sum(), BUYBACK_ABOVE_NET));
                }
            }
        }
        if (products.stream().noneMatch(product -> product.sum().quantity() > 0)) {
            for (OwnProduct product : products) skipped.add(new Skip(product.sum(), WAIT));
            return new OwnPlan(List.of(), List.of(), skipped, List.of(), List.of(), List.of(), BigDecimal.ZERO);
        }
        List<String> warnings = new ArrayList<>();
        List<SalesOrderService.PartnerSalesInvoiceLine> invoiceLines = new ArrayList<>();
        List<SalesExtraLine> extras = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal onLines = BigDecimal.ZERO;
        BigDecimal onExtras = BigDecimal.ZERO;
        List<OwnProduct> placed = new ArrayList<>();
        for (OwnProduct product : products) {
            ProductSum sum = product.sum();
            if (sum.costUnknown) warnings.add("Geen kostprijs bekend voor " + label(catalog, sum.productId));
            else if (sum.costEstimated) warnings.add("Kostprijs deels geschat voor " + label(catalog, sum.productId)
                    + ": niet elk stuk had een kostprijs");
            value = value.add(product.valueEur());
            int quantity = sum.quantity();
            BigDecimal unitPrice = quantity > 0 ? product.valueEur().divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP) : null;
            /* Container-cost pricing replaces a price of 0 by the card cost: such a product goes on an extra line. */
            boolean onLine = unitPrice != null && unitPrice.signum() > 0;
            if (onLine) {
                invoiceLines.add(new SalesOrderService.PartnerSalesInvoiceLine(sum.productId, quantity, unitPrice,
                        product.costEur().divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP)));
                onLines = onLines.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            } else {
                /* Counted in pieces with the product's own noun ("36 rozen", not "3 stuks" for 3 displays). */
                PartnerPortalViews.ProductTile tile = catalog.tile(sum.productId);
                int pieces = quantity * Math.max(1, tile.piecesPerSalesUnit());
                SalesExtraLine extra = new SalesExtraLine(label(catalog, sum.productId) + ": "
                        + PartnerSaleTexts.quantity(pieces, tile.pieceOne(), tile.pieceOther())
                        + (quantity == 0 ? " (verkocht en teruggekocht)" : "")
                        + ", onze waarde " + PartnerSaleTexts.euro(product.valueEur()), BigDecimal.ONE, product.valueEur());
                extras.add(extra);
                onExtras = onExtras.add(extra.total());
            }
            placed.add(new OwnProduct(sum, product.costEur(), product.valueEur(), onLine));
        }
        BigDecimal rounding = value.subtract(onLines.setScale(2, RoundingMode.HALF_UP)).subtract(onExtras);
        if (rounding.signum() != 0) extras.add(new SalesExtraLine(ROUNDING, BigDecimal.ONE, rounding));
        List<ProductSum> included = placed.stream().map(OwnProduct::sum).toList();
        return new OwnPlan(placed, included, skipped, warnings, invoiceLines, extras, value);
    }

    /** v = c + (p - c) x share / 100, with the share part rounded to cents as a settlement line does. */
    private static OwnProduct value(ProductSum sum, BigDecimal share) {
        BigDecimal cost = sum.cost();
        BigDecimal proceeds = sum.proceeds().setScale(2, RoundingMode.HALF_UP);
        BigDecimal value = cost.add(proceeds.subtract(cost).multiply(share).divide(HUNDRED, 2, RoundingMode.HALF_UP));
        return new OwnProduct(sum, cost, value, false);
    }

    /**
     * The own-stock invoice's per-product detail for the internal note (off the PDF): per product the
     * pieces, netto, kost, resultaat and onze waarde (the Documentnotitie's word for the value; "ons deel"
     * there is our share of the profit), from the document's figures.
     */
    private static String ownDetail(PartnerInvoiceFigures.Group figures, PartnerProductTiles.Catalog catalog) {
        StringBuilder detail = new StringBuilder();
        for (PartnerInvoiceFigures.Row row : figures.rows()) {
            PartnerPortalViews.ProductTile tile = catalog.tile(row.productId());
            int pieces = row.quantity() * Math.max(1, tile.piecesPerSalesUnit());
            if (!detail.isEmpty()) detail.append('\n');
            detail.append(label(catalog, row.productId())).append(": ")
                    .append(PartnerSaleTexts.quantity(pieces, tile.pieceOne(), tile.pieceOther()))
                    .append("; netto ").append(PartnerSaleTexts.euro(row.proceedsEur()))
                    .append("; kost ").append(PartnerSaleTexts.euro(row.costEur()))
                    .append("; resultaat ").append(PartnerSaleTexts.euro(row.profitEur()))
                    .append("; onze waarde ").append(PartnerSaleTexts.euro(row.valueEur()));
        }
        return detail.toString();
    }

    /* ================================================================ helpers */

    /**
     * "Veilingverkopen VV-12, VV-14 (01/11–10/11/2026) · 2 meldingen": which meldingen a document holds,
     * the first line of its internal note (numbers collapsed only when consecutive).
     */
    static String internalHead(List<ProductSum> included) {
        LocalDate first = null;
        LocalDate last = null;
        Set<Long> meldingen = new TreeSet<>();
        for (ProductSum sum : included) {
            meldingen.addAll(sum.reportIds);
            if (sum.firstSoldOn != null && (first == null || sum.firstSoldOn.isBefore(first))) first = sum.firstSoldOn;
            if (sum.lastSoldOn != null && (last == null || sum.lastSoldOn.isAfter(last))) last = sum.lastSoldOn;
        }
        return PartnerInvoiceNotes.head(meldingen, first, last, null);
    }

    static String label(PartnerProductTiles.Catalog catalog, long productId) {
        PartnerPortalViews.ProductTile tile = catalog.tile(productId);
        return tile.variant() == null ? tile.name() : tile.name() + " " + tile.variant();
    }

    private static String containerName(Map<Long, PurchaseOrderName> names, long containerId) {
        PurchaseOrderName name = names.get(containerId);
        return name != null ? name.displayName() : PurchaseOrderName.display(containerId, null, null);
    }

    private Customer requirePartner(Long customerId) {
        if (customerId == null) throw new BusinessRuleException("Kies de partner");
        Customer customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
        if (!customer.partner()) throw new BusinessRuleException("Deze klant is geen partner");
        return customer;
    }

    record GroupOutcome(PartnerPortalViews.CreatedInvoice invoice, List<PartnerPortalViews.InvoiceSkipped> skipped) {}
}
