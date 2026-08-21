package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.WebsiteRebuildService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/website-rebuild")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class WebsiteRebuildResource {
    private final WebsiteRebuildService rebuilds;

    public WebsiteRebuildResource(WebsiteRebuildService rebuilds) {
        this.rebuilds = rebuilds;
    }

    @GET
    public WebsiteRebuildDto status() { return rebuilds.status(); }

    @POST
    @Path("/retry")
    public WebsiteRebuildDto retry() { return rebuilds.retry(); }
}
