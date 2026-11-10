package be.enrosed.partnerportal.adapter.in.rest;

import be.enrosed.partnerportal.application.PartnerPortalViews;
import be.enrosed.partnerportal.application.PartnerSaleReportService;
import be.enrosed.partnerportal.application.PartnerSaleRequests;
import be.enrosed.partnerportal.application.PartnerSalesStaffService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Veilingverkopen for staff: the meldingen of every partner, what is still to invoice, the
 * analytics rows, a melding staff enter for the partner, and withdrawing one. Staff only, like
 * every staff resource (never the partner role here).
 */
@Path("/api/partner-sales")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PartnerSalesResource {

    private final PartnerSalesStaffService staff;
    private final PartnerSaleReportService meldingen;

    public PartnerSalesResource(PartnerSalesStaffService staff, PartnerSaleReportService meldingen) {
        this.staff = staff;
        this.meldingen = meldingen;
    }

    @GET
    public PartnerPortalViews.StaffPage list(@QueryParam("customerId") Long customerId,
                                             @QueryParam("status") String status,
                                             @QueryParam("before") Long before,
                                             @QueryParam("limit") Integer limit) {
        return staff.list(customerId, status, before, limit);
    }

    @GET
    @Path("/summary")
    public PartnerPortalViews.Summary summary() {
        return staff.summary();
    }

    /** By sold_on, both ends inclusive and optional (yyyy-mm-dd). */
    @GET
    @Path("/analytics")
    public PartnerPortalViews.Analytics analytics(@QueryParam("from") String from, @QueryParam("to") String to) {
        return staff.analytics(date(from), date(to));
    }

    @GET
    @Path("/reports/{id}")
    public PartnerPortalViews.StaffReport report(@PathParam("id") long id) {
        return staff.report(id);
    }

    /** A melding the partner phoned in, entered by staff (no login on it; the actor is the staff member). */
    @POST
    @Path("/reports")
    public Response create(PartnerSaleRequests.Report request) {
        if (request == null || request.customerId() == null) throw new BusinessRuleException("Kies de partner");
        PartnerSaleReportService.Outcome outcome = meldingen.create(request.customerId(), null, request, true);
        return Response.status(outcome.created() ? Response.Status.CREATED : Response.Status.OK)
                .entity(staff.report(outcome.reportId())).build();
    }

    /** Intrekken: puts the stock back, while nothing of the melding is invoiced. */
    @DELETE
    @Path("/reports/{id}")
    public PartnerPortalViews.StaffReport withdraw(@PathParam("id") long id, @QueryParam("reason") String reason) {
        meldingen.withdraw(null, id, reason, true);
        return staff.report(id);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            throw new BusinessRuleException("Geef een datum als jjjj-mm-dd");
        }
    }
}
