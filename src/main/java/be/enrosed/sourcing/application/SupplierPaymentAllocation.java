package be.enrosed.sourcing.application;

import be.enrosed.shared.Money;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation.SupplierInstalment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rebuilds milestone balances from the ledger; there is no persistent paid/closed status.
 * Payments land on their terms as booked; the terms are then netted, so a surplus on one
 * term settles a shortfall on another and only the payee's net balance stays open.
 */
public final class SupplierPaymentAllocation {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private SupplierPaymentAllocation() {}

    public static List<SupplierInstalment> calculate(PurchaseOrder order, BigDecimal planned,
                                                    List<PurchasePayment> payments) {
        return calculate(order, planned, BigDecimal.ZERO, payments);
    }

    /**
     * @param goodsPlanned   the supplier Afspraak for the goods: the plan's
     *                       percentages split this, and only this
     * @param freightPlanned the CIF transport owed to the supplier; above
     *                       zero it is one extra term "Zeevracht (CIF)",
     *                       placed before any arrival term
     */
    public static List<SupplierInstalment> calculate(PurchaseOrder order, BigDecimal goodsPlanned,
                                                    BigDecimal freightPlanned, List<PurchasePayment> payments) {
        List<PaymentTerms.Instalment> plan = order.paymentInstalments();
        if (plan.isEmpty()) return List.of();
        List<Bucket> buckets = new ArrayList<>();
        BigDecimal planned = Money.money(goodsPlanned);
        BigDecimal unallocated = planned;
        for (int i = 0; i < plan.size(); i++) {
            var step = plan.get(i);
            BigDecimal amount = i == plan.size() - 1 ? unallocated
                    : Money.money(planned.multiply(step.share())).min(unallocated);
            buckets.add(new Bucket(step, amount));
            unallocated = unallocated.subtract(amount);
        }
        BigDecimal freight = freightPlanned == null ? ZERO : Money.money(freightPlanned);
        if (freight.signum() > 0) {
            int beforeArrival = 0;
            while (beforeArrival < buckets.size()
                    && buckets.get(beforeArrival).step.due() != PaymentTerms.Moment.ARRIVED) beforeArrival++;
            buckets.add(beforeArrival, new Bucket(new PaymentTerms.Instalment(PaymentTerms.FREIGHT_LABEL,
                    BigDecimal.ZERO, PaymentTerms.Moment.FREIGHT), freight));
        }
        List<PurchasePayment> ledger = payments == null ? List.of() : payments.stream()
                .filter(p -> p != null && p.payee() == PurchasePayment.Payee.SUPPLIER)
                .sorted(Comparator.comparing(PurchasePayment::paidOn, Comparator.nullsFirst(LocalDate::compareTo))
                        .thenComparing(PurchasePayment::id, Comparator.nullsLast(Long::compareTo)))
                .toList();
        boolean amountsKnown = true;
        boolean wholeGroupSettled = false;
        for (PurchasePayment payment : ledger) {
            if (payment.amountEur() == null || payment.amountEur().signum() < 0) amountsKnown = false;
            BigDecimal amount = payment.amountEur() == null ? ZERO : Money.money(payment.amountEur()).max(ZERO);
            if (payment.instalmentDue() != null) {
                Bucket target = buckets.stream().filter(b -> b.step.due() == payment.instalmentDue())
                        .findFirst().orElse(null);
                if (target == null) {
                    // Legacy inconsistent data must never invent a confirmed saving.
                    amountsKnown = false;
                    buckets.getLast().paid = buckets.getLast().paid.add(amount);
                } else {
                    target.paid = target.paid.add(amount);
                    target.settled |= payment.settles();
                }
            } else {
                // Historical unassigned payments cover the earliest still-open milestone.
                // A later payment never fills a discount already explicitly agreed on an earlier one.
                for (Bucket bucket : buckets) {
                    if (bucket.settled) continue;
                    BigDecimal allocated = bucket.planned.subtract(bucket.paid).max(ZERO).min(amount);
                    bucket.paid = bucket.paid.add(allocated);
                    amount = amount.subtract(allocated);
                }
                if (amount.signum() > 0) buckets.getLast().paid = buckets.getLast().paid.add(amount);
                if (payment.settlesWholeGroup()) wholeGroupSettled = true;
            }
        }
        final boolean known = amountsKnown;
        // A term settled on its own keeps its own result; only known amounts can close it.
        for (Bucket bucket : buckets) bucket.closed = known && bucket.settled;
        net(buckets);
        // A legacy whole-group flag does not assign subsequent transfers to a
        // particular term. Apply it after allocation and netting, otherwise later
        // money would invent a saving on one term and an overrun on another.
        if (wholeGroupSettled) buckets.forEach(bucket -> bucket.settled = true);
        return buckets.stream().map(bucket -> {
            if (bucket.closed) {
                return new SupplierInstalment(bucket.step.due(), bucket.step.label(), bucket.planned, bucket.paid,
                        ZERO, bucket.gap(), bucket.surplus(), true, true, ZERO);
            }
            boolean settled = known && bucket.settled;
            return new SupplierInstalment(bucket.step.due(), bucket.step.label(), bucket.planned, bucket.paid,
                    settled ? ZERO : bucket.remaining, settled ? bucket.remaining : ZERO, bucket.overpaid,
                    bucket.settled,
                    known && (settled || bucket.remaining.signum() == 0 && bucket.overpaid.signum() == 0),
                    bucket.offset);
        }).toList();
    }

    /**
     * The terms of one payee are one account: a surplus on one term covers a
     * shortfall on another, the earliest shortfall first, so whatever stays
     * open sits on the latest terms. A term settled on its own ("Slotbetaling
     * termijn") keeps its own result and stays out. Exact to the cent: one
     * cent of surplus covers one cent of shortfall, no tolerance.
     */
    private static void net(List<Bucket> buckets) {
        List<Bucket> open = buckets.stream().filter(bucket -> !bucket.closed).toList();
        BigDecimal gaps = open.stream().map(Bucket::gap).reduce(ZERO, BigDecimal::add);
        BigDecimal surpluses = open.stream().map(Bucket::surplus).reduce(ZERO, BigDecimal::add);
        BigDecimal toCover = gaps.min(surpluses);
        BigDecimal toUse = toCover;
        for (Bucket bucket : open) {
            BigDecimal covered = bucket.gap().min(toCover);
            BigDecimal used = bucket.surplus().min(toUse);
            toCover = toCover.subtract(covered);
            toUse = toUse.subtract(used);
            bucket.remaining = bucket.gap().subtract(covered);
            bucket.overpaid = bucket.surplus().subtract(used);
            bucket.offset = covered.subtract(used);
        }
    }

    private static final class Bucket {
        final PaymentTerms.Instalment step;
        final BigDecimal planned;
        BigDecimal paid = ZERO;
        boolean settled;
        /** Settled on its own by a scoped payment, with every amount known: out of the netting. */
        boolean closed;
        /* After netting; a closed term keeps its own gap and surplus. */
        BigDecimal remaining = ZERO;
        BigDecimal overpaid = ZERO;
        BigDecimal offset = ZERO;
        Bucket(PaymentTerms.Instalment step, BigDecimal planned) { this.step = step; this.planned = planned; }
        BigDecimal gap() { return planned.subtract(paid).max(ZERO); }
        BigDecimal surplus() { return paid.subtract(planned).max(ZERO); }
    }
}
