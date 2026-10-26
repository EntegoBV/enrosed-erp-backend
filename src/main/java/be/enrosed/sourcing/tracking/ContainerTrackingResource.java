package be.enrosed.sourcing.tracking;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/container-tracking")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ContainerTrackingResource {
    @Inject ContainerTrackingService service;
    @GET public ContainerTracking.Feed feed() { return service.feed(); }
    @POST @Path("/{orderId}/refresh")
    public ContainerTracking.Row refresh(@PathParam("orderId") long orderId) { return service.refresh(orderId); }
}
