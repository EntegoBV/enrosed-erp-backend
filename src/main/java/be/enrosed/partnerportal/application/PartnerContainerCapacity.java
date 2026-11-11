package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.sales.application.PartnerFinancingService;
import be.enrosed.sales.application.PartnerSettlementLedger;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How many sales units a partner container can still take in meldingen (contract section 3):
 *
 * <pre>capacity(C, P) = max(0, min(ledgerRemaining - historicShipped - openNet, batchUnitsLeft))</pre>
 *
 * ledgerRemaining = the settlement ledger's remaining quantity (drafts reserve); historicShipped =
 * pieces of C already shipped on historical advances that no shipped settlement absorbed (the
 * shipGoods rule); openNet = open sale minus open buyback quantity of melding parts on (C, P);
 * batchUnitsLeft = the PARTNER batch pieces of C left / pieces per sales unit, only while batches
 * run. A candidate is a container of this partner whose stock is booked, received at the partner's
 * location (receivingLocationId = it, or null when it is MAIN); oldest received first.
 *
 * Everything here must be read after the section 3 locks: partner row, containers, products.
 */
@ApplicationScoped
public class PartnerContainerCapacity {

    /** One container and product with the terms of its capacity. batchUnitsLeft null = batches off. */
    public record Slot(long purchaseOrderId, long productId, int ledgerRemaining, int historicShipped, int openNet,
                       Integer batchUnitsLeft) {
        public int capacity() {
            int left = ledgerRemaining - historicShipped - openNet;
            if (batchUnitsLeft != null) left = Math.min(left, batchUnitsLeft);
            return Math.max(0, left);
        }
    }

    private final PartnerPortalQueries queries;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerPortalRepositories.InvoiceParts invoiceParts;
    private final PartnerOpenParts openParts;
    private final PurchaseOrderService purchaseOrders;
    private final SalesOrderService sales;
    private final StockBatchService batches;

    public PartnerContainerCapacity(PartnerPortalQueries queries, PartnerPortalRepositories.Lines lines,
                                    PartnerPortalRepositories.InvoiceParts invoiceParts, PartnerOpenParts openParts,
                                    PurchaseOrderService purchaseOrders, SalesOrderService sales,
                                    StockBatchService batches) {
        this.queries = queries;
        this.lines = lines;
        this.invoiceParts = invoiceParts;
        this.openParts = openParts;
        this.purchaseOrders = purchaseOrders;
        this.sales = sales;
        this.batches = batches;
    }

    /** Every container of this partner, ascending id, each FOR UPDATE (the second lock of section 3). */
    public List<PurchaseOrder> lockContainers(long customerId) {
        List<PurchaseOrder> locked = new ArrayList<>();
        for (Long id : queries.partnerContainerIds(customerId)) locked.add(purchaseOrders.lockForPartnerSettlement(id));
        return locked;
    }

    /** The containers a sale may be filled from, oldest received first (then id). */
    public static List<PurchaseOrder> candidates(List<PurchaseOrder> containers, StockLocation location) {
        boolean main = StockLocation.MAIN_CODE.equals(location.code());
        return containers.stream()
                .filter(PurchaseOrder::isStockBooked)
                .filter(order -> Objects.equals(order.receivingLocationId(), location.id())
                        || (order.receivingLocationId() == null && main))
                .sorted(Comparator.comparing(PurchaseOrder::receivedOn, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                        .thenComparing(PurchaseOrder::id))
                .toList();
    }

    /**
     * The slots of these containers for these products, in the containers' order; a container
     * that does not carry a product has no slot for it.
     */
    public Map<Long, List<Slot>> slots(long customerId, List<PurchaseOrder> containers, Collection<Long> productIds,
                                       boolean batchesActive) {
        Map<Long, List<Slot>> result = new HashMap<>();
        Set<Long> wanted = new HashSet<>(productIds);
        List<PurchaseOrder> carrying = containers.stream().filter(order -> order.lines().stream()
                .map(PurchaseOrderLine::productId).anyMatch(wanted::contains)).toList();
        if (carrying.isEmpty()) return result;
        List<Long> containerIds = carrying.stream().map(PurchaseOrder::id).toList();
        Map<Long, Map<Long, Integer>> historic = historicShipped(containerIds);
        Map<String, Integer> openNet = openNet(customerId, containerIds, wanted);
        Map<Long, StockBatchService.Holdings> holdings = new HashMap<>();
        if (batchesActive) for (Long productId : wanted) holdings.put(productId, batches.holdings(productId));
        for (PurchaseOrder container : carrying) {
            PartnerSettlementLedger.Availability availability = sales.partnerSettlementAvailability(container.id());
            for (PartnerSettlementLedger.Line line : availability.lines()) {
                if (line.productId() == null || !wanted.contains(line.productId())) continue;
                long productId = line.productId();
                Integer batchUnits = null;
                if (batchesActive) {
                    StockBatchService.Holdings held = holdings.get(productId);
                    int pieces = held.partnerPiecesByContainer().getOrDefault(container.id(), 0);
                    batchUnits = pieces / Math.max(1, held.unitPieces());
                }
                result.computeIfAbsent(productId, key -> new ArrayList<>()).add(new Slot(container.id(), productId,
                        line.remainingQuantity(),
                        historic.getOrDefault(container.id(), Map.of()).getOrDefault(productId, 0),
                        openNet.getOrDefault(key(container.id(), productId), 0), batchUnits));
            }
        }
        return result;
    }

    /**
     * Per container and product: pieces shipped on historical advances that the ledger does not
     * already hold back, max(0, shipped advances - manual settlements). The settlements are every
     * live (or already shipped) one that is not report-backed, concepts and unshipped ones
     * included: the ledger's remaining already leaves their quantity out, and when they ship they
     * reuse these historical pieces first (the shipGoods rule) instead of booking stock. Counting
     * only shipped settlements here took those pieces off twice until the settlement shipped.
     * Report-backed settlements never absorbed historical pieces and are left out.
     */
    public Map<Long, Map<Long, Integer>> historicShipped(Collection<Long> containerIds) {
        Map<Long, Map<Long, Integer>> result = new HashMap<>();
        if (containerIds.isEmpty()) return result;
        Set<Long> wanted = new HashSet<>(containerIds);
        List<SalesOrder> linked = sales.list().stream()
                .filter(order -> wanted.contains(order.linkedPurchaseOrderId()))
                .filter(order -> order.isPartnerAdvance() ? order.goodsShippedAt() != null
                        : order.purpose() == SalesPurpose.PARTNER_SETTLEMENT && order.isInvoice()
                        && (order.goodsShippedAt() != null || PartnerFinancingService.live(order)))
                .toList();
        if (linked.stream().noneMatch(SalesOrder::isPartnerAdvance)) return result;
        Set<Long> reportBacked = new HashSet<>(invoiceParts.reportBacked(linked.stream()
                .map(SalesOrder::id).filter(Objects::nonNull).toList()));
        Map<Long, Map<Long, Integer>> advances = new HashMap<>();
        Map<Long, Map<Long, Integer>> settlements = new HashMap<>();
        for (SalesOrder order : linked) {
            Map<Long, Map<Long, Integer>> target;
            if (order.isPartnerAdvance()) target = advances;
            else if (!reportBacked.contains(order.id())) target = settlements;
            else continue;
            Map<Long, Integer> perProduct = target.computeIfAbsent(order.linkedPurchaseOrderId(), key -> new HashMap<>());
            for (SalesOrderLine line : order.lines()) {
                if (line.productId() != null && line.quantity() > 0) perProduct.merge(line.productId(), line.quantity(), Integer::sum);
            }
        }
        advances.forEach((containerId, shipped) -> {
            Map<Long, Integer> absorbed = settlements.getOrDefault(containerId, Map.of());
            Map<Long, Integer> left = new HashMap<>();
            shipped.forEach((productId, quantity) -> {
                int remaining = Math.max(0, quantity - absorbed.getOrDefault(productId, 0));
                if (remaining > 0) left.put(productId, remaining);
            });
            if (!left.isEmpty()) result.put(containerId, left);
        });
        return result;
    }

    /** Open sale minus open buyback quantity per (container, product) of this partner's parts. */
    public Map<String, Integer> openNet(long customerId, Collection<Long> containerIds, Collection<Long> productIds) {
        Map<String, Integer> result = new HashMap<>();
        List<PartnerSaleReportLineEntity> parts = lines.containerParts(customerId, containerIds, productIds);
        PartnerOpenParts.Rule rule = openParts.forLines(parts);
        for (PartnerSaleReportLineEntity part : parts) {
            if (part.purchaseOrderId == null || part.productId == null || !rule.isOpen(part, false)) continue;
            int signed = part.isSale() ? part.quantityOrZero() : -part.quantityOrZero();
            result.merge(key(part.purchaseOrderId, part.productId), signed, Integer::sum);
        }
        return result;
    }

    public static String key(long purchaseOrderId, long productId) {
        return purchaseOrderId + ":" + productId;
    }
}
