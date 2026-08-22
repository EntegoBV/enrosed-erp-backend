package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.StockLedger;
import be.enrosed.catalog.domain.StockMovement;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class PanacheStockLedger implements StockLedger, PanacheRepository<StockMovementEntity> {

    @Override
    public void record(StockMovement movement) {
        StockMovementEntity entity = new StockMovementEntity();
        entity.productId = movement.productId();
        entity.at = movement.at();
        entity.delta = movement.delta();
        entity.quantityAfter = movement.quantityAfter();
        entity.kind = movement.kind();
        entity.reference = movement.reference();
        entity.actor = movement.actor();
        persist(entity);
    }

    @Override
    public List<StockMovement> forProduct(long productId) {
        return list("productId = ?1 order by at desc, id desc", productId).stream()
                .map(entity -> new StockMovement(entity.id, entity.productId, entity.at, entity.delta,
                        entity.quantityAfter, entity.kind, entity.reference, entity.actor))
                .toList();
    }
}
