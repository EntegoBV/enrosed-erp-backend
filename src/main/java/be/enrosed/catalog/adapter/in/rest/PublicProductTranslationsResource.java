package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.PublicProductTranslationsService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/products/{productId}/public-translations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PublicProductTranslationsResource {
    private final PublicProductTranslationsService translations;

    public PublicProductTranslationsResource(PublicProductTranslationsService translations) {
        this.translations = translations;
    }

    @GET
    public PublicProductTranslationsDto get(@PathParam("productId") long productId) {
        return translations.get(productId);
    }

    @PUT
    public PublicProductTranslationsDto update(
            @PathParam("productId") long productId,
            PublicProductTranslationsDto.UpdateDto request) {
        return translations.update(productId, request);
    }
}
