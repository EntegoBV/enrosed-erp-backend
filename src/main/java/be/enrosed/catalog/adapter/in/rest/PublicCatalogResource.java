package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.application.PublicProductNameResolver;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.PublicFamilyPhotoProjection;
import be.enrosed.catalog.application.FamilyPhotoPublicationPolicy;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Language;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Public, read-only catalogue contract for the website and future order app. */
@Path("/api/v1/public/catalog")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class PublicCatalogResource {

    private final ProductService products;
    private final CategoryService categories;
    private final CatalogDaos.Products productRows;
    private final PublicProductNameResolver publicProductNames;

    @Inject
    CanonicalCatalogDaos.Families families;
    @Inject
    PublicFamilyPhotoProjection publicPhotos;
    @Inject
    FamilyPhotoPublicationPolicy photoPublication;

    @Inject
    public PublicCatalogResource(
            ProductService products, CategoryService categories,
            CatalogDaos.Products productRows, PublicProductNameResolver publicProductNames) {
        this.products = products;
        this.categories = categories;
        this.productRows = productRows;
        this.publicProductNames = publicProductNames;
    }

    /** Compatibility constructor for direct unit callers written for the legacy projection. */
    public PublicCatalogResource(ProductService products, CategoryService categories) {
        this(products, categories, null, null);
    }

    @GET
    public Response catalog(
            @QueryParam("channel") @DefaultValue("WEBSITE") CatalogChannel channel,
            @QueryParam("language") @DefaultValue("NL") String languageCode,
            @Context UriInfo uriInfo) {
        Language language = Language.of(languageCode);
        Map<Long, Category> categoryById = categories.list().stream()
                .collect(Collectors.toMap(Category::id, Function.identity()));

        Comparator<Product> order = Comparator
                .comparingInt((Product product) -> {
                    Category category = categoryById.get(product.categoryId());
                    return category == null ? Integer.MAX_VALUE : category.position();
                })
                .thenComparing(product -> safe(publicName(product, language)), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(product -> safe(product.sku()), String.CASE_INSENSITIVE_ORDER);

        var publicProducts = products.list().stream()
                .filter(product -> isPublished(product, channel))
                .sorted(order)
                .map(product -> publicProduct(product, categoryById.get(product.categoryId()),
                        language, channel, uriInfo.getBaseUri().toString()))
                .filter(Objects::nonNull)
                .toList();

        return Response.ok(new PublicCatalogDto(channel, language, publicProducts))
                .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300")
                .build();
    }

    @jakarta.inject.Inject
    be.enrosed.catalog.application.CategoryPhotoService categoryPhotos;

    /** A category's photo, public by nature: the picture a collection opens with. */
    @GET
    @Path("/categories/{categoryId}/photos/{photoId}")
    @Produces(MediaType.WILDCARD)
    public Response categoryPhoto(@PathParam("categoryId") long categoryId,
                                  @PathParam("photoId") long photoId) {
        if (categoryPhotos == null) throw new NotFoundException();
        Photo photo;
        try {
            photo = categoryPhotos.photo(categoryId, photoId);
        } catch (be.enrosed.shared.NotFoundException missing) {
            throw new NotFoundException();
        }
        return PhotoResponses.inline(categoryPhotos.data(photo.storageKey()), photo.contentType())
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .build();
    }

    /**
     * Serves original photo bytes only while their product is public somewhere.
     * A 404 avoids exposing whether a private product or photo exists.
     */
    @GET
    @Path("/products/{productId}/photos/{photoId}")
    @Produces(MediaType.WILDCARD)
    public Response photo(@PathParam("productId") long productId,
                          @PathParam("photoId") long photoId) {
        Product product;
        try {
            product = products.get(productId);
        } catch (be.enrosed.shared.NotFoundException missing) {
            throw new NotFoundException();
        }
        if (Arrays.stream(CatalogChannel.values()).noneMatch(channel -> isPublished(product, channel))) {
            throw new NotFoundException();
        }

        Photo photo = product.photos().stream()
                .filter(candidate -> candidate.id() != null && candidate.id() == photoId)
                .findFirst()
                .orElseThrow(NotFoundException::new);
        if (!isPublicPhoto(product, photo)) throw new NotFoundException();
        return PhotoResponses.inline(products.photoData(photo.storageKey()), photo.contentType())
                .header("Cache-Control", "public, max-age=60")
                .build();
    }

    private PublicCatalogDto.PublicProductDto publicProduct(
            Product product, Category category, Language language, CatalogChannel channel, String baseUrl) {
        if (product.familyId() == null) {
            return PublicCatalogDto.product(product, category, language, baseUrl, publicName(product, language));
        }
        ProductFamilyEntity family = families.findById(product.familyId());
        ProductEntity row = productRows.findById(product.id());
        if (family == null || row == null || !family.active || !row.active || row.demo
                || !Objects.equals(row.familyId, family.id) || !isPublished(product, channel)
                || family.publicHandle == null || family.publicHandle.isBlank()) return null;
        List<ProductEntity> members = productRows.list("familyId = ?1 order by variantPosition, id", family.id);
        ProductFamilyPhotoEntity primary = publicPhotos.primary(family, row, members, channel);
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        List<PublicCatalogDto.PhotoDto> images = publicPhotos.images(family, members, channel).stream()
                .filter(image -> photoPublication.isUsableBy(image, row, members, channel))
                .sorted(Comparator.comparingInt((ProductFamilyPhotoEntity image) ->
                        primary != null && Objects.equals(image.id, primary.id) ? 0 : 1)
                        .thenComparingInt(image -> image.position))
                .map(image -> new PublicCatalogDto.PhotoDto(
                        image.id, image.largeContentType, image.largeWidthPx, image.largeHeightPx, image.position,
                        base + "api/v1/public/catalog/families/" + encode(family.publicHandle)
                                + "/images/" + encode(image.sourceKey) + "/large"))
                .toList();
        return PublicCatalogDto.product(product, category, language, publicName(product, language), images);
    }

    private boolean isPublished(Product product, CatalogChannel channel) {
        if (!product.active() || product.demo()) return false;
        if (product.familyId() == null) return product.isPublishedTo(channel);
        if (families == null) return false;
        ProductFamilyEntity family = families.findById(product.familyId());
        if (family == null || !family.active) return false;
        PublicationState state = switch (channel) {
            case WEBSITE -> family.websiteStatus;
            case ORDER_APP -> family.orderAppStatus;
            case CATALOGUE -> family.catalogueStatus;
        };
        return state == PublicationState.PUBLISHED;
    }

    private boolean isPublicPhoto(Product product, Photo photo) {
        if (product.familyId() == null) return !photo.inherited();
        ProductFamilyEntity family = families.findById(product.familyId());
        ProductEntity row = productRows.findById(product.id());
        if (family == null || row == null || !family.active || !row.active || row.demo
                || !Objects.equals(row.familyId, family.id)) return false;
        List<ProductEntity> members = productRows.list("familyId = ?1 order by variantPosition, id", family.id);
        return Arrays.stream(CatalogChannel.values())
                .filter(channel -> isPublished(product, channel))
                .anyMatch(channel -> publicPhotos.images(family, members, channel).stream()
                        .filter(image -> photoPublication.isUsableBy(image, row, members, channel))
                        .anyMatch(image -> Objects.equals(image.largeStorageKey, photo.storageKey())
                                && (photo.inherited() ? Objects.equals(image.id, photo.familyPhotoId())
                                    : Objects.equals(image.id, -photo.id()))));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String publicName(Product product, Language language) {
        if (productRows == null || publicProductNames == null || product.id() == null) {
            return product.nameIn(language);
        }
        ProductEntity row = productRows.findById(product.id());
        return row == null ? product.nameIn(language) : publicProductNames.name(row, language);
    }
}
