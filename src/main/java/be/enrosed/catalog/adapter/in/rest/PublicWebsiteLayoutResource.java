package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.WebsiteBuilderService;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/public/website-layout")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class PublicWebsiteLayoutResource {
    private final WebsiteBuilderService builder;

    public PublicWebsiteLayoutResource(WebsiteBuilderService builder) {
        this.builder = builder;
    }

    @GET
    public Response get() {
        return Response.ok(builder.published())
                .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300")
                .build();
    }
}
