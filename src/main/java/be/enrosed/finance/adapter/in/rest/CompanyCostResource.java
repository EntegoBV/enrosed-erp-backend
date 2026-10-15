package be.enrosed.finance.adapter.in.rest;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.application.CompanyCostService.CostView;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostPayment;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

/**
 * The company's own costs: booked, listed by period, corrected and removed,
 * with their payments. Every cost comes back as a view: its fields at the
 * top level plus the payments and what is paid and open.
 */
@Path("/api/costs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CompanyCostResource {

    private final CompanyCostService costs;
    @jakarta.inject.Inject be.enrosed.finance.odoo.OdooCostImportStore sourceDocuments;

    public CompanyCostResource(CompanyCostService costs) {
        this.costs = costs;
    }

    @GET
    public List<CostView> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        return costs.views(costs.list(parse(from), parse(to)));
    }

    @GET
    @Path("/{id}")
    public CostView get(@PathParam("id") long id) {
        return costs.view(costs.get(id));
    }

    @GET @Path("/{id}/odoo-source")
    public be.enrosed.finance.odoo.OdooCostImportStore.Source source(@PathParam("id") long id) { costs.get(id); return sourceDocuments.source(id); }

    @POST
    public Response create(CompanyCost cost) {
        return Response.status(Response.Status.CREATED).entity(costs.view(costs.create(cost))).build();
    }

    @PUT
    @Path("/{id}")
    public CostView update(@PathParam("id") long id, CompanyCost cost) {
        return costs.view(costs.update(id, cost));
    }

    /** Body: {"paidOn": "2026-09-08"}; an empty body means today. Pays what is still open. */
    @POST
    @Path("/{id}/paid")
    public CostView markPaid(@PathParam("id") long id, PaidRequest body) {
        return costs.view(costs.markPaid(id, body == null ? null : parse(body.paidOn())));
    }

    public record PaidRequest(String paidOn) {}

    /** Body: {"dueDate": "2026-11-05"}; null clears it. The cost PUT never changes the due date. */
    @PUT
    @Path("/{id}/due-date")
    public CostView setDueDate(@PathParam("id") long id, DueDateRequest body) {
        return costs.setDueDate(id, body == null ? null : body.dueDate());
    }

    public record DueDateRequest(LocalDate dueDate) {}

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        costs.delete(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/payments")
    public List<CostPayment> payments(@PathParam("id") long id) {
        return costs.paymentsOf(id);
    }

    /** Body: {"paidOn": "2026-10-05", "amountEur": 40.00, "account": "KBC", "note": null}. */
    @POST
    @Path("/{id}/payments")
    public Response addPayment(@PathParam("id") long id, CostPayment payment) {
        return Response.status(Response.Status.CREATED).entity(costs.addPayment(id, payment)).build();
    }

    @PUT
    @Path("/{id}/payments/{paymentId}")
    public CostView updatePayment(@PathParam("id") long id, @PathParam("paymentId") long paymentId, CostPayment payment) {
        return costs.updatePayment(id, paymentId, payment);
    }

    @DELETE
    @Path("/{id}/payments/{paymentId}")
    public CostView deletePayment(@PathParam("id") long id, @PathParam("paymentId") long paymentId) {
        return costs.deletePayment(id, paymentId);
    }

    private static LocalDate parse(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.strip());
    }
}
