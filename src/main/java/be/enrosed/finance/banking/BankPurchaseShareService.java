package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchasePayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Emre, 2026-10-16: one transfer paid the instalments of three containers, and some of them already had their
 * payment noted. "Verdelen over containers" shares one free outgoing line over its parts in one transaction: a part
 * is an open payment (it is linked, and follows the line: the same euro is a link, more makes the payment grow to the
 * part, less carves the part out with the rest left open in its group) or a container that gets a new payment. Every
 * part takes the line's day; together the parts make the line (two-cent rule); one link of the bank service carries
 * them all, so its direction, offset, two-cent and exclusive-set guards run unchanged. Any refusal undoes everything,
 * and an identical retry after a lost response answers the same rows without writing again.
 *
 * Lock order is the bank service's: the line, then every order (ascending id), then the payment rows (ascending id).
 * A USD/CNY payment is whole on its transfer, as {@link PurchasePaymentSplitService} links it: its euro becomes the
 * part, its own amount stays. A new part is in euro.
 */
@ApplicationScoped
public class BankPurchaseShareService {
    static final String SHARED_SUMMARY = "Betaling aan een gedeelde banktransactie gekoppeld";
    private static final Locale BELGIUM = Locale.forLanguageTag("nl-BE");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** The activity log keeps a summary of at most 500 characters. */
    private static final int SUMMARY_LIMIT = 500;

    @Inject EntityManager em;
    @Inject PurchaseOrderService purchases;
    @Inject BankStatementService bank;

    /**
     * One part of the transfer: exactly one of {@code paymentId} (an open payment, linked) or
     * {@code purchaseOrderId} (a container that gets a new payment). {@code amountEur} is the part in euro.
     * {@code expectedAmountEur} is the open payment's euro as the screen saw it (null: no check). The other fields
     * describe a new payment: payee (default the supplier), term, description, final payment, invoice (null:
     * automatic) and the inspection paid through the supplier; {@code amount}/{@code currency} may be left out and
     * must otherwise be the same euro. They are ignored for an open payment.
     */
    public record SharePart(Long paymentId, Long purchaseOrderId, BigDecimal amountEur, BigDecimal expectedAmountEur,
                            PurchasePayment.Payee payee, PaymentTerms.Moment instalmentDue, String label,
                            boolean settles, Long invoiceId, Boolean inspectionViaSupplier, BigDecimal amount,
                            Currency currency) {
        /** An open payment that takes this part of the line. */
        public static SharePart existing(long paymentId, BigDecimal amountEur, BigDecimal expectedAmountEur) {
            return new SharePart(paymentId, null, amountEur, expectedAmountEur, null, null, null, false, null, null,
                    null, null);
        }

        /** A new payment on a container for this part of the line. */
        public static SharePart created(long purchaseOrderId, BigDecimal amountEur, PurchasePayment.Payee payee,
                                        PaymentTerms.Moment instalmentDue, String label) {
            return new SharePart(null, purchaseOrderId, amountEur, null, payee, instalmentDue, label, false, null,
                    null, null, null);
        }
    }

    public record ShareRequest(List<SharePart> parts) {}

    /** What happened to a part: linked as it was (or with its day), grown to the part, carved out, or new. */
    public enum Outcome { LINKED, GREW, CARVED, CREATED }

    /**
     * One part as it went: {@code paymentId} is the row now on the line, {@code amountEur} its euro;
     * {@code wasEur}/{@code wasPaidOn} what an open payment was before (null for a new payment and on a retry);
     * {@code restPaymentId} the open rest of a carved payment.
     */
    public record PartResult(Long paymentId, long orderId, BigDecimal amountEur, Outcome outcome, BigDecimal wasEur,
                             LocalDate wasPaidOn, Long restPaymentId) {}

    /** The line as described now, the payment on the line per part (request order) and what happened per part. */
    public record ShareResult(BankStatementLineEntity line, List<PurchasePayment> payments, List<PartResult> parts) {}

    /** A checked part: the existing id or the order, the cents, and a new payment's normalised fields. */
    private record Part(Long paymentId, long orderId, BigDecimal amountEur, BigDecimal expectedAmountEur,
                        PurchasePayment.Payee payee, PaymentTerms.Moment instalmentDue, String label, boolean settles,
                        Long invoiceId, boolean inspectionViaSupplier) {
        boolean existing() {
            return paymentId != null;
        }

        Part withOrder(long order) {
            return new Part(paymentId, order, amountEur, expectedAmountEur, payee, instalmentDue, label, settles,
                    invoiceId, inspectionViaSupplier);
        }
    }

    @Transactional
    public ShareResult share(long lineId, ShareRequest request) {
        List<Part> parts = checked(request);
        /* The container of every open payment, read before any lock; the locks re-check it. */
        List<Part> resolved = new ArrayList<>();
        for (Part part : parts) resolved.add(part.existing() ? part.withOrder(orderOf(part.paymentId())) : part);
        parts = resolved;

        BankStatementLineEntity line = em.find(BankStatementLineEntity.class, lineId, LockModeType.PESSIMISTIC_WRITE);
        if (line == null) throw new NotFoundException("Bankbeweging", lineId);
        bank.requireUnclassified(line);
        Map<Long, String> names = new HashMap<>();
        for (Long orderId : new TreeSet<>(parts.stream().map(Part::orderId).toList())) {
            var order = em.find(SourcingEntities.PurchaseOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
            if (order == null) throw new NotFoundException("Inkooporder", orderId);
            names.put(orderId, PurchaseOrderName.display(order.id, order.number, order.alias));
        }
        Map<Long, SourcingEntities.PurchasePaymentEntity> payments = new HashMap<>();
        for (Long paymentId : new TreeSet<>(parts.stream().filter(Part::existing).map(Part::paymentId).toList())) {
            var payment = em.find(SourcingEntities.PurchasePaymentEntity.class, paymentId, LockModeType.PESSIMISTIC_WRITE);
            if (payment == null) throw new NotFoundException("Betaling", paymentId);
            payments.put(paymentId, payment);
        }
        for (Part part : parts) {
            if (part.existing() && payments.get(part.paymentId()).orderId != part.orderId()) {
                throw new BusinessRuleException("De betaling van " + names.get(part.orderId())
                        + " is intussen gewijzigd; vernieuw en probeer opnieuw");
            }
        }
        LocalDate day = line.bookedAt.atZone(ZoneId.of(line.timeZone)).toLocalDate();

        List<SourcingEntities.PurchasePaymentEntity> onLine = em.createQuery(
                        "from PurchasePaymentEntity p where p.bankStatementLineId=:id order by p.id",
                        SourcingEntities.PurchasePaymentEntity.class)
                .setParameter("id", line.id).getResultList();
        if (!onLine.isEmpty()) return retried(line, parts, payments, onLine, day);

        if (line.amountEur.signum() >= 0 || line.salesPaymentId != null || bank.describe(line).allocated
                || hasCostLink(line.id)) {
            throw new BusinessRuleException("Kies een vrije uitgaande banktransactie");
        }
        BigDecimal total = parts.stream().map(Part::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!BankAmountMatch.matches(line.amountEur, total.negate())) {
            throw new BusinessRuleException("Samen " + eur(total) + "; de transactie is " + eur(line.amountEur.negate())
                    + ". De delen moeten samen de transactie maken (maximaal € 0,02 verschil).");
        }
        for (Part part : parts) {
            if (!part.existing()) continue;
            var payment = payments.get(part.paymentId());
            String name = names.get(part.orderId());
            if (isOffset(payment.id)) throw new BusinessRuleException(name + ": een verrekend leverancierstegoed is geen bankbetaling");
            if (payment.bankStatementLineId != null) {
                throw new BusinessRuleException(name + ": deze betaling staat al op de bank; maak die koppeling eerst los");
            }
            if (payment.amountEur == null || payment.amountEur.signum() <= 0) {
                throw new BusinessRuleException("De richting van de inkoopbetaling komt niet overeen met de banktransactie");
            }
            if (part.expectedAmountEur() != null
                    && payment.amountEur.compareTo(part.expectedAmountEur().setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw new BusinessRuleException("De betaling van " + name + " is intussen gewijzigd; vernieuw en probeer opnieuw");
            }
        }

        /* Writes: the open payments first, then the new ones, both in request order (new ids ascend with it). */
        PartResult[] outcomes = new PartResult[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.existing()) outcomes[i] = write(part, payments.get(part.paymentId()), line.id, day);
        }
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.existing()) continue;
            PurchasePayment added = purchases.addPayment(part.orderId(), day, part.amountEur(), Currency.EUR,
                    part.label(), part.payee(), part.settles(), part.instalmentDue(), null, part.invoiceId(),
                    part.inspectionViaSupplier());
            outcomes[i] = new PartResult(added.id(), part.orderId(), added.amountEur(), Outcome.CREATED, null, null, null);
        }
        List<Long> ids = new ArrayList<>();
        for (PartResult outcome : outcomes) ids.add(outcome.paymentId());
        bank.linkExisting(line.id, new BankStatementService.OutgoingLinks(ids, null, null));

        record(line, parts, names);
        return new ShareResult(bank.describe(line), ids.stream().map(this::stored).toList(), List.of(outcomes));
    }

    /**
     * One open payment takes its part: within two cents or more it follows the line (its day, the part as its
     * euro); less carves the part out on the line's day and leaves the rest open in its group on the old day. A
     * USD/CNY payment always goes whole: its euro becomes the part, its own amount stays.
     */
    private PartResult write(Part part, SourcingEntities.PurchasePaymentEntity payment, long lineId, LocalDate day) {
        BigDecimal wasEur = payment.amountEur;
        LocalDate wasPaidOn = payment.paidOn;
        BigDecimal difference = part.amountEur().subtract(wasEur);
        boolean grew = difference.compareTo(BankAmountMatch.MAX_DIFFERENCE) > 0;
        boolean foreign = payment.currency != null && payment.currency != Currency.EUR;
        if (foreign || difference.compareTo(BankAmountMatch.MAX_DIFFERENCE.negate()) >= 0) {
            PurchasePayment before = toDomain(payment);
            purchases.followBankLine(part.orderId(), payment.id, day, part.amountEur(), SHARED_SUMMARY);
            purchases.recordBankAdjustment(before, lineId);
            return new PartResult(payment.id, part.orderId(), part.amountEur(), grew ? Outcome.GREW : Outcome.LINKED,
                    wasEur, wasPaidOn, null);
        }
        BigDecimal rest = wasEur.subtract(part.amountEur());
        List<PurchasePayment> saved = purchases.splitPayment(part.orderId(), payment.id, List.of(
                new PurchaseOrderService.PaymentPart(part.amountEur(), part.amountEur(), day),
                new PurchaseOrderService.PaymentPart(rest, rest, wasPaidOn)), PurchasePaymentSplitService.CARVED_SUMMARY);
        return new PartResult(saved.getFirst().id(), part.orderId(), part.amountEur(), Outcome.CARVED, wasEur, wasPaidOn,
                saved.get(1).id());
    }

    /**
     * The line carries purchase payments already: it is this same request again when nothing else is on it, every
     * open payment named is on it with its part on the line's day, and the line's other payments (by id) are the
     * new parts in request order. Then the same answer, without writing; else the line belongs to another link.
     */
    private ShareResult retried(BankStatementLineEntity line, List<Part> parts,
                                Map<Long, SourcingEntities.PurchasePaymentEntity> payments,
                                List<SourcingEntities.PurchasePaymentEntity> onLine, LocalDate day) {
        if (hasCostLink(line.id)) throw alreadyLinked();
        Set<Long> named = new HashSet<>();
        for (Part part : parts) {
            if (!part.existing()) continue;
            var payment = payments.get(part.paymentId());
            if (!Objects.equals(payment.bankStatementLineId, line.id) || payment.amountEur.compareTo(part.amountEur()) != 0
                    || !day.equals(payment.paidOn)) {
                throw alreadyLinked();
            }
            named.add(payment.id);
        }
        List<SourcingEntities.PurchasePaymentEntity> others = onLine.stream()
                .filter(payment -> !named.contains(payment.id)).toList();
        List<Part> created = parts.stream().filter(part -> !part.existing()).toList();
        if (others.size() != created.size()) throw alreadyLinked();
        for (int i = 0; i < created.size(); i++) {
            if (!sameNew(others.get(i), created.get(i), day)) throw alreadyLinked();
        }
        List<PurchasePayment> rows = new ArrayList<>();
        List<PartResult> outcomes = new ArrayList<>();
        int next = 0;
        for (Part part : parts) {
            var payment = part.existing() ? payments.get(part.paymentId()) : others.get(next++);
            rows.add(toDomain(payment));
            outcomes.add(new PartResult(payment.id, payment.orderId, payment.amountEur,
                    part.existing() ? Outcome.LINKED : Outcome.CREATED, null, null, null));
        }
        return new ShareResult(bank.describe(line), rows, outcomes);
    }

    private static boolean sameNew(SourcingEntities.PurchasePaymentEntity saved, Part requested, LocalDate day) {
        return saved.orderId == requested.orderId() && day.equals(saved.paidOn)
                && saved.amountEur.compareTo(requested.amountEur()) == 0
                && (saved.currency == null ? Currency.EUR : saved.currency) == Currency.EUR
                && (saved.payee == null ? PurchasePayment.Payee.SUPPLIER : saved.payee) == requested.payee()
                && Objects.equals(saved.instalmentDue, requested.instalmentDue())
                && Objects.equals(saved.label, requested.label())
                && Boolean.TRUE.equals(saved.settles) == requested.settles()
                && Objects.equals(saved.invoiceId, requested.invoiceId());
    }

    /**
     * The activity on every container (ascending) and on the line: "Banktransactie van 28/09/2026 (€ 11.403,38)
     * verdeeld over 3 containers · deze container € 3.801,13 · ook PO-2026-039, PO-2026-040".
     */
    private void record(BankStatementLineEntity line, List<Part> parts, Map<Long, String> names) {
        Map<Long, BigDecimal> perOrder = new LinkedHashMap<>();
        for (Part part : parts) perOrder.merge(part.orderId(), part.amountEur(), BigDecimal::add);
        int count = perOrder.size();
        String over = "verdeeld over " + count + (count == 1 ? " container" : " containers");
        String head = "Banktransactie van " + DAY.format(line.bookedAt.atZone(ZoneId.of(line.timeZone)).toLocalDate())
                + " (" + eur(line.amountEur.negate()) + ") " + over;
        for (Long orderId : new TreeSet<>(perOrder.keySet())) {
            List<String> also = perOrder.keySet().stream().filter(other -> !other.equals(orderId)).map(names::get).toList();
            String summary = head + " · deze container " + eur(perOrder.get(orderId))
                    + (also.isEmpty() ? "" : " · ook " + String.join(", ", also));
            purchases.recordBankShare(orderId, limited(summary));
        }
        List<String> rows = parts.stream().map(part -> names.get(part.orderId()) + " " + eur(part.amountEur())).toList();
        bank.audit(line, limited("Verdeeld over " + count + (count == 1 ? " container: " : " containers: ")
                + String.join(" · ", rows)));
    }

    /** The request's shape, before anything is read: one to ten parts, each one payment or one container, euro cents. */
    private static List<Part> checked(ShareRequest request) {
        if (request == null || request.parts() == null || request.parts().isEmpty() || request.parts().size() > 10) {
            throw new BusinessRuleException("Kies één tot tien delen");
        }
        List<Part> parts = new ArrayList<>();
        for (SharePart part : request.parts()) {
            if (part == null || (part.paymentId() == null) == (part.purchaseOrderId() == null)) {
                throw new BusinessRuleException("Kies per deel een bestaande betaling of een container");
            }
            BigDecimal eur = cents(part.amountEur());
            if (part.paymentId() != null) {
                parts.add(new Part(part.paymentId(), 0, eur, part.expectedAmountEur(), null, null, null, false, null,
                        false));
                continue;
            }
            if (part.currency() != null && part.currency() != Currency.EUR
                    || part.amount() != null && part.amount().compareTo(eur) != 0) {
                throw new BusinessRuleException("Een nieuw deel van een gedeelde transactie is in euro");
            }
            String label = part.label() == null || part.label().isBlank() ? null : part.label().strip();
            if (label != null && label.length() > 255) throw new BusinessRuleException("De omschrijving mag maximaal 255 tekens bevatten");
            parts.add(new Part(null, part.purchaseOrderId(), eur, null,
                    part.payee() == null ? PurchasePayment.Payee.SUPPLIER : part.payee(), part.instalmentDue(), label,
                    part.settles(), part.invoiceId(), Boolean.TRUE.equals(part.inspectionViaSupplier())));
        }
        List<Long> existing = parts.stream().filter(Part::existing).map(Part::paymentId).toList();
        if (new HashSet<>(existing).size() != existing.size()) throw new BusinessRuleException("Kies elke betaling maar één keer");
        return parts;
    }

    private static BigDecimal cents(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new BusinessRuleException("Geef voor elk deel een bedrag groter dan nul op");
        try {
            BigDecimal exact = value.setScale(2, RoundingMode.UNNECESSARY);
            if (exact.precision() > 19) throw new BusinessRuleException("Het betalingsbedrag is te groot");
            return exact;
        } catch (ArithmeticException invalid) {
            throw new BusinessRuleException("Betalingsbedragen mogen maximaal twee decimalen bevatten");
        }
    }

    private long orderOf(long paymentId) {
        return em.createQuery("select p.orderId from PurchasePaymentEntity p where p.id=:id", Long.class)
                .setParameter("id", paymentId).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Betaling", paymentId));
    }

    private boolean isOffset(long paymentId) {
        return em.createQuery("select count(c) from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseSupplierCreditEntity c where c.offsetPaymentId=:id",
                Long.class).setParameter("id", paymentId).getSingleResult() > 0;
    }

    private boolean hasCostLink(long lineId) {
        return em.createQuery("select count(p) from CostPaymentEntity p where p.bankStatementLineId=:id", Long.class)
                .setParameter("id", lineId).getSingleResult() > 0
                || em.createQuery("select count(c) from CompanyCostEntity c where c.bankStatementLineId=:id", Long.class)
                .setParameter("id", lineId).getSingleResult() > 0;
    }

    private PurchasePayment stored(long paymentId) {
        return toDomain(em.find(SourcingEntities.PurchasePaymentEntity.class, paymentId));
    }

    /** The activity summary, cut to what the log keeps. */
    private static String limited(String text) {
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT - 1) + "…";
    }

    /** Belgian euro: a point every three digits, a comma before the cents. */
    private static String eur(BigDecimal amount) {
        return "€ " + String.format(BELGIUM, "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static PurchasePayment toDomain(SourcingEntities.PurchasePaymentEntity payment) {
        return new PurchasePayment(payment.id, payment.orderId, payment.paidOn, payment.amount, payment.currency,
                payment.amountEur, payment.label, payment.actor, payment.recordedAt, payment.payee,
                Boolean.TRUE.equals(payment.settles), payment.instalmentDue, payment.bankStatementLineId,
                payment.groupId, payment.invoiceId);
    }

    private static BusinessRuleException alreadyLinked() {
        return new BusinessRuleException("Deze banktransactie is al aan een andere betaling gekoppeld");
    }
}
