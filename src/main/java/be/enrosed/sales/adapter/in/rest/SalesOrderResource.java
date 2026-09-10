package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
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
    @jakarta.inject.Inject be.enrosed.sales.application.IncomingPaymentService incoming;
    @jakarta.inject.Inject be.enrosed.sales.application.PartnerFinancingService partnerFinancing;
    @jakarta.inject.Inject be.enrosed.sales.application.PartnerAdvanceQuotes advanceQuotes;
    @jakarta.inject.Inject be.enrosed.sales.application.PartnerAdvanceContents advanceContents;

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
    /** {@code awaitingResend}: an adopted customer proposal that has not gone back out. */
    public record OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend,
                            /** The invoice made from this quote, by number; null while there is none. */
                            String invoicedAs,
                            /** That invoice's id, to open it. */
                            Long invoicedAsId,
                            /** That invoice's status: a draft is not yet an invoice sent. */
                            be.enrosed.sales.domain.QuoteStatus invoiceStatus,
                            /** For an invoice: the number of the quote it was made from. */
                            String sourceQuoteNumber, be.enrosed.sales.domain.SalesPaymentSummary paymentSummary,
                            be.enrosed.sales.domain.SalesAccounting accounting,
                            be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                            be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement,
                            be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents) {
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                         be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, null);
        }
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, null);
        }
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber, paymentSummary, accounting, null);
        }
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber, null, null);
        }
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend) {
            this(order, priced, awaitingResend, null, null, null, null);
        }
    }

    /** The links between quotes and the invoices made from them, both ways. */
    private record Links(java.util.Map<Long, SalesOrder> invoiceByQuote, java.util.Map<Long, String> quoteNumberById) {
        static Links of(List<SalesOrder> all) {
            java.util.Map<Long, SalesOrder> invoiceByQuote = new java.util.HashMap<>();
            java.util.Map<Long, String> quoteNumberById = new java.util.HashMap<>();
            for (SalesOrder order : all) {
                if (order.id() == null) continue;
                if (order.isInvoice()) {
                    if (order.sourceQuoteId() != null
                            && order.status() != be.enrosed.sales.domain.QuoteStatus.GEANNULEERD) {
                        invoiceByQuote.putIfAbsent(order.sourceQuoteId(), order);
                    }
                } else {
                    quoteNumberById.put(order.id(), order.number());
                }
            }
            return new Links(invoiceByQuote, quoteNumberById);
        }

        OrderView view(SalesOrder order, PricedOrder priced, boolean awaitingResend) {
            SalesOrder invoice = order.isInvoice() || order.id() == null ? null : invoiceByQuote.get(order.id());
            String sourceQuote = order.isInvoice() && order.sourceQuoteId() != null ? quoteNumberById.get(order.sourceQuoteId()) : null;
            return new OrderView(order, priced, awaitingResend,
                    invoice == null ? null : invoice.number(), invoice == null ? null : invoice.id(),
                    invoice == null ? null : invoice.status(), sourceQuote);
        }
    }
    public record PortalLink(boolean available, String status, String url) {}

    @GET
    public List<OrderView> list() {
        List<SalesOrder> all = salesOrders.list();
        java.util.Set<Long> awaiting = quotes.awaitsResendIds(all);
        Links links = Links.of(all);
        return all.stream()
                .map(order -> enrich(links.view(order, salesOrders.price(order), awaiting.contains(order.id()))))
                .toList();
    }

    private OrderView view(SalesOrder order) {
        boolean linked = order.isInvoice() ? order.sourceQuoteId() != null : order.id() != null;
        Links links = linked ? Links.of(salesOrders.list()) : Links.of(List.of());
        return enrich(links.view(order, salesOrders.price(order), quotes.awaitsResend(order)));
    }


    private OrderView enrich(OrderView view) {
        if (incoming == null || partnerFinancing == null) return view;
        var agreement = advanceQuotes == null || view.order().id() == null ? null : advanceQuotes.find(view.order().id());
        // An arrangement is followed by several term invoices; one invoice never means the entire quote was billed.
        return new OrderView(view.order(), view.priced(), view.awaitingResend(), agreement == null ? view.invoicedAs() : null,
                agreement == null ? view.invoicedAsId() : null, agreement == null ? view.invoiceStatus() : null,
                view.sourceQuoteNumber(), incoming.summary(view.order(), view.priced()),
                partnerFinancing.accounting(view.order(), view.priced()), partnerFinancing.settlement(view.order()),
                agreement, advanceContents == null ? null : advanceContents.find(view.order()).orElse(null));
    }

    @GET @Path("/{id}/payments")
    public List<be.enrosed.sales.domain.SalesPayment> payments(@PathParam("id") long id) { return incoming.forOrder(id); }

    @POST @Path("/{id}/payments")
    public OrderView addPayment(@PathParam("id") long id, be.enrosed.sales.application.IncomingPaymentService.Request request) {
        return view(incoming.add(id, request));
    }

    @PUT @Path("/{id}/payments/{paymentId}")
    public OrderView updatePayment(@PathParam("id") long id, @PathParam("paymentId") long paymentId,
            be.enrosed.sales.application.IncomingPaymentService.Request request) { return view(incoming.update(id, paymentId, request)); }

    @DELETE @Path("/{id}/payments/{paymentId}")
    public Response deletePayment(@PathParam("id") long id, @PathParam("paymentId") long paymentId) {
        incoming.delete(id, paymentId); return Response.noContent().build();
    }

    @GET
    @Path("/{id}")
    public OrderView get(@PathParam("id") long id) {
        SalesOrder order = salesOrders.get(id);
        return view(order);
    }

    @POST
    public Response create(CreateRequest request) {
        SalesOrder created = salesOrders.create(request.customerId(), request.countryCode(),
                request.incoterm(),
                request.docType() == null ? DocumentType.OFFERTE : request.docType());
        return Response.status(Response.Status.CREATED)
                .entity(view(created))
                .build();
    }

    /** Creates an unsent draft invoice and archives its quote; retries return the same active invoice. */
    @POST
    @Path("/{id}/invoice")
    public OrderView createInvoice(@PathParam("id") long id) {
        SalesOrder invoice = salesOrders.createInvoiceFrom(id);
        return view(invoice);
    }

    /** A container becomes a quote in one go: lines, costs and the partner deal, or nothing at all. */
    @POST
    @Path("/from-purchase-order")
    public OrderView createFromPurchaseOrder(SalesOrderService.FromPurchaseOrderRequest request) {
        return view(salesOrders.createFromPurchaseOrder(request));
    }

    /** The auction settlement of a partner container: our financed cost and profit share, per product. */
    @POST
    @Path("/auction-settlement")
    public OrderView createAuctionSettlement(SalesOrderService.AuctionSettlementRequest request) {
        return view(salesOrders.createAuctionSettlement(request));
    }

    /** Ties the document to a partner container, or cuts the tie with a null container. */
    @PUT
    @Path("/{id}/partner-deal")
    public OrderView setPartnerDeal(@PathParam("id") long id, SalesOrderService.PartnerDealRequest request) {
        return view(salesOrders.setPartnerDeal(id, request));
    }

    @POST
    @Path("/{id}/issue")
    public OrderView issueInvoice(@PathParam("id") long id) { return view(salesOrders.issueInvoice(id)); }

    @POST
    @Path("/{id}/mark-sent")
    public OrderView markInvoiceSent(@PathParam("id") long id) {
        SalesOrder sent = salesOrders.markInvoiceSent(id);
        return view(sent);
    }

    /** The goods left the door: books the stock out. Explicit, never automatic. */
    @POST
    @Path("/{id}/ship-goods")
    public OrderView shipGoods(@PathParam("id") long id) {
        return view(salesOrders.shipGoods(id));
    }

    @POST
    @Path("/{id}/mark-paid")
    public OrderView markInvoicePaid(@PathParam("id") long id) {
        SalesOrder paid = salesOrders.markInvoicePaid(id);
        return view(paid);
    }

    @PUT
    @Path("/{id}")
    public OrderView update(@PathParam("id") long id, SalesOrder order) {
        SalesOrder saved = salesOrders.update(id, order);
        return view(saved);
    }

    /**
     * Prices an order as it stands on screen, without saving: the editor keeps
     * a draft and writes only on Opslaan, but the figures follow every edit.
     */
    @POST
    @Path("/{id}/preview")
    public OrderView preview(@PathParam("id") long id, SalesOrder order) {
        salesOrders.get(id);
        return view(order);
    }

    /** Fills in delivery weeks without reopening every field of a sent quote. */
    @PUT
    @Path("/{id}/delivery-terms")
    public OrderView updateDeliveryTerms(@PathParam("id") long id, DeliveryTermsRequest request) {
        SalesOrder saved = salesOrders.updateDeliveryWeeks(id,
                request == null ? null : request.lines());
        return view(saved);
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
        return view(saved);
    }

    @POST
    @Path("/{id}/duplicate")
    public OrderView duplicate(@PathParam("id") long id) {
        SalesOrder copy = salesOrders.duplicate(id);
        return view(copy);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        salesOrders.delete(id);
        return Response.noContent().build();
    }

    /** Off the working list, into the archive tab; nothing else changes. */
    @POST
    @Path("/{id}/archive")
    public OrderView archive(@PathParam("id") long id) {
        return view(salesOrders.archive(id));
    }

    @POST
    @Path("/{id}/unarchive")
    public OrderView unarchive(@PathParam("id") long id) {
        return view(salesOrders.unarchive(id));
    }

    /* ------------------------------------------------------------ quote */

    /** Builds the PDF, mails it to the customer and marks the quote sent. */
    @POST
    @Path("/{id}/send")
    public OrderView send(@PathParam("id") long id, SendRequest request) {
        SalesOrder sent = quotes.send(id, request == null ? null : request.message());
        return view(sent);
    }

    /** The history of a quote, oldest step first. */
    @GET
    @Path("/{id}/history")
    public List<QuoteEvent> history(@PathParam("id") long id) {
        return quotes.history(id);
    }

    /** Withdraws an open quote; with notifyCustomer the customer gets a mail with the portal link. */
    @POST
    @Path("/{id}/cancel")
    public OrderView cancel(@PathParam("id") long id, CancelRequest request) {
        SalesOrder cancelled = quotes.cancel(id,
                request == null ? null : request.message(),
                request != null && request.notifyCustomer());
        return view(cancelled);
    }

    public record CancelRequest(String message, boolean notifyCustomer) {}

    /** Puts a rejected or expired quote back on concept for adjusting. */
    @POST
    @Path("/{id}/reopen")
    public OrderView reopen(@PathParam("id") long id) {
        SalesOrder reopened = quotes.reopen(id);
        return view(reopened);
    }

    /** The packing slip: pallets when laid out, plain lines otherwise. */
    @GET
    @Path("/{id}/packing-slip")
    @Produces("application/pdf")
    public Response packingSlip(@PathParam("id") long id,
                                @QueryParam("showOuterCarton") @DefaultValue("false")
                                boolean showOuterCarton,
                                @QueryParam("showBarcode") @DefaultValue("false")
                                boolean showBarcode) {
        QuoteDocumentRenderer.Document document = quotes.packingSlip(id,
                SalesPdfOptions.forPackingSlip(showOuterCarton, showBarcode));
        return Response.ok(document.content())
                .header("Content-Disposition", "inline; filename=" + document.filename())
                .build();
    }

    /** Java-call compatibility for callers predating packing-slip options. */
    public Response packingSlip(long id) {
        return packingSlip(id, false, false);
    }

    @GET
    @Path("/{id}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("id") long id,
                        @QueryParam("language") String language,
                        @QueryParam("includePhotos") @DefaultValue("true") boolean includePhotos,
                        @QueryParam("includeProductDetails") @DefaultValue("true")
                        boolean includeProductDetails,
                        @QueryParam("includeLogistics") @DefaultValue("true")
                        boolean includeLogistics,
                        @QueryParam("includeTerms") @DefaultValue("true") boolean includeTerms,
                        @QueryParam("showOuterCarton") @DefaultValue("false")
                        boolean showOuterCarton,
                        @QueryParam("showBarcode") @DefaultValue("false")
                        boolean showBarcode,
                        @QueryParam("includePaymentDetails") @DefaultValue("true")
                        boolean includePaymentDetails) {
        QuoteDocumentRenderer.Document document = quotes.document(id,
                language == null || language.isBlank() ? null : Language.of(language),
                new SalesPdfOptions(includePhotos, includeProductDetails,
                        includeLogistics, includeTerms, showOuterCarton, showBarcode,
                        includePaymentDetails));
        return Response.ok(document.content())
                .header("Content-Disposition", "attachment; filename=\"" + document.filename() + "\"")
                .build();
    }

    /** Java-call compatibility for callers predating optional payment details. */
    public Response pdf(long id, String language, boolean includePhotos,
                        boolean includeProductDetails, boolean includeLogistics,
                        boolean includeTerms, boolean showOuterCarton, boolean showBarcode) {
        return pdf(id, language, includePhotos, includeProductDetails, includeLogistics,
                includeTerms, showOuterCarton, showBarcode, true);
    }

    /** Java-call compatibility for callers predating printable master-data options. */
    public Response pdf(long id, String language, boolean includePhotos,
                        boolean includeProductDetails, boolean includeLogistics,
                        boolean includeTerms) {
        return pdf(id, language, includePhotos, includeProductDetails, includeLogistics,
                includeTerms, false, false);
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
        return view(order);
    }

    /** We do not adopt it; the quote stays as sent. */
    @POST
    @Path("/revisions/{revisionId}/reject")
    public OrderView rejectRevision(@PathParam("revisionId") long revisionId, RevisionDecision decision) {
        SalesOrder order = quotes.rejectRevision(revisionId,
                decision == null ? null : decision.handledBy(),
                decision == null ? null : decision.message());
        return view(order);
    }
}
