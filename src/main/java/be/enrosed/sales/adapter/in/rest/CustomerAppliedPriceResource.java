package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CustomerAppliedPriceService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** The prices one customer was charged on their quotes and invoices, per product; read from the customer sheet and the product popup. */
@Path("/api/customers/{id}/applied-prices")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CustomerAppliedPriceResource {

    private final CustomerAppliedPriceService applied;

    public CustomerAppliedPriceResource(CustomerAppliedPriceService applied) {
        this.applied = applied;
    }

    /** One row per product, the most recently charged first; {@code productId} narrows to one product. */
    @GET
    public List<CustomerAppliedPriceService.AppliedPriceRow> rows(@PathParam("id") long id,
                                                                  @QueryParam("productId") Long productId) {
        return applied.rows(id, productId);
    }

    /** Every line of one product, newest document first. */
    @GET
    @Path("/{productId}")
    public List<CustomerAppliedPriceService.AppliedPriceEntry> entries(@PathParam("id") long id,
                                                                       @PathParam("productId") long productId) {
        return applied.entries(id, productId);
    }
}
