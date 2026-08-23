package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockDaos;
import be.enrosed.catalog.adapter.out.persistence.StockLevelEntity;
import be.enrosed.catalog.adapter.out.persistence.PanacheStockLedger;
import be.enrosed.catalog.domain.StockLocation;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Moves a catalogue that knew one stock figure per product into the
 * per-location model: the Magazijn location is created, every product's
 * figure becomes its level there, and ledger lines from before get that
 * location too. Idempotent - products that already have a level are left
 * alone - and invisible to a user who never adds a second location.
 */
@ApplicationScoped
public class StockLocationBackfill {

    private static final Logger LOG = Logger.getLogger(StockLocationBackfill.class);

    private final StockService stock;
    private final StockDaos.Levels levels;
    private final CatalogDaos.Products products;
    private final PanacheStockLedger movements;
    private final EntityManager entityManager;

    public StockLocationBackfill(StockService stock, StockDaos.Levels levels, CatalogDaos.Products products,
                                 PanacheStockLedger movements, EntityManager entityManager) {
        this.stock = stock;
        this.levels = levels;
        this.products = products;
        this.movements = movements;
        this.entityManager = entityManager;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        widenMovementKind();
        StockLocation main = stock.mainLocation();
        Set<Long> covered = new HashSet<>();
        for (StockLevelEntity level : levels.listAll()) covered.add(level.productId);

        int moved = 0;
        for (ProductEntity product : products.listAll()) {
            if (covered.contains(product.id)) continue;
            StockLevelEntity level = new StockLevelEntity();
            level.productId = product.id;
            level.locationId = main.id();
            level.quantity = Boolean.TRUE.equals(product.inventoryKnown) ? Math.max(0, product.stockQuantity) : 0;
            levels.persist(level);
            moved++;
        }
        long relabelled = movements.update("locationId = ?1 where locationId is null", main.id());
        if (moved > 0 || relabelled > 0) {
            LOG.infof("Voorraadlocaties: %d product(en) naar %s gezet, %d boekingen gelabeld",
                    moved, main.name(), relabelled);
        }
    }

    /**
     * The first version of the stock book mapped kind as an enum, which left
     * a CHECK constraint (PostgreSQL) or an ENUM column (H2) that only knows
     * the two original kinds. Widen it once; harmless when already done.
     */
    private void widenMovementKind() {
        String[] statements = {
            "alter table stock_movement drop constraint if exists stock_movement_kind_check",
            "alter table stock_movement alter column kind set data type varchar(40)",
        };
        for (String sql : statements) {
            try {
                entityManager.createNativeQuery(sql).executeUpdate();
            } catch (RuntimeException notApplicable) {
                /* The other database's statement, or already widened. */
                LOG.debugf("Overgeslagen: %s (%s)", sql, notApplicable.getMessage());
            }
        }
    }
}
