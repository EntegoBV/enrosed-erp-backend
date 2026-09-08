package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesPaymentEntity;
import be.enrosed.sales.domain.SalesPayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;

/** Persisted receipt repository; voided entries remain available to the audit trail. */
@ApplicationScoped
public class IncomingPayments {
    @Inject EntityManager entities;

    public List<SalesPayment> forOrder(long id) {
        return entities.createQuery("from SalesPaymentEntity p where p.salesOrderId=:id and p.voidedAt is null order by p.receivedAt,p.id", SalesPaymentEntity.class)
                .setParameter("id", id).getResultList().stream().map(IncomingPayments::domain).toList();
    }

    public List<SalesPayment> all() {
        return entities.createQuery("from SalesPaymentEntity p where p.voidedAt is null order by p.receivedAt,p.id", SalesPaymentEntity.class)
                .getResultList().stream().map(IncomingPayments::domain).toList();
    }

    public boolean everRecorded(long id) {
        return entities.createQuery("select count(p) from SalesPaymentEntity p where p.salesOrderId=:id", Long.class)
                .setParameter("id", id).getSingleResult() > 0;
    }

    public SalesPayment save(SalesPayment payment) {
        SalesPaymentEntity entity = payment.id() == null ? new SalesPaymentEntity() : entities.find(SalesPaymentEntity.class, payment.id());
        entity.salesOrderId = payment.salesOrderId(); entity.amountEur = payment.amountEur();
        entity.receivedAt = payment.receivedAt(); entity.timeZone = payment.timeZone(); entity.reference = payment.reference();
        entity.recordedAt = payment.recordedAt(); entity.actor = payment.actor(); entity.legacy = payment.legacy();
        if (payment.legacy()) entity.legacyKey = "paid-at:" + payment.salesOrderId();
        if (entity.id == null) entities.persist(entity);
        entities.flush();
        return domain(entity);
    }

    public void voidPayment(long id) {
        entities.find(SalesPaymentEntity.class, id).voidedAt = Instant.now();
        entities.flush();
    }

    private static SalesPayment domain(SalesPaymentEntity e) {
        return new SalesPayment(e.id, e.salesOrderId, e.amountEur, e.receivedAt, e.timeZone, e.reference, e.recordedAt, e.actor, e.legacy);
    }
}
