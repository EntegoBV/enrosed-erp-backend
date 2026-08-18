package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.CategoryRepository;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Category;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;

/** Manages the fixed list of product categories. */
@ApplicationScoped
public class CategoryService {

    private final CategoryRepository categories;
    private final ProductRepository products;

    public CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    public List<Category> list() {
        return categories.findAll().stream()
                .sorted(Comparator.comparingInt(Category::position).thenComparing(Category::name))
                .toList();
    }

    public Category get(long id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Categorie", id));
    }

    @Transactional
    public Category create(Category category) {
        categories.findByCode(category.code()).ifPresent(existing -> {
            throw new BusinessRuleException("Categoriecode " + category.code() + " bestaat al");
        });
        return categories.save(category);
    }

    @Transactional
    public Category update(long id, Category changes) {
        Category current = get(id);
        return categories.save(new Category(
                current.id(),
                changes.code() == null ? current.code() : changes.code(),
                changes.name() == null ? current.name() : changes.name(),
                changes.description(),
                changes.position()));
    }

    @Transactional
    public void delete(long id) {
        Category category = get(id);
        long inUse = products.countByCategory(category.id());
        if (inUse > 0) {
            throw new BusinessRuleException(
                    "Categorie " + category.name() + " staat nog op " + inUse + " product(en)");
        }
        categories.deleteById(id);
    }
}
