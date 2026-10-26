package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/** Panache repositories for stock locations, the pieces that lie there and the batches they came in. */
public final class StockDaos {

    private StockDaos() {}

    @ApplicationScoped
    public static class Locations implements PanacheRepository<StockLocationEntity> {}

    @ApplicationScoped
    public static class Levels implements PanacheRepository<StockLevelEntity> {}

    @ApplicationScoped
    public static class BarcodePool implements PanacheRepository<BarcodePoolEntity> {}

    @ApplicationScoped
    public static class Batches implements PanacheRepository<StockBatchEntity> {}

    @ApplicationScoped
    public static class BatchMoves implements PanacheRepository<StockBatchMoveEntity> {}

    @ApplicationScoped
    public static class BatchRuns implements PanacheRepository<StockBatchRunEntity> {}
}
