package be.enrosed.sourcing.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.adapter.out.persistence.PurchasePaymentOriginEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchasePaymentEntity;
import be.enrosed.sourcing.domain.PurchasePaymentOrigin;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Objects;

@ApplicationScoped
public class PurchasePaymentOriginService {
    @Inject EntityManager em;
    @Inject PurchaseOrderService orders;
    @Inject CurrentActor actor;
    @Inject ActivityLogService activity;

    /** Null clears the source and makes the existing payment eligible for the bank again. */
    @Transactional
    public PurchasePaymentOrigin update(long paymentId, PurchasePaymentOrigin request) {
        var found = em.find(PurchasePaymentEntity.class, paymentId);
        if (found == null) throw new NotFoundException("Betaling", paymentId);
        var order = orders.getForUpdate(found.orderId);
        var payment = em.find(PurchasePaymentEntity.class, paymentId, LockModeType.PESSIMISTIC_WRITE);
        if (payment == null) throw new NotFoundException("Betaling", paymentId);
        // A concurrent bank match may have populated a previously loaded payment.
        em.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        if (payment.bankStatementLineId != null)
            throw new BusinessRuleException("Maak de bankkoppeling eerst los voordat je de betaalbron wijzigt");
        if (em.createQuery("select count(c) from be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseSupplierCreditEntity c where c.offsetPaymentId=:id", Long.class)
                .setParameter("id", paymentId).getSingleResult() > 0)
            throw new BusinessRuleException("Een verrekend leverancierstegoed heeft geen betaalbron");
        PurchasePaymentOrigin next = validated(request);
        var row = em.find(PurchasePaymentOriginEntity.class, paymentId);
        var before = row == null ? null : row.toDomain();
        if (Objects.equals(before, next)) return before;
        if (next == null) {
            if (row != null) em.remove(row);
        } else {
            boolean created = row == null;
            if (created) { row = new PurchasePaymentOriginEntity(); row.paymentId = paymentId; }
            row.source = next.source(); row.payerName = next.payerName(); row.note = next.note();
            row.updatedAt = Instant.now(); row.updatedBy = actor.name();
            if (created) em.persist(row);
        }
        em.flush();
        activity.record(ActivityLogService.ACTION_UPDATED, "PURCHASE_ORDER", Long.toString(order.id()), order.number(),
                "Betaalbron gewijzigd voor betaling #" + paymentId,
                ActivityChangeSet.create().add("payment.origin", "Betaalbron",
                        before == null ? "Eigen bankrekening" : before.label(),
                        next == null ? "Eigen bankrekening" : next.label()).build());
        return next;
    }

    /** Called while the bank path holds the same order/payment locks as update. */
    public void requireBankEligible(long paymentId) {
        if (em.createQuery("select count(o) from PurchasePaymentOriginEntity o where o.paymentId=:id", Long.class)
                .setParameter("id", paymentId).getSingleResult() > 0)
            throw new BusinessRuleException("Deze betaling heeft een andere betaalbron; kies eerst Eigen bankrekening om een banktransactie te koppelen");
    }

    static PurchasePaymentOrigin validated(PurchasePaymentOrigin request) {
        if (request == null) return null;
        if (request.source() == null) throw new BusinessRuleException("Kies een betaalbron");
        String payer = limited(request.payerName(), 100, "Naam betaler");
        String note = limited(request.note(), 500, "Toelichting");
        if (request.source() == PurchasePaymentOrigin.Source.OTHER_COMPANY && payer == null)
            throw new BusinessRuleException("Vul de naam van de betalende vennootschap in");
        if (request.source() == PurchasePaymentOrigin.Source.OLD_BANK_ACCOUNT) payer = null;
        return new PurchasePaymentOrigin(request.source(), payer, note);
    }

    private static String limited(String value, int max, String label) {
        String text = value == null || value.isBlank() ? null : value.strip();
        if (text != null && text.length() > max) throw new BusinessRuleException(label + " is maximaal " + max + " tekens");
        return text;
    }
}
