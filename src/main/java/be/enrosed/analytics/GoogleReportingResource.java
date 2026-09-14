package be.enrosed.analytics;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/analytics/website")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
public class GoogleReportingResource {
    private final GoogleReportingService reporting;
    public GoogleReportingResource(GoogleReportingService reporting) { this.reporting=reporting; }
    @GET @Path("/google") public Response report(@QueryParam("days") @DefaultValue("30") int days) {
        return Response.ok(reporting.report(days)).header("Cache-Control","no-store").build();
    }
    @GET @Path("/search-console") public Response searchConsole(@QueryParam("days") @DefaultValue("30") int days) {
        return Response.ok(reporting.searchConsole(days)).header("Cache-Control","no-store").build();
    }
}
