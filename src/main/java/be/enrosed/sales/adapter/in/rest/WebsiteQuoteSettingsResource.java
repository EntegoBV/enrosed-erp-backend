package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.WebsiteQuoteSettingsService;
import be.enrosed.shared.security.AdminIdentityProvider;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/website/quote-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Blocking
public class WebsiteQuoteSettingsResource {
    private final WebsiteQuoteSettingsService settings;

    public WebsiteQuoteSettingsResource(WebsiteQuoteSettingsService settings) {
        this.settings = settings;
    }

    public record Settings(Boolean pricesVisible) {}

    @GET
    public Response get() {
        return response(settings.pricesVisible());
    }

    @PUT
    public Response update(Settings request) {
        if (request == null || request.pricesVisible() == null) {
            throw new BadRequestException("pricesVisible is required");
        }
        return response(settings.update(request.pricesVisible()));
    }

    private static Response response(boolean pricesVisible) {
        return Response.ok(new Settings(pricesVisible)).header("Cache-Control", "no-store").build();
    }
}
