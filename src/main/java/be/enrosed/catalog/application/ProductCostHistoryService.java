package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductCostHistoryDao;
import be.enrosed.catalog.adapter.out.persistence.ProductCostHistoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.ProductCostBasis;
import be.enrosed.catalog.domain.ProductCostBreakdown;
import be.enrosed.catalog.domain.ProductCostEntry;
import be.enrosed.catalog.domain.ProductCostKind;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The line of landed costs a product carried over time. Every time a
 * container's cost is written onto a product a row is added; rows are
 * never removed, so a product that was cheaper two containers ago still
 * shows it. The first start on an existing catalogue writes one row per
 * product from the cost and the source it already has.
 *
 * Every row keeps the basis it was applied on (per display or per piece)
 * and, for a container, what one unit was made of. Applying the same
 * container again with the same cost adds nothing: the history shows what
 * changed, not how often the button was pressed. It only fills in the parts
 * of a row written before they were kept.
 */
@ApplicationScoped
public class ProductCostHistoryService {

    private static final Logger LOG = Logger.getLogger(ProductCostHistoryService.class);

    /** A piece price is kept to four decimals; two costs are the same when they agree there. */
    private static final int COST_SCALE = 4;

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

    /** Newest first, each row with its container's name as it is today. */
    public List<ProductCostEntry> history(long productId) {
        List<ProductCostEntry> rows = history.list("productId", newestFirst(), productId)
                .stream().map(ProductCostHistoryService::toDomain).toList();
        Map<Long, Container> containers = containers(rows.stream()
                .map(ProductCostEntry::purchaseOrderId).filter(Objects::nonNull).distinct().toList());
        return rows.stream().map(row -> {
            if (row.purchaseOrderId() == null) return row;
            Container container = containers.get(row.purchaseOrderId());
            /* Purged for good: the number the row kept is all that is left. */
            if (container == null) return row.withContainer(row.source(), true);
            return row.withContainer(container.name(), container.trashed());
        }).toList();
    }

    /**
     * Adds the row for a cost written onto a product. Nothing is added when
     * the product's newest row is this same container at this same cost and
     * the product still carries it; when that row has no parts yet, it takes
     * the parts of this call and, when it has none, its extra unit cost. The
     * same container at another cost is a re-apply, anything else (another
     * container, an older one again) is an apply. A copy's first row only names the container its cost came from:
     * the copy's own first apply from that container is an apply, never a
     * re-apply, and is always written.
     */
    @Transactional
    public void record(long productId, BigDecimal previous, BigDecimal landedUnitEur, String source,
                       Long purchaseOrderId, Integer quantity, BigDecimal exwPrice, String exwCurrency,
                       BigDecimal extraUnitCost, ProductCostBasis basis, ProductCostBreakdown breakdown) {
        if (landedUnitEur == null) return;
        ProductCostHistoryEntity latest = latest(productId);
        boolean sameContainer = latest != null && purchaseOrderId != null
                && purchaseOrderId.equals(latest.purchaseOrderId)
                && !ProductCostKind.COPY.name().equals(latest.kind);
        if (sameContainer && sameCost(latest.landedUnitEur, landedUnitEur) && sameCost(previous, landedUnitEur)) {
            /* A row from before the parts were kept takes them now: same container, same cost, so they belong to it. */
            if (breakdown != null && !breakdown.isEmpty() && partsOf(latest).isEmpty()) {
                breakdown(latest, breakdown);
                /* Its goods part includes the extra unit cost, so the row names it, in its currency, as a new row would. */
                if (latest.extraUnitCost == null && extraUnitCost != null
                        && (latest.exwCurrency == null || latest.exwCurrency.equals(exwCurrency))) {
                    latest.extraUnitCost = extraUnitCost;
                    latest.exwCurrency = exwCurrency;
                }
            }
            return;
        }
        ProductCostHistoryEntity row = new ProductCostHistoryEntity();
        row.productId = productId;
        row.landedUnitEur = landedUnitEur;
        row.previousLandedUnitEur = previous;
        row.source = clean(source);
        row.purchaseOrderId = purchaseOrderId;
        row.quantity = quantity;
        row.exwPrice = exwPrice;
        row.exwCurrency = exwCurrency;
        row.extraUnitCost = extraUnitCost;
        row.kind = (sameContainer ? ProductCostKind.REAPPLY : ProductCostKind.APPLY).name();
        basis(row, basis);
        breakdown(row, breakdown);
        row.appliedAt = Instant.now();
        row.appliedBy = currentActor().username();
        history.persist(row);
    }

    /**
     * The first row of a copied product: the cost it took over. When that
     * cost is still the source's newest row, the copy names the same
     * container and parts; otherwise only the source text the cost carried.
     */
    @Transactional
    public void recordCopy(long copyId, long sourceId, BigDecimal landedUnitEur, String costSource,
                           ProductCostBasis basis) {
        if (landedUnitEur == null) return;
        ProductCostHistoryEntity from = latest(sourceId);
        boolean sameCost = from != null && sameCost(from.landedUnitEur, landedUnitEur);
        ProductCostHistoryEntity row = new ProductCostHistoryEntity();
        row.productId = copyId;
        row.landedUnitEur = landedUnitEur;
        row.kind = ProductCostKind.COPY.name();
        basis(row, basis);
        if (sameCost) {
            row.source = from.source != null ? from.source : clean(costSource);
            row.purchaseOrderId = from.purchaseOrderId;
            row.exwPrice = from.exwPrice;
            row.exwCurrency = from.exwCurrency;
            row.extraUnitCost = from.extraUnitCost;
            row.goodsEur = from.goodsEur;
            row.originEur = from.originEur;
            row.freightEur = from.freightEur;
            row.dutyEur = from.dutyEur;
            row.destinationEur = from.destinationEur;
            row.enrosedEur = from.enrosedEur;
            row.separateEur = from.separateEur;
        } else {
            row.source = clean(costSource);
        }
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
            row.kind = ProductCostKind.SEED.name();
            basis(row, ProductCostBasis.of(product.packaging()));
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

    /**
     * The container's name by the rule sales uses everywhere (the Herkenbare
     * naam, else the number, else "Inkoop #id"; sourcing's
     * {@code PurchaseOrderName.display}), repeated here because the catalogue
     * does not depend on sourcing. A container in the trash still has its name.
     */
    static String containerName(Long id, String number, String alias) {
        if (alias != null && !alias.isBlank()) return alias.strip();
        if (number != null && !number.isBlank()) return number.strip();
        return "Inkoop #" + (id == null ? "?" : id);
    }

    private record Container(String name, boolean trashed) {}

    /** Read past the trash filter of the order entity: a trashed container still has a name. */
    private Map<Long, Container> containers(List<Long> ids) {
        Map<Long, Container> found = new HashMap<>();
        if (ids.isEmpty()) return found;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(
                        "select id, number, alias, deleted_at from purchase_order where id in (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            found.put(id, new Container(containerName(id, (String) row[1], (String) row[2]), row[3] != null));
        }
        return found;
    }

    private ProductCostHistoryEntity latest(long productId) {
        return history.find("productId", newestFirst(), productId).firstResult();
    }

    private static Sort newestFirst() {
        return Sort.descending("appliedAt").and("id", Sort.Direction.Descending);
    }

    private static boolean sameCost(BigDecimal left, BigDecimal right) {
        return left != null && right != null
                && left.setScale(COST_SCALE, RoundingMode.HALF_UP).compareTo(right.setScale(COST_SCALE, RoundingMode.HALF_UP)) == 0;
    }

    private static String clean(String source) {
        return source == null || source.isBlank() ? null : source.strip();
    }

    private static void basis(ProductCostHistoryEntity row, ProductCostBasis basis) {
        if (basis == null) return;
        row.salesUnit = basis.salesUnit();
        row.piecesPerUnit = basis.piecesPerUnit();
        row.unitKey = basis.unitKey();
    }

    private static void breakdown(ProductCostHistoryEntity row, ProductCostBreakdown parts) {
        if (parts == null) return;
        row.goodsEur = parts.goodsEur();
        row.originEur = parts.originEur();
        row.freightEur = parts.freightEur();
        row.dutyEur = parts.dutyEur();
        row.destinationEur = parts.destinationEur();
        row.enrosedEur = parts.enrosedEur();
        row.separateEur = parts.separateEur();
    }

    private static ProductCostBreakdown partsOf(ProductCostHistoryEntity row) {
        return new ProductCostBreakdown(row.goodsEur, row.originEur, row.freightEur,
                row.dutyEur, row.destinationEur, row.enrosedEur, row.separateEur);
    }

    private ActorRef currentActor() {
        return actor != null && actor.isResolvable() ? actor.get().current() : ActorRef.SYSTEM;
    }

    private static ProductCostEntry toDomain(ProductCostHistoryEntity row) {
        ProductCostBasis basis = row.salesUnit == null && row.piecesPerUnit == null && row.unitKey == null
                ? null : new ProductCostBasis(row.salesUnit, row.piecesPerUnit, row.unitKey);
        ProductCostBreakdown parts = partsOf(row);
        /* A developer database never runs the backfill: an unmarked row reads by the same rule the migration used. */
        String kind = row.kind != null ? row.kind : ProductCostKind.legacy(row.appliedBy).name();
        return new ProductCostEntry(row.id, row.productId, row.appliedAt, row.landedUnitEur, row.previousLandedUnitEur,
                row.source, row.purchaseOrderId, row.quantity, row.exwPrice, row.exwCurrency, row.appliedBy,
                kind, basis, row.extraUnitCost, parts.isEmpty() ? null : parts, null, false);
    }
}
