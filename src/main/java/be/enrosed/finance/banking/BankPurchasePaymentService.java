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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Creates every part of one selected transfer and its bank links in one transaction. A part may name its own
 * purchase order, so one transfer can pay several containers at once (the supplier lets us pay 30 % of three
 * containers in one go): the line and every order are locked first (orders by ascending id), the parts together
 * must make the line (two-cent rule), each part becomes a payment on its order, and the one line carries them all.
 */
@ApplicationScoped
public class BankPurchasePaymentService {
    @Inject EntityManager em;
    @Inject PurchaseOrderService purchases;
    @Inject BankStatementService bank;

    /**
     * One part of the transfer. {@code invoiceId} names the invoice (null:
     * automatic); {@code inspectionViaSupplier} on an inspection part says the
     * supplier was paid the inspection in this same transfer;
     * {@code purchaseOrderId} the container this part pays (null: the
     * request's order).
     */
    public record Payment(BigDecimal amount, Currency currency, String label, PurchasePayment.Payee payee,
                          boolean settles, PaymentTerms.Moment instalmentDue, BigDecimal amountEur,
                          Long invoiceId, Boolean inspectionViaSupplier, Long purchaseOrderId) {
        /** Compatibility for callers written before a part could name its own order. */
        public Payment(BigDecimal amount, Currency currency, String label, PurchasePayment.Payee payee,
                       boolean settles, PaymentTerms.Moment instalmentDue, BigDecimal amountEur,
                       Long invoiceId, Boolean inspectionViaSupplier) {
            this(amount, currency, label, payee, settles, instalmentDue, amountEur, invoiceId, inspectionViaSupplier, null);
        }
        /** Compatibility for callers written before parts named an invoice. */
        public Payment(BigDecimal amount, Currency currency, String label, PurchasePayment.Payee payee,
                       boolean settles, PaymentTerms.Moment instalmentDue, BigDecimal amountEur) {
            this(amount, currency, label, payee, settles, instalmentDue, amountEur, null, null, null);
        }
    }
    /** {@code purchaseOrderId}: the order of every part that names none; may be left out when every part names one. */
    public record Request(Long purchaseOrderId, List<Payment> payments) {}
    public record Result(BankStatementLineEntity line, List<PurchasePayment> payments) {}

    @Transactional
    public Result record(long id, Request request) {
        if (request == null || request.payments() == null
                || request.payments().isEmpty() || request.payments().size() > 10) {
            throw new BusinessRuleException("Kies een inkooporder en één tot tien betalingsdelen");
        }
        var parts = request.payments().stream().map(part -> checked(part, request.purchaseOrderId())).toList();
        var line = em.find(BankStatementLineEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (line == null) throw new NotFoundException("Bankbeweging", id);
        bank.requireUnclassified(line);
        if (line.amountEur.signum() >= 0 || line.salesPaymentId != null) {
            throw new BusinessRuleException("Kies een vrije uitgaande banktransactie");
        }
        /* Every container of the transfer, in the lock order every payment write uses. */
        for (Long orderId : new TreeSet<>(parts.stream().map(Payment::purchaseOrderId).toList())) {
            if (em.find(SourcingEntities.PurchaseOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE) == null) {
                throw new NotFoundException("Inkooporder", orderId);
            }
        }
        if (hasCostLink(id)) throw alreadyLinked();
        BigDecimal total = parts.stream().map(Payment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!BankAmountMatch.matches(line.amountEur, total.negate())) {
            throw new BusinessRuleException("Het totaal van de betalingen mag maximaal € 0,02 van het bankbedrag verschillen");
        }
        var day = line.bookedAt.atZone(ZoneId.of(line.timeZone)).toLocalDate();
        var existing = em.createQuery("from PurchasePaymentEntity p where p.bankStatementLineId=:id order by p.id",
                        SourcingEntities.PurchasePaymentEntity.class)
                .setParameter("id", id).getResultList();
        if (!existing.isEmpty()) {
            // IDs are allocated in request order. A lost response can safely return those same IDs.
            if (existing.size() != parts.size()) throw alreadyLinked();
            for (int i = 0; i < parts.size(); i++) {
                var payment = existing.get(i);
                if (payment.orderId != parts.get(i).purchaseOrderId() || !day.equals(payment.paidOn)
                        || !sameMoney(payment, parts.get(i))) throw alreadyLinked();
            }
            return new Result(bank.describe(line), existing.stream().map(BankPurchasePaymentService::toDomain).toList());
        }
        List<Long> ids = new ArrayList<>();
        for (var part : parts) {
            ids.add(purchases.addPayment(part.purchaseOrderId(), day, part.amount(), part.currency(),
                    part.label(), part.payee(), part.settles(), part.instalmentDue(), part.amountEur(),
                    part.invoiceId(), Boolean.TRUE.equals(part.inspectionViaSupplier())).id());
        }
        var linked = bank.linkExisting(id, new BankStatementService.OutgoingLinks(ids, null, null));
        return new Result(linked, ids.stream()
                .map(paymentId -> em.find(SourcingEntities.PurchasePaymentEntity.class, paymentId))
                .map(BankPurchasePaymentService::toDomain).toList());
    }

    private boolean hasCostLink(long id) {
        return em.createQuery("select count(p) from CostPaymentEntity p where p.bankStatementLineId=:id", Long.class)
                .setParameter("id", id).getSingleResult() > 0
                || em.createQuery("select count(c) from CompanyCostEntity c where c.bankStatementLineId=:id", Long.class)
                .setParameter("id", id).getSingleResult() > 0;
    }

    private static Payment checked(Payment part, Long requestOrderId) {
        if (part == null) throw new BusinessRuleException("Vul elk betalingsdeel in");
        Long orderId = part.purchaseOrderId() != null ? part.purchaseOrderId() : requestOrderId;
        if (orderId == null || orderId <= 0) throw new BusinessRuleException("Kies een inkooporder en één tot tien betalingsdelen");
        BigDecimal amount = cents(part.amount());
        Currency currency = part.currency() == null ? Currency.EUR : part.currency();
        BigDecimal eur = part.amountEur() == null && currency == Currency.EUR ? amount : cents(part.amountEur());
        if (currency == Currency.EUR && amount.compareTo(eur) != 0) {
            throw new BusinessRuleException("Voor een betaling in euro is het eurobedrag gelijk aan het bedrag");
        }
        String label = part.label() == null || part.label().isBlank() ? null : part.label().strip();
        if (label != null && label.length() > 255) throw new BusinessRuleException("De omschrijving mag maximaal 255 tekens bevatten");
        return new Payment(amount, currency, label,
                part.payee() == null ? PurchasePayment.Payee.SUPPLIER : part.payee(),
                part.settles(), part.instalmentDue(), eur, part.invoiceId(), part.inspectionViaSupplier(), orderId);
    }

    private static BigDecimal cents(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new BusinessRuleException("Geef voor elk betalingsdeel een bedrag en eurobedrag groter dan nul op");
        try {
            var exact = value.setScale(2, RoundingMode.UNNECESSARY);
            if (exact.precision() > 19) throw new BusinessRuleException("Het betalingsbedrag is te groot");
            return exact;
        } catch (ArithmeticException invalid) {
            throw new BusinessRuleException("Betalingsbedragen mogen maximaal twee decimalen bevatten");
        }
    }

    private static boolean sameMoney(SourcingEntities.PurchasePaymentEntity saved, Payment requested) {
        return saved.amount.compareTo(requested.amount()) == 0 && saved.currency == requested.currency()
                && saved.amountEur.compareTo(requested.amountEur()) == 0
                && (saved.payee == null ? PurchasePayment.Payee.SUPPLIER : saved.payee) == requested.payee()
                && Boolean.TRUE.equals(saved.settles) == requested.settles()
                && Objects.equals(saved.instalmentDue, requested.instalmentDue())
                && Objects.equals(saved.invoiceId, requested.invoiceId());
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
