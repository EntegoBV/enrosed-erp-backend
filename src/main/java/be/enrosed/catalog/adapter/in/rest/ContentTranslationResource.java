package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ContentTranslationService;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/content-translations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ContentTranslationResource {
    private final ContentTranslationService content;

    public ContentTranslationResource(ContentTranslationService content) {
        this.content = content;
    }

    @GET
    public ContentTranslationDto.IndexDto list(@QueryParam("scope") ContentScope scope) {
        return content.index(scope);
    }

    @POST
    public Response create(ContentTranslationDto.CreateDto request) {
        return Response.status(Response.Status.CREATED).entity(content.create(request)).build();
    }

    @GET
    @Path("/{scope}/{key}")
    public ContentTranslationDto get(@PathParam("scope") ContentScope scope,
                                     @PathParam("key") String key) {
        return content.get(scope, key);
    }

    @PUT
    @Path("/{scope}/{key}")
    public ContentTranslationDto update(@PathParam("scope") ContentScope scope,
                                        @PathParam("key") String key,
                                        ContentTranslationDto.UpdateDto request) {
        return content.update(scope, key, request);
    }

    @DELETE
    @Path("/{scope}/{key}")
    public Response delete(@PathParam("scope") ContentScope scope,
                           @PathParam("key") String key,
                           @QueryParam("revision") Long revision) {
        if (revision == null) {
            throw new BadRequestException("revision is verplicht bij verwijderen");
        }
        content.delete(scope, key, revision);
        return Response.noContent().build();
    }
}
