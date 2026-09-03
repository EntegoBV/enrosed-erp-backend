package be.enrosed.analytics;

import be.enrosed.analytics.WebsiteAnalyticsDtos.VisitInput;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The website's edge function posts one beacon per page view here. When an
 * ingest key is configured, a beacon without it is refused, so nobody can
 * pad the statistics from elsewhere.
 */
@Path("/api/public/analytics/visits")
@PermitAll
public class PublicWebsiteVisitResource {

    @Inject
    WebsiteVisitService visits;

    @ConfigProperty(name = "enrosed.analytics.ingest-key")
    Optional<String> ingestKey;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@HeaderParam("X-Enrosed-Analytics-Key") String key, VisitInput input) {
        String required = ingestKey.map(String::strip).filter(value -> !value.isEmpty()).orElse(null);
        if (required != null && !required.equals(key)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return visits.record(input)
                ? Response.noContent().build()
                : Response.status(Response.Status.BAD_REQUEST).build();
    }
}
