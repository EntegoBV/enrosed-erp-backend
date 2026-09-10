package be.enrosed.shared.trash;

import be.enrosed.catalog.application.CatalogMutationLock;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.sales.application.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static be.enrosed.shared.trash.DeletedItemDtos.*;

/** Source rows stay intact. Only this admin service can read/restore tombstoned documents. */
@ApplicationScoped
public class DeletedItemsService {
    @Inject EntityManager entities;
    @Inject ObjectMapper json;
    @Inject CurrentActor actor;
    @Inject ActivityLogService activities;
    @Inject CatalogMutationLock catalogLock;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject CustomerService customers;
    @Inject ProductService products;
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseTrashSnapshot purchaseSnapshots;
    @Inject be.enrosed.sourcing.application.port.out.SourcingRepositories.Documents purchaseDocuments;
    @Inject PartnerAdvanceSchedules schedules;
    @Inject PartnerAdvanceScheduleService advanceService;
    @Inject PartnerAdvanceContents advanceContents;
    @ConfigProperty(name = "enrosed.deleted-items.retention-days", defaultValue = "90") int retentionDays;

    public record Claim(long id, BigDecimal total, boolean issued, boolean live, Long customerId) {}
    public record ContainerLine(Long productId, int quantity, int ordered, int damaged, BigDecimal unitCost, BigDecimal totalCost) {}
    public record PartnerContext(Long customerId, BigDecimal financingPct, BigDecimal sharePct,
                                 BigDecimal purchaseTotal, PartnerAdvanceSchedules.Agreement agreement,
                                 PartnerAdvanceSchedules.Row row, List<Claim> advances, List<Long> settlements, List<ContainerLine> containerLines) {}

    /** Called by the existing guarded DELETE workflow, with its source row already locked. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void trashSales(SalesOrder order) {
        Snapshot snapshot = salesSnapshot(order);
        PartnerContext context = order.isPartnerDeal() ? partnerContext(order) : null;
        var entry = entry("SALES", order.id(), order.isInvoice() ? Type.INVOICE : Type.QUOTE,
                order.number(), customerName(order.customerId()), order.status().name(), snapshot, order);
        entry.partnerContextJson = context == null ? null : write(context);
        entities.persist(entry);
        // Free a planned slot for a replacement, but remember its exact identity for safe restoration.
        schedules.detachInvoice(order.id());
        mark("sales_order", order.id(), entry.deletedAt);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void trashPurchase(PurchaseOrder order) {
        var entry = entry("PURCHASE", order.id(), Type.PURCHASE_ORDER, order.number(),
                purchaseSnapshots.partyName(order), order.status().name(), purchaseSnapshots.snapshot(order), order);
        entities.persist(entry);
        mark("purchase_order", order.id(), entry.deletedAt);
    }

    private DeletedItemEntity entry(String kind, long sourceId, Type type, String number, String party,
                                    String status, Snapshot snapshot, Object order) {
        var entry = new DeletedItemEntity();
        entry.sourceKind = kind; entry.sourceId = sourceId; entry.type = type;
        entry.number = number; entry.partyName = party; entry.status = status;
        entry.deletedAt = Instant.now();
        entry.expiresAt = entry.deletedAt.plus(retention(), ChronoUnit.DAYS);
        entry.deletedBy = actor.current().displayName();
        entry.snapshotJson = write(snapshot); entry.orderJson = write(order);
        return entry;
    }

    @Transactional
    public Page list() {
        var rows = entities.createQuery("from DeletedItem where expiresAt > :now order by deletedAt desc, id desc", DeletedItemEntity.class)
                .setParameter("now", Instant.now()).getResultList();
        return new Page(retention(), rows.stream().map(row -> summary(row, restoreReason(row))).toList());
    }

    @Transactional
    public Detail detail(long id) {
        var row = visible(id);
        Snapshot snapshot = read(row.snapshotJson, Snapshot.class);
        String reason = restoreReason(row);
        return new Detail(row.id, row.type, row.sourceId, row.number, row.partyName, row.status,
                row.deletedAt, row.expiresAt, row.deletedBy, snapshot.totalEur(), reason == null, reason,
                snapshot.fields(), snapshot.lines(), snapshot.notes(), snapshot.attachments().stream()
                        .map(file -> new Attachment(file.id(), file.name(), row.type == Type.PURCHASE_ORDER
                                ? "/api/deleted-items/" + row.id + "/attachments/" + file.id() + "/file" : null)).toList());
    }

    public record FileDownload(String name, String contentType, java.io.InputStream content) {}

    @Transactional
    public FileDownload attachment(long id, long attachmentId) {
        var row = visible(id);
        var snapshot = read(row.snapshotJson, Snapshot.class);
        if (row.type != Type.PURCHASE_ORDER || snapshot.attachments().stream().noneMatch(file -> file.id() == attachmentId))
            throw new NotFoundException("Bijlage", attachmentId);
        var document = purchaseDocuments.find(row.sourceId, attachmentId).orElseThrow(() -> new NotFoundException("Bijlage", attachmentId));
        return new FileDownload(document.originalFilename(), document.contentType(), purchases.documentData(document));
    }

    @Transactional
    public Restored restore(long id) {
        catalogLock.acquire();
        var initial = visible(id);
        String kind = initial.sourceKind;
        long sourceId = initial.sourceId;
        // Match normal mutation ordering: purchase first, then its sales document, then trash metadata.
        SalesOrder sale = "SALES".equals(kind) ? read(initial.orderJson, SalesOrder.class) : null;
        Long purchaseId = sale == null ? Long.valueOf(sourceId) : sale.linkedPurchaseOrderId();
        if (purchaseId != null) lock("purchase_order", purchaseId);
        if (sale != null && sale.sourceQuoteId() != null) lock("sales_order", sale.sourceQuoteId());
        if (sale != null) lock("sales_order", sourceId);
        var row = entities.find(DeletedItemEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw missing();
        entities.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        requireVisible(row);
        String reason = restoreReason(row);
        if (reason != null) throw new BusinessRuleException(reason);
        var context = row.partnerContextJson == null ? null : read(row.partnerContextJson, PartnerContext.class);
        String number = row.number;
        Snapshot frozen = read(row.snapshotJson, Snapshot.class);
        // Native markers are the sole explicit escape from the always-on ORM restriction.
        // Flush then clear prevents any cached entity from bypassing the new visibility state.
        mark(sale == null ? "purchase_order" : "sales_order", sourceId, null);
        if (sale != null && sale.isPartnerAdvance() && sale.isInvoice()) {
            if (context != null && context.row() != null) {
                var slot = context.row();
                schedules.save(new PartnerAdvanceSchedules.Row(slot.id(), slot.purchaseOrderId(), slot.position(),
                        slot.label(), slot.percentage(), slot.amountEur(), slot.dueDate(), sale.id()));
            }
            advanceService.validateReservation(sales.get(sourceId), context == null || context.row() == null ? null : context.row().id());
        }
        if (sale != null && sale.purpose() == SalesPurpose.PARTNER_SETTLEMENT) {
            var available = sales.partnerSettlementAvailability(sale.linkedPurchaseOrderId());
            if (available.lines().stream().anyMatch(line -> line.settledQuantity() > line.totalQuantity()
                    || line.remainingCostEur().signum() < 0)
                    || available.creditedAdvanceEur().compareTo(available.issuedAdvanceEur()) > 0)
                throw new BusinessRuleException("Deze slotfactuur overschrijdt de huidige aantallen, kosten of uitgereikte voorschotten. Maak een nieuwe afrekening.");
        }
        // Frozen partner financial claims must never be silently repriced during a restore.
        if (sale != null && sale.isPartnerDeal() && frozen.totalEur() != null
                && !same(frozen.totalEur(), sales.price(sales.get(sourceId)).totals().totalInclVat())) {
            throw new BusinessRuleException("Het partnerbedrag is gewijzigd. Maak een nieuw concept op basis van de actuele afspraak.");
        }
        entities.remove(entities.find(DeletedItemEntity.class, id));
        activities.record("RESTORED", sale == null ? "PURCHASE_ORDER" : "SALES_ORDER", Long.toString(sourceId), number,
                (sale == null ? "Inkooporder" : sale.isInvoice() ? "Factuur" : "Offerte") + " hersteld uit verwijderde items");
        return new Restored(sourceId, (sale == null ? "/purchasing/" : "/sales/") + sourceId);
    }

    private String restoreReason(DeletedItemEntity row) {
        if ("PURCHASE".equals(row.sourceKind)) return purchaseSnapshots.validateRestore(read(row.orderJson, PurchaseOrder.class));
        SalesOrder order = read(row.orderJson, SalesOrder.class);
        if (order.customerId() != null && !exists("customer", order.customerId())) return "De klant bestaat niet meer. Het document blijft hier raadpleegbaar.";
        if (order.lines() != null && order.lines().stream().anyMatch(line -> line.productId() != null && !exists("product", line.productId())))
            return "Een product bestaat niet meer. Het document blijft hier raadpleegbaar.";
        if (order.sourceQuoteId() != null && orders.findById(order.sourceQuoteId()).isEmpty())
            return "Herstel eerst de oorspronkelijke offerte.";
        if (order.sourceQuoteId() != null && !order.isPartnerAdvance() && orders.findAll().stream().anyMatch(o -> o.isInvoice()
                && Objects.equals(o.sourceQuoteId(), order.sourceQuoteId()) && o.status() != QuoteStatus.GEANNULEERD))
            return "Er bestaat inmiddels een andere factuur voor deze offerte.";
        if (order.linkedPurchaseOrderId() != null) {
            try { purchases.get(order.linkedPurchaseOrderId()); }
            catch (NotFoundException unavailable) { return "Herstel eerst de bijbehorende inkooporder."; }
        }
        if (row.partnerContextJson != null) {
            var before = read(row.partnerContextJson, PartnerContext.class);
            PartnerContext now;
            try { now = partnerContext(order); }
            catch (BusinessRuleException | NotFoundException unavailable) { return "De partnerafspraak kan niet meer worden gecontroleerd. Maak een nieuw concept."; }
            if (!Objects.equals(before.customerId(), now.customerId()) || !same(before.financingPct(), now.financingPct())
                    || !same(before.sharePct(), now.sharePct()) || !same(before.purchaseTotal(), now.purchaseTotal())
                    || !sameAgreement(before.agreement(), now.agreement()) || !Objects.equals(before.containerLines(), now.containerLines()))
                return "De container- of financieringsafspraak is gewijzigd. Maak een nieuw concept voor de huidige afspraak.";
            if (before.row() != null) {
                var slot = schedules.rows(order.linkedPurchaseOrderId()).stream().filter(s -> s.id().equals(before.row().id())).findFirst().orElse(null);
                if (slot == null) return "De oorspronkelijke voorschottermijn bestaat niet meer.";
                if (slot.invoiceId() != null) return "Voor deze voorschottermijn is inmiddels een andere factuur gemaakt.";
                if (!same(slot.amountEur(), before.row().amountEur()) || !same(slot.percentage(), before.row().percentage())
                        || !Objects.equals(slot.dueDate(), before.row().dueDate()) || !Objects.equals(slot.label(), before.row().label()))
                    return "De oorspronkelijke voorschottermijn is gewijzigd. Maak een nieuw concept.";
            }
            if (!before.settlements().equals(now.settlements())) return "Er is inmiddels een andere slotfactuur gemaakt of gewijzigd. Controleer de partnerafrekening.";
            if ((order.purpose() == SalesPurpose.PARTNER_SETTLEMENT) && !before.advances().equals(now.advances()))
                return "De voorschotfacturen zijn gewijzigd. Maak een nieuwe slotfactuur zodat ze correct verrekend worden.";
            if (!order.isInvoice() && orders.findAll().stream().anyMatch(o -> o.isPartnerAdvance() && Objects.equals(o.linkedPurchaseOrderId(), order.linkedPurchaseOrderId())))
                return "Er bestaat inmiddels een nieuwe voorschotafspraak voor deze container.";
        }
        return null;
    }

    private PartnerContext partnerContext(SalesOrder order) {
        var purchase = purchases.get(order.linkedPurchaseOrderId());
        BigDecimal total;
        try { total = PartnerAdvanceBasis.total(purchases.calculate(purchase)); }
        catch (BusinessRuleException incomplete) { total = null; }
        var siblings = orders.findAll().stream().filter(o -> !Objects.equals(o.id(), order.id())
                && Objects.equals(o.linkedPurchaseOrderId(), purchase.id())).toList();
        var advances = siblings.stream().filter(o -> o.isInvoice() && o.isPartnerAdvance()).sorted(Comparator.comparing(SalesOrder::id))
                .map(o -> new Claim(o.id(), sales.price(o).totals().total().stripTrailingZeros(), PartnerFinancingService.issued(o), PartnerFinancingService.live(o), o.customerId())).toList();
        var settlements = siblings.stream().filter(o -> (o.purpose() == SalesPurpose.PARTNER_SETTLEMENT)).map(SalesOrder::id).sorted().toList();
        return new PartnerContext(purchase.partnerCustomerId(), purchase.partnerCostPctOrDefault(), purchase.partnerSharePctOrDefault(),
                total, schedules.find(purchase.id()), schedules.forInvoice(order.id()), advances, settlements, containerLines(purchase));
    }

    private List<ContainerLine> containerLines(PurchaseOrder purchase) {
        Map<Long, be.enrosed.sourcing.domain.LandedCost.Line> costs;
        try { costs = purchases.calculate(purchase).lines().stream().collect(java.util.stream.Collectors.toMap(
                be.enrosed.sourcing.domain.LandedCost.Line::productId, java.util.function.Function.identity(), (a, b) -> a)); }
        catch (BusinessRuleException incomplete) { costs = Map.of(); }
        var calculated = costs;
        return purchase.lines().stream().sorted(Comparator.comparing(line -> Objects.toString(line.productId(), "")))
                .map(line -> {
                    var cost = calculated.get(line.productId());
                    return new ContainerLine(line.productId(), line.quantity(), line.ordered(), line.damaged(),
                            cost == null ? null : cost.landedUnitEur().stripTrailingZeros(),
                            cost == null ? null : cost.totalEur().stripTrailingZeros());
                }).toList();
    }

    private Snapshot salesSnapshot(SalesOrder order) {
        var fields = new ArrayList<Field>();
        field(fields, "Documentdatum", order.orderDate()); field(fields, "Vervaldatum", order.invoiceDueDate());
        field(fields, "Geldig tot", order.validUntil()); field(fields, "Bestemming", order.countryCode());
        field(fields, "Leveringsconditie", order.incoterm()); field(fields, "Betaalvoorwaarden", order.paymentTerms());
        field(fields, "Bericht van klant", order.customerMessage()); field(fields, "Interne notitie", order.internalNotes());
        field(fields, "Gearchiveerd op", order.archivedAt());
        if (order.customerId() != null) {
            try {
                var customer = customers.get(order.customerId());
                field(fields, "Contact", customer.contact()); field(fields, "E-mail", customer.email());
                field(fields, "Adres", String.join(" ", Objects.toString(customer.address(), ""), Objects.toString(customer.postalCode(), ""), Objects.toString(customer.city(), "")).strip());
                field(fields, "Btw-nummer", customer.vatNumber());
            } catch (NotFoundException ignored) { /* Older broken drafts still need a safe way out of the working list. */ }
        }
        var lines = new ArrayList<Line>();
        BigDecimal total = null;
        try {
            var priced = sales.price(order);
            priced.lines().forEach(line -> lines.add(new Line(line.description(), line.sku(), BigDecimal.valueOf(line.quantity()), "stuks", line.netUnitPrice(), line.net())));
            priced.extraLines().forEach(line -> lines.add(new Line(line.description(), null, line.quantity(), null, line.unitPrice(), line.total())));
            total = priced.totals().totalInclVat();
            field(fields, "Subtotaal excl. btw (EUR)", priced.totals().total());
            field(fields, "Verzending en verwerking (EUR)", priced.totals().shippingTotal());
            field(fields, "Btw (EUR)", priced.totals().vatAmount());
            field(fields, "Totaal incl. btw (EUR)", total);
        } catch (BusinessRuleException | NotFoundException incomplete) {
            if (order.lines() != null) for (var line : order.lines()) {
                String description = "Product " + line.productId(); String sku = null;
                try { var p = products.get(line.productId()); description = p.name(); sku = p.sku(); } catch (NotFoundException ignored) {}
                lines.add(new Line(description, sku, BigDecimal.valueOf(line.quantity()), "stuks", line.unitPriceEur(), null));
            }
            order.extraLines().forEach(line -> lines.add(new Line(line.description(), null, line.quantity(), null, line.unitPriceEur(), null)));
            field(fields, "Bedrag", "Nog niet volledig berekend op het moment van verwijderen");
        }
        advanceContents.find(order).ifPresent(cargo -> cargo.lines().forEach(line ->
                lines.add(new Line(line.productName(), line.sku(), BigDecimal.valueOf(line.quantity()), "stuks (containerinhoud)", null, null))));
        return new Snapshot(List.copyOf(fields), List.copyOf(lines), order.notes(), List.of(), total);
    }

    private Summary summary(DeletedItemEntity row, String reason) {
        var snapshot = read(row.snapshotJson, Snapshot.class);
        return new Summary(row.id, row.type, row.sourceId, row.number, row.partyName, row.status, row.deletedAt,
                row.expiresAt, row.deletedBy, snapshot.totalEur(), reason == null, reason);
    }
    private String customerName(Long id) {
        if (id == null) return "Klant nog niet gekozen";
        try { return customers.get(id).company(); } catch (NotFoundException ignored) { return "Klant " + id; }
    }
    private void mark(String table, long id, Instant at) {
        entities.flush();
        String predicate = at == null ? "deleted_at is not null" : "deleted_at is null";
        int changed = entities.createNativeQuery("update " + table + " set deleted_at = :at where id = :id and " + predicate)
                .setParameter("at", at).setParameter("id", id).executeUpdate();
        if (changed != 1) throw missing();
        entities.clear();
    }
    private void lock(String table, long id) {
        if (entities.createNativeQuery("select id from " + table + " where id = :id for update").setParameter("id", id).getResultList().isEmpty()) throw missing();
    }
    private boolean exists(String table, long id) {
        return !entities.createNativeQuery("select id from " + table + " where id = :id").setParameter("id", id).getResultList().isEmpty();
    }
    private DeletedItemEntity visible(long id) {
        var row = entities.find(DeletedItemEntity.class, id);
        if (row == null) throw missing();
        requireVisible(row); return row;
    }
    private void requireVisible(DeletedItemEntity row) { if (!row.expiresAt.isAfter(Instant.now())) throw missing(); }
    private NotFoundException missing() { return new NotFoundException("Verwijderd item", "niet meer beschikbaar"); }
    private int retention() { return Math.max(1, retentionDays); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("Document kon niet veilig bewaard worden", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException("Bewaard document kon niet gelezen worden", e); }
    }
    private static void field(List<Field> fields, String label, Object value) {
        if (value != null && !value.toString().isBlank()) fields.add(new Field(label, value.toString()));
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static boolean sameAgreement(PartnerAdvanceSchedules.Agreement a, PartnerAdvanceSchedules.Agreement b) {
        return a == null ? b == null : b != null && Objects.equals(a.partnerCustomerId(), b.partnerCustomerId())
                && same(a.financingPct(), b.financingPct()) && same(a.agreedAmountEur(), b.agreedAmountEur())
                && same(a.financingBasisEur(), b.financingBasisEur()) && a.financingBasis() == b.financingBasis();
    }
}
