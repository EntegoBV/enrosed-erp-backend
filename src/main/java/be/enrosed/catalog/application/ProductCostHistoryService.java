package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductCostHistoryDao;
import be.enrosed.catalog.adapter.out.persistence.ProductCostHistoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.ProductCostEntry;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The line of landed costs a product carried over time. Every time a
 * container's cost is written onto a product a row is added; rows are
 * never removed, so a product that was cheaper two containers ago still
 * shows it. The first start on an existing catalogue writes one row per
 * product from the cost and the source it already has.
 */
@ApplicationScoped
public class ProductCostHistoryService {

    private static final Logger LOG = Logger.getLogger(ProductCostHistoryService.class);

    private final ProductCostHistoryDao history;
    private final CatalogDaos.Products products;
    private final EntityManager entities;

    @Inject
    Instance<CurrentActor> actor;

    public ProductCostHistoryService(ProductCostHistoryDao history, CatalogDaos.Products products, EntityManager entities) {
        this.history = history;
        this.products = products;
        this.entities = entities;
    }

    /** Newest first. */
    public List<ProductCostEntry> history(long productId) {
        return history.list("productId", Sort.descending("appliedAt").and("id", Sort.Direction.Descending), productId)
                .stream().map(ProductCostHistoryService::toDomain).toList();
    }

    @Transactional
    public void record(long productId, BigDecimal previous, BigDecimal landedUnitEur, String source,
                       Long purchaseOrderId, Integer quantity, BigDecimal exwPrice, String exwCurrency) {
        if (landedUnitEur == null) return;
        ProductCostHistoryEntity row = new ProductCostHistoryEntity();
        row.productId = productId;
        row.landedUnitEur = landedUnitEur;
        row.previousLandedUnitEur = previous;
        row.source = source == null || source.isBlank() ? null : source.strip();
        row.purchaseOrderId = purchaseOrderId;
        row.quantity = quantity;
        row.exwPrice = exwPrice;
        row.exwCurrency = exwCurrency;
        row.appliedAt = Instant.now();
        row.appliedBy = currentActor().username();
        history.persist(row);
    }

    /**
     * A catalogue from before the history: every product with a cost gets
     * its one known row, dated on the container it names when that container
     * is known. Runs once; a product that already has rows is left alone.
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (history.count() > 0) return;
        int written = 0;
        for (ProductEntity product : products.listAll()) {
            if (product.landedCostEur == null) continue;
            ProductCostHistoryEntity row = new ProductCostHistoryEntity();
            row.productId = product.id;
            row.landedUnitEur = product.landedCostEur;
            row.source = product.landedCostSource;
            row.appliedAt = Instant.now();
            row.appliedBy = "system";
            if (product.landedCostSource != null && !product.landedCostSource.isBlank()) {
                List<Object[]> orders = entities.createQuery(
                                "select o.id, o.receivedOn, o.orderDate from be.enrosed.sourcing.adapter.out.persistence"
                                        + ".SourcingEntities$PurchaseOrderEntity o where o.number = :number", Object[].class)
                        .setParameter("number", product.landedCostSource.strip()).setMaxResults(1).getResultList();
                if (!orders.isEmpty()) {
                    row.purchaseOrderId = (Long) orders.get(0)[0];
                    LocalDate day = orders.get(0)[1] != null ? (LocalDate) orders.get(0)[1] : (LocalDate) orders.get(0)[2];
                    if (day != null) row.appliedAt = day.atStartOfDay().toInstant(ZoneOffset.UTC);
                }
            }
            history.persist(row);
            written++;
        }
        if (written > 0) LOG.infof("Kostprijshistoriek: %d product(en) met hun huidige kost ingeschreven", written);
    }

    private ActorRef currentActor() {
        return actor != null && actor.isResolvable() ? actor.get().current() : ActorRef.SYSTEM;
    }

    private static ProductCostEntry toDomain(ProductCostHistoryEntity row) {
        return new ProductCostEntry(row.id, row.productId, row.appliedAt, row.landedUnitEur, row.previousLandedUnitEur,
                row.source, row.purchaseOrderId, row.quantity, row.exwPrice, row.exwCurrency, row.appliedBy);
    }
}
