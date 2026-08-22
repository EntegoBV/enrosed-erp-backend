package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.BarcodeOwner;
import be.enrosed.catalog.application.BarcodeValidator;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductVariantLinkService;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

@Path("/api/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductResource {

    private final ProductService products;
    private final BarcodeValidator barcodes;
    private final ProductVariantLinkService variantLinks;
    private final ProductFamilyDtoFactory familyDtos;

    public ProductResource(
            ProductService products,
            BarcodeValidator barcodes,
            ProductVariantLinkService variantLinks,
            ProductFamilyDtoFactory familyDtos) {
        this.products = products;
        this.barcodes = barcodes;
        this.variantLinks = variantLinks;
        this.familyDtos = familyDtos;
    }

    @GET
    public List<ProductDto> list(@QueryParam("supplierId") Long supplierId) {
        var found = supplierId == null ? products.list() : products.listBySupplier(supplierId);
        return found.stream().map(ProductDto::from).toList();
    }

    @GET
    @Path("/{id}")
    public ProductDto get(@PathParam("id") long id) {
        return ProductDto.from(products.get(id));
    }

    @POST
    public Response create(ProductDto dto) {
        var created = products.create(dto.toDomain(null));
        return Response.status(Response.Status.CREATED).entity(ProductDto.from(created)).build();
    }

    @PUT
    @Path("/{id}")
    public ProductDto update(@PathParam("id") long id, ProductDto dto) {
        var current = products.get(id);
        var changes = dto.toDomainForUpdate(current);
        return ProductDto.from(products.update(id, changes));
    }

    /** Explicit family assignment; a null id intentionally unlinks the SKU. */
    @PUT
    @Path("/{id}/family")
    public ProductDto assignFamily(
            @PathParam("id") long id, FamilyAssignmentRequest request) {
        if (request == null) throw new BadRequestException("Geen familie-opdracht meegestuurd");
        return ProductDto.from(products.assignFamily(id, request.familyId()));
    }

    public record FamilyAssignmentRequest(Long familyId) {}

    /** Links two existing SKUs while the server owns family creation and membership details. */
    @POST
    @Path("/{id}/variants")
    @Transactional
    public ProductFamilyDto linkVariant(
            @PathParam("id") long id, ProductVariantLinkRequest request) {
        if (request == null || request.variantProductId() == null) {
            throw new BadRequestException("Kies een product om als variant te koppelen");
        }
        return familyDtos.from(variantLinks.link(id, request.variantProductId()).family());
    }

    /** Copies a product, usually to make the same style in another colour. */
    @POST
    @Path("/{id}/duplicate")
    public Response duplicate(@PathParam("id") long id, DuplicateRequest request) {
        var copy = products.duplicate(
                id,
                request == null ? null : request.colour(),
                request == null ? null : request.colourHex(),
                request == null ? null : request.variantSize());
        return Response.status(Response.Status.CREATED).entity(ProductDto.from(copy)).build();
    }

    /** Additive request: older clients that only send colour remain compatible. */
    public record DuplicateRequest(String colour, String colourHex, String variantSize) {}

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        products.delete(id);
        return Response.noContent().build();
    }

    /**
     * Validates a barcode without saving - for instant feedback in the form.
     * A well-formed code that already sits on another product is reported
     * as invalid too, naming that product and level.
     */
    @GET
    @Path("/barcode-check")
    public BarcodeValidator.Result checkBarcode(
            @QueryParam("value") String value, @QueryParam("excludeProductId") Long excludeProductId) {
        BarcodeValidator.Result result = barcodes.validate(value);
        if (!result.valid() || value == null || value.isBlank()) return result;
        BarcodeOwner owner = products.barcodeOwner(value, excludeProductId);
        return owner == null ? result : BarcodeValidator.Result.fail(owner.describe(value.trim()));
    }

    /* ------------------------------------------------------------ fotos */

    /**
     * Uploads a photo. No count limit and no rescaling: the file is kept
     * exactly as it arrives.
     */
    @POST
    @Path("/{id}/photos")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ProductDto uploadPhoto(@PathParam("id") long id, @RestForm("file") FileUpload file)
            throws IOException {
        if (file == null) {
            throw new BadRequestException("Geen bestand meegestuurd");
        }
        try (InputStream data = Files.newInputStream(file.uploadedFile())) {
            return ProductDto.from(products.addPhoto(id, file.fileName(), data));
        }
    }

    /** Shows the photo in the browser, in original quality. */
    @GET
    @Path("/{id}/photos/{photoId}")
    @Produces(MediaType.WILDCARD)
    public Response viewPhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        Photo photo = products.photo(id, photoId);
        return PhotoResponses.inline(
                products.photoData(photo.storageKey()),
                        photo.contentType(), photo.originalFilename())
                .header("Cache-Control", "private, max-age=60")
                .build();
    }

    /** Downloads the photo under its original file name. */
    @GET
    @Path("/{id}/photos/{photoId}/download")
    @Produces(MediaType.WILDCARD)
    public Response downloadPhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        Photo photo = products.photo(id, photoId);
        return PhotoResponses.attachment(
                        products.photoData(photo.storageKey()),
                        photo.contentType(), photo.originalFilename())
                .build();
    }

    @DELETE
    @Path("/{id}/photos/{photoId}")
    public ProductDto deletePhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        return ProductDto.from(products.removePhoto(id, photoId));
    }

    /** Orders the photo series as given; the first becomes the primary photo. */
    @PUT
    @Path("/{id}/photos/order")
    public ProductDto reorderPhotos(@PathParam("id") long id, List<Long> photoIdsInOrder) {
        return ProductDto.from(products.reorderPhotos(id, photoIdsInOrder));
    }
}
