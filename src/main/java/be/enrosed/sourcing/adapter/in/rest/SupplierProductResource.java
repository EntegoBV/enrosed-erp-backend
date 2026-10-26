package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.application.ProductSupplierOverviewService;
import be.enrosed.sourcing.application.ProductSupplierOverviewService.SupplierProductView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** The products one supplier makes: its rows (active and inactive) with their products, for the supplier page. */
@Path("/api/suppliers/{supplierId}/products")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class SupplierProductResource {

    private final ProductSupplierOverviewService overview;

    public SupplierProductResource(ProductSupplierOverviewService overview) {
        this.overview = overview;
    }

    @GET
    public List<SupplierProductView> products(@PathParam("supplierId") long supplierId) {
        return overview.productsOfSupplier(supplierId);
    }
}
