package be.enrosed.catalog.application;

import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.CatalogRecipients;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.PriceBasis;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Composes either a compact SKU catalogue or a family-based sales brochure. */
@ApplicationScoped
public class CatalogExportService {

    public enum Layout { SIMPLE, BROCHURE }

    /**
     * Where a product's printed price comes from. Strongest first: a one-off price for this PDF,
     * the customer's stored price (active, on the product's current basis), the catalogue price;
     * without any, "Prijs op aanvraag".
     */
    public enum PriceSource { CATALOGUE, CUSTOMER, ONE_OFF, ON_REQUEST }

    /** A typed price is below this, exclusive. */
    private static final BigDecimal PRICE_LIMIT = new BigDecimal("1000000");
    /** A name typed for a recipient without a customer record. */
    private static final int RECIPIENT_NAME_LIMIT = 120;
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

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
     * A price typed for this PDF only: EUR excl. VAT on the product's own sales unit (per display
     * when sold per display), or "Prijs op aanvraag" with {@code onRequest}. {@code basis} and
     * {@code piecesPerUnit} (null per piece) say what the price was typed against, so a product
     * whose sales unit changed since is refused instead of printing a twelve times wrong amount.
     */
    public record PriceOverride(Long productId, BigDecimal priceEur, Boolean onRequest,
                                SalesUnit basis, Integer piecesPerUnit) {
        public boolean onRequestAsked() {
            return Boolean.TRUE.equals(onRequest);
        }
    }

    /**
     * Backward-compatible request. Older clients omit {@code layout} and {@code brochure},
     * which keeps producing the compact PDF. A null product list still means all products;
     * an explicit empty list is rejected so an accidental empty mobile selection is visible.
     * The recipient and price fields are all optional: an older client prints catalogue prices.
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
            Boolean strictLanguage,
            Map<Long, Integer> familyPhotoLimits,
            Boolean preserveProductOrder,
            /** The stored customer the PDF is made for; their klantprijzen apply. */
            Long customerId,
            /** A recipient without a customer record (a fair lead); used only without customerId. */
            String recipientName,
            /** Printed "Geldig tot …"; only with prices. */
            LocalDate pricesValidUntil,
            List<PriceOverride> priceOverrides) {

        /** Older clients name no recipient and adjust no price. */
        public Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                       Integer photosPerProduct, String title, String intro, String language,
                       Layout layout, BrochureOptions brochure, Boolean strictLanguage,
                       Map<Long, Integer> familyPhotoLimits, Boolean preserveProductOrder) {
            this(productIds, includePrices, includePhotos, photosPerProduct, title, intro, language,
                    layout, brochure, strictLanguage, familyPhotoLimits, preserveProductOrder,
                    null, null, null, List.of());
        }

        /** Older clients retain the existing category and dome ordering. */
        public Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                       Integer photosPerProduct, String title, String intro, String language,
                       Layout layout, BrochureOptions brochure, Boolean strictLanguage,
                       Map<Long, Integer> familyPhotoLimits) {
            this(productIds, includePrices, includePhotos, photosPerProduct,
                    title, intro, language, layout, brochure, strictLanguage, familyPhotoLimits, false);
        }

        /** Source compatibility for callers written before per-family photo limits. */
        public Request(List<Long> productIds, boolean includePrices, boolean includePhotos,
                       Integer photosPerProduct, String title, String intro, String language,
                       Layout layout, BrochureOptions brochure, Boolean strictLanguage) {
            this(productIds, includePrices, includePhotos, photosPerProduct,
                    title, intro, language, layout, brochure, strictLanguage, Map.of());
        }

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
            familyPhotoLimits = familyPhotoLimits == null ? Map.of() : Map.copyOf(familyPhotoLimits);
            /* A null entry names no product and changes nothing. */
            priceOverrides = priceOverrides == null ? List.of()
                    : priceOverrides.stream().filter(Objects::nonNull).toList();
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

        /** A family can use its own photo budget; the global no-photos setting still wins. */
        public int resolvedPhotosPerProduct(Long familyId) {
            int defaultLimit = resolvedPhotosPerProduct();
            if (defaultLimit == 0 || familyId == null) return defaultLimit;
            return Math.max(0, Math.min(8, familyPhotoLimits.getOrDefault(familyId, defaultLimit)));
        }

        public boolean resolvedStrictLanguage() {
            return Boolean.TRUE.equals(strictLanguage);
        }

        /** Family blocks follow their first requested ID; their variants retain requested order. */
        public boolean resolvedPreserveProductOrder() {
            return Boolean.TRUE.equals(preserveProductOrder);
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

    /** Who a PDF is made for: a stored customer (with its id), or a name without a customer record. */
    public record Recipient(Long customerId, String name) {
        /**
         * "bloemen-janssens-bv": the name in the file name, as the studio writes it too. Lower-case
         * ASCII, accents dropped, every other run of characters one dash, at most 60 characters,
         * no dash at either end; "klant" when nothing is left (a Greek name, say).
         */
        public String fileSlug() {
            String plain = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            if (plain.length() > 60) plain = plain.substring(0, 60).replaceAll("-+$", "");
            return plain.isEmpty() ? "klant" : plain;
        }
    }

    /** What one product prints and why; amounts in EUR excl. VAT on the product's own sales unit. */
    public record ResolvedPrice(Long productId, SalesUnit basis, Integer piecesPerUnit, String unitKey,
                                BigDecimal catalogueEur, BigDecimal customerEur,
                                /** Null prints "Prijs op aanvraag". */
                                BigDecimal printedEur,
                                PriceSource source) {}

    /** A price problem; productId is null when it concerns the whole PDF. */
    public record PriceIssue(Long productId, String code, String message) {}

    /** The printed price of every selected product and what deserves a look; empty without prices. */
    public record PriceBook(Map<Long, ResolvedPrice> byProduct, List<PriceIssue> warnings,
                            /** One-off prices that changed what a product prints (an equal one is dropped). */
                            int oneOffCount) {
        public PriceBook {
            byProduct = Collections.unmodifiableMap(new LinkedHashMap<>(byProduct));
            warnings = List.copyOf(warnings);
        }

        public static PriceBook none() {
            return new PriceBook(Map.of(), List.of(), 0);
        }

        public ResolvedPrice of(Product product) {
            return product.id() == null ? null : byProduct.get(product.id());
        }

        /** The amount printed for this product, or null for "Prijs op aanvraag". */
        public BigDecimal printed(Product product) {
            ResolvedPrice resolved = of(product);
            if (resolved != null) return resolved.printedEur();
            BigDecimal catalogue = product.computedSalesPriceEur();
            return catalogue != null && catalogue.signum() > 0 ? catalogue : null;
        }

        public long count(PriceSource source) {
            return byProduct.values().stream().filter(price -> price.source() == source).count();
        }
    }

    public record Model(
            List<Product> products,
            Map<Long, Category> categoriesById,
            List<FamilyGroup> families,
            Request request,
            /** Null for an anonymous catalogue. */
            Recipient recipient,
            PriceBook prices) {
        public Model {
            products = List.copyOf(products);
            categoriesById = Map.copyOf(categoriesById);
            families = List.copyOf(families);
            prices = prices == null ? PriceBook.none() : prices;
        }

        public Model(List<Product> products, Map<Long, Category> categoriesById,
                     List<FamilyGroup> families, Request request) {
            this(products, categoriesById, families, request, null, PriceBook.none());
        }

        /** A PDF with prices or for a recipient is written to the export journal. */
        public boolean journaled() {
            return request.includePrices() || recipient != null;
        }
    }

    /**
     * Lightweight exact-language validation result used before generating a PDF. {@code ready}
     * still means "translations complete" only. {@code prices} has one row per selected product
     * while prices are shown (empty otherwise); its presence also tells the studio this backend
     * honours recipients and one-off prices.
     */
    public record Preflight(boolean ready, List<String> missingPaths, List<ResolvedPrice> prices,
                            List<PriceIssue> priceWarnings, Recipient recipient) {
        public Preflight {
            missingPaths = List.copyOf(missingPaths);
            prices = prices == null ? List.of() : List.copyOf(prices);
            priceWarnings = priceWarnings == null ? List.of() : List.copyOf(priceWarnings);
        }

        public Preflight(boolean ready, List<String> missingPaths) {
            this(ready, missingPaths, List.of(), List.of(), null);
        }
    }

    private final ProductService products;
    private final CategoryService categories;
    private final CatalogFamilyReader families;
    private final CatalogDocumentRenderer renderer;
    private final CatalogRecipients recipients;

    public CatalogExportService(ProductService products, CategoryService categories,
                                CatalogFamilyReader families,
                                CatalogDocumentRenderer renderer,
                                CatalogRecipients recipients) {
        this.products = products;
        this.categories = categories;
        this.families = families;
        this.renderer = renderer;
        this.recipients = recipients;
    }

    public CatalogDocumentRenderer.Document export(Request incoming) {
        return renderer.render(model(incoming));
    }

    /** The same validation as the export; price problems that would refuse the PDF answer 409 here too. */
    public Preflight preflight(Request incoming) {
        Model model = model(incoming);
        List<String> missingPaths = renderer.missingTranslations(model);
        return new Preflight(missingPaths.isEmpty(), missingPaths,
                List.copyOf(model.prices().byProduct().values()), model.prices().warnings(), model.recipient());
    }

    /** The selected products grouped per series, exactly as the renderer receives them. */
    public Model model(Request incoming) {
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

        if (!request.includePrices() && (!request.priceOverrides().isEmpty() || request.pricesValidUntil() != null)) {
            throw new CatalogPriceIssuesException(
                    "Zet 'Prijzen tonen' aan om prijzen aan te passen of een geldigheid te kiezen", List.of());
        }
        Recipient recipient = recipient(request);
        PriceBook prices = request.includePrices() ? prices(request, selected, recipient) : PriceBook.none();

        Map<Long, Category> categoriesById = categories.list().stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));
        return new Model(selected, categoriesById,
                group(selected, categoriesById), request, recipient, prices);
    }

    /** A stored customer wins over a typed name; an unknown customer is refused, never left out silently. */
    private Recipient recipient(Request request) {
        if (request.customerId() != null) {
            return recipients.customer(request.customerId())
                    .map(customer -> new Recipient(customer.id(), customer.name()))
                    .orElseThrow(() -> new NotFoundException("De gekozen klant bestaat niet meer"));
        }
        if (request.recipientName() == null) return null;
        String name = request.recipientName().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) return null;
        if (name.length() > RECIPIENT_NAME_LIMIT) {
            throw new BusinessRuleException("De naam van de ontvanger mag hoogstens "
                    + RECIPIENT_NAME_LIMIT + " tekens lang zijn");
        }
        return new Recipient(null, name);
    }

    /**
     * One price per selected product: a one-off price > the customer's stored price (active, on
     * the product's current basis) > the catalogue price > "Prijs op aanvraag". Refuses one-off
     * prices that cannot be printed as asked; warns about what prints but deserves a look.
     */
    private PriceBook prices(Request request, List<Product> selected, Recipient recipient) {
        LocalDate today = LocalDate.now(BRUSSELS);
        Map<Long, Product> byId = new LinkedHashMap<>();
        selected.forEach(product -> byId.put(product.id(), product));
        Map<Long, PriceOverride> overrides = acceptedOverrides(request.priceOverrides(), byId);
        Map<Long, CatalogRecipients.StoredPrice> stored = recipient == null || recipient.customerId() == null
                ? Map.of() : recipients.prices(recipient.customerId(), byId.keySet());

        List<PriceIssue> warnings = new ArrayList<>();
        if (request.pricesValidUntil() != null && request.pricesValidUntil().isBefore(today)) {
            warnings.add(new PriceIssue(null, "VALIDITY_PAST", "De geldigheidsdatum ligt in het verleden"));
        }
        Map<Long, ResolvedPrice> book = new LinkedHashMap<>();
        int oneOff = 0;
        for (Product product : selected) {
            PriceBasis basis = PriceBasis.of(product.packaging());
            BigDecimal catalogue = positive(product.computedSalesPriceEur());
            BigDecimal customer = customerPrice(product, basis, stored.get(product.id()), today, warnings);
            BigDecimal printed = customer != null ? customer : catalogue;
            PriceSource source = customer != null ? PriceSource.CUSTOMER
                    : catalogue != null ? PriceSource.CATALOGUE : PriceSource.ON_REQUEST;
            PriceOverride override = overrides.get(product.id());
            if (override != null) {
                BigDecimal asked = override.onRequestAsked() ? null : override.priceEur().setScale(2);
                /* The price it would get anyway is no adjustment: dropped, so the journal never claims one. */
                if (!sameAmount(asked, printed)) {
                    printed = asked;
                    source = asked == null ? PriceSource.ON_REQUEST : PriceSource.ONE_OFF;
                    oneOff++;
                }
            }
            if (printed != null && !printable(product)) {
                warnings.add(new PriceIssue(product.id(), "DISPLAY_WITHOUT_PIECES", label(product)
                        + ": display zonder aantal stuks, de PDF toont 'Prijs op aanvraag'"));
                printed = null;
                source = PriceSource.ON_REQUEST;
            }
            BigDecimal cost = positive(product.landedCostEur());
            if (printed != null && cost != null && printed.compareTo(cost) < 0
                    && (source == PriceSource.ONE_OFF || source == PriceSource.CUSTOMER)) {
                warnings.add(new PriceIssue(product.id(), "BELOW_COST", label(product) + ": "
                        + euro(printed) + " ligt onder de kostprijs van " + euro(cost)));
            }
            book.put(product.id(), new ResolvedPrice(product.id(), basis.salesUnit(), basis.piecesPerUnit(),
                    product.packaging().unitKey(), catalogue, customer, printed, source));
        }
        return new PriceBook(book, warnings, oneOff);
    }

    /** Every one-off price must name a selected product once and be printable as typed. */
    private static Map<Long, PriceOverride> acceptedOverrides(List<PriceOverride> requested,
                                                              Map<Long, Product> selected) {
        List<PriceIssue> issues = new ArrayList<>();
        Map<Long, PriceOverride> accepted = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (PriceOverride override : requested) {
            Long id = override.productId();
            if (id == null) {
                issues.add(new PriceIssue(null, "PRODUCT_MISSING", "Een aangepaste prijs noemt geen product"));
                continue;
            }
            Product product = selected.get(id);
            String label = product == null ? "Product #" + id : label(product);
            if (!seen.add(id)) {
                accepted.remove(id);
                issues.add(new PriceIssue(id, "DUPLICATE", label + " staat twee keer bij de aangepaste prijzen"));
                continue;
            }
            if (product == null) {
                issues.add(new PriceIssue(id, "NOT_SELECTED", label + " staat niet in deze catalogus"));
                continue;
            }
            PriceIssue issue = overrideIssue(product, override, label);
            if (issue != null) issues.add(issue);
            else accepted.put(id, override);
        }
        if (!issues.isEmpty()) {
            throw new CatalogPriceIssuesException(issues.size() == 1 ? issues.getFirst().message()
                    : issues.size() + " aangepaste prijzen kunnen zo niet in de PDF", issues);
        }
        return accepted;
    }

    private static PriceIssue overrideIssue(Product product, PriceOverride override, String label) {
        Long id = product.id();
        if (override.onRequestAsked()) {
            return override.priceEur() == null ? null : new PriceIssue(id, "ON_REQUEST_WITH_PRICE",
                    label + ": kies een prijs of 'Op aanvraag', niet allebei");
        }
        BigDecimal price = override.priceEur();
        if (price == null) return new PriceIssue(id, "PRICE_MISSING", label + ": de aangepaste prijs ontbreekt");
        if (price.signum() <= 0 || price.compareTo(PRICE_LIMIT) >= 0) {
            return new PriceIssue(id, "PRICE_OUT_OF_RANGE", label + ": een prijs ligt tussen 0 en 1 000 000 euro");
        }
        if (price.stripTrailingZeros().scale() > 2) {
            return new PriceIssue(id, "TOO_MANY_DECIMALS", label + ": een prijs heeft hoogstens 2 decimalen");
        }
        if (override.basis() == null) {
            return new PriceIssue(id, "BASIS_MISSING", label + ": de prijsbasis (stuk of display) ontbreekt");
        }
        PriceBasis now = PriceBasis.of(product.packaging());
        if (!now.matches(override.basis(), override.piecesPerUnit())) {
            return new PriceIssue(id, "BASIS_CHANGED", label + ": de prijs is ingegeven per "
                    + new PriceBasis(override.basis(), override.piecesPerUnit()).dutchLabel()
                    + ", het product wordt nu per " + now.dutchLabel() + " verkocht; geef de prijs opnieuw in");
        }
        return null;
    }

    /** The stored customer price when it applies; an expired one or one on an older basis is left out with a warning. */
    private static BigDecimal customerPrice(Product product, PriceBasis basis, CatalogRecipients.StoredPrice stored,
                                            LocalDate today, List<PriceIssue> warnings) {
        if (stored == null || positive(stored.priceExclVat()) == null) return null;
        if (!basis.matches(stored.salesUnit(), stored.piecesPerUnit())) {
            warnings.add(new PriceIssue(product.id(), "CUSTOMER_PRICE_BASIS_CHANGED", label(product)
                    + ": de klantprijs geldt voor een andere prijsbasis en staat niet in deze PDF"));
            return null;
        }
        if (stored.validUntil() != null && stored.validUntil().isBefore(today)) {
            warnings.add(new PriceIssue(product.id(), "CUSTOMER_PRICE_EXPIRED", label(product)
                    + ": de klantprijs is verlopen en staat niet in deze PDF"));
            return null;
        }
        return stored.priceExclVat();
    }

    /** A display sold per display needs its piece count before the catalogue can word its price. */
    private static boolean printable(Product product) {
        Integer pieces = product.packaging().piecesPerUnit();
        return !product.packaging().soldAsDisplay() || (pieces != null && pieces > 1);
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static String label(Product product) {
        return product.sku() == null || product.sku().isBlank() ? "Product #" + product.id() : product.sku().strip();
    }

    private static String euro(BigDecimal amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("nl-BE"));
        format.setCurrency(java.util.Currency.getInstance("EUR"));
        return format.format(amount);
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
