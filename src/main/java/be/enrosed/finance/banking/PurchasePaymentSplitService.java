package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PaymentTerms;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A container payment paid in several transfers, and the inspection paid in
 * the goods' transfer. One payment row stays one transfer on its own bank
 * day; these operations split a row and link the parts, each in one
 * transaction where any refusal undoes everything:
 *
 *   bank-lines   one payment over 1 to 10 free outgoing lines that together
 *                make its amount (two-cent rule on the total)
 *   bank-part    one line linked to a payment: a whole match, or a smaller
 *                line carved out of it with the rest left open in the group
 *   inspection   the inspection paid through the supplier split off a goods
 *                payment, on the same line, the line total unchanged
 *
 * Lock order is the bank service's: the lines (ascending id), then the
 * purchase order, then the payment rows. The links themselves go through
 * {@link BankStatementService#linkExisting}, so its direction, two-cent,
 * offset and exclusive-set guards run unchanged per line. An identical retry
 * after a lost response answers the same result without writing again.
 */
@ApplicationScoped
public class PurchasePaymentSplitService {
    private static final Locale BELGIUM = Locale.forLanguageTag("nl-BE");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final String LINKED_SUMMARY = "Betaling aan een banktransactie gekoppeld";
    static final String CARVED_SUMMARY = "Deel van de betaling aan een banktransactie gekoppeld";
    static final String CHANGED = "De betaling is intussen gewijzigd; vernieuw en probeer opnieuw";

    @Inject EntityManager em;
    @Inject PurchaseOrderService purchases;
    @Inject BankStatementService bank;

    public record LineRef(Long bankStatementLineId, BigDecimal amount) {}
    public record LinesRequest(List<LineRef> lines) {}
    /** {@code amount}: the linked part in the payment's currency; empty means pro rata. */
    public record PartRequest(Long bankStatementLineId, BigDecimal amount) {}
    /** {@code expectedAmountEur}: the goods payment's euro amount as the screen saw it. */
    public record InspectionRequest(BigDecimal amountEur, BigDecimal amount, String label, BigDecimal expectedAmountEur) {}
    /** Every member of the group (or goods + inspection), by day then id, and every touched line. */
    public record SplitResult(List<PurchasePayment> payments, List<BankStatementLineEntity> lines) {}

    /** C1: one payment over several transfers (or one), each part on the line's day. */
    @Transactional
    public SplitResult linkLines(long paymentId, LinesRequest request) {
        List<LineRef> refs = request == null || request.lines() == null ? List.of() : request.lines();
        if (refs.isEmpty() || refs.size() > 10 || refs.stream().anyMatch(ref -> ref == null || ref.bankStatementLineId() == null)) {
            throw new BusinessRuleException("Kies één tot tien banktransacties");
        }
        if (new HashSet<>(refs.stream().map(LineRef::bankStatementLineId).toList()).size() != refs.size()) {
            throw new BusinessRuleException("Kies elke banktransactie maar één keer");
        }
        long orderId = orderOf(paymentId);
        List<BankStatementLineEntity> lines = lockLines(refs.stream().map(LineRef::bankStatementLineId).toList());
        lockOrder(orderId);
        var payment = lockPayment(paymentId);
        requireNoOffset(paymentId);
        if (payment.bankStatementLineId != null && isRetryOfLines(payment, lines)) {
            return result(orderId, payment, lines);
        }
        requireNotOnBank(payment);
        lines.forEach(this::requireFree);
        BigDecimal signedTotal = lines.stream().map(line -> line.amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!BankAmountMatch.matches(signedTotal, payment.amountEur.negate())) {
            throw new BusinessRuleException("De gekozen banktransacties (" + eur(signedTotal.negate()) + ") verschillen "
                    + eur(signedTotal.negate().subtract(payment.amountEur).abs()) + " van de betaling ("
                    + eur(payment.amountEur) + "); maximaal € 0,02 verschil is toegestaan. Pas eerst het bedrag of "
                    + "'Afgeschreven in euro' aan.");
        }
        if (lines.size() == 1) {
            linkWhole(orderId, paymentId, lines.getFirst());
            return result(orderId, lockPayment(paymentId), lines);
        }
        List<BankStatementLineEntity> ordered = lines.stream()
                .sorted(Comparator.comparing((BankStatementLineEntity line) -> line.bookedAt).thenComparing(line -> line.id))
                .toList();
        List<BigDecimal> eurs = new ArrayList<>();
        BigDecimal others = BigDecimal.ZERO;
        for (int i = 0; i < ordered.size() - 1; i++) {
            BigDecimal part = ordered.get(i).amountEur.negate();
            eurs.add(part);
            others = others.add(part);
        }
        eurs.add(payment.amountEur.subtract(others));
        if (eurs.stream().anyMatch(part -> part.signum() <= 0)) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
        Map<Long, BigDecimal> given = new HashMap<>();
        for (LineRef ref : refs) if (ref.amount() != null) given.put(ref.bankStatementLineId(), ref.amount());
        List<BigDecimal> amounts = foreignParts(payment, eurs,
                ordered.stream().map(line -> given.get(line.id)).toList());
        List<PurchaseOrderService.PaymentPart> parts = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            parts.add(new PurchaseOrderService.PaymentPart(amounts.get(i), eurs.get(i), day(ordered.get(i))));
        }
        List<PurchasePayment> saved = purchases.splitPayment(orderId, paymentId, parts);
        for (int i = 0; i < ordered.size(); i++) {
            bank.linkExisting(ordered.get(i).id, new BankStatementService.OutgoingLinks(List.of(saved.get(i).id()), null, null));
        }
        return result(orderId, lockPayment(paymentId), lines);
    }

    /** C2: one line to a payment: a whole match takes the line's day; a smaller line is carved out. */
    @Transactional
    public SplitResult linkPart(long paymentId, PartRequest request) {
        if (request == null || request.bankStatementLineId() == null) {
            throw new BusinessRuleException("Kies een vrije uitgaande banktransactie");
        }
        long orderId = orderOf(paymentId);
        BankStatementLineEntity line = lockLines(List.of(request.bankStatementLineId())).getFirst();
        lockOrder(orderId);
        var payment = lockPayment(paymentId);
        requireNoOffset(paymentId);
        if (Objects.equals(payment.bankStatementLineId, line.id)) return result(orderId, payment, List.of(line));
        requireNotOnBank(payment);
        requireFree(line);
        BigDecimal lineEur = line.amountEur.negate();
        if (BankAmountMatch.matches(line.amountEur, payment.amountEur.negate())) {
            linkWhole(orderId, paymentId, line);
            return result(orderId, lockPayment(paymentId), List.of(line));
        }
        if (lineEur.compareTo(payment.amountEur.add(BankAmountMatch.MAX_DIFFERENCE)) > 0) {
            throw new BusinessRuleException("De banktransactie (" + eur(lineEur) + ") is groter dan deze betaling ("
                    + eur(payment.amountEur) + "); koppel ze via Bestaande betaling met alle delen");
        }
        List<BigDecimal> eurs = List.of(lineEur, payment.amountEur.subtract(lineEur));
        if (eurs.stream().anyMatch(part -> part.signum() <= 0)) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
        List<BigDecimal> amounts;
        if (payment.currency == Currency.EUR || request.amount() == null) {
            amounts = foreignParts(payment, eurs, java.util.Arrays.asList(null, null));
        } else {
            BigDecimal first = cents(request.amount());
            BigDecimal rest = payment.amount.subtract(first);
            if (first.signum() <= 0 || rest.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
            amounts = List.of(first, rest);
        }
        List<PurchasePayment> saved = purchases.splitPayment(orderId, paymentId, List.of(
                new PurchaseOrderService.PaymentPart(amounts.get(0), eurs.get(0), day(line)),
                new PurchaseOrderService.PaymentPart(amounts.get(1), eurs.get(1), payment.paidOn)), CARVED_SUMMARY);
        bank.linkExisting(line.id, new BankStatementService.OutgoingLinks(List.of(saved.getFirst().id()), null, null));
        return result(orderId, lockPayment(paymentId), List.of(line));
    }

    /** D: the inspection paid through the supplier, split off a goods payment on the same line. */
    @Transactional
    public SplitResult splitInspection(long paymentId, InspectionRequest request) {
        if (request == null || request.expectedAmountEur() == null) {
            throw new BusinessRuleException("Vernieuw de betaling en probeer opnieuw");
        }
        Object[] stored = em.createQuery("select p.orderId, p.bankStatementLineId from PurchasePaymentEntity p where p.id=:id",
                        Object[].class).setParameter("id", paymentId).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Betaling", paymentId));
        long orderId = (Long) stored[0];
        Long lineId = (Long) stored[1];
        BankStatementLineEntity line = lineId == null ? null : lockLines(List.of(lineId)).getFirst();
        lockOrder(orderId);
        var payment = lockPayment(paymentId);
        if (!Objects.equals(payment.bankStatementLineId, lineId)) throw new BusinessRuleException(CHANGED);
        BigDecimal partEur = request.amountEur() == null ? null : request.amountEur().setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = request.expectedAmountEur().setScale(2, RoundingMode.HALF_UP);
        List<BankStatementLineEntity> lines = line == null ? List.of() : List.of(line);
        if (payment.amountEur.compareTo(expected) != 0) {
            /* An identical retry after a lost response: the split is there already. */
            if (partEur != null && payment.amountEur.compareTo(expected.subtract(partEur)) == 0) {
                var inspection = em.createQuery("from PurchasePaymentEntity p where p.orderId=:order and p.id>:id"
                                + " and p.payee=:payee and p.paidOn=:day and p.amountEur=:eur order by p.id desc",
                        SourcingEntities.PurchasePaymentEntity.class)
                        .setParameter("order", orderId).setParameter("id", paymentId)
                        .setParameter("payee", PurchasePayment.Payee.SEPARATE).setParameter("day", payment.paidOn)
                        .setParameter("eur", partEur).getResultStream()
                        .filter(candidate -> Objects.equals(candidate.bankStatementLineId, payment.bankStatementLineId))
                        .findFirst().orElse(null);
                if (inspection != null) {
                    return new SplitResult(List.of(toDomain(payment), toDomain(inspection)), described(lines));
                }
            }
            throw new BusinessRuleException(CHANGED);
        }
        boolean offset = em.createQuery("select count(c) from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseSupplierCreditEntity c where c.offsetPaymentId=:id",
                Long.class).setParameter("id", paymentId).getSingleResult() > 0;
        if ((payment.payee != null && payment.payee != PurchasePayment.Payee.SUPPLIER) || offset
                || payment.instalmentDue == PaymentTerms.Moment.FREIGHT) {
            throw new BusinessRuleException("Splits de inspectie af van een goederenbetaling aan de leverancier");
        }
        if (partEur == null || partEur.signum() <= 0) throw new BusinessRuleException("Geef een inspectiebedrag groter dan nul op");
        if (partEur.compareTo(payment.amountEur) >= 0) {
            throw new BusinessRuleException("Het inspectiedeel moet kleiner zijn dan de betaling (" + eur(payment.amountEur) + ")");
        }
        BigDecimal part;
        if (payment.currency == Currency.EUR) {
            part = partEur;
        } else if (request.amount() != null) {
            part = cents(request.amount());
            if (part.signum() <= 0) throw new BusinessRuleException("Geef een inspectiebedrag groter dan nul op");
            if (part.compareTo(payment.amount) >= 0) {
                throw new BusinessRuleException("Het inspectiedeel moet kleiner zijn dan de betaling (" + eur(payment.amountEur) + ")");
            }
        } else {
            part = payment.amount.multiply(partEur).divide(payment.amountEur, 2, RoundingMode.HALF_UP);
            if (part.signum() <= 0 || part.compareTo(payment.amount) >= 0) {
                throw new BusinessRuleException("Geef een inspectiebedrag groter dan nul op");
            }
        }
        List<PurchasePayment> both = purchases.splitInspection(orderId, paymentId, part, partEur, request.label());
        if (line != null) {
            bank.audit(line, "Inspectie via leverancier afgesplitst · " + eur(partEur) + " · totaal ongewijzigd");
        }
        return new SplitResult(both, described(lines));
    }

    /** The whole payment on one line: first the line's day, then the bank service's link. */
    private void linkWhole(long orderId, long paymentId, BankStatementLineEntity line) {
        purchases.redatePayment(orderId, paymentId, day(line), LINKED_SUMMARY);
        bank.linkExisting(line.id, new BankStatementService.OutgoingLinks(List.of(paymentId), null, null));
    }

    /**
     * C1's retry: the payment is on one of the lines, every line carries
     * exactly one purchase payment of this payment's group, and together
     * they make the lines' total.
     */
    private boolean isRetryOfLines(SourcingEntities.PurchasePaymentEntity payment, List<BankStatementLineEntity> lines) {
        if (lines.stream().noneMatch(line -> line.id.equals(payment.bankStatementLineId))) return false;
        long root = payment.groupId == null ? payment.id : payment.groupId;
        BigDecimal carried = BigDecimal.ZERO;
        BigDecimal signedLines = BigDecimal.ZERO;
        for (BankStatementLineEntity line : lines) {
            var onLine = em.createQuery("from PurchasePaymentEntity p where p.bankStatementLineId=:id",
                    SourcingEntities.PurchasePaymentEntity.class).setParameter("id", line.id).getResultList();
            if (onLine.size() != 1) return false;
            var member = onLine.getFirst();
            if (!(Objects.equals(member.id, root) || Objects.equals(member.groupId, root))) return false;
            carried = carried.add(member.amountEur);
            signedLines = signedLines.add(line.amountEur);
        }
        return BankAmountMatch.matches(signedLines, carried.negate());
    }

    /**
     * The parts in the payment's own currency: the euro parts themselves for
     * a euro payment; else every given amount (they must make the payment's
     * amount exactly) or, with none given, pro rata to the euro parts with
     * the last part taking the rest.
     */
    private static List<BigDecimal> foreignParts(SourcingEntities.PurchasePaymentEntity payment, List<BigDecimal> eurs,
                                                 List<BigDecimal> given) {
        if (payment.currency == null || payment.currency == Currency.EUR) return eurs;
        long count = given.stream().filter(Objects::nonNull).count();
        List<BigDecimal> amounts = new ArrayList<>();
        if (count == given.size()) {
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal amount : given) {
                BigDecimal part = cents(amount);
                if (part.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
                amounts.add(part);
                total = total.add(part);
            }
            if (total.compareTo(payment.amount) != 0) throw notTogether(payment);
            return amounts;
        }
        if (count > 0) throw notTogether(payment);
        BigDecimal others = BigDecimal.ZERO;
        for (int i = 0; i < eurs.size() - 1; i++) {
            BigDecimal part = payment.amount.multiply(eurs.get(i)).divide(payment.amountEur, 2, RoundingMode.HALF_UP);
            if (part.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
            amounts.add(part);
            others = others.add(part);
        }
        BigDecimal last = payment.amount.subtract(others);
        if (last.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
        amounts.add(last);
        return amounts;
    }

    private static BusinessRuleException notTogether(SourcingEntities.PurchasePaymentEntity payment) {
        return new BusinessRuleException("De delen in " + payment.currency.name() + " moeten samen "
                + money(payment.amount, payment.currency) + " zijn");
    }

    private SplitResult result(long orderId, SourcingEntities.PurchasePaymentEntity payment,
                               List<BankStatementLineEntity> lines) {
        long root = payment.groupId == null ? payment.id : payment.groupId;
        List<PurchasePayment> members = purchases.payments(orderId).stream()
                .filter(candidate -> Objects.equals(candidate.id(), root) || Objects.equals(candidate.groupId(), root)
                        || Objects.equals(candidate.id(), payment.id))
                .sorted(Comparator.comparing(PurchasePayment::paidOn).thenComparing(PurchasePayment::id))
                .toList();
        return new SplitResult(members, described(lines));
    }

    private List<BankStatementLineEntity> described(List<BankStatementLineEntity> lines) {
        return lines.stream().sorted(Comparator.comparing(line -> line.id)).map(bank::describe).toList();
    }

    private long orderOf(long paymentId) {
        return em.createQuery("select p.orderId from PurchasePaymentEntity p where p.id=:id", Long.class)
                .setParameter("id", paymentId).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Betaling", paymentId));
    }

    private List<BankStatementLineEntity> lockLines(List<Long> ids) {
        List<BankStatementLineEntity> lines = new ArrayList<>();
        for (Long id : ids.stream().sorted().toList()) {
            var line = em.find(BankStatementLineEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (line == null) throw new NotFoundException("Bankbeweging", id);
            lines.add(line);
        }
        return lines;
    }

    private void lockOrder(long orderId) {
        if (em.find(SourcingEntities.PurchaseOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new NotFoundException("Inkooporder", orderId);
        }
    }

    private SourcingEntities.PurchasePaymentEntity lockPayment(long paymentId) {
        var payment = em.find(SourcingEntities.PurchasePaymentEntity.class, paymentId, LockModeType.PESSIMISTIC_WRITE);
        if (payment == null) throw new NotFoundException("Betaling", paymentId);
        return payment;
    }

    private void requireNoOffset(long paymentId) {
        if (em.createQuery("select count(c) from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseSupplierCreditEntity c where c.offsetPaymentId=:id",
                Long.class).setParameter("id", paymentId).getSingleResult() > 0) {
            throw new BusinessRuleException("Een verrekend leverancierstegoed is geen bankbetaling");
        }
    }

    private static void requireNotOnBank(SourcingEntities.PurchasePaymentEntity payment) {
        if (payment.bankStatementLineId != null) {
            throw new BusinessRuleException("Deze betaling staat al op de bank; maak de koppeling eerst los");
        }
    }

    /** A free outgoing line: money left, no sales receipt, nothing linked yet. */
    private void requireFree(BankStatementLineEntity line) {
        if (line.amountEur.signum() >= 0 || line.salesPaymentId != null || bank.describe(line).allocated) {
            throw new BusinessRuleException("Banktransactie van " + DAY.format(day(line)) + " ("
                    + eur(line.amountEur.abs()) + ") is al gekoppeld of niet uitgaand; kies een vrije uitgaande banktransactie");
        }
    }

    private static LocalDate day(BankStatementLineEntity line) {
        return line.bookedAt.atZone(ZoneId.of(line.timeZone)).toLocalDate();
    }

    private static BigDecimal cents(BigDecimal value) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalid) {
            throw new BusinessRuleException("Betalingsbedragen mogen maximaal twee decimalen bevatten");
        }
    }

    private static String eur(BigDecimal amount) {
        return money(amount, Currency.EUR);
    }

    /** Belgian figures: a point every three digits, a comma before the cents. */
    private static String money(BigDecimal amount, Currency currency) {
        String symbol = switch (currency == null ? Currency.EUR : currency) {
            case EUR -> "€ ";
            case USD -> "US$ ";
            case CNY -> "CN¥ ";
        };
        return symbol + String.format(BELGIUM, "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static PurchasePayment toDomain(SourcingEntities.PurchasePaymentEntity payment) {
        return new PurchasePayment(payment.id, payment.orderId, payment.paidOn, payment.amount, payment.currency,
                payment.amountEur, payment.label, payment.actor, payment.recordedAt, payment.payee,
                Boolean.TRUE.equals(payment.settles), payment.instalmentDue, payment.bankStatementLineId,
                payment.groupId, payment.invoiceId);
    }
}
