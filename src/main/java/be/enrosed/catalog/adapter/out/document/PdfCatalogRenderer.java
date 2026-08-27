package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.ContentTranslationService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import be.enrosed.shared.LocalizationIncompleteException;
import be.enrosed.shared.company.CompanyProfile;
import be.enrosed.shared.company.CompanyProfileService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Premium compact catalogue and family-based ENROSED wholesale brochure. */
@ApplicationScoped
public class PdfCatalogRenderer implements CatalogDocumentRenderer {

    private static final Logger LOG = Logger.getLogger(PdfCatalogRenderer.class);

    private final Template simpleTemplate;
    private final Template brochureTemplate;
    private final be.enrosed.catalog.application.ProductService products;
    private final PhotoStorage photoStorage;
    private final Brand brand;
    private final CompanyProfileService company;
    private final CatalogPdfFonts fonts;
    private final PdfImageEncoder imageEncoder;
    private final CatalogEditorialAssets editorial;
    private final ContentTranslationService content;

    public PdfCatalogRenderer(@Location("catalog.html") Template simpleTemplate,
                              @Location("catalog-brochure.html") Template brochureTemplate,
                              be.enrosed.catalog.application.ProductService products,
                              PhotoStorage photoStorage,
                              Brand brand,
                              CompanyProfileService company,
                              CatalogPdfFonts fonts,
                              PdfImageEncoder imageEncoder,
                              CatalogEditorialAssets editorial,
                              ContentTranslationService content) {
        this.simpleTemplate = simpleTemplate;
        this.brochureTemplate = brochureTemplate;
        this.products = products;
        this.photoStorage = photoStorage;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
        this.imageEncoder = imageEncoder;
        this.editorial = editorial;
        this.content = content;
    }

    /** One compact, SKU-level card. */
    public record Item(String sku, String name, String size, String colour, String variantSize,
                       String barcodeInner, String barcodeOuter,
                       int piecesPerCarton, String cartonSize,
                       String priceLabel, boolean inventoryKnown, Integer stockQuantity,
                       String photoDataUri, String secondPhotoDataUri,
                       List<String> extraPhotos) {}

    public record Section(String number, String name, String description, List<List<Item>> rows) {}

    public record BrochureVariant(
            String sku, String name, String colour, String size, String colourHex,
            String productSize, String cartonSize, int piecesPerCarton,
            String ean, String priceLabel) {}

    public record BrochureFamily(
            String anchor, String number, String name, String summary, String description, String format,
            List<String> highlights, String categoryKey, String categoryName, String familySize,
            String packageLine, String heroImage, String secondImage, String referencePriceLabel,
            List<String> gallery, List<BrochureVariant> variants) {}

    public record BrochureSection(
            String number, String name, String description, String image,
            String familyCountLabel, List<BrochureFamily> families) {}

    public record OverviewCard(
            boolean placeholder, String anchor, String number, String name,
            String categoryKey, String categoryName, String image, String variantCountLabel,
            String referencePriceLabel) {

        static OverviewCard empty() {
            return new OverviewCard(true, "", "", "", "", "", null, "", null);
        }
    }

    public record OverviewPage(int number, int total, boolean compact,
                               List<List<OverviewCard>> rows) {}

    @Override
    public Document render(CatalogExportService.Model catalog) {
        boolean brochure = catalog.request().resolvedLayout() == CatalogExportService.Layout.BROCHURE;
        String html = renderHtml(catalog);
        return new Document(
                brochure ? "enrosed-wholesale-brochure.pdf" : "enrosed-catalogus.pdf",
                fonts.render(html), "application/pdf");
    }

    /** Package-visible for focused template tests without extracting text from compressed PDFs. */
    String renderHtml(CatalogExportService.Model catalog) {
        Language language = catalogLanguage(catalog.request().language());
        if (catalog.request().resolvedStrictLanguage()) {
            List<String> missing = strictMissing(catalog, language);
            if (!missing.isEmpty()) {
                throw new LocalizationIncompleteException(
                        "Cataloguscopy voor " + language.code() + " is onvolledig", missing);
            }
        }
        return catalog.request().resolvedLayout() == CatalogExportService.Layout.BROCHURE
                ? brochureHtml(catalog) : simpleHtml(catalog);
    }

    private String simpleHtml(CatalogExportService.Model catalog) {
        CatalogExportService.Request request = catalog.request();
        Language language = catalogLanguage(request.language());
        Map<String, String> copy = content.values(ContentScope.CATALOG, language);
        PhotoResolver photos = new PhotoResolver(700);

        Map<Long, List<Item>> byCategory = new LinkedHashMap<>();
        List<Category> ordered = catalog.categoriesById().values().stream()
                .sorted(Comparator.comparingInt(Category::position))
                .toList();
        for (Category category : ordered) byCategory.put(category.id(), new ArrayList<>());
        List<Item> uncategorised = new ArrayList<>();
        for (Product product : catalog.products()) {
            Item item = simpleItem(product, language, request, photos, copy);
            List<Item> bucket = product.categoryId() == null
                    ? uncategorised : byCategory.get(product.categoryId());
            (bucket == null ? uncategorised : bucket).add(item);
        }

        List<Section> sections = new ArrayList<>();
        int chapter = 1;
        for (Category category : ordered) {
            List<Item> items = byCategory.get(category.id());
            if (items.isEmpty()) continue;
            sections.add(new Section(twoDigits(chapter++), category.nameIn(language),
                    category.descriptionIn(language), chunk(items)));
        }
        if (!uncategorised.isEmpty()) {
            sections.add(new Section(twoDigits(chapter), null, null, chunk(uncategorised)));
        }

        CompanyProfile profile = company.get();
        String title = present(request.title()) ? request.title().trim()
                : copy(copy, "catalog.simple.title");
        return simpleTemplate
                .data("sections", sections)
                .data("itemCount", catalog.products().size())
                .data("itemCountLabel", countLabel(catalog.products().size(),
                        copy(copy, "catalog.simple.item.singular"),
                        copy(copy, "catalog.simple.item.plural")))
                .data("title", title)
                .data("intro", request.intro())
                .data("todayText", DocumentText.date(LocalDate.now(), language))
                .data("languageCode", language.code())
                .data("logo", editorial.image("logo-gold.png"))
                .data("company", profile)
                .data("copy", copy)
                .render();
    }

    private String brochureHtml(CatalogExportService.Model catalog) {
        CatalogExportService.Request request = catalog.request();
        Language language = catalogLanguage(request.language());
        Map<String, String> copy = content.values(ContentScope.CATALOG, language);
        CatalogExportService.BrochureOptions requestedOptions = request.resolvedBrochure();
        CatalogExportService.BrochureOptions options = new CatalogExportService.BrochureOptions(
                requestedOptions.includeOverview(), requestedOptions.includeCategoryIntros(),
                requestedOptions.includeCustomisation(), requestedOptions.includeOrdering(),
                requestedOptions.includeBackCover(),
                defaultText(requestedOptions.coverTitle(),
                        copy(copy, "catalog.brochure.defaulttitle")),
                defaultText(requestedOptions.coverSubtitle(),
                        copy(copy, "catalog.brochure.defaultsubtitle")));
        PhotoResolver photos = new PhotoResolver(1_600);
        List<BrochureFamily> allFamilies = new ArrayList<>();
        Map<String, List<BrochureFamily>> byCategory = new LinkedHashMap<>();
        Map<String, Category> categoryByKey = new LinkedHashMap<>();
        CompanyProfile profile = company.get();

        int index = 1;
        for (CatalogExportService.FamilyGroup group : catalog.families()) {
            BrochureFamily family = brochureFamily(
                    group, language, request, photos, copy, index++);
            allFamilies.add(family);
            String key = categoryKey(group.category(), group.content());
            byCategory.computeIfAbsent(key, ignored -> new ArrayList<>()).add(family);
            categoryByKey.putIfAbsent(key, group.category());
        }

        List<BrochureSection> sections = new ArrayList<>();
        int sectionIndex = 1;
        for (Map.Entry<String, List<BrochureFamily>> entry : byCategory.entrySet()) {
            Category category = categoryByKey.get(entry.getKey());
            String name = category != null && present(category.nameIn(language))
                    ? category.nameIn(language) : entry.getValue().getFirst().categoryName();
            String description = category == null ? null : category.descriptionIn(language);
            sections.add(new BrochureSection(twoDigits(sectionIndex++), name, description,
                    editorial.image(categoryAsset(entry.getValue().getFirst().categoryKey())),
                    countLabel(entry.getValue().size(),
                            copy(copy, "catalog.common.selectedfamily.singular"),
                            copy(copy, "catalog.common.selectedfamily.plural")),
                    List.copyOf(entry.getValue())));
        }

        List<OverviewPage> overviewPages = overviewPages(allFamilies, copy);
        String title = present(request.title())
                ? request.title().trim()
                : copy(copy, "catalog.brochure.intro.eyebrow") + " "
                        + LocalDate.now().getYear();
        String intro = present(request.intro()) ? request.intro().trim()
                : copy(copy, "catalog.brochure.defaultintro");

        return brochureTemplate
                .data("title", title)
                .data("intro", intro)
                .data("itemCount", catalog.products().size())
                .data("familyCount", allFamilies.size())
                .data("familyCountLabel", countLabel(allFamilies.size(),
                        copy(copy, "catalog.common.family.singular"),
                        copy(copy, "catalog.common.family.plural")))
                .data("variantCountLabel", countLabel(catalog.products().size(),
                        copy(copy, "catalog.common.variant.singular"),
                        copy(copy, "catalog.common.variant.plural")))
                .data("families", allFamilies)
                .data("sections", sections)
                .data("overviewPages", overviewPages)
                .data("options", options)
                .data("includePrices", request.includePrices())
                .data("copy", copy)
                .data("company", profile)
                .data("logo", editorial.image("logo-gold.png"))
                .data("coverImage", editorial.image("catalog-cover-v2.jpg"))
                .data("introDisplayImage", editorial.image("counter-bowl-retail.jpg"))
                .data("customisationImage", editorial.image("flowerbox-hero.jpg"))
                .data("year", LocalDate.now().getYear())
                .data("languageCode", language.code())
                .render();
    }

    private Item simpleItem(Product product, Language language,
                            CatalogExportService.Request request, PhotoResolver photos,
                            Map<String, String> copy) {
        int allowed = request.resolvedPhotosPerProduct();
        List<String> images = new ArrayList<>();
        for (int index = 0; index < product.photos().size() && index < allowed; index++) {
            String uri = photos.product(product.photos().get(index));
            if (uri != null) images.add(uri);
        }
        return new Item(
                product.sku(), product.nameIn(language), dimensionLabel(product.dimensions()),
                product.colourIn(language), product.variantSizeIn(language),
                product.barcodes() == null ? null : product.barcodes().inner(),
                product.barcodes() == null ? null : product.barcodes().outer(),
                product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                product.carton() == null || product.carton().dimensions() == null
                        ? "" : dimensionLabel(product.carton().dimensions()),
                request.includePrices()
                        ? defaultText(priceLabel(product, language),
                                copy(copy, "catalog.brochure.overview.priceonrequest"))
                        : null,
                product.inventoryKnown(), product.inventoryKnown() ? product.stockQuantity() : null,
                at(images, 0), at(images, 1), images.size() <= 2
                        ? List.of() : List.copyOf(images.subList(2, images.size())));
    }

    private BrochureFamily brochureFamily(
            CatalogExportService.FamilyGroup group, Language language,
            CatalogExportService.Request request, PhotoResolver photos,
            Map<String, String> copy, int index) {
        Product first = group.variants().getFirst();
        CatalogFamilyReader.Family family = group.content();
        String name = family == null ? first.nameIn(language) : family.nameIn(language);
        String summary = family == null ? first.descriptionIn(language) : family.summaryIn(language);
        String description = family == null ? first.descriptionIn(language)
                : family.descriptionIn(language);
        String format = family == null ? null : family.formatIn(language);
        List<String> highlights = family == null ? List.of() : family.highlightsIn(language);
        String categoryKey = group.category() == null
                ? family == null ? "" : defaultText(family.categoryKey(), "")
                : defaultText(group.category().code(), "");
        String categoryName = group.category() == null
                ? copy(copy, "catalog.common.collection")
                : group.category().nameIn(language);

        int allowed = Math.min(4, request.resolvedPhotosPerProduct());
        List<String> images = new ArrayList<>();
        if (allowed > 0 && family != null) {
            Set<Long> selectedIds = group.variants().stream().map(Product::id)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            for (CatalogFamilyReader.GalleryPhoto photo : family.photos()) {
                if (images.size() >= allowed) break;
                if (photo.variantProductId() != null && !selectedIds.contains(photo.variantProductId())) {
                    continue;
                }
                String uri = photos.family(photo);
                if (uri != null && !images.contains(uri)) images.add(uri);
            }
        }
        if (allowed > 0 && images.isEmpty()) {
            outer:
            for (Product variant : group.variants()) {
                for (Photo photo : variant.photos()) {
                    String uri = photos.product(photo);
                    if (uri != null && !images.contains(uri)) images.add(uri);
                    if (images.size() >= allowed) break outer;
                }
            }
        }

        List<BrochureVariant> variants = group.variants().stream()
                .sorted(Comparator.comparingInt(Product::variantPosition)
                        .thenComparing(Product::id, Comparator.nullsLast(Long::compareTo)))
                .map(product -> new BrochureVariant(
                        product.sku(), product.nameIn(language), product.colourIn(language),
                        product.variantSizeIn(language), product.colourHex(),
                        dimensionLabel(product.dimensions()),
                        product.carton() == null ? "" : dimensionLabel(product.carton().dimensions()),
                        product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                        defaultText(product.canonicalBarcode(), "-"),
                        request.includePrices() ? priceLabel(product, language) : null))
                .toList();

        String number = twoDigits(index);
        return new BrochureFamily(
                "family-" + number, number, defaultText(name, first.sku()), summary, description, format,
                highlights, categoryKey, categoryName, familyDimension(family, first),
                packageLine(family, group.variants(), copy), at(images, 0), at(images, 1),
                request.includePrices()
                        ? referencePriceLabel(group.variants(), language, copy) : null,
                images.size() <= 2 ? List.of() : List.copyOf(images.subList(2, images.size())),
                variants);
    }

    private static List<OverviewPage> overviewPages(
            List<BrochureFamily> families, Map<String, String> copy) {
        if (families.isEmpty()) return List.of();
        int pageCount = families.size() <= 10 ? 1 : 2;
        int perPage = (int) Math.ceil(families.size() / (double) pageCount);
        List<OverviewPage> pages = new ArrayList<>();
        for (int page = 0; page < pageCount; page++) {
            int from = page * perPage;
            int to = Math.min(from + perPage, families.size());
            if (from >= to) break;
            List<BrochureFamily> slice = families.subList(from, to);
            int rows = Math.max(1, Math.min(5, (int) Math.ceil(slice.size() / 5d)));
            int columns = (int) Math.ceil(slice.size() / (double) rows);
            List<OverviewCard> cards = slice.stream().map(family -> new OverviewCard(
                    false, family.anchor(), family.number(), family.name(), family.categoryKey(),
                    family.categoryName(), family.heroImage(), countLabel(family.variants().size(),
                            copy(copy, "catalog.common.variant.singular"),
                            copy(copy, "catalog.common.variant.plural")),
                    family.referencePriceLabel())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            while (cards.size() % columns != 0) cards.add(OverviewCard.empty());
            List<List<OverviewCard>> cardRows = new ArrayList<>();
            for (int index = 0; index < cards.size(); index += columns) {
                cardRows.add(List.copyOf(cards.subList(index, index + columns)));
            }
            pages.add(new OverviewPage(page + 1, pageCount, cardRows.size() > 2,
                    List.copyOf(cardRows)));
        }
        return List.copyOf(pages);
    }

    private static String familyDimension(CatalogFamilyReader.Family family, Product first) {
        if (family != null && family.dimensions() != null) {
            CatalogFamilyReader.Dimensions size = family.dimensions();
            if (positive(size.width()) || positive(size.depth()) || positive(size.height())) {
                return axisLabel(size.width(), size.depth(), size.height(), size.unit());
            }
        }
        return dimensionLabel(first.dimensions());
    }

    private static String packageLine(
            CatalogFamilyReader.Family family, List<Product> variants,
            Map<String, String> copy) {
        if (family == null || family.packages().isEmpty()) return "";
        Set<Long> selected = variants.stream().map(Product::id).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        CatalogFamilyReader.PackageInfo item = family.packages().stream()
                .filter(candidate -> candidate.productId() == null
                        || selected.contains(candidate.productId()))
                .sorted(Comparator
                        .comparing((CatalogFamilyReader.PackageInfo candidate) ->
                                !Boolean.TRUE.equals(candidate.operational()))
                        .thenComparingInt(CatalogFamilyReader.PackageInfo::position))
                .findFirst().orElse(null);
        if (item == null) return "";
        StringBuilder line = new StringBuilder();
        if (positive(item.width()) || positive(item.depth()) || positive(item.height())) {
            line.append(axisLabel(item.width(), item.depth(), item.height(), item.dimensionUnit()));
        }
        if (item.piecesPerPackage() != null && item.piecesPerPackage() > 0) {
            if (!line.isEmpty()) line.append(" · ");
            line.append(item.piecesPerPackage()).append(' ')
                    .append(copy(copy, "catalog.common.pieces"));
        }
        return line.toString();
    }

    private static String axisLabel(BigDecimal width, BigDecimal depth, BigDecimal height,
                                    String unit) {
        return "B × D × H: " + decimal(width) + " × " + decimal(depth) + " × "
                + decimal(height) + " " + defaultText(unit, "cm");
    }

    private static String dimensionLabel(Dimensions dimensions) {
        return dimensions == null ? "" : dimensions.label();
    }

    private static String decimal(BigDecimal value) {
        return value == null || value.signum() <= 0 ? "-"
                : value.stripTrailingZeros().toPlainString();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String priceLabel(Product product, Language language) {
        BigDecimal price = product.computedSalesPriceEur();
        if (!positive(price)) return null;
        return formatPrice(price, language);
    }

    private String referencePriceLabel(
            List<Product> variants, Language language, Map<String, String> copy) {
        List<BigDecimal> prices = variants.stream()
                .map(Product::computedSalesPriceEur)
                .filter(PdfCatalogRenderer::positive)
                .map(value -> value.setScale(2, java.math.RoundingMode.HALF_UP))
                .distinct()
                .sorted()
                .toList();
        if (prices.isEmpty()) return copy(copy, "catalog.brochure.overview.priceonrequest");
        String minimum = formatPrice(prices.getFirst(), language);
        if (prices.size() == variants.size() && prices.getFirst().compareTo(prices.getLast()) == 0) {
            return minimum;
        }
        if (prices.size() == variants.size()) {
            return minimum + " - " + formatPrice(prices.getLast(), language);
        }
        return copy(copy, "catalog.brochure.overview.from") + " " + minimum + " · "
                + copy(copy, "catalog.brochure.overview.priceonrequest");
    }

    private static String formatPrice(BigDecimal price, Language language) {
        java.text.NumberFormat format = java.text.NumberFormat.getCurrencyInstance(language.locale());
        format.setCurrency(java.util.Currency.getInstance("EUR"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(price);
    }

    private static List<List<Item>> chunk(List<Item> items) {
        List<List<Item>> rows = new ArrayList<>();
        for (int index = 0; index < items.size(); index += 3) {
            rows.add(items.subList(index, Math.min(index + 3, items.size())));
        }
        return rows;
    }

    private static String categoryKey(Category category, CatalogFamilyReader.Family family) {
        if (category != null && category.id() != null) return "category:" + category.id();
        if (family != null && present(family.categoryKey())) return "key:" + family.categoryKey();
        return "uncategorised";
    }

    private static String categoryAsset(String name) {
        String key = defaultText(name, "").toLowerCase(Locale.ROOT);
        if (key.contains("counter") || key.contains("signature")) return "counter-display.jpg";
        if (key.contains("soap") || key.contains("foam") || key.contains("zeep")) {
            return "soap-roses.jpg";
        }
        return "preserved-roses.jpg";
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static String twoDigits(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }

    private static String countLabel(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private List<String> strictMissing(CatalogExportService.Model catalog, Language language) {
        List<String> missing = new ArrayList<>();
        boolean brochure = catalog.request().resolvedLayout()
                == CatalogExportService.Layout.BROCHURE;
        boolean categoryDescriptionsRendered = !brochure
                || catalog.request().resolvedBrochure().includeCategoryIntros();
        content.missingRequired(ContentScope.CATALOG, language).stream()
                .map(key -> "catalog.copy." + key).forEach(missing::add);
        Map<String, Category> renderedCategories = new LinkedHashMap<>();
        for (CatalogExportService.FamilyGroup group : catalog.families()) {
            Category category = group.category();
            if (category == null) continue;
            String key = category.id() == null ? "code:" + category.code() : "id:" + category.id();
            renderedCategories.putIfAbsent(key, category);
        }
        for (Category category : renderedCategories.values()) {
            requireSource(missing, "categories." + category.code() + ".name",
                    category.nameResolved(language), language);
            if (categoryDescriptionsRendered) {
                requireSource(missing, "categories." + category.code() + ".description",
                        category.descriptionResolved(language), language);
            }
        }
        for (CatalogExportService.FamilyGroup group : catalog.families()) {
            CatalogFamilyReader.Family family = group.content();
            if (brochure && family != null) {
                String prefix = "families." + family.familyKey();
                requireSource(missing, prefix + ".name", family.nameResolved(language), language);
                requireSource(missing, prefix + ".summary", family.summaryResolved(language), language);
                requireSource(missing, prefix + ".description",
                        family.descriptionResolved(language), language);
                if (familyUsesFormat(family)) {
                    requireSource(missing, prefix + ".format",
                            family.formatResolved(language), language);
                }
                if (familyUsesHighlights(family)) {
                    LanguageFallback.Resolved<List<String>> highlights =
                            family.highlightsResolved(language);
                    if (highlights.value() == null || highlights.value().isEmpty()
                            || highlights.sourceLanguage() != language) {
                        missing.add(prefix + ".highlights");
                    }
                }
            }
            for (Product product : group.variants()) {
                String prefix = "products." + product.id();
                if (!brochure || family == null) {
                    requireSource(missing, prefix + ".name",
                            product.nameResolved(language), language);
                }
                if (catalog.request().resolvedLayout() == CatalogExportService.Layout.BROCHURE
                        && family == null) {
                    requireSource(missing, prefix + ".description",
                            product.descriptionResolved(language), language);
                }
                if (productUsesColour(product)) {
                    requireSource(missing, prefix + ".color",
                            product.colourResolved(language), language);
                }
                if (productUsesSize(product)) {
                    requireSource(missing, prefix + ".size",
                            product.variantSizeResolved(language), language);
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(missing));
    }

    private static void requireSource(
            List<String> missing, String path,
            LanguageFallback.Resolved<String> value, Language requested) {
        if (!present(value.value()) || value.sourceLanguage() != requested) missing.add(path);
    }

    private static boolean familyUsesFormat(CatalogFamilyReader.Family family) {
        return present(family.format()) || family.texts().stream()
                .anyMatch(text -> present(text.format()));
    }

    private static boolean familyUsesHighlights(CatalogFamilyReader.Family family) {
        return !family.highlights().isEmpty() || family.texts().stream()
                .anyMatch(text -> text.highlights() != null && !text.highlights().isEmpty());
    }

    private static boolean productUsesColour(Product product) {
        return present(product.colour()) || product.texts().stream()
                .anyMatch(text -> present(text.colour()));
    }

    private static boolean productUsesSize(Product product) {
        return present(product.variantSize()) || product.texts().stream()
                .anyMatch(text -> present(text.variantSize()));
    }

    private static Language catalogLanguage(String code) {
        try {
            return Language.requireSupported(code, Language.NL);
        } catch (IllegalArgumentException exception) {
            throw new jakarta.ws.rs.BadRequestException(exception.getMessage());
        }
    }

    private static String copy(Map<String, String> values, String key) {
        String value = values.get(key);
        if (!present(value)) {
            throw new IllegalStateException("Cataloguscopy ontbreekt: " + key);
        }
        return value;
    }

    private static String defaultText(String value, String fallback) {
        return present(value) ? value : fallback;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** One render-local cache keeps all-collection exports from decoding the same blob repeatedly. */
    private final class PhotoResolver {
        private final Map<String, String> cache = new HashMap<>();
        private final Set<String> failed = new LinkedHashSet<>();
        private final int maxEdge;

        private PhotoResolver(int maxEdge) {
            this.maxEdge = maxEdge;
        }

        String product(Photo photo) {
            if (photo == null || !present(photo.storageKey())) return null;
            String value = cache.computeIfAbsent(photo.storageKey(), key -> {
                try (InputStream in = products.photoData(key)) {
                    return defaultText(encoded(key, in.readAllBytes()), "");
                } catch (Exception exception) {
                    failed(key, exception);
                    return "";
                }
            });
            return present(value) ? value : null;
        }

        String family(CatalogFamilyReader.GalleryPhoto photo) {
            if (photo == null || !present(photo.storageKey())) return null;
            String value = cache.computeIfAbsent(photo.storageKey(), key -> {
                try (InputStream in = photoStorage.read(key)) {
                    return defaultText(encoded(key, in.readAllBytes()), "");
                } catch (Exception exception) {
                    failed(key, exception);
                    return "";
                }
            });
            return present(value) ? value : null;
        }

        private String encoded(String key, byte[] bytes) {
            String uri = imageEncoder.encode(bytes, maxEdge);
            if (uri == null) failed(key, null);
            return uri;
        }

        private void failed(String key, Exception exception) {
            if (!failed.add(key)) return;
            if (exception == null) {
                LOG.warnf("Catalogusfoto %s kon niet gedecodeerd worden; placeholder gebruikt", key);
            } else {
                LOG.warnf("Catalogusfoto %s kon niet geladen worden: %s; placeholder gebruikt",
                        key, exception.getMessage());
            }
        }
    }
}
