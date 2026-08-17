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

    /** Kopieert een product, meestal om dezelfde stijl in een andere kleur te zetten. */
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

    /** Controleert een barcode zonder op te slaan - voor directe feedback in het formulier. */
    @GET
    @Path("/barcode-check")
    public BarcodeValidator.Result checkBarcode(@QueryParam("value") String value) {
        return barcodes.validate(value);
    }

    /* ------------------------------------------------------------ fotos */

    /**
     * Laadt een foto op. Geen aantalbeperking en geen herschaling: het bestand
     * wordt bewaard zoals het binnenkomt.
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
            return ProductDto.from(products.addPhoto(id, file.fileName(), file.contentType(), data));
        }
    }

    /** Toont de foto in de browser, in originele kwaliteit. */
    @GET
    @Path("/{id}/photos/{photoId}")
    @Produces(MediaType.WILDCARD)
    public Response viewPhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        Photo photo = products.photo(id, photoId);
        return Response.ok(products.photoData(photo.storageKey()))
                .type(photo.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : photo.contentType())
                .header("Content-Disposition", "inline; filename=\"" + photo.originalFilename() + "\"")
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .build();
    }

    /** Downloadt de foto onder zijn oorspronkelijke bestandsnaam. */
    @GET
    @Path("/{id}/photos/{photoId}/download")
    @Produces(MediaType.WILDCARD)
    public Response downloadPhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        Photo photo = products.photo(id, photoId);
        return Response.ok(products.photoData(photo.storageKey()))
                .type(photo.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : photo.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + photo.originalFilename() + "\"")
                .build();
    }

    @DELETE
    @Path("/{id}/photos/{photoId}")
    public ProductDto deletePhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        return ProductDto.from(products.removePhoto(id, photoId));
    }

    /** Zet de fotoreeks in de meegegeven volgorde; de eerste wordt de hoofdfoto. */
    @PUT
    @Path("/{id}/photos/order")
    public ProductDto reorderPhotos(@PathParam("id") long id, List<Long> photoIdsInOrder) {
        return ProductDto.from(products.reorderPhotos(id, photoIdsInOrder));
    }
}
