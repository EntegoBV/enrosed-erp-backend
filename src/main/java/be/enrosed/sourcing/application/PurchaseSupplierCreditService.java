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
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Reason;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * "Tegoed leverancier": money the supplier owes on a container after a short
 * delivery, damage or a wrong price, and how it comes back.
 *
 * A credit lowers the container's supplier cost from the day it is noted
 * (see {@link PurchaseReconciliationCalculator}); settling it only says where
 * the money went. Refunded means it came back to the bank. Offset means it
 * paid part of another container of the same supplier: that becomes an
 * ordinary supplier payment on the other container, so its own terms close,
 * and deleting that payment there puts the credit back to open here.
 */
@ApplicationScoped
public class PurchaseSupplierCreditService {

    static final String OFFSET_IS_UNDONE_ELSEWHERE =
            "Een verrekening maak je ongedaan door de betaling op de andere container te verwijderen";
    static final String SAME_SUPPLIER_ONLY =
            "Verrekenen kan alleen met een andere container van dezelfde leverancier";
    /* Its own zone, not the receipt feature's constant, so each of the two can be reverted alone. */
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private final PurchaseOrderService purchaseOrders;
    private final SourcingRepositories.PurchaseOrders orders;
    private final SourcingRepositories.SupplierCredits credits;
    @Inject
    Instance<CurrentActor> actor;
    @Inject
    Instance<ActivityLogService> activity;

    public PurchaseSupplierCreditService(PurchaseOrderService purchaseOrders,
                                         SourcingRepositories.PurchaseOrders orders,
                                         SourcingRepositories.SupplierCredits credits) {
        this.purchaseOrders = purchaseOrders;
        this.orders = orders;
        this.credits = credits;
    }

    /** A new credit: amount in the agreed currency; an explicit euro value is the actual one. */
    public record CreditRequest(LocalDate notedOn, BigDecimal amount, Currency currency, BigDecimal amountEur,
                                Reason reason, String note) {}

    /** A change: every field is optional; a null keeps what is stored, an empty note clears it. */
    public record CreditChange(BigDecimal amount, Currency currency, BigDecimal amountEur, Reason reason,
                               String note, Status status, LocalDate settledOn) {}

    /** Offset onto another container of the same supplier, as a supplier payment there. */
    public record OffsetRequest(Long targetOrderId, LocalDate paidOn, PaymentTerms.Moment instalmentDue) {}

    /** One credit as the container view lists it. */
    public record CreditView(Long id, LocalDate notedOn, BigDecimal amount, Currency currency, BigDecimal amountEur,
                             Reason reason, String note, Status status, LocalDate settledOn, Long offsetOrderId,
                             String offsetOrderNumber, Long offsetPaymentId, Instant recordedAt, String actor) {}

    /** On the container that took an offset: which payment is a credit from which other container. */
    public record CreditOffsetView(Long creditId, long sourceOrderId, String sourceOrderNumber, Long paymentId,
                                   BigDecimal amountEur) {}

    public List<CreditView> views(long orderId) {
        return credits.forOrder(orderId).stream().map(credit -> new CreditView(credit.id(), credit.notedOn(),
                credit.amount(), credit.currency(), credit.amountEur(), credit.reason(), credit.note(),
                credit.status(), credit.settledOn(), credit.offsetOrderId(),
                credit.offsetOrderId() == null ? null : number(credit.offsetOrderId()),
                credit.offsetPaymentId(), credit.recordedAt(), credit.actor())).toList();
    }

    public List<CreditOffsetView> offsetsOnto(long orderId) {
        return credits.offsetOnto(orderId).stream().map(credit -> new CreditOffsetView(credit.id(),
                credit.orderId(), number(credit.orderId()), credit.offsetPaymentId(), credit.amountEur())).toList();
    }

    @Transactional
    public PurchaseSupplierCredit add(long orderId, CreditRequest request) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        if (request == null) throw new BusinessRuleException("Geef een bedrag groter dan nul op");
        BigDecimal amount = positiveAmount(request.amount());
        Currency currency = request.currency() == null ? Currency.EUR : request.currency();
        if (request.reason() == null) throw new BusinessRuleException("Kies de reden van het tegoed");
        BigDecimal eur = request.amountEur() != null ? bankEuro(request.amountEur(), currency, amount)
                : orderRateEuro(order, amount, currency);
        requireWithinAgreement(order, null, eur);
        PurchaseSupplierCredit saved = credits.save(new PurchaseSupplierCredit(null, orderId,
                request.notedOn() == null ? LocalDate.now(BRUSSELS) : request.notedOn(),
                amount, currency, eur, request.reason(), PurchaseOrderService.cleanIssueNote(request.note()),
                Status.OPEN, null, null, null, currentActor().displayName(), Instant.now()));
        saveNotes(order, PurchaseOrderService.appendNote(order.notes(), notedNoteLine(saved)));
        ActivityChangeSet changes = ActivityChangeSet.create()
                .add("credit.amount", "Bedrag", null, saved.amount())
                .add("credit.currency", "Valuta", null, saved.currency())
                .add("credit.amountEur", "In euro", null, saved.amountEur())
                .add("credit.reason", "Reden", null, saved.reason().dutchLabel())
                .add("credit.notedOn", "Genoteerd op", null, saved.notedOn());
        record(order, "Tegoed leverancier genoteerd",
                changes.privateValue("credit.note", "Notitie", null, saved.note()));
        return saved;
    }

    @Transactional
    public PurchaseSupplierCredit update(long orderId, long creditId, CreditChange change) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        PurchaseSupplierCredit before = find(orderId, creditId);
        if (change == null) return before;
        Status target = change.status() == null ? before.status() : change.status();
        BigDecimal amount = change.amount() == null ? before.amount() : positiveAmount(change.amount());
        Currency currency = change.currency() == null ? before.currency() : change.currency();
        Reason reason = change.reason() == null ? before.reason() : change.reason();
        String note = change.note() == null ? before.note() : PurchaseOrderService.cleanIssueNote(change.note());
        boolean termsChanged = amount.compareTo(before.amount()) != 0 || currency != before.currency()
                || reason != before.reason() || !Objects.equals(note, before.note());

        if (before.status() == Status.OFFSET || target == Status.OFFSET) {
            /* Offsetting is its own flow, and only deleting the payment on the other container undoes it. */
            boolean anything = termsChanged || before.status() != target
                    || change.amountEur() != null && change.amountEur().compareTo(before.amountEur()) != 0
                    || change.settledOn() != null && !change.settledOn().equals(before.settledOn());
            if (anything) throw new BusinessRuleException(OFFSET_IS_UNDONE_ELSEWHERE);
            return before;
        }
        if (termsChanged && !before.isOpen()) {
            throw new BusinessRuleException("Een verrekend of terugbetaald tegoed pas je niet meer aan; zet het eerst terug op open");
        }
        BigDecimal eur = change.amountEur() != null ? bankEuro(change.amountEur(), currency, amount)
                : amount.compareTo(before.amount()) == 0 && currency == before.currency() ? before.amountEur()
                : orderRateEuro(order, amount, currency);
        LocalDate settledOn = null;
        if (target == Status.REFUNDED) {
            settledOn = change.settledOn() != null ? change.settledOn()
                    : before.status() == Status.REFUNDED ? before.settledOn() : null;
            if (settledOn == null) throw new BusinessRuleException("Geef de datum van de terugbetaling op");
        }
        /*
         * The cap guards what is owed, not what the bank paid back: a refund that only
         * records its actual euro (the rate moved) is the bank's truth and is never refused.
         */
        boolean refundEuroOnly = target == Status.REFUNDED
                && amount.compareTo(before.amount()) == 0 && currency == before.currency();
        if (!refundEuroOnly && eur.compareTo(before.amountEur()) != 0) requireWithinAgreement(order, before.id(), eur);

        PurchaseSupplierCredit wanted = before.with(amount, currency, eur, reason, note, target, settledOn, null, null);
        if (wanted.equals(before)) return before;
        PurchaseSupplierCredit after = credits.save(wanted);

        String notes = PurchaseOrderService.replaceNoteLine(order.notes(), notedNoteLine(before), notedNoteLine(after));
        if (before.status() == Status.REFUNDED) notes = PurchaseOrderService.removeNoteLine(notes, refundNoteLine(before));
        if (after.status() == Status.REFUNDED) notes = PurchaseOrderService.appendNote(notes, refundNoteLine(after));
        saveNotes(order, notes);
        String summary = before.status() == Status.OPEN && after.status() == Status.REFUNDED
                ? "Tegoed leverancier terugbetaald"
                : before.status() == Status.REFUNDED && after.status() == Status.OPEN
                ? "Terugbetaling van het tegoed ongedaan gemaakt" : "Tegoed leverancier gewijzigd";
        ActivityChangeSet changes = ActivityChangeSet.create()
                .add("credit.amount", "Bedrag", before.amount(), after.amount())
                .add("credit.currency", "Valuta", before.currency(), after.currency())
                .add("credit.amountEur", "In euro", before.amountEur(), after.amountEur())
                .add("credit.reason", "Reden", before.reason().dutchLabel(), after.reason().dutchLabel())
                .add("credit.status", "Tegoed leverancier", before.status().dutchLabel(), after.status().dutchLabel())
                .add("credit.settledOn", "Terugbetaald op", before.settledOn(), after.settledOn());
        record(order, summary, changes.privateValue("credit.note", "Notitie", before.note(), after.note()));
        return after;
    }

    @Transactional
    public void delete(long orderId, long creditId) {
        PurchaseOrder order = purchaseOrders.getForUpdate(orderId);
        PurchaseSupplierCredit credit = find(orderId, creditId);
        if (!credit.isOpen()) throw new BusinessRuleException("Alleen een open tegoed kan verwijderd worden");
        if (!credits.delete(orderId, creditId)) throw new NotFoundException("Tegoed", creditId);
        saveNotes(order, PurchaseOrderService.removeNoteLine(order.notes(), notedNoteLine(credit)));
        record(order, "Tegoed leverancier verwijderd", ActivityChangeSet.create()
                .add("credit.amount", "Bedrag", credit.amount(), null)
                .add("credit.currency", "Valuta", credit.currency(), null)
                .add("credit.amountEur", "In euro", credit.amountEur(), null)
                .add("credit.reason", "Reden", credit.reason().dutchLabel(), null));
    }

    /**
     * Pays part of another container of the same supplier with this credit:
     * a supplier payment there in the credit's amount and currency, at that
     * container's own rate so its term closes exactly, in one transaction
     * with the credit turning "Verrekend".
     */
    @Transactional
    public PurchaseSupplierCredit offset(long orderId, long creditId, OffsetRequest request) {
        Long targetId = request == null ? null : request.targetOrderId();
        /* Both containers are locked in id order, so two offsets in opposite directions cannot deadlock. */
        if (targetId != null && targetId < orderId) purchaseOrders.getForUpdate(targetId);
        PurchaseOrder source = purchaseOrders.getForUpdate(orderId);
        PurchaseSupplierCredit credit = find(orderId, creditId);
        if (!credit.isOpen()) throw new BusinessRuleException("Alleen een open tegoed kan verrekend worden");
        if (targetId == null || targetId == orderId) throw new BusinessRuleException(SAME_SUPPLIER_ONLY);
        PurchaseOrder target = purchaseOrders.getForUpdate(targetId);
        if (!Objects.equals(target.supplierId(), source.supplierId()) || target.status() == PurchaseOrderStatus.CONCEPT) {
            throw new BusinessRuleException(SAME_SUPPLIER_ONLY);
        }
        LocalDate day = request.paidOn() == null ? LocalDate.now(BRUSSELS) : request.paidOn();
        PurchasePayment payment = purchaseOrders.addPayment(targetId, day, credit.amount(), credit.currency(),
                "Verrekend tegoed " + source.number(), PurchasePayment.Payee.SUPPLIER, false,
                request.instalmentDue(), null);
        PurchaseSupplierCredit offset = credits.save(credit.with(credit.amount(), credit.currency(), credit.amountEur(),
                credit.reason(), credit.note(), Status.OFFSET, day, targetId, payment.id()));
        saveNotes(source, PurchaseOrderService.appendNote(source.notes(), offsetNoteLine(offset, target.number())));
        record(source, "Tegoed leverancier verrekend met " + target.number(), ActivityChangeSet.create()
                .add("credit.status", "Tegoed leverancier", credit.status().dutchLabel(), offset.status().dutchLabel())
                .add("credit.offsetOrder", "Verrekend met", null, target.number())
                .add("credit.settledOn", "Verrekend op", null, offset.settledOn()));
        return offset;
    }

    /* ---- diary lines: rebuilt from the credit, so a change can find and rewrite its own line ---- */

    static String notedNoteLine(PurchaseSupplierCredit credit) {
        return "Tegoed leverancier genoteerd " + credit.notedOn().format(PurchaseOrderService.DAY) + ": "
                + money(credit) + " · " + credit.reason().dutchLabel().toLowerCase(Locale.ROOT) + ".";
    }

    static String refundNoteLine(PurchaseSupplierCredit credit) {
        return "Tegoed leverancier terugbetaald " + credit.settledOn().format(PurchaseOrderService.DAY) + ": "
                + money(credit) + ".";
    }

    static String offsetNoteLine(PurchaseSupplierCredit credit, String targetNumber) {
        return "Tegoed leverancier verrekend met " + targetNumber + ": " + money(credit) + ".";
    }

    private static String money(PurchaseSupplierCredit credit) {
        return PurchaseOrderService.describeMoney(credit.amount(), credit.currency())
                + (credit.currency() != Currency.EUR
                        ? " (≈ " + PurchaseOrderService.describeMoney(credit.amountEur(), Currency.EUR) + ")" : "");
    }

    /* ---- rules ---- */

    private static BigDecimal positiveAmount(BigDecimal amount) {
        BigDecimal rounded = amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
        if (rounded == null || rounded.signum() <= 0) throw new BusinessRuleException("Geef een bedrag groter dan nul op");
        return rounded;
    }

    private static BigDecimal bankEuro(BigDecimal amountEur, Currency currency, BigDecimal amount) {
        return positiveEuro(PurchaseOrderService.requireBankAmount(amountEur, currency, amount));
    }

    private static BigDecimal orderRateEuro(PurchaseOrder order, BigDecimal amount, Currency currency) {
        return positiveEuro(PurchaseOrderService.euroAtOrderRate(order, amount, currency));
    }

    /** A rate missing on the order cannot turn a credit into nothing. */
    private static BigDecimal positiveEuro(BigDecimal eur) {
        BigDecimal rounded = eur.setScale(2, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0) throw new BusinessRuleException("Geef een eurobedrag groter dan nul op");
        return rounded;
    }

    /** All credits of an order together never exceed what was agreed with the supplier. */
    private void requireWithinAgreement(PurchaseOrder order, Long replacing, BigDecimal eur) {
        BigDecimal others = credits.forOrder(order.id()).stream()
                .filter(credit -> replacing == null || !replacing.equals(credit.id()))
                .map(PurchaseSupplierCredit::amountEur).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal agreement = purchaseOrders.reconciliation(order, purchaseOrders.calculate(order)).streams().stream()
                .filter(stream -> stream.payee() == PurchasePayment.Payee.SUPPLIER)
                .map(PurchaseReconciliation.Stream::plannedEur).findFirst().orElse(BigDecimal.ZERO);
        if (others.add(eur).compareTo(agreement) > 0) {
            throw new BusinessRuleException("Het tegoed kan niet hoger zijn dan de afspraak met de leverancier ("
                    + PurchaseOrderService.describeMoney(Money.money(agreement), Currency.EUR) + ")");
        }
    }

    private PurchaseSupplierCredit find(long orderId, long creditId) {
        return credits.find(orderId, creditId).orElseThrow(() -> new NotFoundException("Tegoed", creditId));
    }

    private String number(long orderId) {
        return orders.findById(orderId).map(PurchaseOrder::number).orElse(null);
    }

    private void saveNotes(PurchaseOrder order, String notes) {
        if (Objects.equals(notes, order.notes())) return;
        orders.save(order.withReceipt(order.status(), order.receivedOn(), order.paidTotalEur(), order.stockBooked(),
                notes, order.lines()));
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
