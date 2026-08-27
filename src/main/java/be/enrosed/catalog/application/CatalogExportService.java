package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Composes either a compact SKU catalogue or a family-based sales brochure. */
@ApplicationScoped
public class CatalogExportService {

    public enum Layout { SIMPLE, BROCHURE }

    /** Optional brochure pages and editable cover copy. Null values use branded defaults. */
    public record BrochureOptions(
            Boolean includeOverview,
            Boolean includeCategoryIntros,
            Boolean includeCustomisation,
            Boolean includeOrdering,
            Boolean includeBackCover,
            String coverTitle,
            String coverSubtitle) {

        public static BrochureOptions defaults() {
            return new BrochureOptions(true, true, true, true, true, null, null);
        }

        public BrochureOptions resolved() {
            BrochureOptions defaults = defaults();
            return new BrochureOptions(
                    value(includeOverview, defaults.includeOverview),
                    value(includeCategoryIntros, defaults.includeCategoryIntros),
                    value(includeCustomisation, defaults.includeCustomisation),
                    value(includeOrdering, defaults.includeOrdering),
                    value(includeBackCover, defaults.includeBackCover),
                    text(coverTitle, defaults.coverTitle),
                    text(coverSubtitle, defaults.coverSubtitle));
        }

        private static Boolean value(Boolean value, Boolean fallback) {
            return value == null ? fallback : value;
        }

        private static String text(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    /**
     * Backward-compatible request. Older clients omit {@code layout} and {@code brochure},
     * which keeps producing the compact PDF. A null product list still means all products;
     * an explicit empty list is rejected so an accidental empty mobile selection is visible.
     */
    public record Request(
            List<Long> productIds,
            boolean includePrices,
            boolean includePhotos,
            Integer photosPerProduct,
            String title,
            String intro,
            String language,
            Layout layout,
            BrochureOptions brochure,
            Boolean strictLanguage) {

        /** Source compatibility for builder callers written before strict locale validation. */
        public Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                       Integer photosPerProduct, String title, String intro, String language,
                       Layout layout, BrochureOptions brochure) {
            this(productIds, includePrices, includePhotos, photosPerProduct,
                    title, intro, language, layout, brochure, null);
        }

        /** Source compatibility for callers written before the builder existed. */
        public Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                       Integer photosPerProduct, String title, String intro, String language) {
            this(productIds, includePrices, includePhotos, photosPerProduct,
                    title, intro, language, null, null, null);
        }

        public Request {
            productIds = productIds == null ? null : List.copyOf(productIds);
        }

        public static Request defaults() {
            return new Request(null, false, true, 4, null, null, "nl", null, null, null);
        }

        public Layout resolvedLayout() {
            return layout == null ? Layout.SIMPLE : layout;
        }

        public BrochureOptions resolvedBrochure() {
            return brochure == null ? BrochureOptions.defaults() : brochure.resolved();
        }

        public int resolvedPhotosPerProduct() {
            if (!includePhotos) return 0;
            if (photosPerProduct == null) return 4;
            return Math.max(0, Math.min(8, photosPerProduct));
        }

        public boolean resolvedStrictLanguage() {
            return Boolean.TRUE.equals(strictLanguage);
        }
    }

    /** Family grouping prepared by the application layer; renderer stays persistence-agnostic. */
    public record FamilyGroup(
            CatalogFamilyReader.Family content,
            List<Product> variants,
            Category category,
            boolean synthetic) {
        public FamilyGroup {
            variants = List.copyOf(variants);
        }
    }

    public record Model(
            List<Product> products,
            Map<Long, Category> categoriesById,
            List<FamilyGroup> families,
            Request request) {
        public Model {
            products = List.copyOf(products);
            categoriesById = Map.copyOf(categoriesById);
            families = List.copyOf(families);
        }
    }

    private final ProductService products;
    private final CategoryService categories;
    private final CatalogFamilyReader families;
    private final CatalogDocumentRenderer renderer;

    public CatalogExportService(ProductService products, CategoryService categories,
                                CatalogFamilyReader families,
                                CatalogDocumentRenderer renderer) {
        this.products = products;
        this.categories = categories;
        this.families = families;
        this.renderer = renderer;
    }

    public CatalogDocumentRenderer.Document export(Request incoming) {
        Request request = incoming == null ? Request.defaults() : incoming;
        if (request.productIds() != null && request.productIds().isEmpty()) {
            throw new BusinessRuleException("Selecteer minstens een product voor de catalogus");
        }

        /* Customer documents exclude internal assessment products, even when an older saved
           "select all" request still contains their ids. They remain fully available in ERP. */
        List<Product> customerCatalogue = products.list().stream()
                .filter(product -> !product.demo())
                .toList();
        List<Product> selected = select(customerCatalogue, request.productIds());
        if (selected.isEmpty()) {
            throw new BusinessRuleException("Selecteer minstens een product voor de catalogus");
        }

        Map<Long, Category> categoriesById = categories.list().stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        return renderer.render(new Model(selected, categoriesById,
                group(selected, categoriesById), request));
    }

    /** Keeps the explicit builder order and removes duplicate ids without duplicating pages. */
    private static List<Product> select(List<Product> all, List<Long> requestedIds) {
        if (requestedIds == null) return List.copyOf(all);
        Map<Long, Product> byId = all.stream().filter(item -> item.id() != null)
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
        List<Product> selected = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(requestedIds)) {
            Product product = byId.get(id);
            if (product != null) selected.add(product);
        }
        return List.copyOf(selected);
    }

    private List<FamilyGroup> group(List<Product> selected, Map<Long, Category> categoriesById) {
        Set<Long> familyIds = selected.stream().map(Product::familyId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, CatalogFamilyReader.Family> familyContent = families.findByIds(familyIds).stream()
                .collect(Collectors.toMap(CatalogFamilyReader.Family::id, Function.identity()));

        Map<String, List<Product>> grouped = new LinkedHashMap<>();
        for (Product product : selected) {
            String key = product.familyId() != null && familyContent.containsKey(product.familyId())
                    ? "family:" + product.familyId()
                    : "product:" + product.id();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(product);
        }

        List<FamilyGroup> result = new ArrayList<>();
        for (List<Product> variants : grouped.values()) {
            Product first = variants.getFirst();
            CatalogFamilyReader.Family family = first.familyId() == null
                    ? null : familyContent.get(first.familyId());
            Long categoryId = family != null && family.categoryId() != null
                    ? family.categoryId() : first.categoryId();
            result.add(new FamilyGroup(family, variants, categoriesById.get(categoryId), family == null));
        }
        return List.copyOf(result);
    }
}
