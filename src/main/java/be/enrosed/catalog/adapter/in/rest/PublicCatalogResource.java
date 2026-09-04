package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.application.PublicProductNameResolver;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
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
import java.util.Map;
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

        /* The canonical family owns WEBSITE publication. Reuse the same
           projection as public quotations so the legacy flat endpoint cannot
           keep exposing stale SKU publication flags after a family is hidden.
           Unlinked pre-family products still use their own WEBSITE state in
           ProductService.websiteOrderableProducts(). */
        var candidates = channel == CatalogChannel.WEBSITE
                ? products.websiteOrderableProducts()
                : products.list().stream()
                    .filter(product -> product.isPublishedTo(channel))
                    .toList();

        var publicProducts = candidates.stream()
                .sorted(order)
                .map(product -> PublicCatalogDto.product(
                        product, categoryById.get(product.categoryId()), language,
                        uriInfo.getBaseUri().toString(), publicName(product, language)))
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
        Product product = products.get(productId);
        if (!product.isPublishedToAnyPublicChannel()) throw new NotFoundException();

        Photo photo = product.photos().stream()
                .filter(candidate -> candidate.id() != null && candidate.id() == photoId)
                .findFirst()
                .orElseThrow(NotFoundException::new);
        return PhotoResponses.inline(products.photoData(photo.storageKey()), photo.contentType())
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .build();
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
