package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Composes a catalogue to hand over or forward as a PDF.
 *
 * You pick which products go in: a customer who only buys glassware has no
 * use for ten pages of acrylic. Prices are optional, because without prices
 * it is a product sheet you can give to anyone.
 */
@ApplicationScoped
public class CatalogExportService {

    private final ProductService products;
    private final CategoryService categories;
    private final CatalogDocumentRenderer renderer;

    public CatalogExportService(ProductService products, CategoryService categories,
                                CatalogDocumentRenderer renderer) {
        this.products = products;
        this.categories = categories;
        this.renderer = renderer;
    }

    /**
     * @param photosPerProduct how many photos may come along per product
     *                         (1 = primary only). Empty means four at most.
     */
    public record Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                          Integer photosPerProduct, String title, String intro,
                          /** Language of the catalogue; empty means Dutch. */
                          String language) {}

    public CatalogDocumentRenderer.Document export(Request request) {
        List<Product> all = products.list();
        List<Product> selected = request.productIds() == null || request.productIds().isEmpty()
                ? all
                : all.stream().filter(product -> request.productIds().contains(product.id())).toList();

        if (selected.isEmpty()) {
            throw new BusinessRuleException("Selecteer minstens een product voor de catalogus");
        }

        Map<Long, Category> categoriesById = categories.list().stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));

        return renderer.render(selected, categoriesById, request);
    }
}
