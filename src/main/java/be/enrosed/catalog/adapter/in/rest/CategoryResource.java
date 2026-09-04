package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CategoryPhotoService;
import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.domain.Category;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CategoryResource {

    private final CategoryService categories;

    @jakarta.inject.Inject
    CategoryPhotoService photos;

    public CategoryResource(CategoryService categories) {
        this.categories = categories;
    }

    /* ------------------------------------------------------------ foto's */

    /** Uploads a category photo, kept at full quality. */
    @POST
    @Path("/{id}/photos")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Category uploadPhoto(@PathParam("id") long id,
                                @org.jboss.resteasy.reactive.RestForm("file")
                                org.jboss.resteasy.reactive.multipart.FileUpload file) throws java.io.IOException {
        if (file == null) throw new jakarta.ws.rs.BadRequestException("Geen bestand meegestuurd");
        try (java.io.InputStream data = java.nio.file.Files.newInputStream(file.uploadedFile())) {
            return photos.add(id, file.fileName(), data);
        }
    }

    public record PhotoImportRequest(String url) {}

    /** Takes a picture over from enrosed.com as this category's photo. */
    @POST
    @Path("/{id}/photos/import")
    public Category importPhoto(@PathParam("id") long id, PhotoImportRequest request) {
        return photos.importFromUrl(id, request == null ? null : request.url());
    }

    @GET
    @Path("/{id}/photos/{photoId}")
    @Produces(MediaType.WILDCARD)
    public Response viewPhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        be.enrosed.catalog.domain.Photo photo = photos.photo(id, photoId);
        return PhotoResponses.inline(photos.data(photo.storageKey()), photo.contentType(), photo.originalFilename())
                .header("Cache-Control", "private, max-age=60")
                .build();
    }

    @DELETE
    @Path("/{id}/photos/{photoId}")
    public Category deletePhoto(@PathParam("id") long id, @PathParam("photoId") long photoId) {
        return photos.remove(id, photoId);
    }

    /** Orders the photos as given; the first becomes the one the category opens with. */
    @PUT
    @Path("/{id}/photos/order")
    public Category reorderPhotos(@PathParam("id") long id, List<Long> photoIdsInOrder) {
        return photos.reorder(id, photoIdsInOrder);
    }

    @GET
    public List<Category> list() {
        return categories.list();
    }

    @POST
    public Response create(Category category) {
        return Response.status(Response.Status.CREATED).entity(categories.create(category)).build();
    }

    @PUT
    @Path("/{id}")
    public Category update(@PathParam("id") long id, Category category) {
        return categories.update(id, category);
    }

    @PUT
    @Path("/order")
    public List<Category> reorder(List<Long> ids) {
        return categories.reorder(ids);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        categories.delete(id);
        return Response.noContent().build();
    }
}
