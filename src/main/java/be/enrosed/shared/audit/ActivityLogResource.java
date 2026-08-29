package be.enrosed.shared.audit;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Read-only activity feed. Business services are the only writers. */
@Path("/api/activity")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ActivityLogResource {

    private final ActivityLogService activities;

    public ActivityLogResource(ActivityLogService activities) {
        this.activities = activities;
    }

    @GET
    public ActivityPageDto list(@QueryParam("actor") String actor,
                                @QueryParam("category") String category,
                                @QueryParam("entityType") String entityType,
                                @QueryParam("entityId") String entityId,
                                @QueryParam("before") Long before,
                                @QueryParam("limit") @DefaultValue("50") int limit) {
        try {
            return activities.list(actor, category, entityType, entityId, before, limit);
        } catch (IllegalArgumentException invalidFilter) {
            throw new BadRequestException(invalidFilter.getMessage());
        }
    }
}
