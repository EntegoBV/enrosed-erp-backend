package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.application.FairService;
import be.enrosed.finance.domain.Fair;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** The fairs behind the costs: listed with the inactive ones, added, relabelled, switched off, removed when unused. */
@Path("/api/fairs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class FairResource {

    private final FairService fairs;

    public FairResource(FairService fairs) {
        this.fairs = fairs;
    }

    @GET
    public List<Fair> list() {
        return fairs.list();
    }

    /** Body: {"label": "Ede", "code": "EDE"}; the code is derived from the label when left out. */
    @POST
    public Response create(CreateRequest body) {
        return Response.status(Response.Status.CREATED)
                .entity(fairs.create(body == null ? null : body.label(), body == null ? null : body.code()))
                .build();
    }

    /** Body: {"label": "Ede", "active": false}; a missing switch keeps the current one. */
    @PUT
    @Path("/{id}")
    public Fair update(@PathParam("id") long id, UpdateRequest body) {
        return fairs.update(id, body == null ? null : body.label(), body == null ? null : body.active());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        fairs.delete(id);
        return Response.noContent().build();
    }

    public record CreateRequest(String label, String code) {}

    public record UpdateRequest(String label, Boolean active) {}
}
