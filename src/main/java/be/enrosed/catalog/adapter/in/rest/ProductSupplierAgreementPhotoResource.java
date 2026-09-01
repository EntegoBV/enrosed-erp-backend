package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhoto;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService.AgreementPhotoFile;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/** Admin-only API for the supplier-scoped evidence attached to a product agreement. */
@Path("/api/products/{productId}/supplier-agreement/photos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductSupplierAgreementPhotoResource {

    private final ProductSupplierAgreementPhotoService photos;

    @Inject
    public ProductSupplierAgreementPhotoResource(ProductSupplierAgreementPhotoService photos) {
        this.photos = photos;
    }

    @GET
    public List<AgreementPhotoDto> list(@PathParam("productId") long productId) {
        return photos.list(productId).stream().map(AgreementPhotoDto::from).toList();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(
            @PathParam("productId") long productId,
            @RestForm("file") FileUpload file,
            @RestForm("caption") String caption) throws IOException {
        if (file == null) throw new BadRequestException("Geen bestand meegestuurd");
        AgreementPhoto created;
        try (InputStream data = Files.newInputStream(file.uploadedFile())) {
            created = photos.upload(productId, file.fileName(), data, caption);
        }
        return Response.status(Response.Status.CREATED)
                .entity(AgreementPhotoDto.from(created))
                .build();
    }

    @GET
    @Path("/{photoId:\\d+}")
    @Produces(MediaType.WILDCARD)
    public Response view(
            @PathParam("productId") long productId,
            @PathParam("photoId") long photoId) {
        AgreementPhotoFile file = photos.open(productId, photoId);
        return PhotoResponses.inline(
                        file.data(), file.photo().contentType(), file.photo().originalFilename())
                .header("Cache-Control", "private, max-age=60")
                .build();
    }

    @GET
    @Path("/{photoId:\\d+}/download")
    @Produces(MediaType.WILDCARD)
    public Response download(
            @PathParam("productId") long productId,
            @PathParam("photoId") long photoId) {
        AgreementPhotoFile file = photos.open(productId, photoId);
        return PhotoResponses.attachment(
                        file.data(), file.photo().contentType(), file.photo().originalFilename())
                .build();
    }

    @PUT
    @Path("/{photoId:\\d+}")
    public AgreementPhotoDto updateCaption(
            @PathParam("productId") long productId,
            @PathParam("photoId") long photoId,
            CaptionRequest request) {
        if (request == null) throw new BadRequestException("Geen bijschrift meegestuurd");
        return AgreementPhotoDto.from(photos.updateCaption(productId, photoId, request.caption()));
    }

    /** Body: a bare JSON array containing every current photo id exactly once. */
    @PUT
    @Path("/order")
    public List<AgreementPhotoDto> reorder(
            @PathParam("productId") long productId, List<Long> photoIdsInOrder) {
        return photos.reorder(productId, photoIdsInOrder).stream()
                .map(AgreementPhotoDto::from)
                .toList();
    }

    @DELETE
    @Path("/{photoId:\\d+}")
    public Response delete(
            @PathParam("productId") long productId,
            @PathParam("photoId") long photoId) {
        photos.delete(productId, photoId);
        return Response.noContent().build();
    }

    public record CaptionRequest(String caption) {}

    public record AgreementPhotoDto(
            long id,
            long productId,
            long supplierId,
            int position,
            String caption,
            String originalFilename,
            String contentType,
            long sizeBytes,
            Integer widthPx,
            Integer heightPx,
            String viewUrl,
            String downloadUrl) {

        static AgreementPhotoDto from(AgreementPhoto photo) {
            String base = "/api/products/" + photo.productId()
                    + "/supplier-agreement/photos/" + photo.id();
            return new AgreementPhotoDto(
                    photo.id(), photo.productId(), photo.supplierId(), photo.position(),
                    photo.caption(), photo.originalFilename(), photo.contentType(),
                    photo.sizeBytes(), photo.widthPx(), photo.heightPx(),
                    base, base + "/download");
        }
    }
}
