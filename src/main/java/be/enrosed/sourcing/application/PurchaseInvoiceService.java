package be.enrosed.sourcing.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseInvoice;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The invoices of a container: the supplier's commercial invoice that every
 * payment term points at, and the invoices of the other payees (inspection,
 * transport, other costs). Number, date and amount live here; the file is a
 * purchase document the invoice points at.
 *
 * Writes lock the order first and leave an activity record; the container's
 * diary is not touched. Invoices never become a company cost, never go to
 * Odoo and never enter the VAT return: the container's costing does that.
 */
@ApplicationScoped
public class PurchaseInvoiceService {

    static final int MAX_INVOICES = 20;
    /** The labels the payment sheet gave invoice files before invoices had data of their own. */
    static final Set<String> LEGACY_INVOICE_LABELS = Set.of("Factuur", "Factuur inspectie", "Factuur transport");

    private final PurchaseOrderService purchaseOrders;
    private final SourcingRepositories.Invoices invoices;
    private final SourcingRepositories.Payments payments;
    private final SourcingRepositories.Documents documents;
    @Inject
    Instance<CurrentActor> actor;
    @Inject
    Instance<ActivityLogService> activity;

    public PurchaseInvoiceService(PurchaseOrderService purchaseOrders, SourcingRepositories.Invoices invoices,
                                  SourcingRepositories.Payments payments, SourcingRepositories.Documents documents) {
        this.purchaseOrders = purchaseOrders;
        this.invoices = invoices;
        this.payments = payments;
        this.documents = documents;
    }

    /** What the screen sends; the file is uploaded first and named by its document id. */
    public record InvoiceRequest(PurchasePayment.Payee payee, String number, LocalDate invoiceDate, BigDecimal amount,
                                 Currency currency, BigDecimal amountEur, Long documentId, String notes) {}

    /**
     * One invoice as the screens show it, with the payments that belong to it
     * (named or automatic, ascending) and what they paid in euro.
     */
    public record PurchaseInvoiceView(Long id, long orderId, PurchasePayment.Payee payee, String payeeLabel,
                                      String number, LocalDate invoiceDate, BigDecimal amount, Currency currency,
                                      BigDecimal amountEur, Long documentId, String documentName, String notes,
                                      String actor, Instant recordedAt,
                                      /** The payee's first registered invoice. */
                                      boolean primary,
                                      /** The supplier's primary invoice also covers the inspection paid through it. */
                                      boolean coversInspection,
                                      List<Long> paymentIds, BigDecimal paidEur) {}

    /**
     * An invoice file from before invoices had data: a commercial invoice or
     * an invoice document that no invoice holds yet, to fill in with one tap.
     */
    public record InvoiceFileView(Long documentId, PurchaseDocument.Kind kind, String label, String originalFilename,
                                  Long paymentId, PurchasePayment.Payee payee) {}

    /** Every invoice of every container, for a list read once. */
    public List<PurchaseInvoice> all() {
        return invoices.all();
    }

    /** Every document that can be an invoice file, on every container, in one read. */
    public List<PurchaseDocument> invoiceDocuments() {
        return invoiceDocuments(List.of());
    }

    /**
     * As above, plus the files these invoices hold whatever their kind (an
     * invoice may point at a document filed as another kind), so the list
     * names every invoice's file as the single container does.
     */
    public List<PurchaseDocument> invoiceDocuments(List<PurchaseInvoice> invoicesOfAll) {
        List<PurchaseDocument> found = new ArrayList<>(documents.ofKinds(List.of(PurchaseDocument.Kind.COMMERCIAL_INVOICE,
                PurchaseDocument.Kind.INVOICE, PurchaseDocument.Kind.OTHER)));
        Set<Long> have = found.stream().map(PurchaseDocument::id).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> missing = invoicesOfAll == null ? List.of() : invoicesOfAll.stream().map(PurchaseInvoice::documentId)
                .filter(Objects::nonNull).filter(id -> !have.contains(id)).distinct().toList();
        if (!missing.isEmpty()) found.addAll(documents.byIds(missing));
        return found;
    }

    public List<PurchaseInvoiceView> list(long orderId) {
        PurchaseOrder order = purchaseOrders.get(orderId);
        return views(order, invoices.forOrder(orderId), payments.forOrder(orderId), documents.forOrder(orderId));
    }

    @Transactional
    public PurchaseInvoiceView add(long orderId, InvoiceRequest request) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        List<PurchaseInvoice> before = invoices.forOrder(orderId);
        PurchaseInvoice checked = checked(order, null, request, before, null);
        if (before.size() >= MAX_INVOICES) {
            throw new BusinessRuleException("Per container horen hoogstens twintig facturen");
        }
        List<PurchasePayment> recorded = payments.forOrder(orderId);
        /* A second invoice of the payee adds up: the payments that pointed at the first one automatically stay there. */
        pinToOnlyInvoice(order, checked.payee(), before, recorded);
        PurchaseInvoice saved = invoices.save(checked);
        record(order, "Factuur " + saved.number() + " toegevoegd", ActivityChangeSet.create()
                .add("invoice.number", "Factuurnummer", null, saved.number())
                .add("invoice.amount", "Factuurbedrag", null, saved.amount())
                .add("invoice.payee", "Bij wie", null, saved.payee().dutchLabel()));
        return view(order, saved);
    }

    @Transactional
    public PurchaseInvoiceView update(long orderId, long invoiceId, InvoiceRequest request) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        List<PurchaseInvoice> before = invoices.forOrder(orderId);
        PurchaseInvoice current = before.stream().filter(invoice -> Objects.equals(invoice.id(), invoiceId))
                .findFirst().orElseThrow(() -> new NotFoundException("Factuur", invoiceId));
        PurchaseInvoice checked = checked(order, current, request, before, current);
        List<PurchasePayment> recorded = payments.forOrder(orderId);
        if (checked.payee() != current.payee()) {
            if (recorded.stream().anyMatch(payment -> Objects.equals(payment.invoiceId(), invoiceId))) {
                throw new BusinessRuleException("Er zijn betalingen aan deze factuur gekoppeld; kies bij die betalingen eerst een andere factuur");
            }
            /* Moved to another payee that had one invoice: its payments stay with that one. */
            pinToOnlyInvoice(order, checked.payee(), before.stream()
                    .filter(invoice -> !Objects.equals(invoice.id(), invoiceId)).toList(), recorded);
        }
        PurchaseInvoice saved = invoices.save(checked);
        record(order, "Factuur " + saved.number() + " gewijzigd", ActivityChangeSet.create()
                .add("invoice.number", "Factuurnummer", current.number(), saved.number())
                .add("invoice.date", "Factuurdatum", current.invoiceDate(), saved.invoiceDate())
                .add("invoice.amount", "Factuurbedrag", current.amount(), saved.amount())
                .add("invoice.currency", "Valuta", current.currency(), saved.currency())
                .add("invoice.amountEur", "In euro", current.amountEur(), saved.amountEur())
                .add("invoice.payee", "Bij wie", current.payee().dutchLabel(), saved.payee().dutchLabel())
                .add("invoice.document", "Bestand", current.documentId(), saved.documentId())
                .privateValue("invoice.notes", "Notitie", current.notes(), saved.notes()));
        return view(order, saved);
    }

    /**
     * "Toewijzen aan alle betalingen": every payment of the invoice's payee
     * names this invoice, each transfer of a payment in parts included, in
     * one write. Column-only, like the pin of a second invoice: no diary line,
     * no bank lock, so a payment already on the bank takes it too. Answers
     * with every invoice of the container, as their payments moved.
     */
    @Transactional
    public List<PurchaseInvoiceView> assignToAll(long orderId, long invoiceId) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        PurchaseInvoice invoice = invoices.find(orderId, invoiceId)
                .orElseThrow(() -> new NotFoundException("Factuur", invoiceId));
        assignAll(order, invoice);
        return views(order, invoices.forOrder(orderId), payments.forOrder(orderId), documents.forOrder(orderId));
    }

    private void assignAll(PurchaseOrder order, PurchaseInvoice invoice) {
        List<PurchaseInvoice> all = invoices.forOrder(order.id());
        List<PurchasePayment> recorded = payments.forOrder(order.id());
        List<Long> moved = recorded.stream()
                .filter(payment -> (payment.payee() == null ? PurchasePayment.Payee.SUPPLIER : payment.payee()) == invoice.payee())
                .filter(payment -> !invoice.id().equals(payment.invoiceId()))
                .map(PurchasePayment::id).filter(Objects::nonNull).toList();
        if (moved.isEmpty()) return;
        boolean via = order.inspectionPaidViaSupplier();
        long before = recorded.stream()
                .filter(payment -> invoice.id().equals(InvoiceAssignment.resolve(payment, all, recorded, via))).count();
        payments.setInvoice(moved, invoice.id());
        List<PurchasePayment> after = payments.forOrder(order.id());
        long now = after.stream()
                .filter(payment -> invoice.id().equals(InvoiceAssignment.resolve(payment, all, after, via))).count();
        record(order, "Factuur " + invoice.number() + " toegewezen aan alle betalingen van "
                + invoice.payee().dutchLabel(), ActivityChangeSet.create()
                .add("invoice.payments", "Betalingen bij factuur " + invoice.number(), before, now));
    }

    /**
     * A new invoice with its file, in one step: the data is checked before
     * the file is kept, the file goes to the container (never to a single
     * payment) and the invoice holds it. With {@code assignAll} every payment
     * of the payee names the invoice ("voor alle deelbetalingen").
     */
    @Transactional
    public PurchaseInvoiceView addWithFile(long orderId, InvoiceRequest request, String filename, String contentType,
                                           byte[] bytes, boolean assignAll) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        List<PurchaseInvoice> before = invoices.forOrder(orderId);
        InvoiceRequest data = request == null ? null : new InvoiceRequest(request.payee(), request.number(),
                request.invoiceDate(), request.amount(), request.currency(), request.amountEur(), null, request.notes());
        checked(order, null, data, before, null);
        if (before.size() >= MAX_INVOICES) {
            throw new BusinessRuleException("Per container horen hoogstens twintig facturen");
        }
        PurchaseDocument file = purchaseOrders.addDocument(orderId, fileKind(data.payee()), null, null, filename,
                contentType, bytes);
        PurchaseInvoiceView added = add(orderId, new InvoiceRequest(data.payee(), data.number(), data.invoiceDate(),
                data.amount(), data.currency(), data.amountEur(), file.id(), data.notes()));
        if (assignAll) {
            assignAll(order, invoices.find(orderId, added.id()).orElseThrow(() -> new NotFoundException("Factuur", added.id())));
            return view(order, invoices.find(orderId, added.id()).orElseThrow());
        }
        return added;
    }

    /**
     * The file of an invoice that has none yet, uploaded to the invoice: the
     * file goes to the container (never to a single payment) and the invoice
     * holds it. An invoice that already holds a file keeps it: remove that
     * one first. With {@code assignAll} every payment of the payee names the
     * invoice.
     */
    @Transactional
    public PurchaseInvoiceView attachFile(long orderId, long invoiceId, String filename, String contentType,
                                          byte[] bytes, boolean assignAll) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        PurchaseInvoice current = invoices.find(orderId, invoiceId)
                .orElseThrow(() -> new NotFoundException("Factuur", invoiceId));
        if (current.documentId() != null) {
            String held = documents.find(orderId, current.documentId()).map(PurchaseDocument::originalFilename).orElse(null);
            throw new BusinessRuleException("Bij factuur " + current.number() + " hoort al een bestand"
                    + (held == null ? "" : " (" + held + ")") + "; verwijder dat eerst");
        }
        PurchaseDocument file = purchaseOrders.addDocument(orderId, fileKind(current.payee()), null, null, filename,
                contentType, bytes);
        PurchaseInvoice saved = invoices.save(new PurchaseInvoice(current.id(), current.orderId(), current.payee(),
                current.number(), current.invoiceDate(), current.amount(), current.currency(), current.amountEur(),
                file.id(), current.notes(), current.actor(), current.recordedAt()));
        record(order, "Bestand bij factuur " + saved.number() + " toegevoegd", ActivityChangeSet.create()
                .privateValue("invoice.document", "Bestand", null, file.originalFilename()));
        if (assignAll) assignAll(order, saved);
        return view(order, saved);
    }

    /** The kind an invoice file is kept as: the supplier's commercial invoice, or an invoice of another payee. */
    static PurchaseDocument.Kind fileKind(PurchasePayment.Payee payee) {
        return payee == PurchasePayment.Payee.SUPPLIER ? PurchaseDocument.Kind.COMMERCIAL_INVOICE : PurchaseDocument.Kind.INVOICE;
    }

    /** The invoice goes; its payments go back to automatic, its file stays with the container. */
    @Transactional
    public void delete(long orderId, long invoiceId) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        PurchaseInvoice current = invoices.find(orderId, invoiceId)
                .orElseThrow(() -> new NotFoundException("Factuur", invoiceId));
        payments.clearInvoice(orderId, invoiceId);
        if (!invoices.delete(orderId, invoiceId)) throw new NotFoundException("Factuur", invoiceId);
        record(order, "Factuur " + current.number() + " verwijderd", ActivityChangeSet.create()
                .add("invoice.number", "Factuurnummer", current.number(), null)
                .add("invoice.amount", "Factuurbedrag", current.amount(), null)
                .add("invoice.payee", "Bij wie", current.payee().dutchLabel(), null));
    }

    /**
     * Pins the payments that today belong to the payee's only invoice
     * automatically, before a second invoice of that payee arrives: adding an
     * extra invoice never moves an existing payment. An inspection that
     * follows the supplier's primary invoice needs no pin, because the primary
     * is the first registered invoice and a later one never takes its place.
     */
    private void pinToOnlyInvoice(PurchaseOrder order, PurchasePayment.Payee payee, List<PurchaseInvoice> before,
                                  List<PurchasePayment> recorded) {
        List<PurchaseInvoice> own = before.stream().filter(invoice -> invoice.payee() == payee).toList();
        if (own.size() != 1) return;
        Long only = own.getFirst().id();
        List<Long> pinned = recorded.stream()
                .filter(payment -> payment.payee() == payee)
                .filter(payment -> payment.invoiceId() == null || before.stream()
                        .noneMatch(invoice -> payment.invoiceId().equals(invoice.id())))
                .filter(payment -> only.equals(InvoiceAssignment.resolve(payment, before, recorded,
                        order.inspectionPaidViaSupplier())))
                .map(PurchasePayment::id).toList();
        payments.setInvoice(pinned, only);
    }

    private PurchaseInvoice checked(PurchaseOrder order, PurchaseInvoice current, InvoiceRequest request,
                                    List<PurchaseInvoice> existing, PurchaseInvoice self) {
        if (request == null || request.payee() == null) throw new BusinessRuleException("Kies bij wie de factuur hoort");
        String number = request.number() == null ? "" : request.number().strip();
        if (number.isEmpty()) throw new BusinessRuleException("Vul het factuurnummer in");
        if (number.length() > 64) throw new BusinessRuleException("Het factuurnummer mag maximaal 64 tekens bevatten");
        if (request.invoiceDate() == null) throw new BusinessRuleException("Vul de factuurdatum in");
        if (request.invoiceDate().isAfter(LocalDate.now(PurchaseOrderService.BRUSSELS))) {
            throw new BusinessRuleException("De factuurdatum kan niet in de toekomst liggen");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BusinessRuleException("Geef een factuurbedrag groter dan nul op");
        }
        BigDecimal amount = cents(request.amount());
        Currency currency = request.currency() == null ? Currency.EUR : request.currency();
        if (currency == Currency.EUR && request.amountEur() != null && request.amountEur().compareTo(amount) != 0) {
            throw new BusinessRuleException("Voor een factuur in euro is het eurobedrag gelijk aan het bedrag");
        }
        if (request.amountEur() != null && request.amountEur().signum() <= 0) {
            throw new BusinessRuleException("Geef een eurobedrag groter dan nul op");
        }
        Long documentId = request.documentId();
        if (documentId != null) {
            if (documents.find(order.id(), documentId).isEmpty()) {
                throw new BusinessRuleException("Dit bestand hoort niet bij deze inkooporder");
            }
            PurchaseInvoice holder = existing.stream().filter(invoice -> documentId.equals(invoice.documentId())
                    && !Objects.equals(invoice.id(), self == null ? null : self.id())).findFirst().orElse(null);
            if (holder != null) throw new BusinessRuleException("Dit bestand hoort al bij factuur " + holder.number());
        }
        if (existing.stream().anyMatch(invoice -> invoice.payee() == request.payee()
                && !Objects.equals(invoice.id(), self == null ? null : self.id())
                && invoice.number() != null && invoice.number().strip().equalsIgnoreCase(number))) {
            throw new BusinessRuleException("Factuur " + number + " staat al bij deze container");
        }
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().strip();
        if (notes != null && notes.length() > 500) throw new BusinessRuleException("De notitie mag maximaal 500 tekens bevatten");
        BigDecimal amountEur;
        if (currency == Currency.EUR) {
            amountEur = amount;
        } else if (request.amountEur() != null) {
            amountEur = Money.money(request.amountEur());
        } else if (current != null && current.currency() == currency && current.amount() != null
                && current.amount().compareTo(amount) == 0 && current.amountEur() != null) {
            /* Unchanged money keeps its euro value, as a payment does. */
            amountEur = current.amountEur();
        } else {
            amountEur = Money.money(LotCostCalculator.atContainerRate(order, request.payee(), amount, currency));
            if (amountEur.signum() <= 0) {
                throw new BusinessRuleException("Vul de koers van de container in of geef het eurobedrag op");
            }
        }
        return new PurchaseInvoice(current == null ? null : current.id(), order.id(), request.payee(), number,
                request.invoiceDate(), amount, currency, amountEur, documentId, notes,
                current == null ? currentActor().displayName() : current.actor(),
                current == null ? Instant.now() : current.recordedAt());
    }

    private static BigDecimal cents(BigDecimal value) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalid) {
            throw new BusinessRuleException("Een factuurbedrag heeft maximaal twee decimalen");
        }
    }

    private PurchaseInvoiceView view(PurchaseOrder order, PurchaseInvoice invoice) {
        return views(order, invoices.forOrder(order.id()), payments.forOrder(order.id()), documents.forOrder(order.id()))
                .stream().filter(candidate -> Objects.equals(candidate.id(), invoice.id())).findFirst()
                .orElseThrow(() -> new NotFoundException("Factuur", invoice.id()));
    }

    /** Every invoice of one order with the payments that resolve to it; pure. */
    public static List<PurchaseInvoiceView> views(PurchaseOrder order, List<PurchaseInvoice> invoicesOfOrder,
                                                  List<PurchasePayment> paymentsOfOrder,
                                                  List<PurchaseDocument> documentsOfOrder) {
        if (invoicesOfOrder == null || invoicesOfOrder.isEmpty()) return List.of();
        List<PurchasePayment> recorded = paymentsOfOrder == null ? List.of() : paymentsOfOrder;
        boolean via = order != null && order.inspectionPaidViaSupplier();
        Map<Long, List<PurchasePayment>> byInvoice = new java.util.HashMap<>();
        for (PurchasePayment payment : recorded) {
            Long resolved = InvoiceAssignment.resolve(payment, invoicesOfOrder, recorded, via);
            if (resolved != null) byInvoice.computeIfAbsent(resolved, id -> new ArrayList<>()).add(payment);
        }
        Map<Long, String> names = documentsOfOrder == null ? Map.of() : documentsOfOrder.stream()
                .filter(document -> document.id() != null)
                .collect(Collectors.toMap(PurchaseDocument::id, PurchaseDocument::originalFilename, (left, right) -> left));
        List<PurchaseInvoiceView> views = new ArrayList<>();
        for (PurchaseInvoice invoice : invoicesOfOrder) {
            List<PurchasePayment> paid = byInvoice.getOrDefault(invoice.id(), List.of());
            views.add(new PurchaseInvoiceView(invoice.id(), invoice.orderId(), invoice.payee(),
                    invoice.payee().dutchLabel(), invoice.number(), invoice.invoiceDate(), invoice.amount(),
                    invoice.currency(), invoice.amountEur(), invoice.documentId(),
                    invoice.documentId() == null ? null : names.get(invoice.documentId()), invoice.notes(),
                    invoice.actor(), invoice.recordedAt(),
                    InvoiceAssignment.isPrimary(invoice, invoicesOfOrder),
                    InvoiceAssignment.coversInspection(invoice, invoicesOfOrder, via),
                    paid.stream().map(PurchasePayment::id).filter(Objects::nonNull).sorted().toList(),
                    Money.money(paid.stream().map(PurchasePayment::amountEur).filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))));
        }
        return views;
    }

    /**
     * The invoice files that no invoice holds yet: commercial invoices and
     * invoice documents, and the files the payment sheet once filed as
     * "Factuur", "Factuur inspectie" or "Factuur transport". Pure.
     */
    public static List<InvoiceFileView> files(List<PurchaseInvoice> invoicesOfOrder,
                                              List<PurchasePayment> paymentsOfOrder,
                                              List<PurchaseDocument> documentsOfOrder) {
        if (documentsOfOrder == null || documentsOfOrder.isEmpty()) return List.of();
        Set<Long> held = invoicesOfOrder == null ? Set.of() : invoicesOfOrder.stream()
                .map(PurchaseInvoice::documentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PurchasePayment.Payee> payees = paymentsOfOrder == null ? Map.of() : paymentsOfOrder.stream()
                .filter(payment -> payment.id() != null)
                .collect(Collectors.toMap(PurchasePayment::id, PurchasePayment::payee, (left, right) -> left));
        List<InvoiceFileView> files = new ArrayList<>();
        for (PurchaseDocument document : documentsOfOrder) {
            if (document.id() == null || held.contains(document.id()) || !isInvoiceFile(document)) continue;
            String label = document.label() == null ? null : document.label().strip();
            PurchasePayment.Payee payee;
            if (document.kind() == PurchaseDocument.Kind.COMMERCIAL_INVOICE) payee = PurchasePayment.Payee.SUPPLIER;
            else if ("Factuur inspectie".equals(label)) payee = PurchasePayment.Payee.SEPARATE;
            else if ("Factuur transport".equals(label)) payee = PurchasePayment.Payee.LOGISTICS;
            else payee = document.paymentId() == null ? PurchasePayment.Payee.OTHER
                        : payees.getOrDefault(document.paymentId(), PurchasePayment.Payee.OTHER);
            files.add(new InvoiceFileView(document.id(), document.kind(), document.label(), document.originalFilename(),
                    document.paymentId(), payee));
        }
        return files;
    }

    static boolean isInvoiceFile(PurchaseDocument document) {
        if (document.kind() == PurchaseDocument.Kind.COMMERCIAL_INVOICE || document.kind() == PurchaseDocument.Kind.INVOICE) {
            return true;
        }
        return document.kind() == PurchaseDocument.Kind.OTHER && document.label() != null
                && LEGACY_INVOICE_LABELS.contains(document.label().strip());
    }

    private ActorRef currentActor() {
        return actor != null && actor.isResolvable() ? actor.get().current() : ActorRef.SYSTEM;
    }

    private void record(PurchaseOrder order, String summary, ActivityChangeSet changes) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                order.id() == null ? null : order.id().toString(), order.number(), summary, changes.build());
    }
}
