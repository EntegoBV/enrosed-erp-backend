package be.enrosed.inventory.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/** Panache repositories for the count sessions, the opening values and the year-end closings. */
public final class InventoryStore {

    private InventoryStore() {}

    @ApplicationScoped
    public static class Rules implements PanacheRepository<StockValuationRuleEntity> {}

    @ApplicationScoped
    public static class Counts implements PanacheRepository<StockCountEntity> {}

    @ApplicationScoped
    public static class CountLines implements PanacheRepository<StockCountLineEntity> {}

    @ApplicationScoped
    public static class OpeningLayers implements PanacheRepository<StockOpeningLayerEntity> {}

    @ApplicationScoped
    public static class Closings implements PanacheRepository<StockClosingEntity> {}

    @ApplicationScoped
    public static class Decisions implements PanacheRepository<StockClosingDecisionEntity> {}

    @ApplicationScoped
    public static class Movements implements PanacheRepository<StockClosingMovementEntity> {}

    @ApplicationScoped
    public static class Containers implements PanacheRepository<StockClosingContainerEntity> {}

    @ApplicationScoped
    public static class Lots implements PanacheRepository<StockClosingLotEntity> {}

    @ApplicationScoped
    public static class Articles implements PanacheRepository<StockClosingArticleEntity> {}

    @ApplicationScoped
    public static class Layers implements PanacheRepository<StockClosingLayerEntity> {}

    @ApplicationScoped
    public static class Lines implements PanacheRepository<StockClosingLineEntity> {}

    @ApplicationScoped
    public static class Separates implements PanacheRepository<StockClosingSeparateEntity> {}

    @ApplicationScoped
    public static class WriteDowns implements PanacheRepository<StockClosingWriteDownEntity> {}
}
