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
import be.enrosed.shared.BusinessRuleException;
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

import java.awt.Color;
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
    private static final int OVERVIEW_CARDS_PER_PAGE = 8;
    /* Real four-variant families with complete translated sales copy still fit the compact
       A4 composition. Keep a generous guard for pathological dashboard content without
       rejecting normal, print-ready product families. */
    private static final int FAMILY_PAGE_CAPACITY = 3_400;
    private static final int OVERVIEW_COLUMNS = 2;
    private static final Color CATALOG_IMAGE_BACKGROUND = new Color(255, 252, 248);

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
                       PhotoLayout photos) {}

    public record Section(String number, String name, String description, List<List<Item>> rows) {}

    public record BrochureVariant(
            String sku, String name, String colour, String size, String colourHex,
            String productSize, String cartonSize, int piecesPerCarton,
            String ean, String priceLabel) {}

    public record BrochureFamily(
            String anchor, String number, String name, String summary, String description, String format,
            List<String> highlights, String categoryKey, String categoryName, String familySize,
            String packageLine, String overviewImage, PhotoLayout photos,
            String referencePriceLabel, boolean compactDetail,
            List<BrochureVariant> variants) {}

    public record BrochureSection(
            String number, String name, String description, String categoryKey,
            EditorialLayout images,
            String familyCountLabel, List<BrochureFamily> families) {}

    public record PhotoTile(String image, String cssClass, int rowSpan) {}

    public record PhotoRow(List<PhotoTile> tiles) {}

    public record PhotoLayout(
            String kind, boolean present, int count, List<PhotoRow> rows, List<String> extras) {
        static PhotoLayout empty() {
            return new PhotoLayout("empty", false, 0, List.of(), List.of());
        }
    }

    public record EditorialTile(String image, int columnSpan) {}

    public record EditorialRow(String cssClass, List<EditorialTile> tiles) {}

    public record EditorialLayout(
            String kind, boolean present, List<EditorialRow> rows) {
        static EditorialLayout empty() {
            return new EditorialLayout("empty", false, List.of());
        }
    }

    public record OverviewCard(
            boolean placeholder, boolean wide, String anchor, String number, String name,
            String categoryKey, String categoryName, String image, String variantCountLabel,
            String referencePriceLabel) {

        static OverviewCard empty() {
            return new OverviewCard(true, false, "", "", "", "", "", null, "", null);
        }

        OverviewCard asWide() {
            return new OverviewCard(placeholder, true, anchor, number, name, categoryKey,
                    categoryName, image, variantCountLabel, referencePriceLabel);
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
            List<String> missing = missingTranslations(catalog);
            if (!missing.isEmpty()) {
                throw new LocalizationIncompleteException(
                        "Cataloguscopy voor " + language.code() + " is onvolledig", missing);
            }
        }
        return catalog.request().resolvedLayout() == CatalogExportService.Layout.BROCHURE
                ? brochureHtml(catalog) : simpleHtml(catalog);
    }

    @Override
    public List<String> missingTranslations(CatalogExportService.Model catalog) {
        return strictMissing(catalog, catalogLanguage(catalog.request().language()));
    }

    private String simpleHtml(CatalogExportService.Model catalog) {
        CatalogExportService.Request request = catalog.request();
        Language language = catalogLanguage(request.language());
        Map<String, String> copy = content.values(ContentScope.CATALOG, language);
        PhotoResolver photos = new PhotoResolver();
        Set<Long> catalogueFamilyPhotoIds = catalog.families().stream()
                .map(CatalogExportService.FamilyGroup::content)
                .filter(Objects::nonNull)
                .flatMap(family -> family.photos().stream())
                .map(CatalogFamilyReader.GalleryPhoto::id)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Map<Long, List<Item>> byCategory = new LinkedHashMap<>();
        List<Category> ordered = catalog.categoriesById().values().stream()
                .sorted(Comparator.comparingInt(Category::position))
                .toList();
        for (Category category : ordered) byCategory.put(category.id(), new ArrayList<>());
        List<Item> uncategorised = new ArrayList<>();
        for (Product product : catalog.products()) {
            Item item = simpleItem(
                    product, language, request, photos, copy, catalogueFamilyPhotoIds);
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
        PhotoResolver photos = new PhotoResolver();
        List<BrochureFamily> allFamilies = new ArrayList<>();
        List<FamilyRenderData> renderedFamilies = new ArrayList<>();
        Map<String, List<FamilyRenderData>> byCategory = new LinkedHashMap<>();
        Map<String, Category> categoryByKey = new LinkedHashMap<>();
        CompanyProfile profile = company.get();

        int index = 1;
        for (CatalogExportService.FamilyGroup group : catalog.families()) {
            FamilyRenderData rendered = brochureFamily(
                    group, language, request, photos, copy, index++);
            BrochureFamily family = rendered.family();
            allFamilies.add(family);
            renderedFamilies.add(rendered);
            String key = categoryKey(group.category(), group.content());
            byCategory.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rendered);
            categoryByKey.putIfAbsent(key, group.category());
        }

        List<BrochureSection> sections = new ArrayList<>();
        int sectionIndex = 1;
        for (Map.Entry<String, List<FamilyRenderData>> entry : byCategory.entrySet()) {
            Category category = categoryByKey.get(entry.getKey());
            String name = category != null && present(category.nameIn(language))
                    ? category.nameIn(language) : entry.getValue().getFirst().family().categoryName();
            String description = category == null ? null : category.descriptionIn(language);
            List<PhotoRef> categoryPhotos = photos.diverseFamilyPhotos(entry.getValue());
            sections.add(new BrochureSection(twoDigits(sectionIndex++), name, description,
                    entry.getValue().getFirst().family().categoryKey(),
                    photos.editorialLayout(categoryPhotos, false),
                    countLabel(entry.getValue().size(),
                            copy(copy, "catalog.common.selectedfamily.singular"),
                            copy(copy, "catalog.common.selectedfamily.plural")),
                    entry.getValue().stream().map(FamilyRenderData::family).toList()));
        }

        List<PhotoRef> selectedPhotos = photos.diverseFamilyPhotos(renderedFamilies);
        EditorialLayout coverImages = photos.editorialLayout(selectedPhotos, false);
        EditorialLayout backImages = photos.editorialLayout(selectedPhotos, true);

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
                .data("coverImages", coverImages)
                .data("backImages", backImages)
                .data("customisationImage", editorial.image("flowerbox-hero.jpg"))
                .data("year", LocalDate.now().getYear())
                .data("languageCode", language.code())
                .render();
    }

    private Item simpleItem(Product product, Language language,
                            CatalogExportService.Request request, PhotoResolver photos,
                            Map<String, String> copy, Set<Long> catalogueFamilyPhotoIds) {
        int allowed = request.resolvedPhotosPerProduct();
        List<PhotoRef> imageRefs = new ArrayList<>();
        Set<String> imageKeys = new LinkedHashSet<>();
        for (Photo photo : product.photos()) {
            if (imageRefs.size() >= allowed) break;
            /* Product projections can contain family photos from every publication channel.
               An inherited photo is safe only when the CATALOGUE-filtered family reader
               exposed that exact canonical family-photo row for this export. */
            if (photo.inherited() && !catalogueFamilyPhotoIds.contains(photo.familyPhotoId())) {
                continue;
            }
            PhotoRef ref = photos.productRef(photo);
            if (ref != null && imageKeys.add(ref.storageKey()) && photos.usable(ref)) {
                imageRefs.add(ref);
            }
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
                photos.simpleLayout(imageRefs));
    }

    private FamilyRenderData brochureFamily(
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

        int allowed = request.resolvedPhotosPerProduct();
        List<PhotoRef> imageRefs = new ArrayList<>();
        Set<String> imageKeys = new LinkedHashSet<>();
        if (allowed > 0 && family != null) {
            Set<Long> selectedIds = group.variants().stream().map(Product::id)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            for (CatalogFamilyReader.GalleryPhoto photo : family.photos()) {
                if (imageRefs.size() >= allowed) break;
                if (photo.variantProductId() != null && !selectedIds.contains(photo.variantProductId())) {
                    continue;
                }
                PhotoRef ref = photos.familyRef(photo);
                if (ref != null && imageKeys.add(ref.storageKey()) && photos.usable(ref)) {
                    imageRefs.add(ref);
                }
            }
        }
        if (allowed > 0 && imageRefs.size() < allowed) {
            for (Product variant : group.variants()) {
                for (Photo photo : variant.photos()) {
                    /* Inherited photos may be internal or selected for another channel.
                       Catalogue-family photos were already resolved through the channel-safe
                       family projection above. */
                    if (photo.inherited()) continue;
                    PhotoRef ref = photos.productRef(photo);
                    if (ref != null && imageKeys.add(ref.storageKey()) && photos.usable(ref)) {
                        imageRefs.add(ref);
                    }
                    if (imageRefs.size() >= allowed) break;
                }
                if (imageRefs.size() >= allowed) break;
            }
        }
        List<PhotoRef> detailOrder = photos.preferLargeLead(imageRefs);
        String overviewImage = detailOrder.isEmpty()
                ? null : photos.overview(detailOrder.getFirst());
        PhotoLayout photoLayout = photos.brochureLayout(detailOrder);

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

        String familySize = familyDimension(family, first);
        String packageLine = packageLine(family, group.variants(), copy);
        String referencePriceLabel = request.includePrices()
                ? referencePriceLabel(group.variants(), language, copy) : null;
        String number = twoDigits(index);
        ensureFamilyPageCapacity(name, summary, description, format, highlights,
                categoryName, familySize, packageLine, referencePriceLabel,
                variants, photoLayout.extras().size());
        BrochureFamily rendered = new BrochureFamily(
                "family-" + number, number, defaultText(name, first.sku()), summary, description, format,
                highlights, categoryKey, categoryName, familySize,
                packageLine, overviewImage, photoLayout, referencePriceLabel,
                compactDetail(summary, description, highlights, variants.size(),
                        photoLayout.extras().size()),
                variants);
        return new FamilyRenderData(rendered, List.copyOf(detailOrder));
    }

    private static List<OverviewPage> overviewPages(
            List<BrochureFamily> families, Map<String, String> copy) {
        if (families.isEmpty()) return List.of();
        int pageCount = (int) Math.ceil(families.size() / (double) OVERVIEW_CARDS_PER_PAGE);
        int basePageSize = families.size() / pageCount;
        int largerPages = families.size() % pageCount;
        List<OverviewPage> pages = new ArrayList<>();
        int from = 0;
        for (int page = 0; page < pageCount; page++) {
            int pageSize = basePageSize + (page < largerPages ? 1 : 0);
            int to = Math.min(from + pageSize, families.size());
            if (from >= to) break;
            List<BrochureFamily> slice = families.subList(from, to);
            List<OverviewCard> cards = slice.stream().map(family -> new OverviewCard(
                    false, false, family.anchor(), family.number(), family.name(), family.categoryKey(),
                    family.categoryName(), family.overviewImage(), countLabel(family.variants().size(),
                            copy(copy, "catalog.common.variant.singular"),
                            copy(copy, "catalog.common.variant.plural")),
                    family.referencePriceLabel())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            List<List<OverviewCard>> cardRows = new ArrayList<>();
            for (int index = 0; index < cards.size(); index += OVERVIEW_COLUMNS) {
                int end = Math.min(index + OVERVIEW_COLUMNS, cards.size());
                if (end - index == 1) {
                    cardRows.add(List.of(cards.get(index).asWide()));
                } else {
                    cardRows.add(List.copyOf(cards.subList(index, end)));
                }
            }
            pages.add(new OverviewPage(page + 1, pageCount, cardRows.size() == 4,
                    List.copyOf(cardRows)));
            from = to;
        }
        return List.copyOf(pages);
    }

    private static boolean compactDetail(
            String summary, String description, List<String> highlights,
            int variantCount, int galleryCount) {
        int copyLength = textLength(summary) + textLength(description)
                + highlights.stream().mapToInt(PdfCatalogRenderer::textLength).sum();
        return variantCount >= 4 || galleryCount > 0 || copyLength > 520;
    }

    /** Prevents a fixed print sheet from silently clipping extreme dashboard content. */
    private static void ensureFamilyPageCapacity(
            String name, String summary, String description, String format,
            List<String> highlights, String categoryName, String familySize,
            String packageLine, String referencePriceLabel,
            List<BrochureVariant> variants, int galleryCount) {
        int capacityUse = capacityText(name, 80)
                + textLength(summary)
                + textLength(description)
                + capacityText(format, 120)
                + highlights.stream().mapToInt(PdfCatalogRenderer::textLength).sum()
                + capacityText(categoryName, 48)
                + capacityText(familySize, 48)
                + capacityText(packageLine, 80)
                + capacityText(referencePriceLabel, 24)
                + variants.size() * 150
                + variants.stream().mapToInt(PdfCatalogRenderer::variantCapacityUse).sum()
                + galleryCount * 180;
        if (capacityUse <= FAMILY_PAGE_CAPACITY) return;
        throw new BusinessRuleException("Cataloguspagina voor '" + defaultText(name, "product")
                + "' bevat te veel tekst, varianten of beelden voor één vaste A4-pagina. "
                + "Verkort de teksten of uitzonderlijk lange productvelden, of maak een "
                + "kleinere productselectie.");
    }

    private static int variantCapacityUse(BrochureVariant variant) {
        return capacityText(variant.sku(), 24)
                + capacityText(variant.name(), 80)
                + capacityText(variant.colour(), 32)
                + capacityText(variant.size(), 24)
                + capacityText(variant.productSize(), 40)
                + capacityText(variant.cartonSize(), 48)
                + capacityText(variant.ean(), 24)
                + capacityText(variant.priceLabel(), 24);
    }

    /** Narrow table cells consume extra vertical space once their comfortable line length is exceeded. */
    private static int capacityText(String value, int comfortableLength) {
        int length = textLength(value);
        return length + Math.max(0, length - comfortableLength) * 5;
    }

    private static int textLength(String value) {
        return value == null ? 0 : value.strip().length();
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

    private static List<PhotoRef> uniquePhotos(List<PhotoRef> refs) {
        Map<String, PhotoRef> unique = new LinkedHashMap<>();
        for (PhotoRef ref : refs) {
            if (ref != null && present(ref.storageKey())) unique.putIfAbsent(ref.storageKey(), ref);
        }
        return List.copyOf(unique.values());
    }

    private static PhotoLayout photoLayout(List<String> rawImages) {
        List<String> images = rawImages.stream().filter(PdfCatalogRenderer::present)
                .distinct().toList();
        int count = images.size();
        if (count == 0) return PhotoLayout.empty();
        if (count == 1) {
            return new PhotoLayout("one", true, 1,
                    List.of(new PhotoRow(List.of(
                            new PhotoTile(images.getFirst(), "single", 1)))), List.of());
        }
        if (count == 2) {
            return new PhotoLayout("two", true, 2,
                    List.of(new PhotoRow(List.of(
                            new PhotoTile(images.get(0), "half", 1),
                            new PhotoTile(images.get(1), "half", 1)))), List.of());
        }
        if (count == 3) {
            return new PhotoLayout("three", true, 3, List.of(
                    new PhotoRow(List.of(
                            new PhotoTile(images.get(0), "lead", 2),
                            new PhotoTile(images.get(1), "stack", 1))),
                    new PhotoRow(List.of(
                            new PhotoTile(images.get(2), "stack", 1)))), List.of());
        }
        return new PhotoLayout("four-plus", true, count, List.of(
                new PhotoRow(List.of(
                        new PhotoTile(images.get(0), "quarter", 1),
                        new PhotoTile(images.get(1), "quarter", 1))),
                new PhotoRow(List.of(
                        new PhotoTile(images.get(2), "quarter", 1),
                        new PhotoTile(images.get(3), "quarter", 1)))),
                count == 4 ? List.of() : List.copyOf(images.subList(4, count)));
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

    private enum PhotoOwner { PRODUCT, FAMILY }

    private record PhotoRef(String storageKey, PhotoOwner owner) {}

    private record FamilyRenderData(BrochureFamily family, List<PhotoRef> sourcePhotos) {}

    /** One render-local cache keeps all-collection exports from decoding the same blob repeatedly. */
    private final class PhotoResolver {
        private final Map<String, byte[]> sourceCache = new HashMap<>();
        private final Map<String, String> renditionCache = new HashMap<>();
        private final Map<String, PdfImageEncoder.ImageSize> dimensionsCache = new HashMap<>();
        private final Set<String> failed = new LinkedHashSet<>();

        PhotoRef productRef(Photo photo) {
            return photo == null || !present(photo.storageKey())
                    ? null : new PhotoRef(photo.storageKey(), PhotoOwner.PRODUCT);
        }

        PhotoRef familyRef(CatalogFamilyReader.GalleryPhoto photo) {
            return photo == null || !present(photo.storageKey())
                    ? null : new PhotoRef(photo.storageKey(), PhotoOwner.FAMILY);
        }

        List<PhotoRef> preferLargeLead(List<PhotoRef> refs) {
            List<PhotoRef> result = new ArrayList<>(uniquePhotos(refs));
            /* Only the three-photo composition has one materially larger tile. Equal two/four
               grids retain the user's variantPosition/photo order exactly. */
            if (result.size() != 3) return List.copyOf(result);
            int best = bestLeadIndex(result, 7d / 6d);
            if (best > 0) result.add(0, result.remove(best));
            return List.copyOf(result);
        }

        List<PhotoRef> diverseFamilyPhotos(List<FamilyRenderData> families) {
            Map<String, PhotoRef> diverse = new LinkedHashMap<>();
            /* First pass: one strongest portrait/editorial candidate per family. This prevents
               a cover from becoming four colour variants of the first product range. */
            for (FamilyRenderData family : families) {
                List<PhotoRef> refs = uniquePhotos(family.sourcePhotos());
                if (refs.isEmpty()) continue;
                PhotoRef lead = refs.get(bestLeadIndex(refs, 210d / 297d));
                diverse.putIfAbsent(lead.storageKey(), lead);
            }
            /* Second pass: only after family diversity is secured may extra family photos fill
               an editorial mosaic. Explicit photo order stays the fallback order. */
            for (FamilyRenderData family : families) {
                for (PhotoRef ref : uniquePhotos(family.sourcePhotos())) {
                    diverse.putIfAbsent(ref.storageKey(), ref);
                }
            }
            return List.copyOf(diverse.values());
        }

        PhotoLayout simpleLayout(List<PhotoRef> refs) {
            List<PhotoRef> unique = uniquePhotos(refs);
            List<String> images = new ArrayList<>();
            for (int index = 0; index < unique.size(); index++) {
                String image = simple(unique.get(index), unique.size(), index);
                if (present(image)) images.add(image);
            }
            return photoLayout(images);
        }

        PhotoLayout brochureLayout(List<PhotoRef> refs) {
            List<PhotoRef> unique = uniquePhotos(refs);
            List<String> images = new ArrayList<>();
            for (int index = 0; index < unique.size(); index++) {
                String image = detail(unique.get(index), unique.size(), index);
                if (present(image)) images.add(image);
            }
            return photoLayout(images);
        }

        EditorialLayout editorialLayout(List<PhotoRef> refs, boolean reverseOrder) {
            List<PhotoRef> forward = new ArrayList<>(uniquePhotos(refs));
            PhotoRef forwardLead = forward.isEmpty()
                    ? null : forward.get(bestLeadIndex(forward, 210d / 297d));
            List<PhotoRef> explicit = new ArrayList<>(forward);
            if (reverseOrder) {
                for (int left = 0, right = explicit.size() - 1; left < right; left++, right--) {
                    PhotoRef value = explicit.get(left);
                    explicit.set(left, explicit.get(right));
                    explicit.set(right, value);
                }
                /* Give the back cover a genuinely different lead whenever the selection allows
                   it; with four or fewer images the remaining mosaic is still reversed. */
                if (explicit.size() > 1) explicit.remove(forwardLead);
            }
            if (explicit.isEmpty()) return EditorialLayout.empty();

            int leadIndex = bestLeadIndex(explicit, 210d / 297d);
            List<PhotoRef> selected = new ArrayList<>();
            selected.add(explicit.get(leadIndex));
            for (PhotoRef ref : explicit) {
                if (selected.size() >= 4) break;
                if (!selected.contains(ref)) selected.add(ref);
            }

            int count = selected.size();
            List<String> images = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String image = editorial(selected.get(index), count, index);
                if (present(image)) images.add(image);
            }
            return editorialRows(images);
        }

        String overview(PhotoRef ref) {
            return cropped(ref, "overview-portrait", 3, 4, 1_200);
        }

        private String simple(PhotoRef ref, int count, int index) {
            if (count == 1) return cropped(ref, "simple-one", 4, 3, 1_000);
            if (count == 2) return cropped(ref, "simple-two", 2, 3, 900);
            if (count == 3) return cropped(ref,
                    index == 0 ? "simple-three-lead" : "simple-three-stack", 7, 8, 900);
            return cropped(ref, index < 4 ? "simple-grid" : "simple-extra",
                    index < 4 ? 4 : 3, index < 4 ? 3 : 2, index < 4 ? 800 : 600);
        }

        private String detail(PhotoRef ref, int count, int index) {
            if (count == 1) return cropped(ref, "detail-one", 16, 9, 2_400);
            if (count == 2) return cropped(ref, "detail-two", 5, 6, 1_600);
            if (count == 3) return cropped(ref,
                    index == 0 ? "detail-three-lead" : "detail-three-stack",
                    7, 6, index == 0 ? 1_900 : 1_300);
            return cropped(ref, index < 4 ? "detail-grid" : "detail-extra",
                    index < 4 ? 16 : 17, 9, index < 4 ? 1_500 : 900);
        }

        private String editorial(PhotoRef ref, int count, int index) {
            if (count == 1) return cropped(ref, "editorial-one", 210, 297, 2_400);
            if (count == 2) return cropped(ref, "editorial-two", 210, 149, 2_200);
            if (count == 3) return cropped(ref,
                    index == 0 ? "editorial-three-lead" : "editorial-three-tail",
                    index == 0 ? 210 : 105, index == 0 ? 178 : 119,
                    index == 0 ? 2_200 : 1_400);
            return cropped(ref, "editorial-four", 105, 149, 1_600);
        }

        boolean usable(PhotoRef ref) {
            PdfImageEncoder.ImageSize size = dimensions(ref);
            return size.width() > 0 && size.height() > 0;
        }

        private int bestLeadIndex(List<PhotoRef> refs, double targetAspect) {
            int best = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < refs.size(); index++) {
                PdfImageEncoder.ImageSize size = dimensions(refs.get(index));
                double megapixels = Math.min(4d, size.pixels() / 1_000_000d);
                double aspect = size.aspect();
                double orientation = aspect <= 0d ? 0d
                        : Math.exp(-Math.abs(Math.log(aspect / targetAspect)));
                double order = 1d / (1d + index);
                double score = megapixels * 1.9d + orientation * 2.4d + order * .8d;
                if (score > bestScore) {
                    bestScore = score;
                    best = index;
                }
            }
            return best;
        }

        private EditorialLayout editorialRows(List<String> rawImages) {
            List<String> images = rawImages.stream().filter(PdfCatalogRenderer::present)
                    .distinct().toList();
            int count = images.size();
            if (count == 0) return EditorialLayout.empty();
            if (count == 1) {
                return new EditorialLayout("one", true, List.of(
                        new EditorialRow("full", List.of(
                                new EditorialTile(images.getFirst(), 1)))));
            }
            if (count == 2) {
                return new EditorialLayout("two", true, List.of(
                        new EditorialRow("half", List.of(
                                new EditorialTile(images.get(0), 1))),
                        new EditorialRow("half", List.of(
                                new EditorialTile(images.get(1), 1)))));
            }
            if (count == 3) {
                return new EditorialLayout("three", true, List.of(
                        new EditorialRow("lead", List.of(
                                new EditorialTile(images.get(0), 2))),
                        new EditorialRow("tail", List.of(
                                new EditorialTile(images.get(1), 1),
                                new EditorialTile(images.get(2), 1)))));
            }
            return new EditorialLayout("four-plus", true, List.of(
                    new EditorialRow("half", List.of(
                            new EditorialTile(images.get(0), 1),
                            new EditorialTile(images.get(1), 1))),
                    new EditorialRow("half", List.of(
                            new EditorialTile(images.get(2), 1),
                            new EditorialTile(images.get(3), 1)))));
        }

        private String cropped(
                PhotoRef ref, String rendition, int aspectWidth, int aspectHeight, int maxEdge) {
            if (ref == null) return null;
            String value = renditionCache.computeIfAbsent(cacheKey(ref, rendition), ignored -> {
                String uri = imageEncoder.encodeCoverCropped(
                        source(ref), aspectWidth, aspectHeight, maxEdge, CATALOG_IMAGE_BACKGROUND);
                if (uri == null) failed(ref.storageKey(), null);
                return defaultText(uri, "");
            });
            return present(value) ? value : null;
        }

        private PdfImageEncoder.ImageSize dimensions(PhotoRef ref) {
            if (ref == null) return new PdfImageEncoder.ImageSize(0, 0);
            return dimensionsCache.computeIfAbsent(cacheKey(ref, "dimensions"),
                    ignored -> imageEncoder.inspect(source(ref)));
        }

        private byte[] source(PhotoRef ref) {
            if (ref == null) return new byte[0];
            return sourceCache.computeIfAbsent(cacheKey(ref, "source"), ignored -> {
                try (InputStream in = ref.owner() == PhotoOwner.FAMILY
                        ? photoStorage.read(ref.storageKey())
                        : products.photoData(ref.storageKey())) {
                    return in.readAllBytes();
                } catch (Exception exception) {
                    failed(ref.storageKey(), exception);
                    return new byte[0];
                }
            });
        }

        private String cacheKey(PhotoRef ref, String rendition) {
            return ref.owner().name() + ':' + ref.storageKey() + ':' + rendition;
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
