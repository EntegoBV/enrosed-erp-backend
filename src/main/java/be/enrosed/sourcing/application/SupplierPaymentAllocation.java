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

/** Rebuilds milestone balances from the ledger; there is no persistent paid/closed status. */
public final class SupplierPaymentAllocation {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private SupplierPaymentAllocation() {}

    public static List<SupplierInstalment> calculate(PurchaseOrder order, BigDecimal planned,
                                                    List<PurchasePayment> payments) {
        List<PaymentTerms.Instalment> plan = order.paymentInstalments();
        if (plan.isEmpty()) return List.of();
        List<Bucket> buckets = new ArrayList<>();
        BigDecimal unallocated = Money.money(planned);
        for (int i = 0; i < plan.size(); i++) {
            var step = plan.get(i);
            BigDecimal amount = i == plan.size() - 1 ? unallocated
                    : Money.money(planned.multiply(step.share())).min(unallocated);
            buckets.add(new Bucket(step, amount));
            unallocated = unallocated.subtract(amount);
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
        // A legacy whole-group flag does not assign subsequent transfers to a
        // particular term. Apply it after allocation, otherwise later money
        // would invent a saving on one term and an overrun on another.
        if (wholeGroupSettled) buckets.forEach(bucket -> bucket.settled = true);
        final boolean known = amountsKnown;
        return buckets.stream().map(bucket -> {
            BigDecimal gap = bucket.planned.subtract(bucket.paid).max(ZERO);
            boolean settled = known && bucket.settled;
            return new SupplierInstalment(bucket.step.due(), bucket.step.label(), bucket.planned,
                    bucket.paid, settled ? ZERO : gap, settled ? gap : ZERO,
                    bucket.paid.subtract(bucket.planned).max(ZERO), bucket.settled,
                    known && (settled || bucket.paid.compareTo(bucket.planned) == 0));
        }).toList();
    }

    private static final class Bucket {
        final PaymentTerms.Instalment step;
        final BigDecimal planned;
        BigDecimal paid = ZERO;
        boolean settled;
        Bucket(PaymentTerms.Instalment step, BigDecimal planned) { this.step = step; this.planned = planned; }
    }
}
