package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.PartnerFinancingService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;

@Path("/api") @Produces(MediaType.APPLICATION_JSON) @RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PartnerFinanceResource {
    @Inject IncomingPaymentService incoming;
    @Inject PartnerFinancingService partner;
    @GET @Path("/incoming-payments")
    public List<IncomingPaymentService.IncomingPayment> incoming(@QueryParam("from") String from) {
        try { return incoming.list(from == null || from.isBlank() ? null : LocalDate.parse(from)); }
        catch (java.time.format.DateTimeParseException invalid) { throw new BusinessRuleException("Begindatum moet JJJJ-MM-DD zijn"); }
    }
    @GET @Path("/partner-financing")
    public List<PartnerFinancingService.Summary> list() { return partner.list(); }
    @GET @Path("/purchase-orders/{id}/partner-financing")
    public PartnerFinancingService.Summary get(@PathParam("id") long id) { return partner.get(id); }
}
