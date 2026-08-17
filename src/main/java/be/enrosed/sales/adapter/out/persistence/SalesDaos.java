package be.enrosed.sales.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Panache-repositories; de poortadapters delegeren ernaar. */
public final class SalesDaos {

    private SalesDaos() {}

    @ApplicationScoped
    public static class Customers implements PanacheRepository<SalesEntities.CustomerEntity> {}

    @ApplicationScoped
    public static class Countries implements PanacheRepositoryBase<SalesEntities.CountryEntity, String> {}

    @ApplicationScoped
    public static class Tiers implements PanacheRepository<SalesEntities.DiscountTierEntity> {}

    @ApplicationScoped
    public static class Orders implements PanacheRepository<SalesEntities.SalesOrderEntity> {}

    @ApplicationScoped
    public static class Revisions implements PanacheRepository<SalesEntities.QuoteRevisionEntity> {}

    @ApplicationScoped
    public static class Events implements PanacheRepository<SalesEntities.QuoteEventEntity> {}
}
