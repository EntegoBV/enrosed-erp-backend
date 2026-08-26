package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.WebsiteBuilderService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/website-builder/homepage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class WebsiteBuilderResource {
    private final WebsiteBuilderService builder;

    public WebsiteBuilderResource(WebsiteBuilderService builder) {
        this.builder = builder;
    }

    @GET
    public WebsiteBuilderDto.AdminDto get() {
        return builder.get();
    }

    @PUT
    public WebsiteBuilderDto.AdminDto update(WebsiteBuilderDto.UpdateDto request) {
        return builder.update(request);
    }

    @POST
    @Path("/publish")
    public WebsiteBuilderDto.AdminDto publish(WebsiteBuilderDto.PublishDto request) {
        return builder.publish(request);
    }
}
