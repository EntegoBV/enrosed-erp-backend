package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.application.FamilyPhotoAltText;
import be.enrosed.catalog.application.FamilyPhotoVariantResolver;
import be.enrosed.catalog.application.CategoryPublicKey;
import be.enrosed.catalog.application.ContentTranslationService;
import be.enrosed.catalog.application.PublicProductNameResolver;
import be.enrosed.catalog.application.PublicFamilyPhotoProjection;
import be.enrosed.catalog.application.SharedProductDimensions;
import be.enrosed.catalog.application.WebsiteCatalogRevisionService;
import be.enrosed.catalog.application.WebsiteQuotePhotoChoice;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import be.enrosed.shared.LocalizationIncompleteException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Anonymous, read-only source for the website, future order app, and catalogue. */
@Path("/api/v1/public/catalog/families")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class PublicFamilyCatalogResource {
    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final PhotoStorage photoStorage;
    private final FamilyPhotoVariantResolver variantResolver;
    private final PublicFamilyPhotoProjection publicPhotos;
    private final PublicProductNameResolver publicProductNames;
    private final ContentTranslationService content;
    private final ObjectMapper json;

    @Inject
    WebsiteCatalogRevisionService revisions;

    public PublicFamilyCatalogResource(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            PhotoStorage photoStorage,
            FamilyPhotoVariantResolver variantResolver,
            PublicFamilyPhotoProjection publicPhotos,
            PublicProductNameResolver publicProductNames,
            ContentTranslationService content,
            ObjectMapper json) {
        this.families = families;
        this.products = products;
        this.categories = categories;
        this.photoStorage = photoStorage;
        this.variantResolver = variantResolver;
        this.publicPhotos = publicPhotos;
        this.publicProductNames = publicProductNames;
        this.content = content;
        this.json = json;
    }

    @GET
    public Response catalog(
            @QueryParam("channel") @DefaultValue("WEBSITE") CatalogChannel channel,
            @QueryParam("language") @DefaultValue("EN") String languageCode,
            @QueryParam("strictLanguage") @DefaultValue("false") boolean strictLanguage,
            @Context UriInfo uriInfo) {
        Language language;
        try {
            language = Language.requireSupported(languageCode, Language.EN);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
        String revisionBeforeProjection = revisions == null
                ? null : revisions.currentRevision();
        List<CategoryEntity> categoryRows = categories.listAll();
        List<PublicFamilyCatalogDto.CategoryDto> publicCategories = categoryRows.stream()
                .sorted(Comparator.comparingInt((CategoryEntity item) -> item.position)
                        .thenComparing(item -> safe(item.code), String.CASE_INSENSITIVE_ORDER))
                .map(item -> category(item, language, channel))
                .toList();
        ContentTranslationService.ResolvedCopy siteCopy =
                content.resolve(ContentScope.WEBSITE, language);
        List<ProductFamilyEntity> publishedRows = families.findAll().list().stream()
                .filter(family -> family.active)
                .filter(family -> status(family, channel) == PublicationState.PUBLISHED)
                .sorted(Comparator.comparingInt((ProductFamilyEntity item) -> item.categoryPosition)
                        .thenComparingInt(item -> item.productPosition)
                        .thenComparing(item -> safe(item.name), String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<PublicFamilyCatalogDto.FamilyDto> publicFamilies = new ArrayList<>();
        List<String> projectionIssues = new ArrayList<>();
        for (ProductFamilyEntity row : publishedRows) {
            PublicFamilyCatalogDto.FamilyDto projected = family(
                    row, language, channel, categoryRows, siteCopy.values());
            if (projected == null) {
                projectionIssues.add("families." + safePublicHandle(row) + ".publicProjection");
            } else {
                publicFamilies.add(projected);
            }
        }
        publicFamilies = List.copyOf(publicFamilies);
        if (strictLanguage) {
            List<String> missing = new ArrayList<>(projectionIssues);
            missing.addAll(strictCategoryMissing(language, publicCategories));
            missing.addAll(strictMissing(language, publicFamilies));
            missing.addAll(content.missingRequired(ContentScope.WEBSITE, language).stream()
                    .map(key -> "siteCopy." + key).toList());
            if (!missing.isEmpty()) {
                throw new LocalizationIncompleteException(
                        "Publieke copy voor " + language.code() + " is onvolledig", missing);
            }
        }
        String catalogRevision = revisions == null
                ? String.valueOf(siteCopy.revision())
                : requireStableRevision(revisionBeforeProjection, revisions.currentRevision());
        return Response.ok(new PublicFamilyCatalogDto(
                        channel, language, LanguageFallback.chain(language),
                        siteCopy.revision(), catalogRevision,
                        siteCopy.values(), publicCategories, publicFamilies))
                .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300")
                .build();
    }

    static String requireStableRevision(String beforeProjection, String afterProjection) {
        if (!Objects.equals(beforeProjection, afterProjection)) {
            throw new ServiceUnavailableException(
                    "De publieke catalogus wijzigde tijdens het opbouwen; probeer opnieuw", 1L);
        }
        return afterProjection;
    }

    private static String safePublicHandle(ProductFamilyEntity family) {
        if (family.publicHandle != null && !family.publicHandle.isBlank()) {
            return family.publicHandle;
        }
        if (family.familyKey != null && !family.familyKey.isBlank()) return family.familyKey;
        return "unknown";
    }

    /** Java-call compatibility for existing tests and internal consumers; HTTP defaults stay false. */
    public Response catalog(CatalogChannel channel, String languageCode, UriInfo uriInfo) {
        return catalog(channel, languageCode, false, uriInfo);
    }

    /** Stable family/source-id URL; a 404 never reveals private family metadata. */
    @GET
    @Path("/{handle}/images/{sourceId}/{rendition}")
    @Produces(MediaType.WILDCARD)
    public Response image(@PathParam("handle") String handle,
                          @PathParam("sourceId") String sourceId,
                          @PathParam("rendition") String rendition,
                          @QueryParam("v") String version) {
        ProductFamilyEntity family = families.find("publicHandle", handle).firstResult();
        if (family == null || !family.active || !publishedAnywhere(family)) {
            throw new NotFoundException();
        }
        List<ProductEntity> familyMembers = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        // Re-resolve ownership, active membership and channel publication on every request.
        ProductFamilyPhotoEntity image = Arrays.stream(CatalogChannel.values())
                .filter(channel -> status(family, channel) == PublicationState.PUBLISHED)
                .flatMap(channel -> publicPhotos.images(family, familyMembers, channel).stream())
                .filter(candidate -> Objects.equals(candidate.sourceKey, sourceId))
                .findFirst().orElseThrow(NotFoundException::new);
        boolean small = "small".equalsIgnoreCase(rendition);
        if (!small && !"large".equalsIgnoreCase(rendition)) throw new NotFoundException();
        String storageKey = small ? image.smallStorageKey : image.largeStorageKey;
        String contentType = small ? image.smallContentType : image.largeContentType;
        String checksum = small ? image.smallSha256 : image.largeSha256;
        String requestedVersion = version == null ? null : version.strip();
        /* Old statically deployed pages may briefly carry the preceding checksum while a new
           website build is pending. They still receive the current bytes, but only an exact
           content-addressed URL is immutable. */
        boolean versioned = PublicFamilyPhotoProjection.isProductProjection(image)
                || requestedVersion != null && !requestedVersion.isEmpty()
                && checksum != null && checksum.equalsIgnoreCase(requestedVersion);
        return PhotoResponses.inline(photoStorage.read(storageKey), contentType, image.originalFilename)
                .header("Cache-Control", versioned
                        ? "public, max-age=31536000, immutable"
                        : "public, max-age=60")
                .build();
    }

    /** Direct-Java and legacy consumer compatibility for the pre-versioned public URL. */
    public Response image(String handle, String sourceId, String rendition) {
        return image(handle, sourceId, rendition, null);
    }

    private PublicFamilyCatalogDto.FamilyDto family(
            ProductFamilyEntity family, Language language, CatalogChannel channel,
            List<CategoryEntity> categories,
            Map<String, LocalizedValueDto> siteCopy) {
        LanguageFallback.Resolved<String> name = familyText(
                family, language, item -> item.name, family.name);
        LanguageFallback.Resolved<String> summary = familyText(
                family, language, item -> item.summary, family.summary);
        LanguageFallback.Resolved<String> description = familyText(
                family, language, item -> item.description, family.description);
        LanguageFallback.Resolved<String> format = optionalFamilyText(
                family, language, item -> item.format, family.format);
        LanguageFallback.Resolved<List<String>> highlights = optionalFamilyList(
                family, language, family.highlightsJson);
        LanguageFallback.Resolved<List<String>> tags = LanguageFallback.resolve(
                family.texts, language, item -> item.language,
                item -> ProductFamilyDto.readStrings(json, item.tagsJson),
                values -> values != null && !values.isEmpty(),
                ProductFamilyDto.readStrings(json, family.tagsJson));
        LanguageFallback.Resolved<String> fallbackSeoTitle = familyText(
                family, language, item -> item.seoTitle, family.seoTitle);
        LanguageFallback.Resolved<String> fallbackSeoDescription = familyText(
                family, language, item -> item.seoDescription, family.seoDescription);
        LanguageFallback.Resolved<String> seoTitle = seoTitle(
                family, language, name, fallbackSeoTitle, siteCopy);
        LanguageFallback.Resolved<String> seoDescription = seoDescription(
                family, language, summary, description, fallbackSeoDescription);
        List<ProductEntity> familyMembers = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        /* Demo pieces are ours to show, never the website's to sell. */
        List<ProductEntity> variants = familyMembers.stream().filter(item -> item.active && !item.demo).toList();
        List<ProductFamilyPhotoEntity> projectedImages = publicPhotos.images(family, familyMembers, channel);
        List<PublicFamilyCatalogDto.ImageDto> images = java.util.stream.IntStream.range(0, projectedImages.size())
                .mapToObj(position -> image(family, projectedImages.get(position), familyMembers, language, position))
                .toList();
        /* Fail-safe for stale data created outside the validated write paths. */
        if (images.isEmpty() || variants.isEmpty()) return null;
        /* The quote page is a WEBSITE view; other channels never receive a website choice. */
        Long quoteImageId = channel == CatalogChannel.WEBSITE
                ? WebsiteQuotePhotoChoice.resolve(family, familyMembers, publicPhotos, projectedImages)
                : null;

        List<PublicFamilyCatalogDto.VariantDto> publicVariants = variants.stream()
                .map(variant -> variant(family, variant, familyMembers, language, channel))
                .toList();

        var size = SharedProductDimensions.resolve(variants);
        PublicFamilyCatalogDto.DimensionsDto dimensions = size == null ? null
                : new PublicFamilyCatalogDto.DimensionsDto(
                        size.lengthCm(), size.widthCm(), size.heightCm(), "cm", null);
        List<PublicFamilyCatalogDto.PackageDto> packages = family.packages.stream()
                .filter(item -> Boolean.TRUE.equals(item.operational))
                .sorted(Comparator.comparingInt(item -> item.position))
                .map(item -> new PublicFamilyCatalogDto.PackageDto(
                        item.packageType, item.position,
                        new PublicFamilyCatalogDto.DimensionsDto(
                                item.lengthValue, item.widthValue, item.heightValue,
                                item.dimensionUnit, item.rawValue),
                        item.piecesPerPackage, item.weightValue, item.weightUnit,
                        item.productId))
                .toList();
        ProductFamilyCollectionEntity primaryCollection = family.collections.stream()
                .filter(item -> item.primaryCollection).findFirst()
                .orElseGet(() -> family.collections.stream().findFirst().orElse(null));
        PublicFamilyCatalogDto.CategoryDto category = category(
                family, primaryCollection, categories, language, channel);

        Map<String, Language> textSources = new LinkedHashMap<>();
        source(textSources, "name", name.sourceLanguage());
        source(textSources, "summary", summary.sourceLanguage());
        source(textSources, "description", description.sourceLanguage());
        source(textSources, "format", optionalFamilySource(
                family, language, item -> item.format, format));
        source(textSources, "highlights", optionalFamilyListSource(
                family, language, highlights));
        source(textSources, "tags", tags.sourceLanguage());
        source(textSources, "seoTitle", seoTitle.sourceLanguage());
        source(textSources, "seoDescription", seoDescription.sourceLanguage());

        return new PublicFamilyCatalogDto.FamilyDto(
                family.id, family.familyKey, family.publicHandle, name.value(), summary.value(),
                description.value(), format.value(), highlights.value(), category, family.productPosition,
                publicFeaturedProductId(family.cardFeaturedProductId, family.id, null, channel),
                tags.value(), status(family, channel).name(),
                new PublicFamilyCatalogDto.SeoDto(seoTitle.value(), seoDescription.value()), dimensions,
                packages, images, publicVariants, Collections.unmodifiableMap(textSources),
                quoteImageId);
    }

    private PublicFamilyCatalogDto.VariantDto variant(
            ProductFamilyEntity family, ProductEntity product,
            List<ProductEntity> familyMembers, Language language, CatalogChannel channel) {
        ProductFamilyPhotoEntity primary = publicPhotos.primary(family, product, familyMembers, channel);
        BigDecimal amount = Product.calculateSalesPriceEur(
                product.fixedSalesPriceEur, product.landedCostEur, product.markupPct);
        PublicFamilyCatalogDto.PublicPriceDto publicPrice = amount.signum() > 0
                ? new PublicFamilyCatalogDto.PublicPriceDto(amount, "EUR", null) : null;
        String availability = product.inventoryKnown
                ? product.stockQuantity > 0 ? "IN_STOCK" : "OUT_OF_STOCK"
                : "UNKNOWN";
        LanguageFallback.Resolved<String> color = productText(
                product, language, item -> item.colour, product.colour);
        LanguageFallback.Resolved<String> size = productText(
                product, language, item -> item.variantSize, product.variantSize);
        LanguageFallback.Resolved<String> name = publicProductNames.resolve(product, language);
        Map<String, Language> sources = new LinkedHashMap<>();
        source(sources, "color", optionalProductSource(
                product, language, item -> item.colour, color));
        source(sources, "size", optionalProductSource(
                product, language, item -> item.variantSize, size));
        source(sources, "name", name.sourceLanguage());
        /* The unit dictionary is complete in every language, so the source is always exact. */
        source(sources, "unit", language);
        return new PublicFamilyCatalogDto.VariantDto(
                product.id, product.sku, product.canonicalBarcode,
                color.value(),
                size.value(), product.colourHex,
                name.value(), product.variantPosition, availability,
                primary == null ? null : primary.id, publicPrice,
                Collections.unmodifiableMap(sources),
                product.packagingKind == be.enrosed.catalog.domain.PackagingKind.DISPLAY
                        && product.packagingSalesUnit == be.enrosed.catalog.domain.SalesUnit.DISPLAY ? "DISPLAY" : "PIECE",
                product.packagingKind == be.enrosed.catalog.domain.PackagingKind.DISPLAY
                        ? product.packagingPiecesPerUnit : null,
                UnitDto.of(product.packagingUnitKey, language));
    }

    private PublicFamilyCatalogDto.ImageDto image(
            ProductFamilyEntity family, ProductFamilyPhotoEntity image,
            List<ProductEntity> familyMembers, Language language, int position) {
        LanguageFallback.Resolved<String> alt = alt(family, image, familyMembers, language);
        Map<String, Language> sources = new LinkedHashMap<>();
        source(sources, "alt", alt.sourceLanguage());
        return new PublicFamilyCatalogDto.ImageDto(
                image.id,
                imageUrl(family.publicHandle, image.sourceKey, "small", image.smallSha256),
                imageUrl(family.publicHandle, image.sourceKey, "large", image.largeSha256),
                image.smallWidthPx, image.smallHeightPx,
                image.largeWidthPx, image.largeHeightPx,
                alt.value(), position,
                resolvedProductId(image, familyMembers),
                null, image.variantColor,
                Collections.unmodifiableMap(sources));
    }

    private PublicFamilyCatalogDto.CategoryDto category(
            ProductFamilyEntity family,
            ProductFamilyCollectionEntity primary,
            List<CategoryEntity> categories,
            Language language,
            CatalogChannel channel) {
        if (family.categoryKey == null) return null;
        CategoryEntity category = categories.stream()
                .filter(candidate -> Objects.equals(candidate.id, family.categoryId))
                .findFirst()
                .or(() -> categories.stream().filter(candidate ->
                        CategoryPublicKey.from(candidate.code).equals(
                                CategoryPublicKey.from(family.categoryKey))).findFirst())
                .orElse(null);
        String baseName = category == null ? family.categoryName : category.name;
        String baseDescription = category == null
                ? primary == null ? null : primary.collection.description
                : category.description;
        String baseEyebrow = category == null
                ? primary == null ? null : primary.collection.eyebrow
                : category.eyebrow;
        String baseMobile = category == null
                ? primary == null ? null : primary.collection.mobileName
                : category.mobileName;
        String baseNavigation = category == null ? null : category.navigationName;
        String baseFooter = category == null ? null : category.footerName;
        List<CategoryTextEntity> texts = category == null ? List.of() : category.texts;
        LanguageFallback.Resolved<String> name = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.name, baseName);
        LanguageFallback.Resolved<String> description = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.description, baseDescription);
        LanguageFallback.Resolved<String> eyebrow = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.eyebrow, baseEyebrow);
        LanguageFallback.Resolved<String> mobileName = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.mobileName, baseMobile);
        LanguageFallback.Resolved<String> navigationName = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.navigationName,
                baseNavigation);
        LanguageFallback.Resolved<String> footerName = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.footerName, baseFooter);
        Map<String, Language> sources = new LinkedHashMap<>();
        source(sources, "name", name.sourceLanguage());
        source(sources, "description", description.sourceLanguage());
        source(sources, "eyebrow", eyebrow.sourceLanguage());
        source(sources, "mobileName", optionalCategorySource(
                texts, language, item -> item.mobileName, mobileName));
        source(sources, "navigationName", optionalCategorySource(
                texts, language, item -> item.navigationName, navigationName));
        source(sources, "footerName", optionalCategorySource(
                texts, language, item -> item.footerName, footerName));
        return new PublicFamilyCatalogDto.CategoryDto(
                CategoryPublicKey.from(family.categoryKey), name.value(), family.categoryPosition,
                eyebrow.value(), description.value(), mobileName.value(), navigationName.value(),
                footerName.value(),
                primary == null ? null : publicFeaturedProductId(
                        primary.collection.featuredProductId, null, primary.collection, channel),
                Collections.unmodifiableMap(sources),
                categoryPhotoUrl(category), categoryPhotoWidth(category), categoryPhotoHeight(category));
    }

    private PublicFamilyCatalogDto.CategoryDto category(
            CategoryEntity category, Language language, CatalogChannel channel) {
        List<CategoryTextEntity> texts = category.texts;
        LanguageFallback.Resolved<String> name = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.name, category.name);
        LanguageFallback.Resolved<String> description = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.description,
                category.description);
        LanguageFallback.Resolved<String> eyebrow = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.eyebrow, category.eyebrow);
        LanguageFallback.Resolved<String> mobileName = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.mobileName,
                category.mobileName);
        LanguageFallback.Resolved<String> navigationName = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.navigationName,
                category.navigationName);
        LanguageFallback.Resolved<String> footerName = LanguageFallback.text(
                texts, language, item -> item.language, item -> item.footerName,
                category.footerName);
        Map<String, Language> sources = new LinkedHashMap<>();
        source(sources, "name", name.sourceLanguage());
        source(sources, "description", description.sourceLanguage());
        source(sources, "eyebrow", eyebrow.sourceLanguage());
        source(sources, "mobileName", optionalCategorySource(
                texts, language, item -> item.mobileName, mobileName));
        source(sources, "navigationName", optionalCategorySource(
                texts, language, item -> item.navigationName, navigationName));
        source(sources, "footerName", optionalCategorySource(
                texts, language, item -> item.footerName, footerName));
        return new PublicFamilyCatalogDto.CategoryDto(
                CategoryPublicKey.from(category.code), name.value(), category.position,
                eyebrow.value(), description.value(), mobileName.value(), navigationName.value(),
                footerName.value(), publicFeaturedProductId(
                        category.featuredProductId, null, null, channel),
                Collections.unmodifiableMap(sources),
                categoryPhotoUrl(category), categoryPhotoWidth(category), categoryPhotoHeight(category));
    }

    /** The category's first photo as a public address, or null while it has none. */
    static String categoryPhotoUrl(CategoryEntity category) {
        if (category == null || category.photos == null || category.photos.isEmpty()) return null;
        var photo = category.photos.getFirst();
        return "/api/v1/public/catalog/categories/" + category.id + "/photos/" + photo.id
                + "?v=" + encode(photo.storageKey);
    }

    private static Integer categoryPhotoWidth(CategoryEntity category) {
        return category == null || category.photos == null || category.photos.isEmpty()
                ? null : category.photos.getFirst().widthPx;
    }

    private static Integer categoryPhotoHeight(CategoryEntity category) {
        return category == null || category.photos == null || category.photos.isEmpty()
                ? null : category.photos.getFirst().heightPx;
    }

    private Long resolvedProductId(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        ProductEntity resolved = variantResolver.resolve(image, familyMembers);
        return resolved == null || !resolved.active || resolved.demo ? null : resolved.id;
    }

    /** Never expose a stored merchandising id that the public variant graph cannot resolve. */
    private Long publicFeaturedProductId(
            Long productId, Long requiredFamilyId, ProductCollectionEntity requiredCollection,
            CatalogChannel channel) {
        if (productId == null) return null;
        ProductEntity product = products.findById(productId);
        if (product == null || !product.active || product.demo || product.familyId == null) return null;
        if (requiredFamilyId != null && !Objects.equals(product.familyId, requiredFamilyId)) {
            return null;
        }
        ProductFamilyEntity family = families.findById(product.familyId);
        if (family == null || !family.active
                || status(family, channel) != PublicationState.PUBLISHED) {
            return null;
        }
        if (requiredCollection != null && family.collections.stream().noneMatch(item ->
                item.collection != null && (item.collection == requiredCollection
                        || requiredCollection.id != null
                                && Objects.equals(item.collection.id, requiredCollection.id)
                        || Objects.equals(item.collection.collectionKey,
                                requiredCollection.collectionKey)))) {
            return null;
        }
        List<ProductEntity> members = products.list(
                "familyId = ?1 order by variantPosition, id", family.id);
        boolean hasPhoto = publicPhotos.primary(family, product, members, channel) != null;
        return hasPhoto ? productId : null;
    }

    private static LanguageFallback.Resolved<String> productText(
            ProductEntity product, Language requested,
            Function<ProductTextEntity, String> field, String baseFallback) {
        return LanguageFallback.text(product.texts, requested,
                item -> item.language, field, baseFallback);
    }

    private static LanguageFallback.Resolved<String> familyText(
            ProductFamilyEntity family, Language requested,
            Function<ProductFamilyTextEntity, String> field, String baseFallback) {
        return LanguageFallback.text(family.texts, requested,
                item -> item.language, field, baseFallback);
    }

    private static LanguageFallback.Resolved<String> seoTitle(
            ProductFamilyEntity family, Language requested,
            LanguageFallback.Resolved<String> localizedName,
            LanguageFallback.Resolved<String> fallback,
            Map<String, LocalizedValueDto> siteCopy) {
        String explicit = exactFamilyText(family, requested, item -> item.seoTitle);
        if (!blank(explicit)) return new LanguageFallback.Resolved<>(explicit, requested);
        LocalizedValueDto suffix = siteCopy.get("common.tradeWholesale");
        if (localizedName.sourceLanguage() == requested && !blank(localizedName.value())
                && suffix != null && suffix.language() == requested && !blank(suffix.value())) {
            return new LanguageFallback.Resolved<>(
                    localizedName.value() + " | " + suffix.value() + " | ENROSED", requested);
        }
        return fallback;
    }

    private static LanguageFallback.Resolved<String> seoDescription(
            ProductFamilyEntity family, Language requested,
            LanguageFallback.Resolved<String> summary,
            LanguageFallback.Resolved<String> description,
            LanguageFallback.Resolved<String> fallback) {
        String explicit = exactFamilyText(family, requested, item -> item.seoDescription);
        if (!blank(explicit)) return new LanguageFallback.Resolved<>(explicit, requested);
        if (summary.sourceLanguage() == requested && !blank(summary.value())) {
            return new LanguageFallback.Resolved<>(summary.value(), requested);
        }
        if (description.sourceLanguage() == requested && !blank(description.value())) {
            return new LanguageFallback.Resolved<>(description.value(), requested);
        }
        return fallback;
    }

    private static String exactFamilyText(
            ProductFamilyEntity family, Language requested,
            Function<ProductFamilyTextEntity, String> field) {
        return family.texts.stream().filter(text -> text.language == requested)
                .map(field).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);
    }

    private static LanguageFallback.Resolved<String> optionalFamilyText(
            ProductFamilyEntity family, Language requested,
            Function<ProductFamilyTextEntity, String> field, String baseFallback) {
        return familyText(family, requested, field, baseFallback);
    }

    private LanguageFallback.Resolved<List<String>> optionalFamilyList(
            ProductFamilyEntity family, Language requested, String baseFallback) {
        return LanguageFallback.resolve(
                family.texts, requested, item -> item.language,
                item -> ProductFamilyDto.readStrings(json, item.highlightsJson),
                values -> values != null && !values.isEmpty(),
                ProductFamilyDto.readStrings(json, baseFallback));
    }

    private LanguageFallback.Resolved<String> alt(
            ProductFamilyEntity family, ProductFamilyPhotoEntity image,
            List<ProductEntity> familyMembers, Language requested) {
        List<ProductFamilyDto.AltTextDto> values = ProductFamilyDto.read(json, image.altTextsJson,
                new TypeReference<List<ProductFamilyDto.AltTextDto>>() {});
        return FamilyPhotoAltText.resolve(family, image, familyMembers, values, requested);
    }

    private static void source(Map<String, Language> result, String key, Language language) {
        if (language != null) result.put(key, language);
    }

    private static Language optionalFamilySource(
            ProductFamilyEntity family, Language requested,
            Function<ProductFamilyTextEntity, String> field,
            LanguageFallback.Resolved<String> resolved) {
        if (!blank(resolved.value())) return resolved.sourceLanguage();
        return family.texts.stream().filter(text -> !blank(field.apply(text)))
                .map(text -> text.language).findFirst().orElse(null);
    }

    private Language optionalFamilyListSource(
            ProductFamilyEntity family, Language requested,
            LanguageFallback.Resolved<List<String>> resolved) {
        if (resolved.value() != null && !resolved.value().isEmpty()) {
            return resolved.sourceLanguage();
        }
        return family.texts.stream()
                .filter(text -> !ProductFamilyDto.readStrings(json, text.highlightsJson).isEmpty())
                .map(text -> text.language).findFirst().orElse(null);
    }

    private static Language optionalProductSource(
            ProductEntity product, Language requested,
            Function<ProductTextEntity, String> field,
            LanguageFallback.Resolved<String> resolved) {
        if (!blank(resolved.value())) return resolved.sourceLanguage();
        return product.texts.stream().filter(text -> !blank(field.apply(text)))
                .map(text -> text.language).findFirst().orElse(null);
    }

    private static Language optionalCategorySource(
            List<CategoryTextEntity> texts, Language requested,
            Function<CategoryTextEntity, String> field,
            LanguageFallback.Resolved<String> resolved) {
        if (!blank(resolved.value())) return resolved.sourceLanguage();
        return texts.stream().filter(text -> !blank(field.apply(text)))
                .map(text -> text.language).findFirst().orElse(null);
    }

    private static List<String> strictMissing(
            Language requested, List<PublicFamilyCatalogDto.FamilyDto> families) {
        List<String> missing = new ArrayList<>();
        for (PublicFamilyCatalogDto.FamilyDto family : families) {
            String prefix = "families." + family.publicHandle();
            requireSource(missing, prefix + ".name", family.name(),
                    family.textSources().get("name"), requested);
            requireSource(missing, prefix + ".summary", family.summary(),
                    family.textSources().get("summary"), requested);
            requireSource(missing, prefix + ".description", family.description(),
                    family.textSources().get("description"), requested);
            if (!blank(family.format()) || family.textSources().containsKey("format")) {
                requireSource(missing, prefix + ".format", family.format(),
                        family.textSources().get("format"), requested);
            }
            if (family.highlights() != null && !family.highlights().isEmpty()
                    || family.textSources().containsKey("highlights")) {
                if (family.highlights() == null || family.highlights().isEmpty()
                        || family.textSources().get("highlights") != requested) {
                    missing.add(prefix + ".highlights");
                }
            }
            requireSource(missing, prefix + ".seo.title", family.seo().title(),
                    family.textSources().get("seoTitle"), requested);
            requireSource(missing, prefix + ".seo.description", family.seo().description(),
                    family.textSources().get("seoDescription"), requested);
            if (family.category() == null) {
                missing.add(prefix + ".category");
            } else {
                requireSource(missing, prefix + ".category.name", family.category().name(),
                        family.category().textSources().get("name"), requested);
                requireSource(missing, prefix + ".category.description",
                        family.category().description(),
                        family.category().textSources().get("description"), requested);
                requireSource(missing, prefix + ".category.eyebrow",
                        family.category().eyebrow(),
                        family.category().textSources().get("eyebrow"), requested);
                requireApplicableOptional(missing, prefix + ".category.mobileName",
                        family.category().mobileName(), family.category().textSources(),
                        "mobileName", requested);
                requireApplicableOptional(missing, prefix + ".category.navigationName",
                        family.category().navigationName(), family.category().textSources(),
                        "navigationName", requested);
                requireApplicableOptional(missing, prefix + ".category.footerName",
                        family.category().footerName(), family.category().textSources(),
                        "footerName", requested);
            }
            for (PublicFamilyCatalogDto.VariantDto variant : family.variants()) {
                requireSource(missing, prefix + ".variants." + variant.id() + ".name",
                        variant.name(), variant.textSources().get("name"), requested);
                if (!blank(variant.color()) || variant.textSources().containsKey("color")) {
                    requireSource(missing, prefix + ".variants." + variant.id() + ".color",
                            variant.color(), variant.textSources().get("color"), requested);
                }
                if (!blank(variant.size()) || variant.textSources().containsKey("size")) {
                    requireSource(missing, prefix + ".variants." + variant.id() + ".size",
                            variant.size(), variant.textSources().get("size"), requested);
                }
            }
            for (PublicFamilyCatalogDto.ImageDto image : family.images()) {
                requireSource(missing, prefix + ".images." + image.id() + ".alt",
                        image.alt(), image.textSources().get("alt"), requested);
            }
        }
        return List.copyOf(new LinkedHashSet<>(missing));
    }

    private static List<String> strictCategoryMissing(
            Language requested, List<PublicFamilyCatalogDto.CategoryDto> categories) {
        List<String> missing = new ArrayList<>();
        for (PublicFamilyCatalogDto.CategoryDto category : categories) {
            String prefix = "categories." + category.key();
            requireSource(missing, prefix + ".name", category.name(),
                    category.textSources().get("name"), requested);
            requireSource(missing, prefix + ".description", category.description(),
                    category.textSources().get("description"), requested);
            requireSource(missing, prefix + ".eyebrow", category.eyebrow(),
                    category.textSources().get("eyebrow"), requested);
            requireApplicableOptional(missing, prefix + ".mobileName", category.mobileName(),
                    category.textSources(), "mobileName", requested);
            requireApplicableOptional(missing, prefix + ".navigationName",
                    category.navigationName(), category.textSources(),
                    "navigationName", requested);
            requireApplicableOptional(missing, prefix + ".footerName", category.footerName(),
                    category.textSources(), "footerName", requested);
        }
        return List.copyOf(new LinkedHashSet<>(missing));
    }

    private static void requireSource(
            List<String> missing, String path, String value,
            Language source, Language requested) {
        if (blank(value) || source != requested) missing.add(path);
    }

    private static void requireApplicableOptional(
            List<String> missing, String path, String value,
            Map<String, Language> sources, String key, Language requested) {
        if (!blank(value) || sources.containsKey(key)) {
            requireSource(missing, path, value, sources.get(key), requested);
        }
    }

    private static PublicationState status(ProductFamilyEntity family, CatalogChannel channel) {
        PublicationState state = switch (channel) {
            case WEBSITE -> family.websiteStatus;
            case ORDER_APP -> family.orderAppStatus;
            case CATALOGUE -> family.catalogueStatus;
        };
        return state == null ? PublicationState.DRAFT : state;
    }

    private static boolean publishedAnywhere(ProductFamilyEntity family) {
        return status(family, CatalogChannel.WEBSITE) == PublicationState.PUBLISHED
                || status(family, CatalogChannel.ORDER_APP) == PublicationState.PUBLISHED
                || status(family, CatalogChannel.CATALOGUE) == PublicationState.PUBLISHED;
    }

    static String imageUrl(String handle, String sourceId, String rendition, String sha256) {
        String path = "/api/v1/public/catalog/families/" + encode(handle)
                + "/images/" + encode(sourceId) + "/" + rendition;
        return sha256 == null || sha256.isBlank() ? path : path + "?v=" + encode(sha256);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
