package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

/** The company's own costs: booked, listed by period, corrected and removed. */
@Path("/api/costs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CompanyCostResource {

    private final CompanyCostService costs;

    public CompanyCostResource(CompanyCostService costs) {
        this.costs = costs;
    }

    @GET
    public List<CompanyCost> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        return costs.list(parse(from), parse(to));
    }

    @GET
    @Path("/{id}")
    public CompanyCost get(@PathParam("id") long id) {
        return costs.get(id);
    }

    @POST
    public Response create(CompanyCost cost) {
        return Response.status(Response.Status.CREATED).entity(costs.create(cost)).build();
    }

    @PUT
    @Path("/{id}")
    public CompanyCost update(@PathParam("id") long id, CompanyCost cost) {
        return costs.update(id, cost);
    }

    /** Body: {"paidOn": "2026-09-08"}; an empty body means today. */
    @POST
    @Path("/{id}/paid")
    public CompanyCost markPaid(@PathParam("id") long id, PaidRequest body) {
        return costs.markPaid(id, body == null ? null : parse(body.paidOn()));
    }

    public record PaidRequest(String paidOn) {}

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        costs.delete(id);
        return Response.noContent().build();
    }

    private static LocalDate parse(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.strip());
    }
}
