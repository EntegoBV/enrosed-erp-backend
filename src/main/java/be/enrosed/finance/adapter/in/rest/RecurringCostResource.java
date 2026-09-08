package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.application.RecurringCostService;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.RecurringCost;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** The recurring costs: set up once, booked by the server every time they fall due. */
@Path("/api/recurring-costs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class RecurringCostResource {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private final RecurringCostService recurring;

    public RecurringCostResource(RecurringCostService recurring) {
        this.recurring = recurring;
    }

    @GET
    public List<RecurringCost> list() {
        return recurring.list();
    }

    @GET
    @Path("/{id}")
    public RecurringCost get(@PathParam("id") long id) {
        return recurring.get(id);
    }

    @POST
    public Response create(RecurringCost definition) {
        RecurringCost saved = recurring.create(definition);
        /* A first date in the past is booked right away, so the list shows what the rhythm does. */
        recurring.bookDue(LocalDate.now(BRUSSELS));
        return Response.status(Response.Status.CREATED).entity(recurring.get(saved.id())).build();
    }

    @PUT
    @Path("/{id}")
    public RecurringCost update(@PathParam("id") long id, RecurringCost definition) {
        recurring.update(id, definition);
        recurring.bookDue(LocalDate.now(BRUSSELS));
        return recurring.get(id);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        recurring.delete(id);
        return Response.noContent().build();
    }

    /** Books everything due today, now, instead of waiting for the hourly run. */
    @POST
    @Path("/book")
    public List<CompanyCost> book() {
        return recurring.bookDue(LocalDate.now(BRUSSELS));
    }
}
