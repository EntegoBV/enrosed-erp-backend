package be.enrosed.finance.forecast;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * The Kasprognose's choices per container. A prefix of its own: a second
 * class under /api/purchase-orders would shadow the sourcing routes.
 */
@Path("/api/purchase-order-forecasts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PurchaseOrderForecastResource {
    @Inject PurchaseOrderForecastService forecasts;

    @GET
    public List<PurchaseOrderForecastService.PurchaseOrderForecastView> list() {
        return forecasts.list();
    }

    /** The whole set of choices; an all-null body removes the row. */
    @PUT
    @Path("/{orderId}")
    public PurchaseOrderForecastService.PurchaseOrderForecastView save(@PathParam("orderId") long orderId,
                                                                        PurchaseOrderForecastService.Write request) {
        return forecasts.save(orderId, request);
    }

    @DELETE
    @Path("/{orderId}")
    public Response delete(@PathParam("orderId") long orderId) {
        forecasts.delete(orderId);
        return Response.noContent().build();
    }
}
