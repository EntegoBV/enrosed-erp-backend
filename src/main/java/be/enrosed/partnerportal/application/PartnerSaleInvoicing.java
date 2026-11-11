package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleInvoicePartEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.partnerportal.domain.PartnerSaleTexts;
import be.enrosed.sales.application.PartnerSettlements;
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
import java.time.format.DateTimeFormatter;
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

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

    public PartnerSaleInvoicing(PartnerPortalRepositories.Settings settingsRows, PartnerPortalRepositories.Reports reports,
                                PartnerPortalRepositories.Lines lines, PartnerPortalRepositories.InvoiceParts invoiceParts,
                                PartnerOpenParts openParts, PartnerContainerCapacity capacity, PartnerProductTiles tiles,
                                StockService stock, SalesOrderService sales, SalesRepositories.Customers customers,
                                PurchaseOrderService purchaseOrders) {
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
    }

    /* =============================================================== preview */

    /**
     * What Factureren would make now. reportIds null = every melding of the partner with an open
     * part (sold on or before {@code until} when given). Nothing is written or locked; a container
     * group is planned with createAuctionSettlement's own arithmetic in a transaction of its own, so
     * its refusal becomes {@code blocked} and touches nothing else.
     */
    @Transactional
    public PartnerPortalViews.InvoicePreview preview(Long customerId, List<Long> reportIds, LocalDate until) {
        Customer partner = requirePartner(customerId);
        List<OpenPart> parts = selectOpen(partner.id(), reportIds, until);
        PartnerProductTiles.Catalog catalog = tiles.load();
        Map<Long, Map<Long, ProductSum>> bySource = group(parts);
        Map<Long, PurchaseOrderName> containers = purchaseOrders.names(bySource.keySet().stream().filter(Objects::nonNull).toList());
        List<PartnerPortalViews.InvoicePreviewGroup> groups = new ArrayList<>();
        List<PartnerPortalViews.InvoiceSkipped> skipped = new ArrayList<>();
        BigDecimal share = partner.partnerSharePctOrDefault();
        for (Map.Entry<Long, Map<Long, ProductSum>> source : bySource.entrySet()) {
            Long containerId = source.getKey();
            if (containerId != null) {
                ContainerPlan plan = planContainer(source.getValue(), catalog);
                skipped.addAll(skippedRows(plan.skipped(), SOURCE_CONTAINER, containerId, catalog));
                if (plan.lines().isEmpty()) continue;
                groups.add(previewContainer(partner.id(), containerId, containerName(containers, containerId), plan,
                        notes(plan.included()), catalog));
            } else {
                OwnPlan plan = planOwn(source.getValue(), share, catalog);
                skipped.addAll(skippedRows(plan.skipped(), SOURCE_OWN, null, catalog));
                if (plan.included().isEmpty()) continue;
                groups.add(previewOwn(plan, catalog));
            }
        }
        List<Long> chosen = parts.stream().map(part -> part.report().id).distinct().sorted().toList();
        return new PartnerPortalViews.InvoicePreview(groups, skipped, chosen, PartnerNumbers.pct(share));
    }

    private PartnerPortalViews.InvoicePreviewGroup previewContainer(long customerId, long containerId, String containerName,
                                                                   ContainerPlan plan, String note,
                                                                   PartnerProductTiles.Catalog catalog) {
        SalesOrderService.AuctionSettlementRequest request = settlementRequest(customerId, containerId, plan, note);
        SalesOrderService.AuctionSettlementPlan settlement = null;
        String blocked = null;
        try {
            settlement = QuarkusTransaction.requiringNew().call(() -> sales.previewAuctionSettlementFromReports(request));
        } catch (BusinessRuleException | NotFoundException refusal) {
            blocked = refusal.getMessage();
        }
        if (settlement != null && settlement.finalSettlement()) {
            blocked = leftBehind(containerId, plan);
            if (blocked != null) settlement = null;
        }
        Map<Long, PartnerSettlements.Line> figures = new HashMap<>();
        if (settlement != null) for (PartnerSettlements.Line line : settlement.snapshotLines()) figures.put(line.productId(), line);
        List<PartnerPortalViews.InvoicePreviewLine> rows = new ArrayList<>();
        for (Line line : plan.lines()) {
            PartnerSettlements.Line figure = figures.get(line.sum().productId);
            rows.add(previewLine(line.sum(), line.proceeds(), figure == null ? null : figure.costEur(),
                    figure == null ? null : figure.revenueEur(), catalog));
        }
        return new PartnerPortalViews.InvoicePreviewGroup(SOURCE_CONTAINER, containerId, containerName, rows,
                settlement == null ? null : PartnerNumbers.money(settlement.valueEur()),
                settlement == null ? null : PartnerNumbers.money(settlement.advanceEur()),
                settlement == null ? null : PartnerNumbers.money(settlement.balanceEur()),
                settlement != null && settlement.finalSettlement(), blocked, plan.warnings());
    }

    private PartnerPortalViews.InvoicePreviewGroup previewOwn(OwnPlan plan, PartnerProductTiles.Catalog catalog) {
        List<PartnerPortalViews.InvoicePreviewLine> rows = new ArrayList<>();
        for (OwnProduct product : plan.products()) {
            rows.add(previewLine(product.sum(), product.sum().proceeds(), product.costEur(), product.valueEur(), catalog));
        }
        BigDecimal value = PartnerNumbers.money(plan.valueEur());
        return new PartnerPortalViews.InvoicePreviewGroup(SOURCE_OWN, null, null, rows, value, ZERO, value, false, null,
                plan.warnings());
    }

    private static PartnerPortalViews.InvoicePreviewLine previewLine(ProductSum sum, BigDecimal proceeds, BigDecimal cost,
                                                                     BigDecimal value, PartnerProductTiles.Catalog catalog) {
        PartnerPortalViews.ProductTile tile = catalog.tile(sum.productId);
        return new PartnerPortalViews.InvoicePreviewLine(sum.productId, tile.name(), tile.variant(), sum.sold,
                sum.boughtBack, sum.quantity(), PartnerNumbers.money(sum.saleNet), PartnerNumbers.money(sum.buyback),
                PartnerNumbers.money(proceeds), PartnerNumbers.money(cost), PartnerNumbers.money(value));
    }

    /* =============================================================== invoice */

    /**
     * Makes the invoices for the open parts of these meldingen (the preview's reportIds), one
     * transaction per source: the partner's containers ascending, then own stock.
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

    /** One source, in the caller's new transaction: locks, a fresh read, the document, the links. */
    private GroupOutcome runGroup(long customerId, List<Long> reportIds, Long containerId) {
        settingsRows.lockOrCreate(customerId);
        capacity.lockContainers(customerId);
        TreeSet<Long> products = new TreeSet<>();
        for (OpenPart part : selectOpen(customerId, reportIds, null)) {
            if (Objects.equals(part.line().purchaseOrderId, containerId)) products.add(part.line().productId);
        }
        for (Long productId : products) stock.lockProduct(productId);
        /* After the locks: still open, not withdrawn, of this source. */
        List<OpenPart> parts = selectOpen(customerId, reportIds, null).stream()
                .filter(part -> Objects.equals(part.line().purchaseOrderId, containerId)).toList();
        PartnerProductTiles.Catalog catalog = tiles.load();
        Map<Long, ProductSum> sums = group(parts).getOrDefault(containerId, Map.of());
        if (containerId != null) {
            ContainerPlan plan = planContainer(sums, catalog);
            List<PartnerPortalViews.InvoiceSkipped> skipped = skippedRows(plan.skipped(), SOURCE_CONTAINER, containerId, catalog);
            if (plan.lines().isEmpty()) return new GroupOutcome(null, skipped);
            SalesOrderService.AuctionSettlementRequest request = settlementRequest(customerId, containerId, plan,
                    notes(plan.included()));
            String behind = leftBehind(containerId, plan);
            if (behind != null && sales.previewAuctionSettlementFromReports(request).finalSettlement()) {
                throw new BusinessRuleException(behind);
            }
            SalesOrder created = sales.createAuctionSettlementFromReports(request);
            link(created, plan.included());
            return new GroupOutcome(new PartnerPortalViews.CreatedInvoice(created.id(), created.number(), SOURCE_CONTAINER,
                    containerId), skipped);
        }
        Customer partner = requirePartner(customerId);
        OwnPlan plan = planOwn(sums, partner.partnerSharePctOrDefault(), catalog);
        List<PartnerPortalViews.InvoiceSkipped> skipped = skippedRows(plan.skipped(), SOURCE_OWN, null, catalog);
        if (plan.included().isEmpty()) return new GroupOutcome(null, skipped);
        SalesOrder created = sales.createPartnerSalesInvoice(new SalesOrderService.PartnerSalesInvoiceRequest(customerId,
                plan.invoiceLines(), plan.extras(), ownNotes(plan, partner.partnerSharePctOrDefault(), catalog),
                notes(plan.included())));
        link(created, plan.included());
        return new GroupOutcome(new PartnerPortalViews.CreatedInvoice(created.id(), created.number(), SOURCE_OWN, null), skipped);
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
     * container, so a part it leaves open (a buyback waiting for a resale, or a melding outside this run) could
     * never be invoiced. The refusal text when such parts exist, else null.
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
        String numbers = String.join(", ", waiting.stream().map(PartnerPortalRules::reportNumber).toList());
        return "Dit wordt de slotfactuur van deze container, maar " + numbers + (waiting.size() == 1 ? " heeft" : " hebben")
                + " er nog een open deel; factureer dat mee of wacht op een volgende verkoop";
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
     * are left out; the open-part rule decides the rest.
     */
    private List<OpenPart> selectOpen(long customerId, Collection<Long> reportIds, LocalDate until) {
        List<Long> ids = reportIds == null
                ? lines.reportsWithOpenParts(customerId, openParts.releasing(lines.liveInvoiceIdsOf(customerId)), until)
                : reportIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) return List.of();
        Map<Long, PartnerSaleReportEntity> chosen = new HashMap<>();
        for (PartnerSaleReportEntity report : reports.list("id in ?1", ids)) {
            if (!Objects.equals(report.customerId, customerId) || report.isVoided()) continue;
            if (until != null && report.soldOn != null && report.soldOn.isAfter(until)) continue;
            chosen.put(report.id, report);
        }
        if (chosen.isEmpty()) return List.of();
        List<PartnerSaleReportLineEntity> found = lines.byReports(chosen.keySet()).stream()
                .filter(line -> line.isSale() || line.isBuyback()).toList();
        PartnerOpenParts.Rule rule = openParts.forLines(found);
        List<OpenPart> open = new ArrayList<>();
        for (PartnerSaleReportLineEntity line : found) {
            PartnerSaleReportEntity report = chosen.get(line.reportId);
            if (report != null && line.productId != null && rule.isOpen(line, report.isVoided())) open.add(new OpenPart(line, report));
        }
        return open;
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
        BigDecimal buyback = BigDecimal.ZERO;
        BigDecimal ownCost = BigDecimal.ZERO;
        boolean costUnknown;
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
                ownCost = ownCost.add(unitCost.multiply(BigDecimal.valueOf(quantity)));
                if (line.purchaseOrderId == null && unitCost.signum() <= 0) costUnknown = true;
            } else {
                boughtBack += quantity;
                buyback = buyback.add(line.grossEur == null ? BigDecimal.ZERO : line.grossEur);
                ownCost = ownCost.subtract(unitCost.multiply(BigDecimal.valueOf(quantity)));
            }
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
                SalesExtraLine extra = new SalesExtraLine(label(catalog, sum.productId) + ": " + quantity
                        + (quantity == 1 ? " stuk" : " stuks") + (quantity == 0 ? " (verkocht en teruggekocht)" : "")
                        + ", ons deel " + PartnerSaleTexts.euro(product.valueEur()), BigDecimal.ONE, product.valueEur());
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

    /** The invoice's own text, in the style of a settlement's: one line per product, then the totals. */
    private static String ownNotes(OwnPlan plan, BigDecimal share, PartnerProductTiles.Catalog catalog) {
        StringBuilder notes = new StringBuilder("Veilingverkopen uit eigen voorraad · ")
                .append(PartnerNumbers.pct(share).toPlainString().replace('.', ','))
                .append(" % van het netto resultaat (ook verlies).\n");
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (OwnProduct product : plan.products()) {
            ProductSum sum = product.sum();
            BigDecimal net = sum.proceeds().setScale(2, RoundingMode.HALF_UP);
            proceeds = proceeds.add(net);
            cost = cost.add(product.costEur());
            notes.append(label(catalog, sum.productId)).append(": ").append(sum.quantity()).append(" stuks; netto ")
                    .append(PartnerSaleTexts.euro(net)).append("; kost ").append(PartnerSaleTexts.euro(product.costEur()))
                    .append("; resultaat ").append(PartnerSaleTexts.euro(net.subtract(product.costEur())))
                    .append("; ons deel ").append(PartnerSaleTexts.euro(product.valueEur())).append('\n');
        }
        return notes.append("Netto opbrengst ").append(PartnerSaleTexts.euro(proceeds)).append("; kost ")
                .append(PartnerSaleTexts.euro(cost)).append("; ons deel ").append(PartnerSaleTexts.euro(plan.valueEur()))
                .append('.').toString();
    }

    /* ================================================================ helpers */

    /** "Veilingverkopen 01/11/2026 - 10/11/2026 · 3 meldingen": which meldingen a document holds. */
    private static String notes(List<ProductSum> included) {
        LocalDate first = null;
        LocalDate last = null;
        Set<Long> meldingen = new TreeSet<>();
        for (ProductSum sum : included) {
            meldingen.addAll(sum.reportIds);
            if (sum.firstSoldOn != null && (first == null || sum.firstSoldOn.isBefore(first))) first = sum.firstSoldOn;
            if (sum.lastSoldOn != null && (last == null || sum.lastSoldOn.isAfter(last))) last = sum.lastSoldOn;
        }
        String period = first == null ? "" : first.equals(last) ? " " + DAY.format(first)
                : " " + DAY.format(first) + " - " + DAY.format(last);
        return "Veilingverkopen" + period + " · " + meldingen.size() + (meldingen.size() == 1 ? " melding" : " meldingen");
    }

    private static String label(PartnerProductTiles.Catalog catalog, long productId) {
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
