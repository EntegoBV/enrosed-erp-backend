package be.enrosed.sourcing.adapter.in.rest;

import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.sourcing.application.ProductSupplierOverviewService;
import be.enrosed.sourcing.application.ProductSupplierOverviewService.ProductSupplierChange;
import be.enrosed.sourcing.application.ProductSupplierOverviewService.ProductSupplierNoteRequest;
import be.enrosed.sourcing.application.ProductSupplierOverviewService.ProductSupplierRequest;
import be.enrosed.sourcing.application.ProductSupplierOverviewService.ProductSupplierView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * The suppliers ("versies") of one product: each with its own EXW price, reference, version label, MOQ, lead time
 * and agreement note. Every change answers with the rows and the product card's mirror after it.
 */
@Path("/api/products/{productId}/suppliers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductSupplierResource {

    /** {@code {"active": false}}; a missing value reads as active. */
    public record ActiveRequest(Boolean active) {}

    private final ProductSupplierOverviewService overview;

    public ProductSupplierResource(ProductSupplierOverviewService overview) {
        this.overview = overview;
    }

    @GET
    public List<ProductSupplierView> rows(@PathParam("productId") long productId) {
        return overview.views(productId);
    }

    @POST
    public Response add(@PathParam("productId") long productId, ProductSupplierRequest request) {
        return Response.status(Response.Status.CREATED).entity(overview.add(productId, request)).build();
    }

    @PUT
    @Path("/{rowId}")
    public ProductSupplierChange update(@PathParam("productId") long productId, @PathParam("rowId") long rowId,
                                        ProductSupplierRequest request) {
        return overview.update(productId, rowId, request);
    }

    @PUT
    @Path("/{rowId}/note")
    public ProductSupplierChange updateNote(@PathParam("productId") long productId, @PathParam("rowId") long rowId,
                                            ProductSupplierNoteRequest request) {
        return overview.updateNote(productId, rowId, request);
    }

    @PUT
    @Path("/{rowId}/preferred")
    public ProductSupplierChange setPreferred(@PathParam("productId") long productId, @PathParam("rowId") long rowId) {
        return overview.setPreferred(productId, rowId);
    }

    @PUT
    @Path("/{rowId}/active")
    public ProductSupplierChange setActive(@PathParam("productId") long productId, @PathParam("rowId") long rowId,
                                           ActiveRequest request) {
        return overview.setActive(productId, rowId, request == null || !Boolean.FALSE.equals(request.active()));
    }

    @DELETE
    @Path("/{rowId}")
    public ProductSupplierChange delete(@PathParam("productId") long productId, @PathParam("rowId") long rowId) {
        return overview.delete(productId, rowId);
    }
}
