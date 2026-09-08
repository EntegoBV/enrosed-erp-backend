package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.PurchaseOrderService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Containers that already had partner documents before they carried the
 * partner themselves take him over once, from their oldest linked document.
 */
@ApplicationScoped
public class PartnerContainerBackfill {

    @Inject
    EntityManager entities;

    @Inject
    PurchaseOrderService purchaseOrders;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        /* A nested entity is not reachable by its simple name in HQL. */
        String entity = entities.getMetamodel().entity(SalesEntities.SalesOrderEntity.class).getName();
        List<Object[]> rows = entities.createQuery(
                        "select o.partnerPurchaseOrderId, o.customerId, o.partnerSharePct from " + entity + " o"
                                + " where o.partnerPurchaseOrderId is not null and o.customerId is not null order by o.id",
                        Object[].class)
                .getResultList();
        Set<Long> seen = new HashSet<>();
        for (Object[] row : rows) {
            Long containerId = (Long) row[0];
            if (!seen.add(containerId)) continue;
            try {
                purchaseOrders.adoptPartner(containerId, (Long) row[1], null, (BigDecimal) row[2]);
            } catch (NotFoundException gone) {
                /* The document points at a container that is no longer there. */
            }
        }
    }
}
