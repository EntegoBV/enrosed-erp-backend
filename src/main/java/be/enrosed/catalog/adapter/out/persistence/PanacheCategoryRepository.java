package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.application.CategoryPublicKey;
import be.enrosed.catalog.domain.Category;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheCategoryRepository implements CategoryRepository {

    private final CatalogDaos.Categories dao;
    private final CanonicalCatalogDaos.Collections collections;
    private final CanonicalCatalogDaos.Families families;

    public PanacheCategoryRepository(
            CatalogDaos.Categories dao,
            CanonicalCatalogDaos.Collections collections,
            CanonicalCatalogDaos.Families families) {
        this.dao = dao;
        this.collections = collections;
        this.families = families;
    }

    @Override
    public List<Category> findAll() {
        return dao.listAll().stream().map(CatalogMapper::toDomain).toList();
    }

    @Override
    public Optional<Category> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(CatalogMapper::toDomain);
    }

    @Override
    public Optional<Category> findByCode(String code) {
        return dao.find("code", code).firstResultOptional().map(CatalogMapper::toDomain);
    }

    @Override
    public Category save(Category category) {
        CategoryEntity entity = category.id() == null ? null : dao.findById(category.id());
        if (entity == null) entity = new CategoryEntity();
        String oldCode = entity.code;
        String oldPublicKey = oldCode == null ? null : CategoryPublicKey.from(oldCode);
        CatalogMapper.apply(category, entity);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        String publicKey = CategoryPublicKey.from(entity.code);
        ProductCollectionEntity collection = collections.listAll().stream()
                .filter(item -> equalsAny(item.collectionKey, oldCode, oldPublicKey, publicKey))
                .findFirst().orElse(null);
        if (collection == null) {
            collection = new ProductCollectionEntity();
        }
        collection.collectionKey = publicKey;
        collection.name = entity.name;
        collection.eyebrow = entity.eyebrow;
        collection.description = entity.description;
        collection.position = entity.position;
        collection.mobileName = entity.mobileName;
        collection.featuredProductId = entity.featuredProductId;
        if (collection.id == null) collections.persist(collection);

        for (ProductFamilyEntity family : families.list("categoryId", entity.id)) {
            family.categoryKey = publicKey;
            family.categoryName = entity.name;
            family.categoryPosition = entity.position;
            if (equalsAny(family.collectionKey, oldCode, oldPublicKey, publicKey)) {
                family.collectionKey = publicKey;
            }
        }
        families.flush();
        return CatalogMapper.toDomain(entity);
    }

    @Override
    public void deleteById(long id) {
        CategoryEntity category = dao.findById(id);
        if (category == null) return;
        String publicKey = CategoryPublicKey.from(category.code);
        ProductCollectionEntity projection = collections.listAll().stream()
                .filter(item -> equalsAny(item.collectionKey, category.code, publicKey))
                .findFirst().orElse(null);
        dao.delete(category);
        dao.flush();
        if (projection != null && families.listAll().stream().noneMatch(family ->
                family.collections.stream().anyMatch(membership ->
                        membership.collection != null
                                && java.util.Objects.equals(
                                        membership.collection.id, projection.id)))) {
            collections.delete(projection);
        }
    }

    private static boolean equalsAny(String value, String... candidates) {
        if (value == null) return false;
        for (String candidate : candidates) {
            if (candidate != null && value.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }
}
