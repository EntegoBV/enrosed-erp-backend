package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.sales.application.PartnerReportBacking;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The sales side's view of documents made from meldingen ({@link PartnerReportBacking}): which are
 * report-backed (rows in partner_sale_invoice_part, append-only, so a trashed or replaced document
 * stays report-backed), what their meldingen booked per product, and what open meldingen still claim
 * of a container for the manual settlement limit. Read-only; never depends on sales services.
 */
@ApplicationScoped
public class PartnerReportBackingService implements PartnerReportBacking {

    /** Up to this many documents are read by id; a longer list reads every report-backed document at once. */
    static final int BY_ID_LIMIT = 200;

    private final PartnerPortalRepositories.InvoiceParts invoiceParts;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerOpenParts openParts;

    public PartnerReportBackingService(PartnerPortalRepositories.InvoiceParts invoiceParts,
                                       PartnerPortalRepositories.Lines lines, PartnerOpenParts openParts) {
        this.invoiceParts = invoiceParts;
        this.lines = lines;
        this.openParts = openParts;
    }

    @Override
    public Set<Long> reportBacked(Collection<Long> salesOrderIds) {
        List<Long> ids = salesOrderIds == null ? List.of() : salesOrderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(invoiceParts.reportBacked(ids));
    }

    @Override
    public Map<Long, Map<Long, Integer>> bookedQuantities(Collection<SalesOrder> documents) {
        Map<Long, Map<Long, Integer>> result = new HashMap<>();
        if (documents == null || documents.isEmpty()) return result;
        Map<Long, SalesOrder> byId = new HashMap<>();
        for (SalesOrder document : documents) if (document != null && document.id() != null) byId.put(document.id(), document);
        if (byId.isEmpty()) return result;
        /* Net sales units per document and product: sale parts minus buyback parts. */
        Map<Long, Map<Long, Integer>> net = new HashMap<>();
        for (Object[] row : invoiceParts.linkedQuantities(byId.size() <= BY_ID_LIMIT ? byId.keySet() : null)) {
            long documentId = ((Number) row[0]).longValue();
            if (!byId.containsKey(documentId) || row[1] == null) continue;
            long productId = ((Number) row[1]).longValue();
            int quantity = row[3] == null ? 0 : ((Number) row[3]).intValue();
            int signed = PartnerSaleReportLineEntity.BUYBACK.equals(row[2]) ? -quantity : quantity;
            net.computeIfAbsent(documentId, key -> new HashMap<>()).merge(productId, signed, Integer::sum);
        }
        net.forEach((documentId, perProduct) -> {
            Map<Long, Integer> onLines = new HashMap<>();
            for (SalesOrderLine line : byId.get(documentId).lines()) {
                if (line.productId() == null || line.isUnavailable() || line.quantity() <= 0) continue;
                onLines.merge(line.productId(), line.quantity(), Integer::sum);
            }
            Map<Long, Integer> booked = new HashMap<>();
            onLines.forEach((productId, quantity) ->
                    booked.put(productId, Math.min(quantity, Math.max(0, perProduct.getOrDefault(productId, 0)))));
            result.put(documentId, booked);
        });
        return result;
    }

    @Override
    public Instant lastMovementAt(long salesOrderId) {
        return invoiceParts.lastMovementAt(salesOrderId);
    }

    @Override
    public Map<Long, OpenReported> openReported(long purchaseOrderId) {
        List<PartnerSaleReportLineEntity> parts = lines.partsOnContainer(purchaseOrderId);
        PartnerOpenParts.Rule rule = openParts.forLines(parts);
        Map<Long, Integer> quantities = new HashMap<>();
        Map<Long, BigDecimal> nets = new HashMap<>();
        for (PartnerSaleReportLineEntity part : parts) {
            if (part.productId == null || !rule.isOpen(part, false)) continue;
            quantities.merge(part.productId, part.isSale() ? part.quantityOrZero() : -part.quantityOrZero(), Integer::sum);
            if (part.netEur != null) nets.merge(part.productId, part.netEur, BigDecimal::add);
        }
        Map<Long, OpenReported> result = new HashMap<>();
        quantities.forEach((productId, quantity) ->
                result.put(productId, new OpenReported(quantity, nets.getOrDefault(productId, BigDecimal.ZERO))));
        return result;
    }
}
