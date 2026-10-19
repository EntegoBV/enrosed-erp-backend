package be.enrosed.finance.banking;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.InvoiceAssignment;
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
 *   bank-part    one line linked to a payment, which follows the line: a
 *                whole match takes the line's day and amount, a larger line
 *                takes the owner's choice (the payment's amount follows, or
 *                the rest is the inspection paid through the supplier, or
 *                the container's transaction costs), a smaller line is
 *                carved out with the rest left open in the group; a USD/CNY
 *                payment is whole unless a part is named in its currency; a
 *                payment with every transfer on the bank grows by the line on
 *                the owner's choice; a named fee in euro is the container's
 *                transaction costs on the same line
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
    static final String GROWN_SUMMARY = "Transactie toegevoegd aan de betaling";
    static final String CHANGED = "De betaling is intussen gewijzigd; vernieuw en probeer opnieuw";
    /** The label of the bank's fee on a container transfer, booked as Bijkomende kosten. */
    static final String FEE_LABEL = "Transactiekosten";

    @Inject EntityManager em;
    @Inject PurchaseOrderService purchases;
    @Inject BankStatementService bank;

    public record LineRef(Long bankStatementLineId, BigDecimal amount) {}
    public record LinesRequest(List<LineRef> lines) {}

    /** What to do when the line is more than the payment: the owner's choice in the sheet. */
    public enum Higher {
        /** "Bedrag aanpassen": the payment takes the line's amount. */
        ADJUST,
        /** "Rest als inspectie via leverancier": the rest becomes the inspection on the same line. */
        INSPECTION,
        /** "Rest als transactiekosten": the rest becomes the container's transaction costs on the same line. */
        FEE
    }

    /**
     * One line to one payment.
     *
     * @param amount            the linked part in the payment's currency when a smaller line is carved out;
     *                          empty means pro rata
     * @param higher            the choice for a line more than € 0,02 above the payment; without it that link is refused
     * @param withPaymentIds    other open payments of the same container on the same transfer (the inspection paid
     *                          with the goods): they keep their amounts and take the line's day, the addressed payment
     *                          takes the rest of the line
     * @param expectedAmountEur the payment's euro as the screen saw it; a different stored amount is refused
     * @param feeEur            the bank's fee in this line ("Waarvan transactiekosten"): it becomes the container's
     *                          open "Transactiekosten" payment (Bijkomende kosten) or a new one, on the same line,
     *                          and the addressed payment takes the line without it; empty means none
     */
    public record PartRequest(Long bankStatementLineId, BigDecimal amount, Higher higher, List<Long> withPaymentIds,
                              BigDecimal expectedAmountEur, BigDecimal feeEur) {
        /** A link without a choice: a whole match or a smaller line. */
        public PartRequest(Long bankStatementLineId, BigDecimal amount) {
            this(bankStatementLineId, amount, null, null, null, null);
        }

        /** Compatibility for callers written before a line could carry transaction costs. */
        public PartRequest(Long bankStatementLineId, BigDecimal amount, Higher higher, List<Long> withPaymentIds,
                           BigDecimal expectedAmountEur) {
            this(bankStatementLineId, amount, higher, withPaymentIds, expectedAmountEur, null);
        }
    }
    /** {@code expectedAmountEur}: the goods payment's euro amount as the screen saw it. */
    public record InspectionRequest(BigDecimal amountEur, BigDecimal amount, String label, BigDecimal expectedAmountEur) {}

    /**
     * Every member of the group (or goods + inspection) and every payment of
     * the container on a touched line, by day then id; every touched line;
     * and one sentence on what the link changed (null when nothing changed,
     * also on an identical retry).
     */
    public record SplitResult(List<PurchasePayment> payments, List<BankStatementLineEntity> lines, String notice) {
        public SplitResult(List<PurchasePayment> payments, List<BankStatementLineEntity> lines) {
            this(payments, lines, null);
        }
    }

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
            BankStatementLineEntity line = lines.getFirst();
            PurchasePayment before = toDomain(payment);
            PurchasePayment after = follow(orderId, paymentId, day(line), line.amountEur.negate(), LINKED_SUMMARY, line.id);
            link(line, List.of(paymentId));
            return result(orderId, lockPayment(paymentId), lines, followed(before, after));
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

    /**
     * C2: one line to a payment, and the payment follows the transaction.
     * A whole match (± € 0,02) takes the line's day and its exact amount; a
     * line more than the payment needs the owner's choice (the payment takes
     * the line's amount, or the rest is the inspection paid through the
     * supplier); a smaller line is carved out of the payment, the rest stays
     * open as a second transfer of it. A USD/CNY payment compares in its own
     * currency (see {@link #linkForeign}). Other open payments named for the
     * same transfer keep their amounts: the addressed payment (the goods)
     * absorbs every difference, never the inspection. The root of a payment
     * in parts names its one open transfer; a payment whose transfers are all
     * on the bank grows by this one with the owner's ADJUST ("Transactie
     * toevoegen"). A named fee comes off the line first and becomes the
     * container's transaction costs on it; FEE puts a larger line's rest
     * there too.
     */
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
        if (Objects.equals(payment.bankStatementLineId, line.id)) return result(orderId, payment, List.of(line), null);
        /* On the bank already: the group's one open transfer takes the line; with none open the payment grows. */
        boolean grow = false;
        if (payment.bankStatementLineId != null) {
            long root = payment.groupId == null ? payment.id : payment.groupId;
            var onLine = groupMemberOn(root, line.id);
            if (onLine != null) return result(orderId, onLine, List.of(line), null);
            var open = em.createQuery("from PurchasePaymentEntity p where (p.groupId=:root or p.id=:root)"
                            + " and p.bankStatementLineId is null", SourcingEntities.PurchasePaymentEntity.class)
                    .setParameter("root", root).getResultList();
            if (open.size() == 1) {
                payment = lockPayment(open.getFirst().id);
                requireNoOffset(payment.id);
            } else {
                grow = open.isEmpty();
            }
        }
        if (!grow) requireNotOnBank(payment);
        requireFree(line);
        long goodsId = payment.id;
        List<SourcingEntities.PurchasePaymentEntity> others = otherParts(orderId, goodsId, request.withPaymentIds());
        if (request.expectedAmountEur() != null
                && payment.amountEur.compareTo(request.expectedAmountEur().setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new BusinessRuleException(CHANGED);
        }
        BigDecimal lineEur = line.amountEur.negate();
        BigDecimal fee = fee(request.feeEur(), lineEur);
        BigDecimal othersEur = others.stream().map(other -> other.amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal target = lineEur.subtract(othersEur).subtract(fee == null ? BigDecimal.ZERO : fee);
        if (target.signum() <= 0) {
            throw new BusinessRuleException((fee == null ? "De andere betalingen (" + eur(othersEur)
                    : "De andere betalingen en de transactiekosten (" + eur(othersEur.add(fee)))
                    + ") zijn samen niet kleiner dan de banktransactie (" + eur(lineEur) + ")");
        }
        LocalDate day = day(line);
        PurchasePayment before = toDomain(payment);
        List<Long> otherIds = others.stream().map(other -> other.id).toList();
        if (grow) return grow(orderId, payment, line, request, target, day, otherIds, fee);
        if (payment.currency != null && payment.currency != Currency.EUR) {
            return linkForeign(orderId, payment, line, request, target, day, otherIds, before, fee);
        }
        BigDecimal difference = target.subtract(payment.amountEur);
        boolean higher = difference.compareTo(BankAmountMatch.MAX_DIFFERENCE) > 0;
        if (!higher && difference.compareTo(BankAmountMatch.MAX_DIFFERENCE.negate()) >= 0
                || higher && request.higher() == Higher.ADJUST) {
            /* The payment follows the transfer: its day and, the other parts and the fee kept, the rest of the line. */
            PurchasePayment after = follow(orderId, goodsId, day, target, LINKED_SUMMARY, line.id);
            for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
            link(line, withFee(orderId, line.id, day, fee, concat(goodsId, otherIds)));
            return result(orderId, lockPayment(goodsId), List.of(line), sentences(followed(before, after), feeNotice(orderId, fee)));
        }
        if (higher && request.higher() == Higher.FEE) {
            /* "Rest als transactiekosten": the payment keeps its amount, the rest is the bank's fee on the container. */
            PurchasePayment after = follow(orderId, goodsId, day, null, LINKED_SUMMARY, line.id);
            for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
            BigDecimal costs = fee == null ? difference : difference.add(fee);
            link(line, withFee(orderId, line.id, day, costs, concat(goodsId, otherIds)));
            return result(orderId, lockPayment(goodsId), List.of(line), sentences(followed(before, after), feeNotice(orderId, costs)));
        }
        if (higher) {
            String refused = inspectionRestRefusal(orderId, payment, others, target);
            if (request.higher() == null) {
                throw new BusinessRuleException("Transactie is " + eur(difference) + " hoger dan deze betaling: kies 'Bedrag aanpassen naar "
                        + eur(target) + "'" + (refused == null ? " of 'Rest als inspectie via leverancier'" : "") + ".");
            }
            if (refused != null) throw new BusinessRuleException(refused);
            PurchasePayment after = follow(orderId, goodsId, day, null, LINKED_SUMMARY, line.id);
            for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
            PurchasePayment inspection = purchases.addInspectionRest(orderId, goodsId, difference);
            List<Long> linked = new ArrayList<>(concat(goodsId, otherIds));
            linked.add(inspection.id());
            link(line, withFee(orderId, line.id, day, fee, linked));
            String rest = "Inspectie via leverancier " + eur(inspection.amountEur()) + " toegevoegd op dezelfde transactie.";
            return result(orderId, lockPayment(goodsId), List.of(line),
                    sentences(followed(before, after), rest, feeNotice(orderId, fee)));
        }
        /* A smaller line: its part is carved out, the rest stays open in the group. */
        BigDecimal whole = payment.amountEur;
        BigDecimal rest = whole.subtract(target);
        List<PurchasePayment> saved = purchases.splitPayment(orderId, goodsId, List.of(
                new PurchaseOrderService.PaymentPart(target, target, day),
                new PurchaseOrderService.PaymentPart(rest, rest, payment.paidOn)), CARVED_SUMMARY);
        for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
        link(line, withFee(orderId, line.id, day, fee, concat(saved.getFirst().id(), otherIds)));
        return result(orderId, lockPayment(goodsId), List.of(line), sentences(carved(target, whole), feeNotice(orderId, fee)));
    }

    /**
     * A USD/CNY payment and its transfer. The bank line holds euro only, and
     * the payment's euro is a forecast at the order's rate, so a gap in euro
     * is the exchange rate, never a part or a surplus: the line is the whole
     * payment, which takes the bank's euro and keeps its own amount, unless
     * the request names a smaller part in the payment's currency. That part
     * is carved out at the payment's booked rate and then takes the bank's
     * euro; the rest stays open at the booked rate. "Rest als inspectie" and
     * "Rest als transactiekosten" do not apply: the inspection is split off
     * after the link, the bank's fee is named in euro ({@code feeEur}) and
     * then comes off the line before the payment takes its euro.
     */
    private SplitResult linkForeign(long orderId, SourcingEntities.PurchasePaymentEntity payment,
                                    BankStatementLineEntity line, PartRequest request, BigDecimal target,
                                    LocalDate day, List<Long> otherIds, PurchasePayment before, BigDecimal fee) {
        Currency currency = payment.currency;
        BigDecimal named = request.amount() == null ? null : cents(request.amount());
        if (named != null && named.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
        if (named != null && named.compareTo(payment.amount) > 0) {
            throw new BusinessRuleException("Het deel (" + money(named, currency) + ") is meer dan de betaling ("
                    + money(payment.amount, currency) + "); pas eerst het bedrag aan");
        }
        if (named == null || named.compareTo(payment.amount) == 0) {
            if (request.higher() == Higher.INSPECTION) {
                throw new BusinessRuleException("Een betaling in " + currency.name() + " neemt het eurobedrag van de transactie "
                        + "over: een verschil in euro is koers, geen inspectie. Splits de inspectie na het koppelen af.");
            }
            if (request.higher() == Higher.FEE) {
                throw new BusinessRuleException("Een betaling in " + currency.name() + " neemt het eurobedrag van de transactie "
                        + "over: een verschil in euro is koers. Geef de transactiekosten op in euro.");
            }
            PurchasePayment after = follow(orderId, payment.id, day, target, LINKED_SUMMARY, line.id);
            for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
            link(line, withFee(orderId, line.id, day, fee, concat(payment.id, otherIds)));
            return result(orderId, lockPayment(payment.id), List.of(line),
                    sentences(followed(before, after), feeNotice(orderId, fee)));
        }
        BigDecimal rest = payment.amount.subtract(named);
        BigDecimal partBooked = payment.amountEur.multiply(named).divide(payment.amount, 2, RoundingMode.HALF_UP);
        BigDecimal restEur = payment.amountEur.subtract(partBooked);
        if (partBooked.signum() <= 0 || restEur.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
        long goodsId = payment.id;
        BigDecimal whole = payment.amount;
        List<PurchasePayment> saved = purchases.splitPayment(orderId, goodsId, List.of(
                new PurchaseOrderService.PaymentPart(named, partBooked, day),
                new PurchaseOrderService.PaymentPart(rest, restEur, payment.paidOn)), CARVED_SUMMARY);
        purchases.followBankLine(orderId, saved.getFirst().id(), day, target, CARVED_SUMMARY);
        for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
        link(line, withFee(orderId, line.id, day, fee, concat(saved.getFirst().id(), otherIds)));
        return result(orderId, lockPayment(goodsId), List.of(line),
                sentences(carvedForeign(named, target, whole, currency), feeNotice(orderId, fee)));
    }

    /**
     * "Transactie toevoegen": every transfer of the payment is on the bank
     * and one more left. With the owner's ADJUST the payment grows by a new
     * transfer of this line (its euro, its day; a USD/CNY part as named or at
     * the payment's own rate), in the payment's group with its payee, term,
     * settlement and invoice; the linked transfers keep everything. Without
     * that choice it is refused, saying what the payment would become.
     */
    private SplitResult grow(long orderId, SourcingEntities.PurchasePaymentEntity payment, BankStatementLineEntity line,
                             PartRequest request, BigDecimal target, LocalDate day, List<Long> otherIds, BigDecimal fee) {
        long root = payment.groupId == null ? payment.id : payment.groupId;
        List<SourcingEntities.PurchasePaymentEntity> group = em.createQuery(
                        "from PurchasePaymentEntity p where p.groupId=:root or p.id=:root order by p.id",
                        SourcingEntities.PurchasePaymentEntity.class)
                .setParameter("root", root).getResultList();
        BigDecimal totalEur = group.stream().map(member -> member.amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = group.stream().map(member -> member.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.higher() != Higher.ADJUST) {
            throw new BusinessRuleException("Alle transacties van deze betaling zijn gekoppeld: kies 'Bedrag aanpassen naar "
                    + eur(totalEur.add(target)) + "' om deze transactie (" + eur(target) + ") toe te voegen.");
        }
        Currency currency = payment.currency == null ? Currency.EUR : payment.currency;
        BigDecimal amount;
        if (currency == Currency.EUR) {
            amount = target;
        } else if (request.amount() != null) {
            amount = cents(request.amount());
        } else {
            amount = total.multiply(target).divide(totalEur, 2, RoundingMode.HALF_UP);
        }
        if (amount.signum() <= 0) throw new BusinessRuleException("Elk deel moet groter dan nul zijn");
        PurchasePayment added = purchases.addTransfer(orderId, payment.id,
                new PurchaseOrderService.PaymentPart(amount, target, day), GROWN_SUMMARY);
        for (Long other : otherIds) follow(orderId, other, day, null, LINKED_SUMMARY, line.id);
        link(line, withFee(orderId, line.id, day, fee, concat(added.id(), otherIds)));
        String notice = currency == Currency.EUR
                ? "Transactie van " + eur(target) + " op " + DAY.format(day) + " toegevoegd; de betaling is nu "
                        + eur(totalEur.add(target)) + " (was " + eur(totalEur) + ")."
                : "Transactie van " + eur(target) + " (" + money(amount, currency) + ") op " + DAY.format(day)
                        + " toegevoegd; de betaling is nu " + money(total.add(amount), currency) + " (was "
                        + money(total, currency) + ").";
        return result(orderId, lockPayment(added.id()), List.of(line), sentences(notice, feeNotice(orderId, fee)));
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
        if (line != null) {
            /* One transfer carries one inspection: a second split of the same line would book it twice. */
            var carried = em.createQuery("from PurchasePaymentEntity p where p.orderId=:order and p.bankStatementLineId=:line"
                            + " and p.payee=:payee order by p.id", SourcingEntities.PurchasePaymentEntity.class)
                    .setParameter("order", orderId).setParameter("line", line.id)
                    .setParameter("payee", PurchasePayment.Payee.SEPARATE).getResultStream().findFirst().orElse(null);
            if (carried != null) {
                throw new BusinessRuleException("Deze transactie draagt al Inspectie " + eur(carried.amountEur)
                        + " (via leverancier); een tweede afsplitsing kan niet. Maak de koppeling los om de inspectie te wijzigen.");
            }
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

    /**
     * A whole payment follows its transaction (the line's day, and its euro when given) and remembers, the first
     * time, what it was before: "aangepast aan de bank (was ...)". A carved part is a new transfer, not a payment
     * that moved: those paths call {@link PurchaseOrderService#followBankLine} directly.
     */
    private PurchasePayment follow(long orderId, long paymentId, LocalDate day, BigDecimal amountEur, String summary,
                                   long lineId) {
        PurchasePayment before = toDomain(lockPayment(paymentId));
        PurchasePayment after = purchases.followBankLine(orderId, paymentId, day, amountEur, summary);
        purchases.recordBankAdjustment(before, lineId);
        return after;
    }

    /** The bank service's own link, with its direction, two-cent, offset and exclusive-set guards. */
    private void link(BankStatementLineEntity line, List<Long> paymentIds) {
        bank.linkExisting(line.id, new BankStatementService.OutgoingLinks(paymentIds, null, null));
    }

    private static List<Long> concat(Long first, List<Long> rest) {
        List<Long> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    /**
     * The bank's fee named in the line, in euro cents; null when none. It must
     * leave something of the line for the payment.
     */
    private static BigDecimal fee(BigDecimal feeEur, BigDecimal lineEur) {
        if (feeEur == null) return null;
        BigDecimal fee = cents(feeEur);
        if (fee.signum() <= 0) throw new BusinessRuleException("Geef transactiekosten groter dan nul op");
        if (fee.compareTo(lineEur) >= 0) {
            throw new BusinessRuleException("De transactiekosten (" + eur(fee) + ") moeten kleiner zijn dan de banktransactie ("
                    + eur(lineEur) + ")");
        }
        return fee;
    }

    /**
     * The payments to link, and with a fee the container's "Transactiekosten"
     * payment (Bijkomende kosten: no Afspraak, in the nacalculatie, out of the
     * stock value, never a company cost), in euro on the line's day. An open
     * one of the container, such as the fee "Koppeling losmaken" left, goes on
     * the line with this fee (one of the same amount first), so a relink never
     * books the fee twice; else a new one is written, only here, right before
     * the link.
     */
    private List<Long> withFee(long orderId, long lineId, LocalDate day, BigDecimal fee, List<Long> ids) {
        if (fee == null) return ids;
        List<Long> all = new ArrayList<>(ids);
        List<SourcingEntities.PurchasePaymentEntity> open = em.createQuery("from PurchasePaymentEntity p"
                        + " where p.orderId=:order and p.payee=:payee and p.label=:label and p.currency=:eur"
                        + " and p.bankStatementLineId is null and p.groupId is null order by p.id",
                        SourcingEntities.PurchasePaymentEntity.class)
                .setParameter("order", orderId).setParameter("payee", PurchasePayment.Payee.OTHER)
                .setParameter("label", FEE_LABEL).setParameter("eur", Currency.EUR).getResultStream()
                .filter(candidate -> !ids.contains(candidate.id)).toList();
        var reused = open.stream().filter(candidate -> candidate.amountEur.compareTo(fee) == 0).findFirst()
                .orElse(open.isEmpty() ? null : open.getFirst());
        if (reused != null) {
            follow(orderId, reused.id, day, fee, LINKED_SUMMARY, lineId);
            all.add(reused.id);
            return all;
        }
        all.add(purchases.addPayment(orderId, day, fee, Currency.EUR, FEE_LABEL, PurchasePayment.Payee.OTHER, false,
                null).id());
        return all;
    }

    /** "Transactiekosten € 25,00 bij PO-2026-005 op dezelfde transactie."; null without a fee. */
    private String feeNotice(long orderId, BigDecimal fee) {
        if (fee == null) return null;
        var order = em.find(SourcingEntities.PurchaseOrderEntity.class, orderId);
        return FEE_LABEL + " " + eur(fee) + " bij " + PurchaseOrderName.display(orderId, order == null ? null : order.number, null)
                + " op dezelfde transactie.";
    }

    /** The sentences that are there, one space apart; null when none. */
    private static String sentences(String... parts) {
        List<String> present = java.util.Arrays.stream(parts).filter(Objects::nonNull).toList();
        return present.isEmpty() ? null : String.join(" ", present);
    }

    /** A member of the group already on this line: the retry of a link addressed to the group's root. */
    private SourcingEntities.PurchasePaymentEntity groupMemberOn(long root, long lineId) {
        return em.createQuery("from PurchasePaymentEntity p where (p.groupId=:root or p.id=:root) and p.bankStatementLineId=:line",
                        SourcingEntities.PurchasePaymentEntity.class)
                .setParameter("root", root).setParameter("line", lineId).getResultStream().findFirst().orElse(null);
    }

    /**
     * The other open payments of the same container named for the same
     * transfer, locked, in id order. They are not on the bank, not a credit
     * offset and carry money.
     */
    private List<SourcingEntities.PurchasePaymentEntity> otherParts(long orderId, long paymentId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size() || ids.contains(paymentId)) {
            throw new BusinessRuleException("Kies elke betaling maar één keer");
        }
        if (ids.size() > 9) throw new BusinessRuleException("Kies hoogstens tien betalingen voor één banktransactie");
        List<SourcingEntities.PurchasePaymentEntity> others = new ArrayList<>();
        for (Long id : ids.stream().sorted().toList()) {
            var other = lockPayment(id);
            if (other.orderId != orderId) {
                throw new BusinessRuleException("Kies betalingen van dezelfde container voor één banktransactie");
            }
            requireNoOffset(id);
            if (other.bankStatementLineId != null) {
                throw new BusinessRuleException("Een van de gekozen betalingen staat al op de bank; maak die koppeling eerst los");
            }
            if (other.amountEur == null || other.amountEur.signum() <= 0) {
                throw new BusinessRuleException("De richting van de inkoopbetaling komt niet overeen met de banktransactie");
            }
            others.add(other);
        }
        return others;
    }

    /**
     * Why the rest of a larger line cannot be the inspection paid through the
     * supplier; null when it can. Only a goods payment to the supplier (not
     * the CIF freight term) of a container with an inspection cost, and never
     * a second inspection: not when an inspection payment goes on the same
     * transfer, nor when the inspection paid through the supplier already
     * reaches the container's inspection cost.
     */
    private String inspectionRestRefusal(long orderId, SourcingEntities.PurchasePaymentEntity payment,
                                         List<SourcingEntities.PurchasePaymentEntity> others, BigDecimal target) {
        if (payment.payee != null && payment.payee != PurchasePayment.Payee.SUPPLIER
                || payment.instalmentDue == PaymentTerms.Moment.FREIGHT) {
            return PurchaseOrderService.INSPECTION_REST_REFUSED;
        }
        var order = em.find(SourcingEntities.PurchaseOrderEntity.class, orderId);
        if (order == null || order.inspectionCostEur == null || order.inspectionCostEur.signum() <= 0) {
            return PurchaseOrderService.INSPECTION_REST_REFUSED;
        }
        String adjust = " Kies 'Bedrag aanpassen naar " + eur(target) + "'.";
        BigDecimal carried = others.stream().filter(other -> other.payee == PurchasePayment.Payee.SEPARATE)
                .map(other -> other.amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (carried.signum() > 0) {
            return "Op deze transactie staat al Inspectie " + eur(carried) + " (via leverancier); een tweede inspectie kan niet." + adjust;
        }
        List<PurchasePayment> recorded = purchases.payments(orderId);
        boolean via = Boolean.TRUE.equals(order.inspectionViaSupplier);
        BigDecimal booked = recorded.stream().filter(other -> InvoiceAssignment.viaSupplier(other, recorded, via))
                .map(PurchasePayment::amountEur).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (booked.signum() > 0 && booked.compareTo(order.inspectionCostEur) >= 0) {
            return "Inspectie via leverancier staat al geboekt: " + eur(booked) + " van " + eur(order.inspectionCostEur)
                    + " inspectiekost; een tweede inspectie kan niet." + adjust;
        }
        return null;
    }

    /**
     * What following the transfer changed, in one sentence: "Bedrag en datum
     * gevolgd van de transactie: € 10.300,00 op 05/01/2026 (was € 10.250,00
     * op 03/01/2026)." The euro is what the bank shows; null when nothing
     * changed.
     */
    static String followed(PurchasePayment before, PurchasePayment after) {
        boolean money = before.amountEur().compareTo(after.amountEur()) != 0;
        boolean date = !before.paidOn().equals(after.paidOn());
        if (money && date) {
            return "Bedrag en datum gevolgd van de transactie: " + eur(after.amountEur()) + " op " + DAY.format(after.paidOn())
                    + " (was " + eur(before.amountEur()) + " op " + DAY.format(before.paidOn()) + ").";
        }
        if (money) return "Bedrag gevolgd van de transactie: " + eur(after.amountEur()) + " (was " + eur(before.amountEur()) + ").";
        if (date) return "Datum gevolgd van de transactie: " + DAY.format(after.paidOn()) + " (was " + DAY.format(before.paidOn()) + ").";
        return null;
    }

    /** The part a smaller line took, as Kosten &amp; bank says it before the link. */
    static String carved(BigDecimal part, BigDecimal whole) {
        return "Deel van deze betaling: " + eur(part) + " van " + eur(whole) + "; de rest (" + eur(whole.subtract(part))
                + ") blijft open als tweede transactie.";
    }

    /** The part named in a USD/CNY payment's own currency, with the euro the bank charged for it. */
    static String carvedForeign(BigDecimal part, BigDecimal partEur, BigDecimal whole, Currency currency) {
        return "Deel van deze betaling: " + money(part, currency) + " (" + eur(partEur) + ") van " + money(whole, currency)
                + "; de rest (" + money(whole.subtract(part), currency) + ") blijft open als tweede transactie.";
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
        return result(orderId, payment, lines, null);
    }

    /** The payment's group and every payment of the container on the touched lines. */
    private SplitResult result(long orderId, SourcingEntities.PurchasePaymentEntity payment,
                               List<BankStatementLineEntity> lines, String notice) {
        long root = payment.groupId == null ? payment.id : payment.groupId;
        java.util.Set<Long> lineIds = new HashSet<>(lines.stream().map(line -> line.id).toList());
        List<PurchasePayment> members = purchases.payments(orderId).stream()
                .filter(candidate -> Objects.equals(candidate.id(), root) || Objects.equals(candidate.groupId(), root)
                        || Objects.equals(candidate.id(), payment.id)
                        || candidate.bankStatementLineId() != null && lineIds.contains(candidate.bankStatementLineId()))
                .sorted(Comparator.comparing(PurchasePayment::paidOn).thenComparing(PurchasePayment::id))
                .toList();
        return new SplitResult(members, described(lines), notice);
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
