package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ProductChannelPriceService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/products/{id}/channel-prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductChannelPriceResource {
    @Inject ProductChannelPriceService prices;

    @GET public List<ProductChannelPriceService.Price> list(@PathParam("id") long id) {
        return prices.prices(id);
    }

    @PUT @Path("/TICA")
    public ProductChannelPriceService.Price update(@PathParam("id") long id, ProductChannelPriceService.Change change) {
        return prices.update(id, change);
    }

    @GET @Path("/TICA/history")
    public List<ProductChannelPriceService.History> history(@PathParam("id") long id) {
        return prices.history(id);
    }
}
