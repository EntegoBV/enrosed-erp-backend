package be.enrosed.partnerportal.adapter.out.persistence;

import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Collection;

/**
 * The one read the partner's photo guard needs from the purchase tables: whether a product is on a container the
 * partner may see. The statuses come from the caller ({@code PartnerContainerReader.VISIBLE}), so the list and the
 * guard share one predicate. Reads ids only, never a price, supplier or note column.
 */
@ApplicationScoped
public class PartnerContainerQueries {

    private final EntityManager entityManager;

    public PartnerContainerQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** True when a line of a not trashed purchase order with one of these statuses carries the product. */
    public boolean productOnContainer(long productId, Collection<PurchaseOrderStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return false;
        String lines = entityManager.getMetamodel().entity(SourcingEntities.PurchaseOrderLineEntity.class).getName();
        Long count = entityManager.createQuery("select count(l.id) from " + lines + " l join l.order o"
                        + " where l.productId = :product and o.status in :statuses and o.deletedAt is null", Long.class)
                .setParameter("product", productId)
                .setParameter("statuses", statuses)
                .getSingleResult();
        return count != null && count > 0;
    }
}
