package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.shared.Language;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.math.BigDecimal;

/** Our own side of the sales order - cost price and margin included. */
@Path("/api/sales-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class SalesOrderResource {

    private final SalesOrderService salesOrders;
    private final QuoteService quotes;

    public SalesOrderResource(SalesOrderService salesOrders, QuoteService quotes) {
        this.salesOrders = salesOrders;
        this.quotes = quotes;
    }

    public record CreateRequest(long customerId, String countryCode, String incoterm,
                                DocumentType docType) {}
    public record SendRequest(String message) {}
    public record RevisionDecision(String handledBy, String message) {}
    public record DeliveryTermsRequest(List<SalesOrderService.DeliveryWeekChange> lines) {}
    public record FreightRequest(FreightState state, BigDecimal manualFreightEur,
                                 FreightPricingStrategy freightPricingStrategy,
                                 BigDecimal freightRatePerCbmEur,
                                 Long freightCarrierId) {}
    public record OrderView(SalesOrder order, PricedOrder priced) {}
    public record PortalLink(boolean available, String status, String url) {}

    @GET
    public List<OrderView> list() {
        return salesOrders.list().stream()
                .map(order -> new OrderView(order, salesOrders.price(order)))
                .toList();
    }

    @GET
    @Path("/{id}")
    public OrderView get(@PathParam("id") long id) {
        SalesOrder order = salesOrders.get(id);
        return new OrderView(order, salesOrders.price(order));
    }

    @POST
    public Response create(CreateRequest request) {
        SalesOrder created = salesOrders.create(request.customerId(), request.countryCode(),
                request.incoterm(),
                request.docType() == null ? DocumentType.OFFERTE : request.docType());
        return Response.status(Response.Status.CREATED)
                .entity(new OrderView(created, salesOrders.price(created)))
                .build();
    }

    /** Freezes the quote's content into a new invoice; the quote stays. */
    @POST
    @Path("/{id}/invoice")
    public OrderView createInvoice(@PathParam("id") long id) {
        SalesOrder invoice = salesOrders.createInvoiceFrom(id);
        return new OrderView(invoice, salesOrders.price(invoice));
    }

    @POST
    @Path("/{id}/mark-sent")
    public OrderView markInvoiceSent(@PathParam("id") long id) {
        SalesOrder sent = salesOrders.markInvoiceSent(id);
        return new OrderView(sent, salesOrders.price(sent));
    }

    @POST
    @Path("/{id}/mark-paid")
    public OrderView markInvoicePaid(@PathParam("id") long id) {
        SalesOrder paid = salesOrders.markInvoicePaid(id);
        return new OrderView(paid, salesOrders.price(paid));
    }

    @PUT
    @Path("/{id}")
    public OrderView update(@PathParam("id") long id, SalesOrder order) {
        SalesOrder saved = salesOrders.update(id, order);
        return new OrderView(saved, salesOrders.price(saved));
    }

    /**
     * Prices an order as it stands on screen, without saving: the editor keeps
     * a draft and writes only on Opslaan, but the figures follow every edit.
     */
    @POST
    @Path("/{id}/preview")
    public OrderView preview(@PathParam("id") long id, SalesOrder order) {
        salesOrders.get(id);
        return new OrderView(order, salesOrders.price(order));
    }

    /** Fills in delivery weeks without reopening every field of a sent quote. */
    @PUT
    @Path("/{id}/delivery-terms")
    public OrderView updateDeliveryTerms(@PathParam("id") long id, DeliveryTermsRequest request) {
        SalesOrder saved = salesOrders.updateDeliveryWeeks(id,
                request == null ? null : request.lines());
        return new OrderView(saved, salesOrders.price(saved));
    }

    /** Updates only the open freight item on a sent quote. */
    @PUT
    @Path("/{id}/freight")
    public OrderView updateFreight(@PathParam("id") long id, FreightRequest request) {
        SalesOrder saved = salesOrders.updateFreight(id,
                request == null ? null : request.state(),
                request == null ? null : request.manualFreightEur(),
                request == null ? null : request.freightPricingStrategy(),
                request == null ? null : request.freightRatePerCbmEur(),
                request == null ? null : request.freightCarrierId());
        return new OrderView(saved, salesOrders.price(saved));
    }

    @POST
    @Path("/{id}/duplicate")
    public OrderView duplicate(@PathParam("id") long id) {
        SalesOrder copy = salesOrders.duplicate(id);
        return new OrderView(copy, salesOrders.price(copy));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        salesOrders.delete(id);
        return Response.noContent().build();
    }

    /* ------------------------------------------------------------ quote */

    /** Builds the PDF, mails it to the customer and marks the quote sent. */
    @POST
    @Path("/{id}/send")
    public OrderView send(@PathParam("id") long id, SendRequest request) {
        SalesOrder sent = quotes.send(id, request == null ? null : request.message());
        return new OrderView(sent, salesOrders.price(sent));
    }

    /** The history of a quote, oldest step first. */
    @GET
    @Path("/{id}/history")
    public List<QuoteEvent> history(@PathParam("id") long id) {
        return quotes.history(id);
    }

    /** Puts a rejected or expired quote back on concept for adjusting. */
    @POST
    @Path("/{id}/reopen")
    public OrderView reopen(@PathParam("id") long id) {
        SalesOrder reopened = quotes.reopen(id);
        return new OrderView(reopened, salesOrders.price(reopened));
    }

    /** The packing slip: pallets when laid out, plain lines otherwise. */
    @GET
    @Path("/{id}/packing-slip")
    @Produces("application/pdf")
    public Response packingSlip(@PathParam("id") long id) {
        QuoteDocumentRenderer.Document document = quotes.packingSlip(id);
        return Response.ok(document.content())
                .header("Content-Disposition", "inline; filename=" + document.filename())
                .build();
    }

    @GET
    @Path("/{id}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("id") long id, @QueryParam("language") String language) {
        QuoteDocumentRenderer.Document document = quotes.document(id,
                language == null || language.isBlank() ? null : Language.of(language));
        return Response.ok(document.content())
                .header("Content-Disposition", "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }

    @GET
    @Path("/{id}/portal-link")
    public PortalLink portalLink(@PathParam("id") long id) {
        SalesOrder order = salesOrders.get(id);
        var url = quotes.activePortalUrl(order);
        if (url.isPresent()) {
            return new PortalLink(true, "BESCHIKBAAR", url.get());
        }
        boolean reopenedDraft = order.status() == be.enrosed.sales.domain.QuoteStatus.CONCEPT
                && order.portalToken() != null && !order.portalToken().isBlank()
                && order.sentAt() != null;
        return new PortalLink(false,
                reopenedDraft ? "CONCEPT_IN_BEWERKING" : "NIET_VERSTUURD", null);
    }

    /* -------------------------------------------------- wijzigingen ---- */

    @GET
    @Path("/{id}/revisions")
    public List<QuoteRevision> revisions(@PathParam("id") long id) {
        return quotes.revisionsFor(id);
    }

    @GET
    @Path("/revisions/pending")
    public List<QuoteRevision> pendingRevisions() {
        return quotes.pendingRevisions();
    }

    /** We adopt the customer's proposal. */
    @POST
    @Path("/revisions/{revisionId}/approve")
    public OrderView approveRevision(@PathParam("revisionId") long revisionId, RevisionDecision decision) {
        SalesOrder order = quotes.approveRevision(revisionId,
                decision == null ? null : decision.handledBy(),
                decision == null ? null : decision.message());
        return new OrderView(order, salesOrders.price(order));
    }

    /** We do not adopt it; the quote stays as sent. */
    @POST
    @Path("/revisions/{revisionId}/reject")
    public OrderView rejectRevision(@PathParam("revisionId") long revisionId, RevisionDecision decision) {
        SalesOrder order = quotes.rejectRevision(revisionId,
                decision == null ? null : decision.handledBy(),
                decision == null ? null : decision.message());
        return new OrderView(order, salesOrders.price(order));
    }
}
