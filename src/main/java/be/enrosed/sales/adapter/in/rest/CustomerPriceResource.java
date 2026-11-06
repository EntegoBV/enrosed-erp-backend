package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CustomerPriceService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** The prices agreed with one customer (klantprijzen), saved on their own from the customer sheet. */
@Path("/api/customers/{id}/prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CustomerPriceResource {

    private final CustomerPriceService prices;

    public CustomerPriceResource(CustomerPriceService prices) {
        this.prices = prices;
    }

    @GET
    public List<CustomerPriceService.Row> list(@PathParam("id") long id) {
        return prices.list(id);
    }

    /** Only the listed products change; a null price removes one. Answers the full list. */
    @PUT
    public List<CustomerPriceService.Row> change(@PathParam("id") long id,
                                                 CustomerPriceService.ChangeRequest request) {
        return prices.change(id, request);
    }

    @GET
    @Path("/history")
    public List<CustomerPriceService.HistoryRow> history(@PathParam("id") long id,
                                                         @QueryParam("productId") Long productId) {
        return prices.history(id, productId);
    }
}
