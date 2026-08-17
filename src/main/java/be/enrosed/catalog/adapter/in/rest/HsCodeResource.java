package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.HsCodeService;
import be.enrosed.catalog.domain.HsCode;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/hs-codes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class HsCodeResource {

    private final HsCodeService hsCodes;

    public HsCodeResource(HsCodeService hsCodes) {
        this.hsCodes = hsCodes;
    }

    @GET
    public List<HsCode> list() {
        return hsCodes.list();
    }

    @PUT
    public HsCode save(HsCode hsCode) {
        return hsCodes.save(hsCode);
    }

    @GET
    @Path("/{code}/usage")
    public Map<String, Long> usage(@PathParam("code") String code) {
        return Map.of("products", hsCodes.productsUsing(code));
    }

    @DELETE
    @Path("/{code}")
    public Response delete(@PathParam("code") String code) {
        hsCodes.delete(code);
        return Response.noContent().build();
    }
}
