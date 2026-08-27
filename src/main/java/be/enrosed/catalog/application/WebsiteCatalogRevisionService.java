package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.WebsiteHomepageLayoutEntity;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Stable digest of every value that can change the eight public WEBSITE payloads. */
@ApplicationScoped
public class WebsiteCatalogRevisionService {
    private final CanonicalCatalogDaos.ContentTranslations content;
    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final CanonicalCatalogDaos.PriceObservations prices;
    private final CanonicalCatalogDaos.DimensionObservations dimensions;
    private final CanonicalCatalogDaos.WebsiteHomepageLayouts homepageLayouts;
    private final PublicProductNameResolver publicProductNames;
    private final FamilyPhotoPublicationPolicy photoPublication;
    private final ObjectMapper json;

    public WebsiteCatalogRevisionService(
            CanonicalCatalogDaos.ContentTranslations content,
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            CanonicalCatalogDaos.PriceObservations prices,
            CanonicalCatalogDaos.DimensionObservations dimensions,
            CanonicalCatalogDaos.WebsiteHomepageLayouts homepageLayouts,
            PublicProductNameResolver publicProductNames,
            FamilyPhotoPublicationPolicy photoPublication,
            ObjectMapper json) {
        this.content = content;
        this.families = families;
        this.products = products;
        this.categories = categories;
        this.prices = prices;
        this.dimensions = dimensions;
        this.homepageLayouts = homepageLayouts;
        this.publicProductNames = publicProductNames;
        this.photoPublication = photoPublication;
        this.json = json;
    }

    public String currentRevision() {
        StringBuilder out = new StringBuilder(64_000);
        List<ContentTranslationEntity> websiteCopy = content.list(
                "scope = ?1 order by key", ContentScope.WEBSITE);
        long siteCopyRevision = websiteCopy.stream().map(group -> group.updatedAt)
                .filter(java.util.Objects::nonNull)
                .mapToLong(java.time.Instant::toEpochMilli).max().orElse(0L);
        add(out, "siteCopyRevision"); add(out, siteCopyRevision);
        websiteCopy.forEach(group -> {
            add(out, "copy"); add(out, group.key); add(out, group.required); add(out, group.system);
            group.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text -> {
                add(out, text.language); add(out, text.value);
            });
        });
        WebsiteHomepageLayoutEntity homepage = homepageLayouts.findById(1L);
        add(out, "homepageLayout");
        add(out, homepage == null ? 0 : homepage.publishedRevision);
        add(out, normalized(homepage == null
                ? encodedDefaultHomepage() : homepage.publishedSectionsJson));
        categories.listAll().stream().sorted(Comparator.comparing(category -> safe(category.code)))
                .forEach(category -> category(out, category));
        List<ProductFamilyEntity> familyRows = families.listAll().stream()
                .sorted(Comparator.comparing(family -> safe(family.familyKey))).toList();
        for (ProductFamilyEntity family : familyRows) family(out, family);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(out.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is niet beschikbaar", exception);
        }
    }

    private void category(StringBuilder out, CategoryEntity category) {
        add(out, "category"); add(out, category.code); add(out, category.name);
        add(out, category.description); add(out, category.eyebrow); add(out, category.mobileName);
        add(out, category.navigationName);
        add(out, category.footerName);
        add(out, category.position);
        ProductEntity featured = category.featuredProductId == null
                ? null : products.findById(category.featuredProductId);
        add(out, category.featuredProductId);
        add(out, featured == null ? null : featured.canonicalVariantKey);
        category.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text -> {
            add(out, text.language); add(out, text.name); add(out, text.description);
            add(out, text.eyebrow); add(out, text.mobileName); add(out, text.navigationName);
            add(out, text.footerName);
        });
    }

    private void family(StringBuilder out, ProductFamilyEntity family) {
        add(out, "family"); add(out, family.id); add(out, family.familyKey); add(out, family.publicHandle);
        add(out, family.active); add(out, family.websiteStatus); add(out, family.orderAppStatus);
        add(out, family.catalogueStatus); add(out, family.categoryKey); add(out, family.categoryPosition);
        add(out, family.productPosition); add(out, family.name); add(out, family.summary);
        add(out, family.description); add(out, family.format); add(out, normalized(family.highlightsJson));
        add(out, family.seoTitle); add(out, family.seoDescription); add(out, normalized(family.tagsJson));
        add(out, family.dimensionLength); add(out, family.dimensionWidth); add(out, family.dimensionHeight);
        add(out, family.dimensionUnit); add(out, family.dimensionRaw);
        ProductEntity featured = family.cardFeaturedProductId == null
                ? null : products.findById(family.cardFeaturedProductId);
        add(out, family.cardFeaturedProductId);
        add(out, featured == null ? null : featured.canonicalVariantKey);
        family.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text -> {
            add(out, text.language); add(out, text.name); add(out, text.summary);
            add(out, text.description); add(out, text.format); add(out, normalized(text.highlightsJson));
            add(out, text.seoTitle); add(out, text.seoDescription);
        });
        family.collections.stream().sorted(Comparator
                        .comparingInt((be.enrosed.catalog.adapter.out.persistence.ProductFamilyCollectionEntity item) -> item.position)
                        .thenComparing(item -> item.collection == null
                                ? "" : safe(item.collection.collectionKey))
                        .thenComparing(item -> item.id, Comparator.nullsLast(Long::compareTo)))
                .forEach(item -> {
                    add(out, item.collection == null ? null : item.collection.collectionKey);
                    add(out, item.position); add(out, item.primaryCollection);
                    if (item.collection != null) {
                        add(out, item.collection.name); add(out, item.collection.eyebrow);
                        add(out, item.collection.description); add(out, item.collection.mobileName);
                        add(out, item.collection.featuredProductId);
                        ProductEntity collectionFeatured = item.collection.featuredProductId == null
                                ? null : products.findById(item.collection.featuredProductId);
                        add(out, collectionFeatured == null
                                ? null : collectionFeatured.canonicalVariantKey);
                    }
                });
        family.packages.stream()
                .sorted(Comparator.comparingInt((be.enrosed.catalog.adapter.out.persistence.ProductPackageEntity item) -> item.position)
                        .thenComparing(item -> safe(item.packageType))
                        .thenComparing(item -> safe(item.variantExternalId))
                        .thenComparing(item -> safe(item.sourceKey)))
                .forEach(item -> {
                    add(out, "package"); add(out, item.operational); add(out, item.packageType);
                    add(out, item.position); add(out, item.lengthValue); add(out, item.widthValue);
                    add(out, item.heightValue); add(out, item.dimensionUnit); add(out, item.rawValue);
                    add(out, item.piecesPerPackage); add(out, item.weightValue);
                    add(out, item.weightUnit); add(out, item.variantExternalId);
                });
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, canonicalVariantKey, sku", family.id);
        family.photos.stream()
                .filter(image -> photoPublication.isPublic(
                        image, members, CatalogChannel.WEBSITE))
                .sorted(Comparator.comparingInt((ProductFamilyPhotoEntity image) -> image.position)
                        .thenComparing(image -> safe(image.sourceKey)))
                .forEach(image -> {
                    add(out, image.id); add(out, image.sourceKey); add(out, image.sourceAssetId);
                    add(out, image.position);
                    add(out, image.smallSha256); add(out, image.smallWidthPx); add(out, image.smallHeightPx);
                    add(out, image.largeSha256); add(out, image.largeWidthPx); add(out, image.largeHeightPx);
                    add(out, image.variantProduct == null ? null : image.variantProduct.id);
                    add(out, image.variantProduct == null
                            ? null : image.variantProduct.canonicalVariantKey);
                    add(out, image.variantExternalId); add(out, image.variantColor);
                    add(out, normalized(image.altTextsJson));
                });
        for (ProductEntity member : members) product(out, member);
        dimensions.list("familyId = ?1 order by position, id", family.id).forEach(item -> {
            add(out, "dimension"); add(out, item.dimensionType); add(out, item.position);
            add(out, item.unit); add(out, normalized(item.valuesJson)); add(out, item.rawValue);
            add(out, item.sourceType);
        });
    }

    private void product(StringBuilder out, ProductEntity product) {
        add(out, "variant"); add(out, product.id); add(out, product.canonicalVariantKey);
        add(out, product.sku);
        add(out, product.canonicalBarcode); add(out, product.active); add(out, product.publicAvailability);
        add(out, product.inventoryKnown); add(out, product.stockQuantity); add(out, product.variantPosition);
        for (Language language : Language.values()) {
            var publicName = publicProductNames.resolve(product, language);
            add(out, "publicName"); add(out, language);
            add(out, publicName.value()); add(out, publicName.sourceLanguage());
        }
        add(out, product.description); add(out, product.colour);
        add(out, product.colourHex); add(out, product.variantSize); add(out, product.fixedSalesPriceEur);
        product.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text -> {
            add(out, text.language);
            add(out, text.description);
            add(out, text.colour); add(out, text.variantSize);
        });
        prices.list("productId = ?1 and publicPrice = true order by publicRole, context, id", product.id)
                .forEach(price -> {
                    add(out, price.publicRole); add(out, price.context); add(out, price.amount);
                    add(out, price.currency); add(out, price.taxTreatment);
                });
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return json.writeValueAsString(json.readTree(value));
        } catch (Exception ignored) {
            return value.strip();
        }
    }

    private String encodedDefaultHomepage() {
        try {
            return json.writeValueAsString(WebsiteBuilderService.defaultSections());
        } catch (Exception exception) {
            throw new IllegalStateException("Standaard homepage-layout kon niet gelezen worden", exception);
        }
    }

    private static void add(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
