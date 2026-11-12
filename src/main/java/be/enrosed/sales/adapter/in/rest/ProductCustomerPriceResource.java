package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CustomerPriceService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * The customers who have their own price for one product (klantprijzen), for the product screens:
 * customer, price, end date and status. Read only; the prices are managed on the customer.
 */
@Path("/api/products/{id}/customer-prices")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductCustomerPriceResource {

    private final CustomerPriceService prices;

    public ProductCustomerPriceResource(CustomerPriceService prices) {
        this.prices = prices;
    }

    @GET
    public List<CustomerPriceService.ProductRow> list(@PathParam("id") long id) {
        return prices.forProduct(id);
    }
}
