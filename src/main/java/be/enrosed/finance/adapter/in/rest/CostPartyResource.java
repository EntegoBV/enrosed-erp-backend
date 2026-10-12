package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.application.CostPartyService;
import be.enrosed.finance.domain.CostParty;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** The list "Aan wie": who the company pays, with the defaults a cost to them takes. */
@Path("/api/cost-parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CostPartyResource {

    private final CostPartyService parties;

    public CostPartyResource(CostPartyService parties) {
        this.parties = parties;
    }

    @GET
    public List<CostParty> list() {
        return parties.list();
    }

    @GET
    @Path("/{id}")
    public CostParty get(@PathParam("id") long id) {
        return parties.get(id);
    }

    @POST
    public Response create(CostParty party) {
        return Response.status(Response.Status.CREATED).entity(parties.create(party)).build();
    }

    @PUT
    @Path("/{id}")
    public CostParty update(@PathParam("id") long id, CostParty party) {
        return parties.update(id, party);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        parties.delete(id);
        return Response.noContent().build();
    }
}
