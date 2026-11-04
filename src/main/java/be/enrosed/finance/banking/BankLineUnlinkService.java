package be.enrosed.finance.banking;

import be.enrosed.finance.adapter.out.persistence.CompanyCostEntity;
import be.enrosed.finance.adapter.out.persistence.CostPaymentEntity;
import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchasePayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * "Transactie losmaken" that says first what it does and leaves nothing half linked (Emre, 2026-11-04: "als ik 1 vd
 * 2 transactie verwijder popup doen of ik zeker ben en alles dekoppelen"). DELETE /links only cuts the line: a
 * transfer of a payment paid in parts then stays on its payment and counts as paid without a bank line, the
 * Transactiekosten booked on the line stay as Bijkomende kosten, and the Bankkosten or Koersverschil cost made for
 * the bank's difference stays paid (linking the line again books it twice). Here, per thing the line carries:
 * <ul>
 *   <li>a transfer of a payment in parts goes back into that payment's open transfer when it has one (MERGED: the
 *   payment keeps its amount, with one open part), else it leaves the payment (REMOVED: the payment is what its
 *   other transfers paid), or with {@code keepParts} stays on it, open (STAYS);</li>
 *   <li>Transactiekosten on the line (Bijkomende kosten, euro, one transfer) go (DELETED);</li>
 *   <li>the company cost that only held the bank's difference (its one payment noted "Verschil bij bankkoppeling")
 *   goes (DELETED);</li>
 *   <li>every other payment and cost stays, without the line (STAYS), as DELETE /links always did.</li>
 * </ul>
 * The plan (GET) and the unlink (POST) apply the same rules; {@code expected}, the plan's fingerprint, refuses an
 * unlink when what the line carries changed since the plan was read. One transaction, locked as the bank service
 * locks: the line first, then the orders and payments through the unlink.
 */
@ApplicationScoped
public class BankLineUnlinkService {
    static final String MERGED_SUMMARY = "Transactie losgemaakt; het deel staat weer bij het open deel van de betaling";
    static final String CHANGED = "Wat aan deze transactie hangt, is intussen gewijzigd; bekijk het opnieuw";

    public enum Action { STAYS, MERGED, REMOVED, DELETED }

    /**
     * One thing the line carries. {@code kind} PURCHASE: purchase payment {@code id} of {@code orderId}; its payment
     * as a whole is {@code transfers} rows worth {@code paymentEur} now and {@code paymentAfterEur} after, and a
     * MERGED part goes into {@code intoPaymentId}; {@code fee} marks Transactiekosten. COST: cost payment {@code id}
     * (or the cost itself when it has no payment rows) of {@code costId}; {@code difference} marks the bank's
     * difference.
     */
    public record Row(String kind, long id, Long orderId, String orderNumber, String orderAlias, String payee,
                      String label, BigDecimal amountEur, BigDecimal amount, Currency currency, Action action,
                      int transfers, BigDecimal paymentEur, BigDecimal paymentAfterEur, Long intoPaymentId,
                      Long costId, boolean fee, boolean difference) {}

    /**
     * What unlinking the line does (or, {@code done}, did). {@code partsKeepable}: a part would leave its payment,
     * and {@code keepParts} would keep it there, open. {@code expected} goes back with the unlink.
     */
    public record Plan(long lineId, BigDecimal amountEur, LocalDate day, String counterparty, String reference,
                       List<Row> rows, boolean keepParts, boolean partsKeepable, String expected, boolean done) {}

    public record Request(Boolean keepParts, String expected) {}

    @Inject EntityManager em;
    @Inject BankStatementService bank;
    @Inject PurchaseOrderService purchases;
    @Inject CompanyCostService costs;

    @Transactional
    public Plan plan(long lineId, boolean keepParts) {
        return build(line(lineId, false), keepParts, false);
    }

    @Transactional
    public Plan unlink(long lineId, Request request) {
        BankStatementLineEntity row = line(lineId, true);
        boolean keep = request != null && Boolean.TRUE.equals(request.keepParts());
        Plan plan = build(row, keep, false);
        if (plan.rows().isEmpty()) throw new BusinessRuleException("Deze banktransactie is aan niets gekoppeld");
        if (request != null && request.expected() != null && !request.expected().equals(plan.expected())) {
            throw new BusinessRuleException(CHANGED);
        }
        bank.unlinkExisting(lineId);
        /* A merge keeps the lower id: a later merge into the row that went follows it to the one that stayed. */
        Map<Long, Long> merged = new HashMap<>();
        for (Row item : plan.rows()) {
            if (!"PURCHASE".equals(item.kind())) continue;
            switch (item.action()) {
                case MERGED -> {
                    long freed = current(merged, item.id());
                    long open = current(merged, item.intoPaymentId());
                    long kept = purchases.mergeTransfer(item.orderId(), freed, open, MERGED_SUMMARY).id();
                    merged.put(kept == freed ? open : freed, kept);
                }
                case REMOVED, DELETED -> purchases.deletePayment(item.orderId(), current(merged, item.id()));
                case STAYS -> { }
            }
        }
        for (Row item : plan.rows()) {
            if ("COST".equals(item.kind()) && item.action() == Action.DELETED) costs.delete(item.costId());
        }
        bank.audit(row, summary(plan));
        return build(row, keep, true, plan.rows());
    }

    private static long current(Map<Long, Long> merged, long id) {
        Long next = merged.get(id);
        return next == null ? id : current(merged, next);
    }

    private BankStatementLineEntity line(long id, boolean lock) {
        BankStatementLineEntity row = lock ? em.find(BankStatementLineEntity.class, id, LockModeType.PESSIMISTIC_WRITE)
                : em.find(BankStatementLineEntity.class, id);
        if (row == null) throw new NotFoundException("Bankbeweging", id);
        bank.requireUnclassified(row);
        if (row.salesPaymentId != null) throw new BusinessRuleException("Maak de factuurkoppeling ongedaan via de factuur");
        return row;
    }

    private Plan build(BankStatementLineEntity row, boolean keepParts, boolean done) {
        List<Row> rows = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        boolean keepable = purchaseRows(row.id, keepParts, rows, keys);
        costRows(row.id, rows, keys);
        return build(row, keepParts, done, rows, keepable, fingerprint(keys));
    }

    private Plan build(BankStatementLineEntity row, boolean keepParts, boolean done, List<Row> rows) {
        boolean keepable = rows.stream().anyMatch(item -> item.action() == Action.REMOVED)
                || keepParts && rows.stream().anyMatch(item -> "PURCHASE".equals(item.kind()) && item.transfers() > 1
                && item.action() == Action.STAYS);
        return build(row, keepParts, done, rows, keepable, null);
    }

    private Plan build(BankStatementLineEntity row, boolean keepParts, boolean done, List<Row> rows, boolean keepable,
                       String expected) {
        return new Plan(row.id, row.amountEur.abs(), row.bookedAt.atZone(ZoneId.of(row.timeZone)).toLocalDate(),
                row.counterparty, row.reference, List.copyOf(rows), keepParts, keepable, expected, done);
    }

    /**
     * The purchase payments on the line, per order and by id, each with what happens to it. Returns whether a part
     * leaves (or, with keepParts, would have left) its payment: the choice the screen offers.
     */
    private boolean purchaseRows(long lineId, boolean keepParts, List<Row> rows, List<String> keys) {
        List<Long> orderIds = em.createQuery("select distinct p.orderId from PurchasePaymentEntity p "
                + "where p.bankStatementLineId=:id order by p.orderId", Long.class).setParameter("id", lineId).getResultList();
        if (orderIds.isEmpty()) return false;
        Map<Long, PurchaseOrderName> names = purchases.names(orderIds);
        boolean keepable = false;
        for (Long orderId : orderIds) {
            List<PurchasePayment> all = purchases.payments(orderId);
            List<PurchasePayment> onLine = all.stream().filter(payment -> Objects.equals(payment.bankStatementLineId(), lineId))
                    .sorted(Comparator.comparing(PurchasePayment::id)).toList();
            /* What leaves each payment in parts, so its total after the unlink adds up over several rows. */
            Map<Long, BigDecimal> leaving = new HashMap<>();
            Map<Long, Action> actions = new HashMap<>();
            Map<Long, Long> into = new HashMap<>();
            for (PurchasePayment payment : onLine) {
                List<PurchasePayment> group = groupOf(all, payment);
                List<PurchasePayment> others = group.stream().filter(member -> !Objects.equals(member.bankStatementLineId(), lineId)).toList();
                PurchasePayment open = others.stream().filter(member -> member.bankStatementLineId() == null
                        && member.currency() == payment.currency()).min(Comparator.comparing(PurchasePayment::id)).orElse(null);
                PurchasePayment first = group.stream().min(Comparator.comparing(PurchasePayment::id)).orElseThrow();
                Action action;
                if (fee(payment)) action = Action.DELETED;
                else if (group.size() < 2) action = Action.STAYS;
                else if (open != null) { action = Action.MERGED; into.put(payment.id(), open.id()); }
                else if (others.isEmpty()) {
                    /* Every transfer on this one line (never by design): the first stays, the others join it. */
                    if (first.id().equals(payment.id())) action = Action.STAYS;
                    else { action = Action.MERGED; into.put(payment.id(), first.id()); }
                } else {
                    keepable = true;
                    action = keepParts ? Action.STAYS : Action.REMOVED;
                }
                if (action == Action.REMOVED) leaving.merge(payment.groupRoot(), payment.amountEur(), BigDecimal::add);
                actions.put(payment.id(), action);
                keys.add("p" + payment.id() + ":" + payment.amountEur() + ":" + payment.groupId());
                for (PurchasePayment member : group) {
                    keys.add("g" + member.id() + ":" + member.amountEur() + ":" + member.bankStatementLineId());
                }
            }
            PurchaseOrderName name = names.get(orderId);
            for (PurchasePayment payment : onLine) {
                List<PurchasePayment> group = groupOf(all, payment);
                BigDecimal total = group.stream().map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
                Action action = actions.get(payment.id());
                BigDecimal after = action == Action.DELETED ? BigDecimal.ZERO
                        : total.subtract(leaving.getOrDefault(payment.groupRoot(), BigDecimal.ZERO));
                rows.add(new Row("PURCHASE", payment.id(), orderId, name == null ? null : name.number(),
                        name == null ? null : name.alias(), payment.payee() == null ? "SUPPLIER" : payment.payee().name(),
                        payment.label(), payment.amountEur(), payment.amount(), payment.currency(), action, group.size(),
                        total, after, into.get(payment.id()), null, fee(payment), false));
            }
        }
        return keepable;
    }

    /** Every transfer of the payment in parts this one belongs to (only itself when it was never split). */
    private static List<PurchasePayment> groupOf(List<PurchasePayment> all, PurchasePayment payment) {
        if (payment.groupId() == null) return List.of(payment);
        List<PurchasePayment> group = all.stream().filter(member -> Objects.equals(member.groupId(), payment.groupId())).toList();
        return group.isEmpty() ? List.of(payment) : group;
    }

    /** Transactiekosten as the bank-part fee and Kosten & bank book them: Bijkomende kosten, euro, one transfer. */
    private static boolean fee(PurchasePayment payment) {
        return payment.payee() == PurchasePayment.Payee.OTHER && PurchasePaymentSplitService.FEE_LABEL.equals(payment.label())
                && payment.currency() == Currency.EUR && payment.groupId() == null;
    }

    /** The cost payments on the line (and a cost linked without payment rows); the bank's difference cost goes. */
    private void costRows(long lineId, List<Row> rows, List<String> keys) {
        Set<Long> withRows = new HashSet<>();
        for (CostPaymentEntity payment : em.createQuery("from CostPaymentEntity p where p.bankStatementLineId=:id order by p.id",
                CostPaymentEntity.class).setParameter("id", lineId).getResultList()) {
            CompanyCostEntity cost = em.find(CompanyCostEntity.class, payment.companyCostId);
            long count = em.createQuery("select count(p) from CostPaymentEntity p where p.companyCostId=:cost", Long.class)
                    .setParameter("cost", payment.companyCostId).getSingleResult();
            boolean difference = cost != null && count == 1 && BankStatementService.DIFFERENCE_NOTE.equals(payment.note)
                    && differenceCost(cost.description);
            withRows.add(payment.companyCostId);
            rows.add(new Row("COST", payment.id, null, null, null, null, cost == null ? null : cost.description,
                    payment.amountEur, payment.amountEur, Currency.EUR, difference ? Action.DELETED : Action.STAYS, 1,
                    null, null, null, payment.companyCostId, false, difference));
            keys.add("c" + payment.id + ":" + payment.amountEur + ":" + payment.companyCostId);
        }
        for (CompanyCostEntity cost : em.createQuery("from CompanyCostEntity c where c.bankStatementLineId=:id order by c.id",
                CompanyCostEntity.class).setParameter("id", lineId).getResultList()) {
            if (withRows.contains(cost.id)) continue;
            BigDecimal amount = cost.amountInclEur == null ? cost.amountExclEur : cost.amountInclEur;
            rows.add(new Row("COST", cost.id, null, null, null, null, cost.description, amount, amount, Currency.EUR,
                    Action.STAYS, 1, null, null, null, cost.id, false, false));
            keys.add("k" + cost.id);
        }
    }

    /** The descriptions BankStatementService gives the cost of a kept difference. */
    private static boolean differenceCost(String description) {
        return description != null && (description.equals("Bankkosten") || description.startsWith("Bankkosten · ")
                || description.startsWith("Koersverschil · "));
    }

    /** The audit line of the bank transaction: what came off and what went. */
    private static String summary(Plan plan) {
        List<String> parts = new ArrayList<>();
        for (Row row : plan.rows()) {
            String what = "PURCHASE".equals(row.kind())
                    ? PurchaseOrderName.display(row.orderId(), row.orderNumber(), row.orderAlias()) + " · " + (row.label() == null ? row.payee() : row.label())
                    : "kost " + (row.label() == null ? "#" + row.costId() : row.label());
            String done = switch (row.action()) {
                case STAYS -> "blijft staan";
                case MERGED -> "terug bij het open deel";
                case REMOVED -> "deel verwijderd";
                case DELETED -> "verwijderd";
            };
            parts.add(what + " " + BankStatementService.eur(row.amountEur()) + " " + done);
        }
        String text = "Koppeling losgemaakt: " + String.join("; ", parts);
        return text.length() > 480 ? text.substring(0, 479) + "…" : text;
    }

    private static String fingerprint(List<String> keys) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("|", keys).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 20);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
