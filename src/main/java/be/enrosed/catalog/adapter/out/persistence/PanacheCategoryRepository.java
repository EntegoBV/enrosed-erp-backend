package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.domain.Category;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheCategoryRepository implements CategoryRepository {

    private final CatalogDaos.Categories dao;

    public PanacheCategoryRepository(CatalogDaos.Categories dao) {
        this.dao = dao;
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
        CatalogMapper.apply(category, entity);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return CatalogMapper.toDomain(entity);
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }
}
