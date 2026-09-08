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
    @Inject be.enrosed.sales.application.PartnerAdvanceScheduleService schedules;
    @Inject be.enrosed.sales.application.SalesOrderService sales;
    @GET @Path("/incoming-payments")
    public List<IncomingPaymentService.IncomingPayment> incoming(@QueryParam("from") String from) {
        try { return incoming.list(from == null || from.isBlank() ? null : LocalDate.parse(from)); }
        catch (java.time.format.DateTimeParseException invalid) { throw new BusinessRuleException("Begindatum moet JJJJ-MM-DD zijn"); }
    }
    @GET @Path("/partner-financing")
    public List<PartnerFinancingService.Summary> list() { return partner.list(); }
    @GET @Path("/purchase-orders/{id}/partner-financing")
    public PartnerFinancingService.Summary get(@PathParam("id") long id) { return partner.get(id); }

    @GET @Path("/purchase-orders/{id}/partner-advance-schedule")
    public be.enrosed.sales.application.PartnerAdvanceScheduleService.Schedule schedule(@PathParam("id") long id) {
        return schedules.get(id);
    }
    @PUT @Path("/purchase-orders/{id}/partner-advance-schedule") @Consumes(MediaType.APPLICATION_JSON)
    public be.enrosed.sales.application.PartnerAdvanceScheduleService.Schedule saveSchedule(@PathParam("id") long id,
            be.enrosed.sales.application.PartnerAdvanceScheduleService.Request request) { return schedules.save(id, request); }

    @POST @Path("/purchase-orders/{id}/partner-advance-schedule/{rowId}/invoice")
    public SalesOrderResource.OrderView scheduleInvoice(@PathParam("id") long id, @PathParam("rowId") long rowId) {
        var invoice = schedules.createInvoice(id, rowId);
        var priced = sales.price(invoice);
        return new SalesOrderResource.OrderView(invoice, priced, false, null, null, null, null,
                incoming.summary(invoice, priced), partner.accounting(invoice, priced), null);
    }
    @GET @Path("/purchase-orders/{id}/partner-settlement-availability")
    public be.enrosed.sales.application.PartnerSettlementLedger.Availability settlementAvailability(@PathParam("id") long id) {
        return sales.partnerSettlementAvailability(id);
    }
}
