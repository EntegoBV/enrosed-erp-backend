package be.enrosed.catalog.adapter.out.document;

import be.enrosed.catalog.application.CatalogExportService;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.application.port.out.CatalogFamilyReader;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
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

    public PdfCatalogRenderer(@Location("catalog.html") Template simpleTemplate,
                              @Location("catalog-brochure.html") Template brochureTemplate,
                              be.enrosed.catalog.application.ProductService products,
                              PhotoStorage photoStorage,
                              Brand brand,
                              CompanyProfileService company,
                              CatalogPdfFonts fonts,
                              PdfImageEncoder imageEncoder,
                              CatalogEditorialAssets editorial) {
        this.simpleTemplate = simpleTemplate;
        this.brochureTemplate = brochureTemplate;
        this.products = products;
        this.photoStorage = photoStorage;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
        this.imageEncoder = imageEncoder;
        this.editorial = editorial;
    }

    /** One compact, SKU-level card. */
    public record Item(String sku, String name, String size, String colour,
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
            String number, String name, String summary, String description, String format,
            List<String> highlights, String categoryName, String familySize,
            String packageLine, String heroImage, String secondImage,
            List<String> gallery, List<BrochureVariant> variants) {}

    public record BrochureSection(
            String number, String name, String description, String image,
            String familyCountLabel, List<BrochureFamily> families) {}

    public record ContentsEntry(String number, String name, String categoryName) {}

    public record ComparisonFamily(
            String name, String image, String sku, String productSize,
            String cartonSize, int piecesPerCarton, String ean) {}

    private enum ComparisonGroup { COUNTER, SOAP_DECORATIVE }

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
        return catalog.request().resolvedLayout() == CatalogExportService.Layout.BROCHURE
                ? brochureHtml(catalog) : simpleHtml(catalog);
    }

    private String simpleHtml(CatalogExportService.Model catalog) {
        CatalogExportService.Request request = catalog.request();
        Language language = Language.of(request.language());
        Map<String, String> text = DocumentText.of(language);
        PhotoResolver photos = new PhotoResolver(700);

        Map<Long, List<Item>> byCategory = new LinkedHashMap<>();
        List<Category> ordered = catalog.categoriesById().values().stream()
                .sorted(Comparator.comparingInt(Category::position))
                .toList();
        for (Category category : ordered) byCategory.put(category.id(), new ArrayList<>());
        List<Item> uncategorised = new ArrayList<>();
        for (Product product : catalog.products()) {
            Item item = simpleItem(product, language, request, photos);
            List<Item> bucket = product.categoryId() == null
                    ? uncategorised : byCategory.get(product.categoryId());
            (bucket == null ? uncategorised : bucket).add(item);
        }

        List<Section> sections = new ArrayList<>();
        int chapter = 1;
        for (Category category : ordered) {
            List<Item> items = byCategory.get(category.id());
            if (items.isEmpty()) continue;
            sections.add(new Section(twoDigits(chapter++), category.name(),
                    category.description(), chunk(items)));
        }
        if (!uncategorised.isEmpty()) {
            sections.add(new Section(twoDigits(chapter), null, null, chunk(uncategorised)));
        }

        CompanyProfile profile = company.get();
        String title = present(request.title()) ? request.title().trim() : text.get("catalogTitle");
        return simpleTemplate
                .data("sections", sections)
                .data("itemCount", catalog.products().size())
                .data("itemCountLabel", itemCountLabel(catalog.products().size(), language))
                .data("title", title)
                .data("intro", request.intro())
                .data("todayText", DocumentText.date(LocalDate.now(), language))
                .data("languageCode", language.code())
                .data("logo", brand.logoDataUri())
                .data("company", profile)
                .data("footerText", profile.footerFor(language))
                .data("t", text)
                .render();
    }

    private String brochureHtml(CatalogExportService.Model catalog) {
        CatalogExportService.Request request = catalog.request();
        CatalogExportService.BrochureOptions options = request.resolvedBrochure();
        Language language = Language.of(request.language());
        PhotoResolver photos = new PhotoResolver(1_600);
        List<BrochureFamily> allFamilies = new ArrayList<>();
        Map<String, List<BrochureFamily>> byCategory = new LinkedHashMap<>();
        Map<String, Category> categoryByKey = new LinkedHashMap<>();

        int index = 1;
        for (CatalogExportService.FamilyGroup group : catalog.families()) {
            BrochureFamily family = brochureFamily(group, language, request, photos, index++);
            allFamilies.add(family);
            String key = categoryKey(group.category(), group.content());
            byCategory.computeIfAbsent(key, ignored -> new ArrayList<>()).add(family);
            categoryByKey.putIfAbsent(key, group.category());
        }

        List<BrochureSection> sections = new ArrayList<>();
        int sectionIndex = 1;
        for (Map.Entry<String, List<BrochureFamily>> entry : byCategory.entrySet()) {
            Category category = categoryByKey.get(entry.getKey());
            String name = category != null && present(category.name())
                    ? category.name() : entry.getValue().getFirst().categoryName();
            String description = category != null && present(category.description())
                    ? category.description() : categoryDescription(name);
            sections.add(new BrochureSection(twoDigits(sectionIndex++), name, description,
                    editorial.image(categoryAsset(name)),
                    countLabel(entry.getValue().size(), "selected product family",
                            "selected product families"),
                    List.copyOf(entry.getValue())));
        }

        List<ContentsEntry> contents = allFamilies.stream()
                .map(item -> new ContentsEntry(item.number(), item.name(), item.categoryName()))
                .toList();
        List<ComparisonFamily> comparisonCounters = comparisonFamilies(
                allFamilies, ComparisonGroup.COUNTER, 4);
        List<ComparisonFamily> comparisonDecorative = comparisonFamilies(
                allFamilies, ComparisonGroup.SOAP_DECORATIVE, 3);
        CompanyProfile profile = company.get();
        String title = present(request.title())
                ? request.title().trim() : "Wholesale Collection " + LocalDate.now().getYear();
        String intro = present(request.intro()) ? request.intro().trim()
                : "Long-lasting gifting for counters, flower shops and wholesale buyers - "
                + "from preserved display roses to domes, flowerboxes and decorative roses.";

        return brochureTemplate
                .data("title", title)
                .data("intro", intro)
                .data("itemCount", catalog.products().size())
                .data("familyCount", allFamilies.size())
                .data("familyCountLabel", countLabel(allFamilies.size(), "FAMILY", "FAMILIES"))
                .data("variantCountLabel", countLabel(catalog.products().size(),
                        "SELECTED VARIANT", "SELECTED VARIANTS"))
                .data("families", allFamilies)
                .data("sections", sections)
                .data("contents", contents)
                .data("comparisonCounters", comparisonCounters)
                .data("comparisonDecorative", comparisonDecorative)
                .data("options", options)
                .data("company", profile)
                .data("logo", editorial.image("logo-gold.png"))
                .data("coverImage", editorial.image("hero-open-desktop.jpg"))
                .data("introDisplayImage", editorial.image("counter-bowl-retail.jpg"))
                .data("customisationImage", editorial.image("flowerbox-hero.jpg"))
                .data("year", LocalDate.now().getYear())
                .render();
    }

    private Item simpleItem(Product product, Language language,
                            CatalogExportService.Request request, PhotoResolver photos) {
        int allowed = request.resolvedPhotosPerProduct();
        List<String> images = new ArrayList<>();
        for (int index = 0; index < product.photos().size() && index < allowed; index++) {
            String uri = photos.product(product.photos().get(index));
            if (uri != null) images.add(uri);
        }
        return new Item(
                product.sku(), product.nameIn(language), dimensionLabel(product.dimensions()),
                product.colourIn(language),
                product.barcodes() == null ? null : product.barcodes().inner(),
                product.barcodes() == null ? null : product.barcodes().outer(),
                product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                product.carton() == null || product.carton().dimensions() == null
                        ? "" : dimensionLabel(product.carton().dimensions()),
                request.includePrices() ? priceLabel(product) : null,
                product.inventoryKnown(), product.inventoryKnown() ? product.stockQuantity() : null,
                at(images, 0), at(images, 1), images.size() <= 2
                        ? List.of() : List.copyOf(images.subList(2, images.size())));
    }

    private BrochureFamily brochureFamily(
            CatalogExportService.FamilyGroup group, Language language,
            CatalogExportService.Request request, PhotoResolver photos, int index) {
        Product first = group.variants().getFirst();
        CatalogFamilyReader.Family family = group.content();
        String name = family == null ? first.nameIn(language) : family.nameIn(language);
        String summary = family == null ? first.descriptionIn(language) : family.summaryIn(language);
        String description = family == null ? first.descriptionIn(language)
                : family.descriptionIn(language);
        String format = family == null ? null : family.formatIn(language);
        List<String> highlights = family == null ? List.of() : family.highlightsIn(language);
        String categoryName = group.category() == null
                ? family == null ? "Collection" : defaultText(family.categoryName(), "Collection")
                : group.category().name();

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
                        product.variantSize(), product.colourHex(),
                        dimensionLabel(product.dimensions()),
                        product.carton() == null ? "" : dimensionLabel(product.carton().dimensions()),
                        product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                        defaultText(product.canonicalBarcode(), "-"),
                        request.includePrices() ? priceLabel(product) : null))
                .toList();

        return new BrochureFamily(
                twoDigits(index), defaultText(name, first.sku()), summary, description, format,
                highlights, categoryName, familyDimension(family, first),
                packageLine(family, group.variants()), at(images, 0), at(images, 1),
                images.size() <= 2 ? List.of() : List.copyOf(images.subList(2, images.size())),
                variants);
    }

    private static List<ComparisonFamily> comparisonFamilies(
            List<BrochureFamily> families, ComparisonGroup group, int limit) {
        return families.stream()
                .filter(family -> comparisonGroup(family) == group)
                .limit(limit)
                .map(family -> {
                    BrochureVariant variant = family.variants().isEmpty()
                            ? null : family.variants().getFirst();
                    return new ComparisonFamily(
                            family.name(), family.heroImage(),
                            variant == null ? "" : variant.sku(),
                            defaultText(family.familySize(),
                                    variant == null ? "" : variant.productSize()),
                            variant == null ? "" : variant.cartonSize(),
                            variant == null ? 0 : variant.piecesPerCarton(),
                            variant == null || "-".equals(variant.ean())
                                    ? "" : defaultText(variant.ean(), ""));
                })
                .toList();
    }

    private static ComparisonGroup comparisonGroup(BrochureFamily family) {
        String key = (defaultText(family.categoryName(), "") + " "
                + defaultText(family.format(), "")).toLowerCase(Locale.ROOT);
        if (key.contains("soap") || key.contains("decorative")
                || key.contains("foam") || key.contains("zeep")) {
            return ComparisonGroup.SOAP_DECORATIVE;
        }
        if (key.contains("counter") || key.contains("display")) {
            return ComparisonGroup.COUNTER;
        }
        return null;
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

    private static String packageLine(CatalogFamilyReader.Family family, List<Product> variants) {
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
            line.append(item.piecesPerPackage()).append(" pcs");
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

    private String priceLabel(Product product) {
        return DocumentFormat.eur(product.computedSalesPriceEur());
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

    private static String categoryDescription(String name) {
        String key = defaultText(name, "").toLowerCase(Locale.ROOT);
        if (key.contains("counter") || key.contains("signature")) {
            return "Turn a small space near the till into an easy last-minute gifting moment.";
        }
        if (key.contains("soap") || key.contains("foam") || key.contains("zeep")) {
            return "Decorative rose gifts with strong shelf presence and easy counter appeal.";
        }
        return "Real preserved roses in refined displays, created for lasting impact without water.";
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static String twoDigits(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }

    private static String itemCountLabel(int count, Language language) {
        String word = switch (language) {
            case NL -> count == 1 ? "artikel" : "artikelen";
            case FR -> count == 1 ? "article" : "articles";
            case EN -> count == 1 ? "item" : "items";
            case DE -> "Artikel";
            case ES -> count == 1 ? "artículo" : "artículos";
            case PL -> count == 1 ? "artykuł" : "artykuły";
            case PT -> count == 1 ? "artigo" : "artigos";
            case TR -> "ürün";
        };
        return count + " " + word;
    }

    private static String countLabel(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
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
