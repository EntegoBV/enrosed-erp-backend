package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * De Panache-repositories.
 *
 * Ze staan bewust apart van de poortadapters: Panache brengt zelf al
 * findAll() en findById() mee, met andere returntypes dan onze poorten.
 * Compositie in plaats van overerving houdt beide kanten leesbaar.
 */
public final class CatalogDaos {

    private CatalogDaos() {}

    @ApplicationScoped
    public static class Products implements PanacheRepository<ProductEntity> {}

    @ApplicationScoped
    public static class Categories implements PanacheRepository<CategoryEntity> {}

    @ApplicationScoped
    public static class HsCodes implements PanacheRepository<HsCodeEntity> {}
}
