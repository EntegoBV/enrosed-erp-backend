package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/** Panache repositories for stock locations and the pieces that lie there. */
public final class StockDaos {

    private StockDaos() {}

    @ApplicationScoped
    public static class Locations implements PanacheRepository<StockLocationEntity> {}

    @ApplicationScoped
    public static class Levels implements PanacheRepository<StockLevelEntity> {}

    @ApplicationScoped
    public static class BarcodePool implements PanacheRepository<BarcodePoolEntity> {}
}
