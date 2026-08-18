package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.shared.Language;
import be.enrosed.sales.domain.QuoteRevision;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

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

    public record CreateRequest(long customerId, String countryCode, String incoterm) {}
    public record SendRequest(String message) {}
    public record RevisionDecision(String handledBy, String message) {}
    public record OrderView(SalesOrder order, PricedOrder priced) {}

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
        SalesOrder created = salesOrders.create(request.customerId(), request.countryCode(), request.incoterm());
        return Response.status(Response.Status.CREATED)
                .entity(new OrderView(created, salesOrders.price(created)))
                .build();
    }

    @PUT
    @Path("/{id}")
    public OrderView update(@PathParam("id") long id, SalesOrder order) {
        SalesOrder saved = salesOrders.update(id, order);
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
    public Map<String, String> portalLink(@PathParam("id") long id) {
        SalesOrder order = salesOrders.get(id);
        return order.portalToken() == null
                ? Map.of("status", "nog niet verzonden")
                : Map.of("token", order.portalToken());
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
