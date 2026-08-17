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
 * Stelt een catalogus samen om als PDF mee te geven of door te sturen.
 *
 * Je kiest zelf welke producten erin gaan: een klant die alleen glaswerk koopt
 * heeft niets aan tien pagina's acryl. Prijzen zijn optioneel, want zonder
 * prijzen is het een productblad dat je aan iedereen kan geven.
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
     * @param photosPerProduct hoeveel foto's per product mee mogen (1 = alleen de
     *                         hoofdfoto). Leeg betekent maximaal vier.
     */
    public record Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                          Integer photosPerProduct, String title, String intro) {}

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
