package be.enrosed.analytics;

import be.enrosed.analytics.WebsiteAnalyticsDtos.Report;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** The website statistics behind Analyses › Website. */
@Path("/api/analytics/website")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
public class WebsiteAnalyticsResource {

    @Inject
    WebsiteVisitService visits;

    @GET
    public Report report(@QueryParam("days") @DefaultValue("30") int days) {
        return visits.report(days);
    }
}
