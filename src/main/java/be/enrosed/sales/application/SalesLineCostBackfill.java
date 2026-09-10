package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines from before the cost was remembered on them take the product's cost
 * as it is now, once: the best cost known for them, and from then on fixed.
 */
@ApplicationScoped
public class SalesLineCostBackfill {

    @Inject
    EntityManager entities;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        String orders = entities.getMetamodel().entity(SalesEntities.SalesOrderEntity.class).getName();
        List<SalesEntities.SalesOrderLineEntity> lines = entities.createQuery(
                        "select l from SalesOrderLineEntity l where l.unitCostEur is null"
                                + " and exists (select o.id from " + orders + " o where o.id=l.order.id)",
                        SalesEntities.SalesOrderLineEntity.class)
                .getResultList();
        if (lines.isEmpty()) return;
        Map<Long, BigDecimal> costs = new HashMap<>();
        for (Object[] row : entities.createQuery(
                        "select p.id, p.landedCostEur from ProductEntity p where p.landedCostEur is not null", Object[].class)
                .getResultList()) {
            costs.put((Long) row[0], (BigDecimal) row[1]);
        }
        for (SalesEntities.SalesOrderLineEntity line : lines) {
            BigDecimal cost = costs.get(line.productId);
            if (cost != null && cost.signum() > 0) line.unitCostEur = cost.setScale(4, RoundingMode.HALF_UP);
        }
    }
}
