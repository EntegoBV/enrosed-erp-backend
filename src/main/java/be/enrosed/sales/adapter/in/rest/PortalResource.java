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
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    public PortalResource(QuoteService quotes, SalesOrderService salesOrders,
                          CustomerService customers, ProductService products) {
        this.quotes = quotes;
        this.salesOrders = salesOrders;
        this.customers = customers;
        this.products = products;
    }

    /* ------------------------------------------------------------ DTOs */

    public record CustomerLine(
            Long productId, String sku, String description, String photoUrl,
            int quantity, int cartons, int pallets,
            /** Carton content, so the portal can round quantities to full cartons. */
            int piecesPerCarton,
            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal net,
            /* Delivery. The exact stock count stays inside; the customer only
               needs to know whether it is available and when. */
            boolean inStock, String deliveryDate, String deliveryWeek) {}

    public record CustomerTotals(
            int pieces, int cartons, int pallets,
            BigDecimal subtotal, BigDecimal orderDiscountPercent, BigDecimal orderDiscountAmount,
            BigDecimal extraDiscountPercent, String extraDiscountLabel, BigDecimal extraDiscountAmount,
            BigDecimal goodsTotal, BigDecimal freight, BigDecimal handling,
            BigDecimal total, BigDecimal vatRatePct, BigDecimal vatAmount, BigDecimal totalInclVat,
            String vatTreatment, String vatLegalMention) {}

    /**
     * What the customer sees. Deliberately its own record: internal notes,
     * cost price and margin are not in it, and will not be even when someone
     * later adds them to SalesOrder.
     */
    public record QuoteView(
            String number, String status, String orderDate, String validUntil, String incoterm,
            String notes, String companyName, String contactName, String countryCode,
            List<CustomerLine> lines, CustomerTotals totals,
            boolean canRespond, String signedByName, List<PendingProposal> proposals,
            /**
             * State of the delivery terms: VOLLEDIG, TE_BEPALEN or AANGEVULD.
             * With AANGEVULD the customer sees a banner that we filled in the
             * term they were missing.
             */
            String deliveryTerms,
            /** State of the freight: BEREKEND, TE_BEPALEN or AANGEVULD. */
            String freight,
            /** The customer's language code, so the portal opens in their language. */
            String language,
            /**
             * The translated texts for this portal.
             *
             * They come from the server rather than from the Angular bundle:
             * that way the quote, the PDF, the mail and this screen are
             * guaranteed to use the same words, and a new language only has
             * to be added in one place.
             */
            Map<String, String> text) {}

    public record PendingProposal(String status, String proposedAt, String message, String responseMessage) {}

    /** Product the customer can add; without cost price, obviously. */
    public record CatalogItem(Long productId, String sku, String description, String photoUrl,
                              int piecesPerCarton, BigDecimal unitPrice,
                              /* Available from stock, or do we need to order it first? */
                              boolean inStock) {}

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
    public QuoteView open(@PathParam("token") String token,
                          @QueryParam("language") String language) {
        SalesOrder order = quotes.openByToken(token);
        return view(order, language);
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
    public QuoteView accept(@PathParam("token") String token, AcceptRequest request) {
        return view(quotes.acceptByCustomer(token,
                request == null ? null : request.signedByName(),
                request == null ? null : request.message()));
    }

    @POST
    @Path("/{token}/reject")
    public QuoteView reject(@PathParam("token") String token, RejectRequest request) {
        return view(quotes.rejectByCustomer(token, request == null ? null : request.message()));
    }

    /**
     * The customer withdraws their proposal.
     *
     * The proposal stays in the history; the quote simply lies with them
     * again instead of with us.
     */
    @POST
    @Path("/{token}/withdraw")
    public QuoteView withdraw(@PathParam("token") String token) {
        return view(quotes.withdrawRevision(token));
    }

    /**
     * The customer proposes changes. This does not alter the quote - it is a
     * proposal that comes to us for approval.
     */
    @POST
    @Path("/{token}/propose")
    public QuoteView propose(@PathParam("token") String token, ProposeRequest request) {
        List<QuoteRevision.Line> lines = request == null || request.lines() == null
                ? List.of()
                : request.lines().stream()
                        .map(line -> new QuoteRevision.Line(null, line.productId(), line.quantity(), line.note()))
                        .toList();

        quotes.proposeRevision(token, lines,
                request == null ? null : request.proposedBy(),
                request == null ? null : request.message());

        return view(quotes.byToken(token));
    }

    /* --------------------------------------------------------- mapping */

    /**
     * What the customer gets to see about a proposal's progress.
     *
     * Clicking "take over" on our side fills the numbers into our editor, but
     * nothing has been decided towards the customer until the amended quote
     * actually leaves. Until that re-send, an approved proposal still reads as
     * "being handled" - announcing "processed" while we may still be editing
     * would be confirming something that does not exist yet.
     */
    private static String customerFacingStatus(SalesOrder order, QuoteRevision revision) {
        boolean takenOverButNotResent = revision.status() == RevisionStatus.GOEDGEKEURD
                && (order.sentAt() == null
                    || (revision.handledAt() != null && order.sentAt().isBefore(revision.handledAt())));
        return takenOverButNotResent
                ? RevisionStatus.IN_AFWACHTING.name()
                : revision.status().name();
    }

    /**
     * The order status as the customer should read it.
     *
     * While we are editing (CONCEPT after taking over a proposal or reopening),
     * the honest message is "your change is being handled", not "awaiting your
     * response" - there is nothing for the customer to respond to yet.
     */
    private static String customerFacingOrderStatus(SalesOrder order) {
        return order.status() == QuoteStatus.CONCEPT
                ? QuoteStatus.WIJZIGING_GEVRAAGD.name()
                : order.status().name();
    }

    /**
     * Carton content of a product.
     *
     * Deliberately looked up rather than derived from quantity divided by
     * cartons: that only holds while the quantity is a full carton, and it is
     * exactly the lines where it is not that need the number.
     */
    private int piecesPerCarton(Long productId) {
        if (productId == null) return 1;
        try {
            int per = products.get(productId).carton().piecesPerCarton();
            return Math.max(1, per);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private QuoteView view(SalesOrder order) {
        return view(order, null);
    }

    /**
     * @param preferred language the customer picked in the portal themselves,
     *                  or null for the language on record. Their pick changes
     *                  nothing on the customer file: the next quote simply
     *                  leaves in the agreed language again. Whoever sits in
     *                  France but prefers reading English should not have to
     *                  call us for that.
     */
    private QuoteView view(SalesOrder order, String preferred) {
        PricedOrder priced = salesOrders.price(order);
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());
        Language language = preferred != null && !preferred.isBlank()
                ? Language.of(preferred)
                : customer == null ? Language.NL : customer.language();

        List<CustomerLine> lines = priced.lines().stream()
                .map(line -> new CustomerLine(
                        line.productId(), line.sku(), line.customerDescription(), line.photoUrl(),
                        line.quantity(), line.cartons(), line.pallets(),
                        piecesPerCarton(line.productId()),
                        line.unitPrice(), line.discountPct(), line.net(),
                        line.inStock(), line.deliveryDate(), line.deliveryWeek()))
                .toList();

        PricedOrder.Totals totals = priced.totals();
        CustomerTotals customerTotals = new CustomerTotals(
                totals.pieces(), totals.cartons(), totals.palletsStrict(),
                totals.subtotal(), totals.orderDiscountPercent(), totals.orderDiscountAmount(),
                totals.extraDiscountPercent(), totals.extraDiscountLabel(), totals.extraDiscountAmount(),
                totals.goodsTotal(), totals.freight(), totals.handling(),
                totals.total(), totals.vatRatePct(), totals.vatAmount(), totals.totalInclVat(),
                totals.vatTreatment().labelIn(language),
                totals.vatTreatment().legalMentionIn(language));

        List<PendingProposal> proposals = quotes.revisionsFor(order.id()).stream()
                .map(revision -> new PendingProposal(
                        customerFacingStatus(order, revision),
                        revision.proposedAt() == null ? null : revision.proposedAt().toString(),
                        revision.message(), revision.responseMessage()))
                .toList();

        return new QuoteView(
                order.number(), customerFacingOrderStatus(order),
                order.orderDate() == null ? null : order.orderDate().toString(),
                order.validUntil() == null ? null : order.validUntil().toString(),
                order.incoterm(), order.notes(),
                customer == null ? null : customer.company(),
                customer == null ? null : customer.contact(),
                order.countryCode(),
                lines, customerTotals,
                order.status().isOpenForCustomer(), order.signedByName(), proposals,
                order.deliveryTerms().name(),
                order.freight().name(),
                language.code(),
                DocumentText.of(language));
    }
}
