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
import be.enrosed.shared.DocumentFormat;
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
    /** Family and group rows the range table fits on one A4 page. */
    private static final int OVERVIEW_ROWS_PER_PAGE = 16;
    /** The category tints, bordeaux first; chapters take them in turn. */
    private static final int TONE_COUNT = 6;
    /* Real four-variant families with complete translated sales copy still fit the compact
       A4 composition. Keep a generous guard for pathological dashboard content without
       rejecting normal, print-ready product families. */
    private static final int FAMILY_PAGE_CAPACITY = 3_400;
    /** The overview says each family in one line; longer copy is cut at a word. */
    private static final int OVERVIEW_SUMMARY_CHARS = 92;
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

    /** One line of the specification block: a translated label and its value. */
    public record SpecRow(String label, String value) {}

    public record BrochureFamily(
            String anchor, String number, String name, String summary, String description, String format,
            List<String> highlights, String categoryKey, String categoryName, String familySize,
            String packageLine, String overviewImage, PhotoLayout photos,
            String referencePriceLabel, boolean compactDetail,
            List<BrochureVariant> variants, List<SpecRow> specs,
            /** The printed page this family sits on; 0 until the book is paginated. */
            int page,
            /** The facts the range table shows on one line. */
            RangeFacts facts,
            /** The chapter's tint class: bordeaux for the first chapter, then the other tones. */
            String tone) {

        /** Compatibility for callers written before the pages were numbered. */
        public BrochureFamily(
                String anchor, String number, String name, String summary, String description,
                String format, List<String> highlights, String categoryKey, String categoryName,
                String familySize, String packageLine, String overviewImage, PhotoLayout photos,
                String referencePriceLabel, boolean compactDetail,
                List<BrochureVariant> variants, List<SpecRow> specs) {
            this(anchor, number, name, summary, description, format, highlights, categoryKey,
                    categoryName, familySize, packageLine, overviewImage, photos, referencePriceLabel,
                    compactDetail, variants, specs, 0, RangeFacts.empty(), "tone-1");
        }

        /** The same family on its printed page, numbered in reading order. */
        public BrochureFamily placed(String number, int page) {
            return placed(number, page, tone);
        }

        /** The same family on its printed page, numbered in reading order, in its chapter's tint. */
        public BrochureFamily placed(String number, int page, String tone) {
            return new BrochureFamily("family-" + number, number, name, summary, description, format,
                    highlights, categoryKey, categoryName, familySize, packageLine, overviewImage, photos,
                    referencePriceLabel, compactDetail, variants, specs, page, facts, tone);
        }

        /** The one line the overview says about this family. */
        public String overviewSummary() {
            String source = present(summary) ? summary : present(format) ? format : description;
            if (!present(source)) return "";
            String text = source.strip().replaceAll("\\s+", " ");
            if (text.length() <= OVERVIEW_SUMMARY_CHARS) return text;
            int cut = text.lastIndexOf(' ', OVERVIEW_SUMMARY_CHARS);
            return text.substring(0, cut > 40 ? cut : OVERVIEW_SUMMARY_CHARS).strip() + "…";
        }

        /** Compatibility for callers written before the specification block existed. */
        public BrochureFamily(
                String anchor, String number, String name, String summary, String description,
                String format, List<String> highlights, String categoryKey, String categoryName,
                String familySize, String packageLine, String overviewImage, PhotoLayout photos,
                String referencePriceLabel, boolean compactDetail, List<BrochureVariant> variants) {
            this(anchor, number, name, summary, description, format, highlights, categoryKey,
                    categoryName, familySize, packageLine, overviewImage, photos, referencePriceLabel,
                    compactDetail, variants, List.of());
        }

        /** The story column only earns its width when there is a story to tell. */
        public boolean hasStory() {
            return present(summary) || present(description)
                    || (highlights != null && !highlights.isEmpty());
        }

        /** One variant reads better as a specification list than as a one-row table. */
        public boolean showVariantTable() {
            return variants != null && variants.size() > 1;
        }

        /** A single product without story copy gets the large product shot and the roomy list. */
        public boolean sheetLayout() {
            return !hasStory() && !showVariantTable();
        }
    }

    public record BrochureSection(
            String number, String name, String description, String categoryKey,
            EditorialLayout images,
            String familyCountLabel, List<BrochureFamily> families,
            /** The printed page the chapter starts on; 0 until the book is paginated. */
            int page,
            /** The chapter's tint class: bordeaux first, then the other tones in turn. */
            String tone) {

        /** Compatibility for callers written before the pages were numbered. */
        public BrochureSection(String number, String name, String description, String categoryKey,
                               EditorialLayout images, String familyCountLabel,
                               List<BrochureFamily> families) {
            this(number, name, description, categoryKey, images, familyCountLabel, families, 0, "tone-1");
        }

        /** Compatibility for callers written before the chapters were tinted. */
        public BrochureSection(String number, String name, String description, String categoryKey,
                               EditorialLayout images, String familyCountLabel,
                               List<BrochureFamily> families, int page) {
            this(number, name, description, categoryKey, images, familyCountLabel, families, page, "tone-1");
        }
    }

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

    /** One colour a family comes in: the swatch when the product card knows it, else just the name. */
    public record ColourDot(String hex, String name) {
        public boolean hasHex() {
            return present(hex);
        }
    }

    /**
     * What a buyer scans in the range table, one line per family: the facts
     * every variant shares, or the first two when they differ. Colours are
     * dots, not prose; the description stays on the family page.
     */
    public record RangeFacts(String skuLabel, List<ColourDot> colours, String colourLabel, String sizeLabel,
                             String productSize, String packaging, String carton, String cartonPieces,
                             String cbm, String hcCapacity, String ean) {
        static RangeFacts empty() {
            return new RangeFacts("", List.of(), "", "", "", "", "", "", "", "", "");
        }
    }

    /** A line of the range table: a chapter heading in its tint, or one family with its facts. */
    public record OverviewRow(boolean header, String tone, String number, String name, String countLabel,
                              String anchor, int page, String image, RangeFacts facts, String priceLabel) {
        static OverviewRow group(BrochureSection section) {
            return new OverviewRow(true, section.tone(), section.number(), section.name(),
                    section.familyCountLabel(), "", section.page(), null, RangeFacts.empty(), null);
        }

        static OverviewRow family(BrochureFamily family) {
            return new OverviewRow(false, family.tone(), family.number(), family.name(), "",
                    family.anchor(), family.page(), family.overviewImage(), family.facts(),
                    family.referencePriceLabel());
        }
    }

    public record OverviewPage(int number, int total, List<OverviewRow> rows) {}

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
        Map<String, String> copy = catalogCopy(language);
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
        Map<String, String> copy = catalogCopy(language);
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

        /* The book in reading order: cover, overview, then per chapter its
           intro page and the family sheets. Every family learns its page so
           the overview can point at it and the footers can say it. */
        List<List<int[]>> overviewSlots = overviewSlots(
                byCategory.values().stream().map(List::size).toList());
        int overviewPageCount = overviewSlots.size();
        /* The first page after the cover is 2; the overview pages come before the chapters. */
        int page = 2 + (options.includeOverview() ? overviewPageCount : 0);
        List<BrochureSection> sections = new ArrayList<>();
        List<BrochureFamily> pagedFamilies = new ArrayList<>();
        List<PhotoRef> categoryLeads = new ArrayList<>();
        int sectionIndex = 1;
        int familyNumber = 1;
        for (Map.Entry<String, List<FamilyRenderData>> entry : byCategory.entrySet()) {
            Category category = categoryByKey.get(entry.getKey());
            String name = category != null && present(category.nameIn(language))
                    ? category.nameIn(language) : entry.getValue().getFirst().family().categoryName();
            String description = category == null ? null : category.descriptionIn(language);
            /* The category's own photo opens its chapter; without one, the
               chapter opens with a mosaic of its products. */
            PhotoRef categoryLead = category == null || category.leadPhoto() == null
                    ? null : photos.productRef(category.leadPhoto());
            if (categoryLead != null && photos.usable(categoryLead)) categoryLeads.add(categoryLead);
            List<PhotoRef> categoryPhotos = categoryLead != null && photos.usable(categoryLead)
                    ? List.of(categoryLead) : photos.diverseFamilyPhotos(entry.getValue());
            int sectionPage = page;
            if (options.includeCategoryIntros()) page++;
            /* Bordeaux for the first chapter, then the other tones, round and round. */
            String tone = "tone-" + ((sectionIndex - 1) % TONE_COUNT + 1);
            List<BrochureFamily> chapter = new ArrayList<>();
            for (FamilyRenderData rendered : entry.getValue()) {
                chapter.add(rendered.family().placed(twoDigits(familyNumber++), page++, tone));
            }
            pagedFamilies.addAll(chapter);
            sections.add(new BrochureSection(twoDigits(sectionIndex++), name, description,
                    entry.getValue().getFirst().family().categoryKey(),
                    photos.editorialLayout(categoryPhotos, false),
                    countLabel(entry.getValue().size(),
                            copy(copy, "catalog.common.selectedfamily.singular"),
                            copy(copy, "catalog.common.selectedfamily.plural")),
                    List.copyOf(chapter), sectionPage, tone));
        }

        List<PhotoRef> selectedPhotos = photos.diverseFamilyPhotos(renderedFamilies);
        /* The cover opens with one picture: the first chapter's own photo when
           it has one, else the strongest product photo of the selection. */
        List<PhotoRef> coverLead = !categoryLeads.isEmpty() ? List.of(categoryLeads.getFirst())
                : selectedPhotos.isEmpty() ? List.of() : List.of(selectedPhotos.getFirst());
        EditorialLayout coverImages = photos.coverLayout(coverLead);
        EditorialLayout backImages = photos.editorialLayout(selectedPhotos, true);

        List<OverviewPage> overviewPages = overviewPages(overviewSlots, sections);
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
                .data("families", pagedFamilies)
                .data("sections", sections)
                .data("overviewPages", overviewPages)
                .data("lastPage", page - 1)
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
        /* A photo the buyer chose to open the catalogue with goes first,
           whatever the gallery order; the rest fill in behind it. */
        if (allowed > 0) {
            for (Product variant : group.variants()) {
                for (Photo photo : variant.photos()) {
                    if (!photo.leads(be.enrosed.catalog.domain.PhotoRole.CATALOGUE)) continue;
                    PhotoRef ref = photos.productRef(photo);
                    if (ref != null && imageKeys.add(ref.storageKey()) && photos.usable(ref)) {
                        imageRefs.add(ref);
                    }
                }
            }
        }
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
                        compactDimensions(product.dimensions()),
                        product.carton() == null ? "" : compactDimensions(product.carton().dimensions()),
                        product.carton() == null ? 0 : product.carton().piecesPerCarton(),
                        defaultText(product.canonicalBarcode(), "-"),
                        request.includePrices() ? priceLabel(product, language) : null))
                .toList();

        String familySize = familyDimension(family, first);
        String packageLine = packageLine(family, group.variants(), copy);
        String referencePriceLabel = request.includePrices()
                ? referencePriceLabel(group.variants(), language, copy) : null;
        String number = twoDigits(index);
        List<SpecRow> specs = specRows(group.variants(), language, copy, request.includePrices());
        ensureFamilyPageCapacity(name, summary, description, format, highlights,
                categoryName, familySize, packageLine, referencePriceLabel,
                variants, photoLayout.extras().size(), specs.size());
        BrochureFamily rendered = new BrochureFamily(
                "family-" + number, number, defaultText(name, first.sku()), summary, description, format,
                highlights, categoryKey, categoryName, familySize,
                packageLine, overviewImage, photoLayout, referencePriceLabel,
                compactDetail(summary, description, highlights, variants.size(),
                        photoLayout.extras().size()),
                variants, specs, 0, rangeFacts(group.variants(), language, copy), "tone-1");
        return new FamilyRenderData(rendered, List.copyOf(detailOrder));
    }

    /** The family's line in the range table, read off its variants. */
    private static RangeFacts rangeFacts(List<Product> variants, Language language, Map<String, String> copy) {
        Product first = variants.getFirst();
        Map<String, String> words = DocumentText.of(language);
        String pieces = copy(copy, "catalog.common.pieces");
        List<ColourDot> colours = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Product product : variants) {
            String name = product.colourIn(language);
            String hex = product.colourHex();
            if (!present(name) && !present(hex)) continue;
            String key = (present(hex) ? hex.toLowerCase(Locale.ROOT) : "") + "|" + (present(name) ? name.strip().toLowerCase(Locale.ROOT) : "");
            if (seen.add(key)) colours.add(new ColourDot(present(hex) ? hex : null, present(name) ? name.strip() : ""));
        }
        String packaging = sharedOrFirst(variants, product -> {
            if (product.packaging() == null || !product.packaging().isPresent()) return "";
            String kind = words.getOrDefault(product.packaging().kind() == be.enrosed.catalog.domain.PackagingKind.DISPLAY
                    ? "displayPackaging" : "giftPackaging", product.packaging().kind().dutchLabel());
            Integer per = product.packaging().piecesPerUnit();
            return per != null && per > 1 ? kind + " · " + integer(per, language) + " " + pieces : kind;
        });
        String cartonPieces = sharedOrFirst(variants, product -> {
            if (product.carton() == null) return "";
            String count = product.carton().piecesPerCarton() > 0
                    ? integer(product.carton().piecesPerCarton(), language) + " " + pieces : "";
            String weight = positive(product.carton().weightKg()) ? DocumentFormat.kg(product.carton().weightKg()) : "";
            return count.isEmpty() ? weight : weight.isEmpty() ? count : count + " · " + weight;
        });
        return new RangeFacts(
                skuLabel(variants), List.copyOf(colours),
                distinctJoined(variants, product -> product.colourIn(language)),
                distinctJoined(variants, product -> product.variantSizeIn(language)),
                sharedOrFirst(variants, product -> compactDimensions(product.dimensions())),
                packaging,
                sharedOrFirst(variants, product -> product.carton() == null ? "" : compactDimensions(product.carton().dimensions())),
                cartonPieces,
                sharedOrFirst(variants, product -> product.carton() == null || !positive(product.carton().cbm())
                        ? "" : DocumentFormat.cbm(product.carton().cbm())),
                sharedOrFirst(variants, product -> {
                    Integer capacity = product.carton() == null ? null : product.carton().hcCapacity();
                    return capacity == null || capacity <= 0 ? "" : integer(capacity, language);
                }),
                variants.size() == 1 ? defaultText(first.canonicalBarcode(), "") : "");
    }

    /** One SKU, two SKUs, or the first and the last with a dash: enough to find the family in a price list. */
    private static String skuLabel(List<Product> variants) {
        List<String> skus = variants.stream().map(Product::sku).filter(PdfCatalogRenderer::present)
                .map(String::strip).distinct().toList();
        if (skus.isEmpty()) return "";
        if (skus.size() <= 2) return String.join(" · ", skus);
        return skus.getFirst() + " – " + skus.getLast();
    }

    /** The value every variant shares; when they differ, the first two with a hint that there is more. */
    private static String sharedOrFirst(List<Product> variants, java.util.function.Function<Product, String> value) {
        Set<String> values = new LinkedHashSet<>();
        for (Product product : variants) {
            String text = value.apply(product);
            if (present(text)) values.add(text.strip());
        }
        if (values.isEmpty()) return "";
        List<String> list = new ArrayList<>(values);
        if (list.size() == 1) return list.getFirst();
        return list.get(0) + " / " + list.get(1) + (list.size() > 2 ? " …" : "");
    }

    /**
     * Where each line of the range table lands: a chapter heading followed
     * by its families, {@link #OVERVIEW_ROWS_PER_PAGE} lines a page, and a
     * heading never stranded at the foot of a page. Each slot is
     * {chapter index, family index} with -1 for the heading itself.
     */
    static List<List<int[]>> overviewSlots(List<Integer> chapterSizes) {
        List<List<int[]>> pages = new ArrayList<>();
        List<int[]> current = new ArrayList<>();
        for (int chapter = 0; chapter < chapterSizes.size(); chapter++) {
            int size = chapterSizes.get(chapter);
            if (size <= 0) continue;
            if (current.size() >= OVERVIEW_ROWS_PER_PAGE - 1) {
                pages.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(new int[] {chapter, -1});
            for (int family = 0; family < size; family++) {
                if (current.size() >= OVERVIEW_ROWS_PER_PAGE) {
                    pages.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
                current.add(new int[] {chapter, family});
            }
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return List.copyOf(pages);
    }

    private static List<OverviewPage> overviewPages(List<List<int[]>> slots, List<BrochureSection> sections) {
        List<OverviewPage> pages = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            List<OverviewRow> rows = new ArrayList<>();
            for (int[] slot : slots.get(index)) {
                BrochureSection section = sections.get(slot[0]);
                rows.add(slot[1] < 0 ? OverviewRow.group(section)
                        : OverviewRow.family(section.families().get(slot[1])));
            }
            pages.add(new OverviewPage(index + 1, slots.size(), List.copyOf(rows)));
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
            List<BrochureVariant> variants, int galleryCount, int specCount) {
        int capacityUse = capacityText(name, 80)
                + specCount * 45
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

    private static void ensureFamilyPageCapacity(
            String name, String summary, String description, String format,
            List<String> highlights, String categoryName, String familySize,
            String packageLine, String referencePriceLabel,
            List<BrochureVariant> variants, int galleryCount) {
        ensureFamilyPageCapacity(name, summary, description, format, highlights, categoryName,
                familySize, packageLine, referencePriceLabel, variants, galleryCount, 0);
    }

    /**
     * The facts a buyer needs before ordering, at family level. Only values
     * shared by every variant are listed; anything that differs lives in the
     * variant table below. A single variant carries its item number, EAN and
     * price here because a one-row table would only repeat them.
     */
    private static List<SpecRow> specRows(
            List<Product> variants, Language language, Map<String, String> copy, boolean includePrices) {
        List<SpecRow> rows = new ArrayList<>();
        Product first = variants.getFirst();
        boolean single = variants.size() == 1;
        Map<String, String> words = DocumentText.of(language);
        if (single && present(first.sku())) rows.add(new SpecRow(copy(copy, "catalog.spec.itemnumber"), first.sku()));

        addSpec(rows, copy(copy, "catalog.spec.productsize"), variants,
                product -> dimensionLabel(product.dimensions()));
        addSpec(rows, copy(copy, "catalog.spec.weight"), variants,
                product -> product.dimensions() == null || !positive(product.dimensions().weightKg())
                        ? "" : DocumentFormat.kg(product.dimensions().weightKg()));
        addSpec(rows, copy(copy, "catalog.spec.packaging"), variants, product -> {
            if (product.packaging() == null || !product.packaging().isPresent()) return "";
            String kind = words.getOrDefault(product.packaging().kind() == be.enrosed.catalog.domain.PackagingKind.DISPLAY
                    ? "displayPackaging" : "giftPackaging", product.packaging().kind().dutchLabel());
            String size = compactDimensions(product.packaging().dimensions());
            return size.isEmpty() ? kind : kind + " · " + size;
        });
        addSpec(rows, copy(copy, "catalog.spec.outercarton"), variants,
                product -> product.carton() == null ? "" : dimensionLabel(product.carton().dimensions()));
        addSpec(rows, copy(copy, "catalog.spec.piecespercarton"), variants,
                product -> product.carton() == null || product.carton().piecesPerCarton() <= 0
                        ? "" : integer(product.carton().piecesPerCarton(), language));
        addSpec(rows, copy(copy, "catalog.spec.cartonweight"), variants,
                product -> product.carton() == null || !positive(product.carton().weightKg())
                        ? "" : DocumentFormat.kg(product.carton().weightKg()));
        addSpec(rows, copy(copy, "catalog.spec.cartonvolume"), variants,
                product -> product.carton() == null || !positive(product.carton().cbm())
                        ? "" : DocumentFormat.cbm(product.carton().cbm()));
        addSpec(rows, copy(copy, "catalog.spec.container"), variants, product -> {
            Integer capacity = product.carton() == null ? null : product.carton().hcCapacity();
            return capacity == null || capacity <= 0 ? "" : integer(capacity, language);
        });
        addSpec(rows, copy(copy, "catalog.spec.hscode"), variants,
                product -> defaultText(product.hsCode(), ""));
        if (!single) {
            String colours = distinctJoined(variants, product -> product.colourIn(language));
            if (present(colours)) rows.add(new SpecRow(copy(copy, "catalog.spec.colours"), colours));
            String sizes = distinctJoined(variants, product -> product.variantSizeIn(language));
            if (present(sizes)) rows.add(new SpecRow(copy(copy, "catalog.spec.sizes"), sizes));
        } else {
            if (present(first.canonicalBarcode())) rows.add(new SpecRow("EAN", first.canonicalBarcode()));
            if (includePrices) {
                BigDecimal price = first.computedSalesPriceEur();
                rows.add(new SpecRow(copy(copy, "catalog.brochure.overview.referenceprice"),
                        positive(price) ? formatPrice(price, language)
                                : copy(copy, "catalog.brochure.overview.priceonrequest")));
            }
        }
        return List.copyOf(rows);
    }

    /** Adds the value every variant shares; a differing or blank value is left to the table. */
    private static void addSpec(List<SpecRow> rows, String label, List<Product> variants,
                                java.util.function.Function<Product, String> value) {
        Set<String> values = new LinkedHashSet<>();
        for (Product product : variants) {
            String text = value.apply(product);
            values.add(text == null ? "" : text.strip());
        }
        values.remove("");
        if (values.size() == 1) rows.add(new SpecRow(label, values.iterator().next()));
    }

    private static String distinctJoined(List<Product> variants,
                                         java.util.function.Function<Product, String> value) {
        Set<String> values = new LinkedHashSet<>();
        for (Product product : variants) {
            String text = value.apply(product);
            if (present(text)) values.add(text.strip());
        }
        return String.join(" · ", values);
    }

    private static String integer(int value, Language language) {
        return java.text.NumberFormat.getIntegerInstance(language.locale()).format(value);
    }

    /** "11 × 11 × 25 cm" for a table whose header already names the axis order. */
    private static String compactDimensions(Dimensions dimensions) {
        String label = dimensionLabel(dimensions);
        int colon = label.indexOf(": ");
        return colon < 0 ? label : label.substring(colon + 2);
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

    /**
     * The store's texts over the shipped seed: a key that never reached the
     * store (a seeding that rolled back, an older environment) still prints
     * its shipped wording instead of failing the whole document.
     */
    private Map<String, String> catalogCopy(Language language) {
        Map<String, String> merged = new LinkedHashMap<>(
                be.enrosed.catalog.application.PublicContentSeedLoader.catalogSeedValues(language));
        merged.putAll(content.values(ContentScope.CATALOG, language));
        return merged;
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

        /* The overview card is landscape: a 4:3 crop fills its picture frame edge to edge. */
        String overview(PhotoRef ref) {
            return cropped(ref, "overview-landscape", 4, 3, 1_200);
        }

        /** The cover opens on one picture cropped to the hero frame above the title panel. */
        EditorialLayout coverLayout(List<PhotoRef> refs) {
            List<PhotoRef> unique = uniquePhotos(refs);
            if (unique.isEmpty()) return EditorialLayout.empty();
            String image = cropped(unique.getFirst(), "cover", 210, 176, 2_400);
            if (!present(image)) return EditorialLayout.empty();
            return new EditorialLayout("one", true,
                    List.of(new EditorialRow("full", List.of(new EditorialTile(image, 1)))));
        }

        private String simple(PhotoRef ref, int count, int index) {
            if (count == 1) return cropped(ref, "simple-one", 4, 3, 1_000);
            if (count == 2) return cropped(ref, "simple-two", 2, 3, 900);
            if (count == 3) return cropped(ref,
                    index == 0 ? "simple-three-lead" : "simple-three-stack", 7, 8, 900);
            return cropped(ref, index < 4 ? "simple-grid" : "simple-extra",
                    index < 4 ? 4 : 3, index < 4 ? 3 : 2, index < 4 ? 800 : 600);
        }

        /* Specification pages show the whole product: fitted, never cropped. */
        private String detail(PhotoRef ref, int count, int index) {
            if (count == 1) return contained(ref, "detail-one", 16, 9, 2_400);
            if (count == 2) return contained(ref, "detail-two", 5, 6, 1_600);
            if (count == 3) return contained(ref,
                    index == 0 ? "detail-three-lead" : "detail-three-stack",
                    7, 6, index == 0 ? 1_900 : 1_300);
            return contained(ref, index < 4 ? "detail-grid" : "detail-extra",
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

        private String contained(
                PhotoRef ref, String rendition, int aspectWidth, int aspectHeight, int maxEdge) {
            if (ref == null) return null;
            String value = renditionCache.computeIfAbsent(cacheKey(ref, rendition), ignored -> {
                String uri = imageEncoder.encodeContainedTrimmed(
                        source(ref), aspectWidth, aspectHeight, maxEdge, CATALOG_IMAGE_BACKGROUND);
                if (uri == null) failed(ref.storageKey(), null);
                return defaultText(uri, "");
            });
            return present(value) ? value : null;
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
