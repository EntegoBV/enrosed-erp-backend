package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.Objects;

/** Authoritative lifecycle read at mutation entry, before any edits to the sales document. */
@ApplicationScoped
public class SalesDocumentLocks {
    @Inject EntityManager entities;
    @Inject PurchaseOrderService purchases;

    @Transactional(Transactional.TxType.MANDATORY)
    public void lock(long id) {
        // A scalar projection bypasses a document that an earlier read (including mail dispatch) cached.
        var builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Object[].class);
        var order = query.from(SalesOrderEntity.class);
        query.select(builder.array(order.get("partnerPurchaseOrderId"), order.get("sourcePurchaseOrderId"), order.get("purpose")))
                .where(builder.equal(order.get("id"), id));
        Object[] hint = entities.createQuery(query)
                .setFlushMode(FlushModeType.COMMIT).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Verkooporder", id));
        Long partnerId = (Long) hint[0];
        Long sourceId = (Long) hint[1];
        SalesPurpose purpose = (SalesPurpose) hint[2];
        boolean partner = partnerId != null && purpose != SalesPurpose.STANDARD;
        Long purchaseId = partner ? sourceId != null ? sourceId : partnerId : null;
        if (purchaseId != null) purchases.lockForPartnerSettlement(purchaseId);

        SalesOrderEntity document = entities.find(SalesOrderEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (document == null) throw new NotFoundException("Verkooporder", id);
        // find(..., lock) does not refresh an already managed instance after waiting for another transaction.
        // This is deliberately local to mutation entry; ordinary repository locks and cargo capture do not refresh.
        entities.refresh(document, LockModeType.PESSIMISTIC_WRITE);
        boolean lockedPartner = document.partnerPurchaseOrderId != null && document.purpose != SalesPurpose.STANDARD;
        Long lockedSource = document.sourcePurchaseOrderId != null ? document.sourcePurchaseOrderId : document.partnerPurchaseOrderId;
        Long hintedSource = sourceId != null ? sourceId : partnerId;
        if (partner != lockedPartner || !Objects.equals(hintedSource, lockedSource))
            throw new BusinessRuleException("De containerkoppeling is intussen gewijzigd; laad het document opnieuw");
    }
}
