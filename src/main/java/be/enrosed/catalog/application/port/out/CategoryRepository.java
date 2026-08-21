package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Category;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository {
    List<Category> findAll();
    Optional<Category> findById(long id);
    Optional<Category> findByIdForUpdate(long id);
    Optional<Category> findByCode(String code);
    Category save(Category category);
    void deleteById(long id);
}
