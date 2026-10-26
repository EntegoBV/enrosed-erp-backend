package be.enrosed.finance.invoices;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.application.CostPartyService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostParty;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.media.MediaRole;
import be.enrosed.media.MediaTargetType;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.IncomingPayments;
import be.enrosed.sales.application.PartnerFinancingService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentSign;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPayment;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.application.PurchaseInvoiceService;
import be.enrosed.sourcing.application.PurchaseInvoiceService.InvoiceFileView;
import be.enrosed.sourcing.application.PurchaseInvoiceService.PurchaseInvoiceView;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseInvoice;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * The ENROSED side of the finder: every container invoice and invoice file, company cost and issued sales document as
 * finder rows, and what hides or marks an Odoo move. Each read loads its sources once (never per row or per order) and
 * writes nothing. Data owned elsewhere that has no public read is read here with JPQL.
 */
@ApplicationScoped
public class ErpInvoiceRows {
    static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");
    private static final int CHUNK = 1000;
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** What hides or marks an Odoo move; a short read that runs before the Odoo call. Empty maps when namespace is null. */
    public record Dedup(String namespace, Map<Long, Long> registered /* moveId → costId (cost exists) */, Set<Long> removed,
                        Set<Long> ticaMoves, Map<Long, Long> ticaSettlements /* only moves with a settlement id */,
                        Map<Long, Long> outbox /* odooMoveId → salesOrderId */, Map<Long, OdooDocumentMarks.Mark> marks,
                        Map<Long, InvoiceRow.ContainerRef> markContainers /* CONTAINER marks of live orders */) {
        public static Dedup empty(String namespace) {
            return new Dedup(namespace, Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * Every row of the direction, unfiltered, with the match index when asked and each row's search text by key
     * (number, party, payee word, label, kind words, container, description, notes, category, file names, aliases).
     */
    public record Built(List<InvoiceRow> rows, InvoiceMatch match, Map<String, String> searchText) {
        public Built(List<InvoiceRow> rows, InvoiceMatch match) {
            this(rows, match, Map.of());
        }
    }

    @Inject EntityManager em;
    @Inject ObjectMapper json;
    @Inject PurchaseOrderService purchaseOrders;
    @Inject SupplierService suppliers;
    @Inject PurchaseInvoiceService purchaseInvoices;
    @Inject SourcingRepositories.Payments purchasePayments;
    @Inject CompanyCostService costs;
    @Inject CostPartyService costParties;
    @Inject SalesOrderService sales;
    @Inject IncomingPayments incomingPayments;
    @Inject CustomerService customers;
    @Inject OdooDocumentMarks marks;

    // ------------------------------------------------------------------------------------------------------------ dedup

    @Transactional
    public Dedup dedup(String namespace /* null when NOT_CONNECTED */) {
        if (namespace == null || namespace.isBlank()) return Dedup.empty(null);
        List<Object[]> bindings = em.createQuery("select i.moveId, i.companyCostId from OdooCostImportEntity i"
                        + " where i.namespace = :ns and i.companyCostId is not null", Object[].class)
                .setParameter("ns", namespace).getResultList();
        Set<Long> existing = existingCosts(bindings.stream().map(row -> (Long) row[1]).toList());
        Map<Long, Long> registered = new HashMap<>();
        Set<Long> removed = new HashSet<>();
        for (Object[] row : bindings) {
            long moveId = (Long) row[0];
            Long costId = (Long) row[1];
            if (existing.contains(costId)) registered.put(moveId, costId);
            else removed.add(moveId);
        }
        Set<Long> ticaMoves = new HashSet<>();
        Map<Long, Long> ticaSettlements = new HashMap<>();
        for (Object[] row : em.createQuery("select t.moveId, t.settlementId from OdooTicaDocumentEntity t"
                + " where t.sourceNamespace = :ns", Object[].class).setParameter("ns", namespace).getResultList()) {
            long moveId = (Long) row[0];
            ticaMoves.add(moveId);
            if (row[1] != null) ticaSettlements.put(moveId, (Long) row[1]);
        }
        Map<Long, Long> outbox = new HashMap<>();
        for (Object[] row : em.createQuery("select o.odooMoveId, o.salesOrderId from OdooOutboxEntity o"
                + " where o.odooMoveId is not null and o.sourceNamespace = :ns", Object[].class)
                .setParameter("ns", namespace).getResultList())
            outbox.put((Long) row[0], (Long) row[1]);
        Map<Long, OdooDocumentMarks.Mark> all = marks.all(namespace);
        List<Long> orderIds = all.values().stream().filter(mark -> OdooDocumentMarks.CONTAINER.equals(mark.resolution()))
                .map(OdooDocumentMarks.Mark::purchaseOrderId).filter(Objects::nonNull).distinct().toList();
        Map<Long, PurchaseOrderName> names = orderIds.isEmpty() ? Map.of() : purchaseOrders.names(orderIds);
        Map<Long, InvoiceRow.ContainerRef> markContainers = new HashMap<>();
        for (OdooDocumentMarks.Mark mark : all.values()) {
            if (!OdooDocumentMarks.CONTAINER.equals(mark.resolution()) || mark.purchaseOrderId() == null) continue;
            PurchaseOrderName name = names.get(mark.purchaseOrderId());
            if (name != null) markContainers.put(mark.moveId(), new InvoiceRow.ContainerRef(name.id(), name.number(), name.alias()));
        }
        return new Dedup(namespace, Map.copyOf(registered), Set.copyOf(removed), Set.copyOf(ticaMoves),
                Map.copyOf(ticaSettlements), Map.copyOf(outbox), all, Map.copyOf(markContainers));
    }

    private Set<Long> existingCosts(Collection<Long> ids) {
        Set<Long> found = new HashSet<>();
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < wanted.size(); start += CHUNK) {
            found.addAll(em.createQuery("select c.id from CompanyCostEntity c where c.id in :ids", Long.class)
                    .setParameter("ids", wanted.subList(start, Math.min(wanted.size(), start + CHUNK))).getResultList());
        }
        return found;
    }

    /** Whether a live container exists: a CONTAINER mark may only point at one. */
    @Transactional
    public InvoiceRow.ContainerRef liveContainer(long orderId) {
        PurchaseOrderName name = purchaseOrders.names(List.of(orderId)).get(orderId);
        return name == null ? null : new InvoiceRow.ContainerRef(name.id(), name.number(), name.alias());
    }

    // ------------------------------------------------------------------------------------------------------------ build

    @Transactional
    public Built build(String direction, boolean withMatch) {
        LocalDate today = LocalDate.now(BRUSSELS);
        return "OUT".equals(direction) ? buildOut(withMatch, today) : buildIn(withMatch, today);
    }

    private Built buildIn(boolean withMatch, LocalDate today) {
        List<InvoiceRow> rows = new ArrayList<>();
        Map<String, String> search = new HashMap<>();

        // Containers: live orders only (trashed orders are absent from list(); archived ones stay).
        Map<Long, PurchaseOrder> live = new LinkedHashMap<>();
        for (PurchaseOrder order : purchaseOrders.list()) if (order.id() != null) live.put(order.id(), order);
        Map<Long, String> supplierNames = new HashMap<>();
        for (Supplier supplier : suppliers.list()) if (supplier.id() != null) supplierNames.put(supplier.id(), supplier.name());
        List<PurchaseInvoice> invoices = purchaseInvoices.all();
        List<PurchaseDocument> documents = purchaseInvoices.invoiceDocuments(invoices);
        List<PurchasePayment> payments = purchasePayments.since(null);
        Map<Long, List<PurchaseInvoice>> invoicesByOrder = group(invoices, PurchaseInvoice::orderId, live.keySet());
        Map<Long, List<PurchaseDocument>> documentsByOrder = group(documents, PurchaseDocument::orderId, live.keySet());
        Map<Long, List<PurchasePayment>> paymentsByOrder = group(payments, PurchasePayment::orderId, live.keySet());
        Map<Long, PurchaseDocument> documentById = new HashMap<>();
        for (PurchaseDocument document : documents) if (document.id() != null) documentById.putIfAbsent(document.id(), document);
        Map<Long, PurchasePayment> paymentById = new HashMap<>();
        for (PurchasePayment payment : payments) if (payment.id() != null) paymentById.put(payment.id(), payment);

        record OrderViews(PurchaseOrder order, List<PurchaseInvoiceView> views, List<InvoiceFileView> files) {}
        List<OrderViews> orderViews = new ArrayList<>();
        Set<Long> lineIds = new HashSet<>();
        for (PurchaseOrder order : live.values()) {
            List<PurchaseInvoice> ofOrder = invoicesByOrder.getOrDefault(order.id(), List.of());
            List<PurchasePayment> paid = paymentsByOrder.getOrDefault(order.id(), List.of());
            List<PurchaseDocument> docs = documentsByOrder.getOrDefault(order.id(), List.of());
            List<PurchaseInvoiceView> views = PurchaseInvoiceService.views(order, ofOrder, paid, docs);
            List<InvoiceFileView> files = PurchaseInvoiceService.files(ofOrder, paid, docs);
            if (views.isEmpty() && files.isEmpty()) continue;
            orderViews.add(new OrderViews(order, views, files));
            for (PurchaseInvoiceView view : views)
                for (Long paymentId : view.paymentIds()) {
                    PurchasePayment payment = paymentById.get(paymentId);
                    if (payment != null && payment.bankStatementLineId() != null) lineIds.add(payment.bankStatementLineId());
                }
            for (InvoiceFileView file : files) {
                PurchasePayment payment = file.paymentId() == null ? null : paymentById.get(file.paymentId());
                if (payment != null && payment.bankStatementLineId() != null) lineIds.add(payment.bankStatementLineId());
            }
        }

        // Costs, with their payments, parties, files and Odoo bindings.
        List<CompanyCost> costList = costs.list(null, null);
        List<CompanyCostService.CostView> costViews = costs.views(costList);
        for (CompanyCostService.CostView view : costViews) {
            if (view.cost().bankStatementLineId() != null) lineIds.add(view.cost().bankStatementLineId());
            for (CostPayment payment : view.payments())
                if (payment.bankStatementLineId() != null) lineIds.add(payment.bankStatementLineId());
        }
        Map<Long, BankLine> lines = bankLinesById(lineIds);
        Map<String, CostParty> partyByName = costPartyIndex(costParties.list());
        Map<Long, List<MediaFile>> mediaByCost = costMedia();
        Map<Long, Binding> bindings = costBindings();

        List<InvoiceMatch.ContainerInvoiceRef> matchInvoices = new ArrayList<>();
        for (OrderViews entry : orderViews) {
            PurchaseOrder order = entry.order();
            InvoiceRow.ContainerRef container = new InvoiceRow.ContainerRef(order.id(), order.number(), order.alias());
            String supplierName = order.supplierId() == null ? null : supplierNames.get(order.supplierId());
            for (PurchaseInvoiceView view : entry.views()) {
                List<PurchasePayment> ofView = view.paymentIds().stream().map(paymentById::get).filter(Objects::nonNull).toList();
                PurchaseDocument document = view.documentId() == null ? null : documentById.get(view.documentId());
                InvoiceRow row = containerInvoiceRow(order, container, supplierName, view, ofView, document, lines);
                rows.add(row);
                search.put(row.key(), searchText(row, view.notes()));
                if ((view.currency() == null || view.currency() == Currency.EUR) && view.id() != null)
                    matchInvoices.add(new InvoiceMatch.ContainerInvoiceRef(view.id(), view.number(), row.amountEur()));
            }
            for (InvoiceFileView file : entry.files()) {
                PurchaseDocument document = documentById.get(file.documentId());
                PurchasePayment payment = file.paymentId() == null ? null : paymentById.get(file.paymentId());
                InvoiceRow row = containerFileRow(order, container, supplierName, file, document, payment, lines);
                rows.add(row);
                search.put(row.key(), searchText(row, file.label()));
            }
        }

        List<InvoiceMatch.CostRef> matchCosts = new ArrayList<>();
        for (CompanyCostService.CostView view : costViews) {
            CompanyCost cost = view.cost();
            if (cost.id() == null) continue;
            CostParty party = cost.party() == null ? null : partyByName.get(InvoiceSearchText.normalize(cost.party()));
            InvoiceRow row = costRow(view, party, mediaByCost.getOrDefault(cost.id(), List.of()), bindings.get(cost.id()),
                    lines, today);
            rows.add(row);
            List<String> extra = new ArrayList<>();
            extra.add(cost.notes());
            if (party != null) {
                extra.add(party.name());
                if (party.aliases() != null) extra.addAll(party.aliases());
            }
            for (MediaFile file : mediaByCost.getOrDefault(cost.id(), List.of())) extra.add(file.originalFilename());
            search.put(row.key(), searchText(row, extra.toArray(String[]::new)));
            matchCosts.add(new InvoiceMatch.CostRef(cost.id(), view.cashTotalEur() != null && view.cashTotalEur().signum() < 0,
                    cost.reference(), row.amount()));
        }
        InvoiceMatch match = withMatch ? InvoiceMatch.of(matchCosts, matchInvoices, List.of()) : null;
        return new Built(List.copyOf(rows), match, Map.copyOf(search));
    }

    private InvoiceRow containerInvoiceRow(PurchaseOrder order, InvoiceRow.ContainerRef container, String supplierName,
                                           PurchaseInvoiceView view, List<PurchasePayment> ofView, PurchaseDocument document,
                                           Map<Long, BankLine> lines) {
        BigDecimal amount = view.amount() == null ? null : Money.money(view.amount());
        boolean euro = view.currency() == null || view.currency() == Currency.EUR;
        BigDecimal amountEur = view.amountEur() != null ? Money.money(view.amountEur()) : euro ? amount : null;
        BigDecimal paidEur = Money.money(view.paidEur());
        String status = containerInvoiceStatus(view, ofView);
        BigDecimal openEur;
        if ("PAID".equals(status)) openEur = euro ? Money.money(BigDecimal.ZERO) : null;
        else openEur = amountEur == null ? null : amountEur.subtract(paidEur).max(BigDecimal.ZERO).setScale(2);
        LocalDate date = view.invoiceDate() != null ? view.invoiceDate() : day(view.recordedAt());
        List<InvoiceRow.Link> links = new ArrayList<>();
        links.add(containerLink(container));
        Set<Long> seen = new LinkedHashSet<>();
        for (PurchasePayment payment : ofView) if (payment.bankStatementLineId() != null) seen.add(payment.bankStatementLineId());
        for (Long lineId : seen) addTransaction(links, lines.get(lineId));
        List<InvoiceRow.FileRef> files = document == null ? List.of() : List.of(purchaseFile(document));
        return new InvoiceRow("pi:" + view.id(), InvoiceRow.CONTAINER_INVOICE, "IN", "INVOICE",
                docLabel(document, view.payee()), view.number(), date, date,
                view.invoiceDate() != null ? "INVOICE" : "RECORDED", null, amount,
                view.currency() == null ? "EUR" : view.currency().name(), amountEur, paidEur, openEur,
                party(view.payee(), order.supplierId(), supplierName), view.payee().name(), null, null, null, status, null,
                files.isEmpty() ? "MANUAL" : "UPLOAD", null, null, null, container, List.copyOf(links), files, files.isEmpty());
    }

    private InvoiceRow containerFileRow(PurchaseOrder order, InvoiceRow.ContainerRef container, String supplierName, InvoiceFileView file,
                                        PurchaseDocument document, PurchasePayment payment, Map<Long, BankLine> lines) {
        LocalDate date = document == null ? null : day(document.addedAt());
        List<InvoiceRow.Link> links = new ArrayList<>();
        links.add(containerLink(container));
        if (payment != null && payment.bankStatementLineId() != null) addTransaction(links, lines.get(payment.bankStatementLineId()));
        List<InvoiceRow.FileRef> files = document == null ? List.of() : List.of(purchaseFile(document));
        String label = document != null ? docLabel(document, file.payee())
                : file.kind() == null ? "Factuur" : file.kind().dutchLabel();
        return new InvoiceRow("pd:" + file.documentId(), InvoiceRow.CONTAINER_FILE, "IN",
                file.kind() == PurchaseDocument.Kind.PROFORMA_INVOICE ? "PROFORMA" : "INVOICE", label, null,
                date, date, "UPLOADED", null, null, null, null, null, null,
                party(file.payee(), order.supplierId(), supplierName),
                file.payee().name(), null, null, null, "NO_DATA", null, "UPLOAD", null, null, null, container,
                List.copyOf(links), files, false);
    }

    private InvoiceRow costRow(CompanyCostService.CostView view, CostParty party, List<MediaFile> media, Binding binding,
                               Map<Long, BankLine> lines, LocalDate today) {
        CompanyCost cost = view.cost();
        BigDecimal cash = Money.money(view.cashTotalEur());
        BigDecimal paid = Money.money(view.paidEur()).abs();
        BigDecimal open = Money.money(view.openEur()).abs();
        String status;
        if (open.signum() == 0) status = view.ticaSettlementId() != null ? "SETTLED" : "PAID";
        else if (cost.dueDate() != null && cost.dueDate().isBefore(today)) status = "OVERDUE";
        else if (paid.signum() != 0) status = "PARTIAL";
        else status = "OPEN";
        List<InvoiceRow.Link> links = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        if (cost.bankStatementLineId() != null) seen.add(cost.bankStatementLineId());
        for (CostPayment payment : view.payments()) if (payment.bankStatementLineId() != null) seen.add(payment.bankStatementLineId());
        for (Long lineId : seen) addTransaction(links, lines.get(lineId));
        if (view.ticaSettlementId() != null) links.add(new InvoiceRow.Link("TICA", view.ticaSettlementId(), "TICA", null, null));
        List<InvoiceRow.FileRef> files = media.stream().map(MediaFile::ref).toList();
        String origin = binding != null ? "ODOO" : !files.isEmpty() ? "UPLOAD" : "MANUAL";
        LocalDate date = cost.date() != null ? cost.date() : day(cost.createdAt());
        String partyName = blankToNull(cost.party());
        return new InvoiceRow("cost:" + cost.id(), InvoiceRow.COST, "IN", cash.signum() < 0 ? "CREDIT_NOTE" : "INVOICE", null,
                blankToNull(cost.reference()), date, date, "INVOICE", cost.dueDate(), cash.abs(), "EUR", cash.abs(), paid, open,
                new InvoiceRow.Party(partyName, "COST_PARTY", party == null ? null : party.id()), null,
                blankToNull(cost.description()), blankToNull(cost.category()), null, status, null, origin,
                binding == null ? null : binding.peppolState(), binding == null ? null : binding.moveId(), null, null,
                List.copyOf(links), files, files.isEmpty() && cost.recurringCostId() == null);
    }

    private Built buildOut(boolean withMatch, LocalDate today) {
        List<SalesOrder> issued = sales.list().stream()
                .filter(order -> order.id() != null && order.isClaimDocument() && PartnerFinancingService.issued(order))
                .toList();
        List<PricedOrder> priced = sales.priceAll(issued);
        Map<Long, BigDecimal> received = new HashMap<>();
        for (SalesPayment payment : incomingPayments.all())
            received.merge(payment.salesOrderId(), Money.nz(payment.amountEur()), BigDecimal::add);
        Map<Long, Customer> customerById = new HashMap<>();
        for (Customer customer : customers.list()) if (customer.id() != null) customerById.put(customer.id(), customer);
        List<Long> containerIds = issued.stream().map(SalesOrder::linkedPurchaseOrderId).filter(Objects::nonNull).distinct().toList();
        Map<Long, PurchaseOrderName> containers = containerIds.isEmpty() ? Map.of() : sales.containerNames(containerIds);
        Map<Long, Object[]> outbox = new HashMap<>();
        for (Object[] row : em.createQuery("select o.salesOrderId, o.odooMoveId, o.peppolState from OdooOutboxEntity o",
                Object[].class).getResultList())
            outbox.put((Long) row[0], row);
        Map<Long, List<BankLine>> linesByOrder = bankLinesBySalesOrder(issued.stream().map(SalesOrder::id).toList());

        List<InvoiceRow> rows = new ArrayList<>();
        Map<String, String> search = new HashMap<>();
        List<InvoiceMatch.SalesRef> matchSales = new ArrayList<>();
        for (int i = 0; i < issued.size(); i++) {
            SalesOrder order = issued.get(i);
            PricedOrder price = priced.get(i);
            BigDecimal claim = DocumentSign.claim(order, price);
            BigDecimal got = Money.money(received.getOrDefault(order.id(), BigDecimal.ZERO));
            BigDecimal amount = Money.money(price.totals().totalInclVat()).abs();
            boolean credit = order.isCreditNote();
            PurchaseOrderName name = order.linkedPurchaseOrderId() == null ? null : containers.get(order.linkedPurchaseOrderId());
            InvoiceRow.ContainerRef container = name == null ? null : new InvoiceRow.ContainerRef(name.id(), name.number(), name.alias());
            List<InvoiceRow.Link> links = new ArrayList<>();
            if (container != null) links.add(containerLink(container));
            for (BankLine line : linesByOrder.getOrDefault(order.id(), List.of())) addTransaction(links, line);
            Object[] sent = outbox.get(order.id());
            Customer customer = order.customerId() == null ? null : customerById.get(order.customerId());
            String number = blankToNull(order.number());
            List<InvoiceRow.FileRef> files = List.of(new InvoiceRow.FileRef("SALES_PDF", order.id(), null, null,
                    (number == null ? "verkoopdocument-" + order.id() : number) + ".pdf", "application/pdf", "PDF", null, false));
            InvoiceRow row = new InvoiceRow("so:" + order.id(), InvoiceRow.SALES, "OUT", credit ? "CREDIT_NOTE" : "INVOICE", null,
                    number, order.orderDate(), order.orderDate(), "INVOICE", order.invoiceDueDate(), amount, "EUR", amount,
                    got.abs(), credit ? null : claim.abs().subtract(got.abs()).max(BigDecimal.ZERO).setScale(2),
                    new InvoiceRow.Party(customer == null ? null : blankToNull(customer.company()), "CUSTOMER", order.customerId()),
                    null, null, null, order.purpose() == null ? null : order.purpose().name(),
                    salesStatus(order, claim, got, today), null, "ENROSED", sent == null ? null : (String) sent[2],
                    sent == null ? null : (Long) sent[1], null, container, List.copyOf(links), files, false);
            rows.add(row);
            search.put(row.key(), searchText(row, credit ? "creditnota" : "verkoopfactuur"));
            matchSales.add(new InvoiceMatch.SalesRef(order.id(), credit, order.number()));
        }
        InvoiceMatch match = withMatch ? InvoiceMatch.of(List.of(), List.of(), matchSales) : null;
        return new Built(List.copyOf(rows), match, Map.copyOf(search));
    }

    // ------------------------------------------------------------------------------------------------------- statuses

    /**
     * PAID when the amount is positive and a payment of the invoice settles its whole group, or the payments in the
     * invoice's own currency reach its amount (within a cent); euro values never decide PAID. Else PARTIAL when euro was
     * paid, else OPEN.
     */
    static String containerInvoiceStatus(PurchaseInvoiceView view, List<PurchasePayment> paymentsOfView) {
        List<PurchasePayment> paid = paymentsOfView == null ? List.of() : paymentsOfView;
        BigDecimal amount = view.amount();
        if (amount != null && amount.signum() > 0) {
            if (paid.stream().anyMatch(PurchasePayment::settlesWholeGroup)) return "PAID";
            Currency currency = view.currency() == null ? Currency.EUR : view.currency();
            BigDecimal inCurrency = paid.stream()
                    .filter(payment -> (payment.currency() == null ? Currency.EUR : payment.currency()) == currency)
                    .map(PurchasePayment::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (inCurrency.compareTo(amount.subtract(CENT)) >= 0) return "PAID";
        }
        if (view.paidEur() != null && view.paidEur().signum() > 0) return "PARTIAL";
        return "OPEN";
    }

    /** The rule of IncomingPaymentService.summary on batched payments; a credit note reads as a negative claim. */
    static String salesStatus(SalesOrder order, BigDecimal claim, BigDecimal received, LocalDate today) {
        BigDecimal total = Money.nz(claim);
        BigDecimal got = Money.nz(received);
        if (total.signum() < 0) return total.negate().add(got).signum() > 0 ? "OPEN" : "SETTLED";
        if (order.status() == QuoteStatus.BETAALD || got.compareTo(total) >= 0) return "PAID";
        if (order.invoiceDueDate() != null && today != null && order.invoiceDueDate().isBefore(today)) return "OVERDUE";
        if (got.signum() > 0) return "PARTIAL";
        return "OPEN";
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    private static <T> Map<Long, List<T>> group(List<T> rows, Function<T, Long> orderOf, Set<Long> live) {
        Map<Long, List<T>> grouped = new HashMap<>();
        for (T row : rows) {
            Long orderId = orderOf.apply(row);
            if (orderId != null && live.contains(orderId)) grouped.computeIfAbsent(orderId, id -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static InvoiceRow.Party party(PurchasePayment.Payee payee, Long supplierId, String supplierName) {
        if (payee == PurchasePayment.Payee.SUPPLIER) return new InvoiceRow.Party(supplierName, "SUPPLIER", supplierId);
        return new InvoiceRow.Party(payee.dutchLabel(), "PAYEE", null);
    }

    /** The held document's kind label (OTHER: its own label); without a document the payee decides. */
    static String docLabel(PurchaseDocument document, PurchasePayment.Payee payee) {
        if (document == null) return payee == PurchasePayment.Payee.SUPPLIER ? "Commercial invoice" : "Factuur";
        if (document.kind() == PurchaseDocument.Kind.OTHER) {
            String label = document.label() == null ? "" : document.label().strip();
            return label.isEmpty() ? "Factuur" : label;
        }
        return document.kind() == null ? "Factuur" : document.kind().dutchLabel();
    }

    private static InvoiceRow.FileRef purchaseFile(PurchaseDocument document) {
        String name = document.originalFilename() == null ? "document-" + document.id() : document.originalFilename();
        return new InvoiceRow.FileRef("PURCHASE_DOCUMENT", document.id(), document.orderId(), null, name,
                document.contentType(), fileKind(name, document.contentType()), document.sizeBytes(), false);
    }

    /** PDF, XML, IMAGE or FILE by content type and name. */
    static String fileKind(String name, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (type.equals("application/pdf") || lower.endsWith(".pdf")) return "PDF";
        // Office files carry "xml" in their content type but are no XML invoice.
        if (type.contains("openxmlformats") || type.contains("officedocument")) return "FILE";
        if (EmbeddedPdf.looksLikeXml(name, contentType, null)) return "XML";
        if (type.startsWith("image/")) return "IMAGE";
        return "FILE";
    }

    private static InvoiceRow.Link containerLink(InvoiceRow.ContainerRef container) {
        return new InvoiceRow.Link("CONTAINER", container.id(), container.label(), null, null);
    }

    private static void addTransaction(List<InvoiceRow.Link> links, BankLine line) {
        if (line == null) return;
        links.add(new InvoiceRow.Link("TRANSACTION", line.id(), line.day() == null ? "" : line.day().format(DAY_MONTH),
                line.day(), line.account()));
    }

    static LocalDate day(Instant instant) {
        return instant == null ? null : instant.atZone(BRUSSELS).toLocalDate();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A row's search text: what the contract's haystack lists, plus the extra words the row itself does not carry. */
    static String searchText(InvoiceRow row, String... extra) {
        StringBuilder text = new StringBuilder();
        append(text, row.number());
        if (row.party() != null) append(text, row.party().name());
        if (row.payee() != null) {
            try {
                append(text, PurchasePayment.Payee.valueOf(row.payee()).dutchLabel());
            } catch (IllegalArgumentException ignored) {
                // not a payee word
            }
        }
        append(text, row.docLabel());
        append(text, switch (row.kind() == null ? "" : row.kind()) {
            case "CREDIT_NOTE" -> "creditnota";
            case "PROFORMA" -> "proforma";
            default -> "factuur";
        });
        if (row.container() != null) {
            append(text, row.container().number());
            append(text, row.container().alias());
        }
        append(text, row.description());
        append(text, row.category());
        for (InvoiceRow.FileRef file : row.files()) append(text, file.name());
        if (extra != null) for (String word : extra) append(text, word);
        return text.toString();
    }

    private static void append(StringBuilder text, String value) {
        if (value == null || value.isBlank()) return;
        if (!text.isEmpty()) text.append(' ');
        text.append(value);
    }

    private static Map<String, CostParty> costPartyIndex(List<CostParty> parties) {
        Map<String, CostParty> index = new HashMap<>();
        List<CostParty> sorted = parties.stream().filter(party -> party.id() != null)
                .sorted(Comparator.comparingLong(CostParty::id)).toList();
        for (CostParty party : sorted) index.putIfAbsent(InvoiceSearchText.normalize(party.name()), party);
        for (CostParty party : sorted)
            if (party.aliases() != null)
                for (String alias : party.aliases()) index.putIfAbsent(InvoiceSearchText.normalize(alias), party);
        index.remove("");
        return index;
    }

    /** One file of a cost, from the media library. */
    record MediaFile(long costId, InvoiceRow.FileRef ref, String originalFilename, boolean invoiceRole, Instant linkedAt) {}

    /** Every file linked to a company cost (archived assets included): role INVOICE first, then by link time. */
    private Map<Long, List<MediaFile>> costMedia() {
        List<Object[]> rows = em.createQuery("select l.targetId, l.role, l.createdAt, a.id, a.name, a.archived,"
                        + " v.originalFilename, v.contentType, v.sizeBytes"
                        + " from MediaLinkEntity l, MediaAssetEntity a, MediaVersionEntity v"
                        + " where l.targetType = :type and a.id = l.assetId and v.id = a.currentVersionId", Object[].class)
                .setParameter("type", MediaTargetType.COMPANY_COST).getResultList();
        Map<Long, Map<Long, MediaFile>> byCost = new HashMap<>();
        for (Object[] row : rows) {
            long costId = (Long) row[0];
            long assetId = (Long) row[3];
            String assetName = (String) row[4];
            String original = (String) row[6];
            String name = assetName == null || assetName.isBlank() ? original : assetName;
            String contentType = (String) row[7];
            InvoiceRow.FileRef ref = new InvoiceRow.FileRef("MEDIA_ASSET", assetId, null, null, name, contentType,
                    fileKind(name, contentType).equals("FILE") ? fileKind(original, contentType) : fileKind(name, contentType),
                    (Long) row[8], Boolean.TRUE.equals(row[5]));
            MediaFile file = new MediaFile(costId, ref, original, row[1] == MediaRole.INVOICE, (Instant) row[2]);
            // One asset may be linked twice (two roles): keep it once, with its INVOICE link when it has one.
            byCost.computeIfAbsent(costId, id -> new LinkedHashMap<>()).merge(assetId, file,
                    (left, right) -> left.invoiceRole() || !right.invoiceRole() ? left : right);
        }
        Comparator<MediaFile> order = Comparator.comparing((MediaFile file) -> !file.invoiceRole())
                .thenComparing(MediaFile::linkedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(file -> file.ref().id());
        Map<Long, List<MediaFile>> result = new HashMap<>();
        byCost.forEach((costId, files) -> result.put(costId, files.values().stream().sorted(order).toList()));
        return result;
    }

    /** The Odoo source of a registered cost. */
    record Binding(long moveId, String peppolState) {}

    /** Costs registered from an Odoo move (a cost has at most one binding). */
    private Map<Long, Binding> costBindings() {
        Map<Long, Binding> bindings = new HashMap<>();
        for (Object[] row : em.createQuery("select i.companyCostId, i.moveId, i.snapshotJson from OdooCostImportEntity i"
                + " where i.companyCostId is not null", Object[].class).getResultList()) {
            bindings.putIfAbsent((Long) row[0], new Binding((Long) row[1], peppolState((String) row[2])));
        }
        return bindings;
    }

    private String peppolState(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode state = json.readTree(snapshotJson).path("document").path("peppolState");
            return state.isTextual() && !state.asText().isBlank() ? state.asText() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** A bank line as a link label needs it. */
    record BankLine(long id, LocalDate day, String account) {}

    private Map<Long, BankLine> bankLinesById(Collection<Long> ids) {
        Map<Long, BankLine> lines = new HashMap<>();
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        for (int start = 0; start < wanted.size(); start += CHUNK) {
            for (Object[] row : em.createQuery("select b.id, b.bookedAt, b.account from BankStatementLineEntity b where b.id in :ids",
                    Object[].class).setParameter("ids", wanted.subList(start, Math.min(wanted.size(), start + CHUNK))).getResultList())
                lines.put((Long) row[0], new BankLine((Long) row[0], day((Instant) row[1]), (String) row[2]));
        }
        return lines;
    }

    private Map<Long, List<BankLine>> bankLinesBySalesOrder(List<Long> orderIds) {
        Map<Long, List<BankLine>> lines = new HashMap<>();
        List<Long> wanted = orderIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        for (int start = 0; start < wanted.size(); start += CHUNK) {
            for (Object[] row : em.createQuery("select b.salesOrderId, b.id, b.bookedAt, b.account from BankStatementLineEntity b"
                            + " where b.salesOrderId in :ids order by b.bookedAt, b.id", Object[].class)
                    .setParameter("ids", wanted.subList(start, Math.min(wanted.size(), start + CHUNK))).getResultList())
                lines.computeIfAbsent((Long) row[0], id -> new ArrayList<>())
                        .add(new BankLine((Long) row[1], day((Instant) row[2]), (String) row[3]));
        }
        return lines;
    }
}
