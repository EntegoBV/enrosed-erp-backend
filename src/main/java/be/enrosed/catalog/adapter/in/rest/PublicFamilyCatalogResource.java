package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
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
    private final CanonicalCatalogDaos.DimensionObservations dimensionObservations;
    private final CatalogDaos.Products products;
    private final CanonicalCatalogDaos.PriceObservations prices;
    private final PhotoStorage photoStorage;
    private final ObjectMapper json;

    public PublicFamilyCatalogResource(
            CanonicalCatalogDaos.Families families,
            CanonicalCatalogDaos.DimensionObservations dimensionObservations,
            CatalogDaos.Products products,
            CanonicalCatalogDaos.PriceObservations prices,
            PhotoStorage photoStorage,
            ObjectMapper json) {
        this.families = families;
        this.dimensionObservations = dimensionObservations;
        this.products = products;
        this.prices = prices;
        this.photoStorage = photoStorage;
        this.json = json;
    }

    @GET
    public Response catalog(
            @QueryParam("channel") @DefaultValue("WEBSITE") CatalogChannel channel,
            @QueryParam("language") @DefaultValue("EN") String languageCode,
            @Context UriInfo uriInfo) {
        Language language = Language.of(languageCode);
        List<PublicFamilyCatalogDto.FamilyDto> publicFamilies = families.findAll().list().stream()
                .filter(family -> family.active)
                .filter(family -> status(family, channel) == PublicationState.PUBLISHED)
                .sorted(Comparator.comparingInt((ProductFamilyEntity item) -> item.categoryPosition)
                        .thenComparingInt(item -> item.productPosition)
                        .thenComparing(item -> safe(item.name), String.CASE_INSENSITIVE_ORDER))
                .map(family -> family(family, language, channel))
                .toList();
        return Response.ok(new PublicFamilyCatalogDto(channel, language, publicFamilies))
                .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300")
                .build();
    }

    /** Stable family/source-id URL; a 404 never reveals private family metadata. */
    @GET
    @Path("/{handle}/images/{sourceId}/{rendition}")
    @Produces(MediaType.WILDCARD)
    public Response image(@PathParam("handle") String handle,
                          @PathParam("sourceId") String sourceId,
                          @PathParam("rendition") String rendition) {
        ProductFamilyEntity family = families.find("publicHandle", handle).firstResult();
        if (family == null || !family.active || !publishedAnywhere(family)) {
            throw new NotFoundException();
        }
        ProductFamilyPhotoEntity image = family.photos.stream()
                .filter(candidate -> Objects.equals(candidate.sourceKey, sourceId))
                .findFirst().orElseThrow(NotFoundException::new);
        boolean small = "small".equalsIgnoreCase(rendition);
        if (!small && !"large".equalsIgnoreCase(rendition)) throw new NotFoundException();
        String storageKey = small ? image.smallStorageKey : image.largeStorageKey;
        String contentType = small ? image.smallContentType : image.largeContentType;
        return PhotoResponses.inline(photoStorage.read(storageKey), contentType, image.originalFilename)
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .build();
    }

    private PublicFamilyCatalogDto.FamilyDto family(
            ProductFamilyEntity family, Language language, CatalogChannel channel) {
        List<ProductFamilyTextEntity> textFallbacks = texts(family, language);
        String name = text(textFallbacks, item -> item.name, family.name);
        String summary = text(textFallbacks, item -> item.summary, family.summary);
        String description = text(textFallbacks, item -> item.description, family.description);
        String format = text(textFallbacks, item -> item.format, family.format);
        List<String> highlights = textList(textFallbacks, family.highlightsJson);
        String seoTitle = text(textFallbacks, item -> item.seoTitle, family.seoTitle);
        String seoDescription = text(textFallbacks, item -> item.seoDescription, family.seoDescription);
        List<PublicFamilyCatalogDto.ImageDto> images = family.photos.stream()
                .sorted(Comparator.comparingInt(item -> item.position))
                .map(image -> new PublicFamilyCatalogDto.ImageDto(
                        image.id,
                        imageUrl(family.publicHandle, image.sourceKey, "small"),
                        imageUrl(family.publicHandle, image.sourceKey, "large"),
                        image.smallWidthPx, image.smallHeightPx,
                        image.largeWidthPx, image.largeHeightPx,
                        alt(image, language), image.position,
                        image.variantExternalId, image.variantColor))
                .toList();

        List<ProductEntity> variants = products.list("familyId = ?1 and active = true order by variantPosition, id",
                family.id);
        List<PublicFamilyCatalogDto.VariantDto> publicVariants = variants.stream()
                .map(variant -> variant(family, variant))
                .toList();

        PublicFamilyCatalogDto.DimensionsDto dimensions = channel == CatalogChannel.WEBSITE
                ? websiteDimensions(family.id)
                : noDimensions(family) ? null : new PublicFamilyCatalogDto.DimensionsDto(
                    family.dimensionLength, family.dimensionWidth, family.dimensionHeight,
                    family.dimensionUnit, family.dimensionRaw);
        List<PublicFamilyCatalogDto.PackageDto> packages = family.packages.stream()
                .filter(item -> Boolean.TRUE.equals(item.operational))
                .sorted(Comparator.comparingInt(item -> item.position))
                .map(item -> new PublicFamilyCatalogDto.PackageDto(
                        item.packageType, item.position,
                        new PublicFamilyCatalogDto.DimensionsDto(
                                item.lengthValue, item.widthValue, item.heightValue,
                                item.dimensionUnit, item.rawValue),
                        item.piecesPerPackage, item.weightValue, item.weightUnit,
                        item.variantExternalId))
                .toList();
        ProductFamilyCollectionEntity primaryCollection = family.collections.stream()
                .filter(item -> item.primaryCollection).findFirst()
                .orElseGet(() -> family.collections.stream().findFirst().orElse(null));
        PublicFamilyCatalogDto.CategoryDto category = family.categoryKey == null ? null
                : new PublicFamilyCatalogDto.CategoryDto(
                        family.categoryKey, family.categoryName, family.categoryPosition,
                        primaryCollection == null ? null : primaryCollection.collection.eyebrow,
                        primaryCollection == null ? null : primaryCollection.collection.description);

        return new PublicFamilyCatalogDto.FamilyDto(
                family.id, family.familyKey, family.publicHandle, name, summary, description,
                format, highlights, category, family.productPosition,
                ProductFamilyDto.readStrings(json, family.tagsJson), status(family, channel).name(),
                new PublicFamilyCatalogDto.SeoDto(seoTitle, seoDescription), dimensions,
                packages, images, publicVariants);
    }

    private PublicFamilyCatalogDto.VariantDto variant(
            ProductFamilyEntity family, ProductEntity product) {
        ProductFamilyPhotoEntity primary = family.photos.stream()
                .filter(image -> Objects.equals(image.variantExternalId, product.canonicalVariantKey))
                .min(Comparator.comparingInt(image -> image.position))
                .orElseGet(() -> family.photos.stream()
                        .min(Comparator.comparingInt(image -> image.position)).orElse(null));
        ProductPriceObservationEntity retail = publicPrice(product.id, "RETAIL");
        ProductPriceObservationEntity compareAt = publicPrice(product.id, "COMPARE_AT");
        PublicFamilyCatalogDto.PublicPriceDto publicPrice;
        if (product.fixedSalesPriceEur != null && product.fixedSalesPriceEur.signum() > 0) {
            /* An explicit dashboard-owned fixed price supersedes historical import observations. */
            publicPrice = new PublicFamilyCatalogDto.PublicPriceDto(
                    product.fixedSalesPriceEur, "EUR", null);
        } else {
            publicPrice = retail == null ? null : new PublicFamilyCatalogDto.PublicPriceDto(
                    retail.amount, retail.currency, compareAt == null ? null : compareAt.amount);
        }
        Object availability = product.publicAvailability != null
                ? product.publicAvailability
                : product.inventoryKnown
                    ? product.stockQuantity > 0 ? "IN_STOCK" : "OUT_OF_STOCK"
                    : "UNKNOWN";
        return new PublicFamilyCatalogDto.VariantDto(
                product.id, product.sku, product.canonicalBarcode, product.colour,
                product.colour != null ? product.colour : product.name,
                product.variantPosition, availability, primary == null ? null : primary.id, publicPrice);
    }

    private ProductPriceObservationEntity publicPrice(long productId, String role) {
        return prices.find("productId = ?1 and publicRole = ?2 and publicPrice = true", productId, role)
                .firstResult();
    }

    /** Website dimensions are presentation content; PDF-only operational facts stay internal. */
    private PublicFamilyCatalogDto.DimensionsDto websiteDimensions(long familyId) {
        ProductDimensionObservationEntity observation = dimensionObservations.find(
                        "familyId = ?1 and dimensionType = ?2 and sourceType = ?3 order by position",
                        familyId, "PRODUCT_DISPLAY", "WEBSITE_FRONTEND")
                .firstResult();
        if (observation == null) return null;
        List<BigDecimal> values = ProductFamilyDto.read(json, observation.valuesJson,
                new TypeReference<List<BigDecimal>>() {});
        return new PublicFamilyCatalogDto.DimensionsDto(
                value(values, 0), value(values, 1), value(values, 2),
                observation.unit, observation.rawValue);
    }

    private static List<ProductFamilyTextEntity> texts(
            ProductFamilyEntity family, Language requested) {
        Map<Language, Integer> rank = new EnumMap<>(Language.class);
        rank.put(requested, 0);
        rank.putIfAbsent(Language.EN, 1);
        rank.putIfAbsent(Language.NL, 2);
        return family.texts.stream()
                .sorted(Comparator.comparingInt(item -> rank.getOrDefault(item.language, 3)))
                .toList();
    }

    private static String text(
            List<ProductFamilyTextEntity> candidates,
            Function<ProductFamilyTextEntity, String> field,
            String baseFallback) {
        return candidates.stream().map(field).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(baseFallback);
    }

    private List<String> textList(
            List<ProductFamilyTextEntity> candidates, String baseFallback) {
        return candidates.stream()
                .map(item -> ProductFamilyDto.readStrings(json, item.highlightsJson))
                .filter(values -> !values.isEmpty()).findFirst()
                .orElseGet(() -> ProductFamilyDto.readStrings(json, baseFallback));
    }

    private String alt(ProductFamilyPhotoEntity image, Language requested) {
        List<ProductFamilyDto.AltTextDto> values = ProductFamilyDto.read(json, image.altTextsJson,
                new TypeReference<List<ProductFamilyDto.AltTextDto>>() {});
        return values.stream().filter(value -> value.language() == requested).map(ProductFamilyDto.AltTextDto::alt)
                .findFirst()
                .or(() -> values.stream().filter(value -> value.language() == Language.EN)
                        .map(ProductFamilyDto.AltTextDto::alt).findFirst())
                .or(() -> values.stream().map(ProductFamilyDto.AltTextDto::alt).filter(Objects::nonNull).findFirst())
                .orElse("");
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

    private static boolean noDimensions(ProductFamilyEntity family) {
        return family.dimensionLength == null && family.dimensionWidth == null
                && family.dimensionHeight == null && family.dimensionUnit == null
                && family.dimensionRaw == null;
    }

    private static String imageUrl(String handle, String sourceId, String rendition) {
        return "/api/v1/public/catalog/families/" + encode(handle)
                + "/images/" + encode(sourceId) + "/" + rendition;
    }

    private static BigDecimal value(List<BigDecimal> values, int index) {
        return values.size() > index ? values.get(index) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
