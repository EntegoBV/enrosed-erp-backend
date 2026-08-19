package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.BarcodeValidator;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
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

    public ProductResource(ProductService products, BarcodeValidator barcodes) {
        this.products = products;
        this.barcodes = barcodes;
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
        return ProductDto.from(products.update(id, dto.toDomain(id)));
    }

    /** Copies a product, usually to make the same style in another colour. */
    @POST
    @Path("/{id}/duplicate")
    public Response duplicate(@PathParam("id") long id, DuplicateRequest request) {
        var copy = products.duplicate(id, request == null ? null : request.colour());
        return Response.status(Response.Status.CREATED).entity(ProductDto.from(copy)).build();
    }

    public record DuplicateRequest(String colour) {}

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        products.delete(id);
        return Response.noContent().build();
    }

    /** Validates a barcode without saving - for instant feedback in the form. */
    @GET
    @Path("/barcode-check")
    public BarcodeValidator.Result checkBarcode(@QueryParam("value") String value) {
        return barcodes.validate(value);
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
                .header("Cache-Control", "public, max-age=31536000, immutable")
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
