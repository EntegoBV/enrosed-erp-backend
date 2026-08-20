package be.enrosed.sales.adapter.in.rest;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.adapter.in.rest.PhotoResponses;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The customer side of the quote.
 *
 * Reachable with only a portal token, no account: whoever has the link from
 * the mail may view the quote and respond to it.
 *
 * Mind what does <b>not</b> leave through here: cost price, margin and the
 * difference between strict and mixed pallets stay inside. The customer gets
 * their own view, not ours with a filter on top.
 */
@Path("/api/portal")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class PortalResource {

    private final QuoteService quotes;
    private final SalesOrderService salesOrders;
    private final CustomerService customers;
    private final ProductService products;
    private final CustomerQuoteMapper customerQuotes;

    public PortalResource(QuoteService quotes, SalesOrderService salesOrders,
                          CustomerService customers, ProductService products,
                          CustomerQuoteMapper customerQuotes) {
        this.quotes = quotes;
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.products = products;
        this.customerQuotes = customerQuotes;
    }

    /* ------------------------------------------------------------ DTOs */

    /** Product the customer can add; without cost price, obviously. */
    public record CatalogItem(Long productId, String sku, String description, String photoUrl,
                              int piecesPerCarton, BigDecimal unitPrice,
                              /* Available from stock, or do we need to order it first? */
                              boolean inventoryKnown, boolean inStock) {}

    public record AcceptRequest(String signedByName, String message) {}
    public record RejectRequest(String message) {}
    public record ProposalLine(Long productId, int quantity, String note) {}
    public record ProposeRequest(String proposedBy, String message, List<ProposalLine> lines) {}

    /**
     * What the customer can still add.
     *
     * The token is the key here: only whoever has the quote link sees this
     * list, and even then without cost price or margin.
     */
    @GET
    @Path("/{token}/products")
    public List<CatalogItem> catalog(@PathParam("token") String token,
                                     @QueryParam("language") String preferredLanguage) {
        SalesOrder order = quotes.byToken(token);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
        Language language = preferredLanguage != null && !preferredLanguage.isBlank()
                ? Language.of(preferredLanguage)
                : customer == null ? Language.NL : customer.language();
        return products.list().stream()
                .filter(Product::active)
                .map(product -> new CatalogItem(
                        product.id(),
                        product.sku(),
                        product.describeIn(language),
                        product.primaryPhoto() == null ? null
                                : "/api/portal/" + token + "/products/" + product.id() + "/photo",
                        product.carton() == null ? 1 : product.carton().piecesPerCarton(),
                        salesOrders.unitPriceFor(product, order),
                        product.inventoryKnown(),
                        product.stockQuantity() > 0))
                .toList();
    }

    /** Product photo for the portal; reachable with the token instead of a login. */
    @GET
    @Path("/{token}/products/{productId}/photo")
    @Produces(MediaType.WILDCARD)
    public Response photo(@PathParam("token") String token, @PathParam("productId") long productId) {
        quotes.byToken(token);
        Product product = products.get(productId);
        if (!product.active()) return Response.status(Response.Status.NOT_FOUND).build();
        Photo photo = product.primaryPhoto();
        if (photo == null) return Response.status(Response.Status.NOT_FOUND).build();
        return PhotoResponses.inline(products.photoData(photo.storageKey()), photo.contentType())
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    /* ---------------------------------------------------------- lezen */

    @GET
    @Path("/{token}")
    public CustomerQuoteView open(@PathParam("token") String token,
                                  @QueryParam("language") String language) {
        SalesOrder order = quotes.openByToken(token);
        return customerQuotes.portal(order, language);
    }

    @GET
    @Path("/{token}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("token") String token) {
        SalesOrder order = quotes.byToken(token);
        QuoteDocumentRenderer.Document document = quotes.document(order.id());
        return Response.ok(document.content())
                .header("Content-Disposition", "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }

    /* -------------------------------------------------------- reageren */

    /** The customer signs for approval; the typed name is the signature. */
    @POST
    @Path("/{token}/accept")
    public CustomerQuoteView accept(@PathParam("token") String token, AcceptRequest request) {
        return customerQuotes.portal(quotes.acceptByCustomer(token,
                request == null ? null : request.signedByName(),
                request == null ? null : request.message()), null);
    }

    @POST
    @Path("/{token}/reject")
    public CustomerQuoteView reject(@PathParam("token") String token, RejectRequest request) {
        return customerQuotes.portal(
                quotes.rejectByCustomer(token, request == null ? null : request.message()), null);
    }

    /**
     * The customer withdraws their proposal.
     *
     * The proposal stays in the history; the quote simply lies with them
     * again instead of with us.
     */
    @POST
    @Path("/{token}/withdraw")
    public CustomerQuoteView withdraw(@PathParam("token") String token) {
        return customerQuotes.portal(quotes.withdrawRevision(token), null);
    }

    /**
     * The customer proposes changes. This does not alter the quote - it is a
     * proposal that comes to us for approval.
     */
    @POST
    @Path("/{token}/propose")
    public CustomerQuoteView propose(@PathParam("token") String token, ProposeRequest request) {
        List<QuoteRevision.Line> lines = request == null || request.lines() == null
                ? List.of()
                : request.lines().stream()
                        .map(line -> new QuoteRevision.Line(null, line.productId(), line.quantity(), line.note()))
                        .toList();

        quotes.proposeRevision(token, lines,
                request == null ? null : request.proposedBy(),
                request == null ? null : request.message());

        return customerQuotes.portal(quotes.byToken(token), null);
    }
}
