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
    @jakarta.inject.Inject be.enrosed.sales.application.PartnerInvoiceDeclarations invoiceDeclarations;
    @jakarta.inject.Inject be.enrosed.sales.application.SalesSplits splits;
    @jakarta.inject.Inject be.enrosed.sales.application.SalesCustomerMessages customerMessages;
    @jakarta.inject.Inject be.enrosed.sales.application.SalesAdvanceBillingService advanceBilling;
    @jakarta.inject.Inject be.enrosed.sales.application.WebOrders webOrders;
    @jakarta.inject.Inject be.enrosed.sales.application.WebOrderDeliveries deliveries;
    @jakarta.inject.Inject be.enrosed.sales.application.WebOrderMails webOrderMails;
    @jakarta.inject.Inject be.enrosed.sales.application.CustomerService customers;

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
                            be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents,
                            be.enrosed.sales.application.SalesSplits.Fulfillment fulfillment,
                            boolean customerRequestMessageReadonly, String customerRequestMessage,
                            /** Credit notes: the invoice they correct. */
                            Long creditedInvoiceId, String creditedInvoiceNumber,
                            be.enrosed.sales.domain.QuoteStatus creditedInvoiceStatus,
                            /** Invoices: their live credit notes, concepts included, and what the issued ones credit incl. VAT. */
                            List<CreditNoteLink> creditNotes, BigDecimal creditedEur,
                            /**
                             * The container this document comes from ({@code order.linkedPurchaseOrderId()}), partner or
                             * regular container sale: its name as sales shows it ("Herkenbare naam", else the number) and
                             * its purchase order number. Null when the document has no container or it is gone.
                             */
                            String partnerContainerName, String partnerContainerNumber,
                            /** Advance invoices and slotfactuur of a regular quote: this document's own role, or null. */
                            be.enrosed.sales.application.SalesAdvanceBillingService.Billing advanceBilling,
                            /** On a regular quote: its live advance invoices (empty when none); null on other documents. */
                            List<be.enrosed.sales.application.SalesAdvanceBillingService.AdvanceInvoice> advanceInvoices,
                            /** On a slotfactuur: the advance invoices it deducted, with how they were paid; null otherwise. */
                            List<be.enrosed.sales.application.SalesAdvanceBillingService.AdvanceDeduction> advanceDeductions,
                            /** Only on the document that IS the website order of a logged-in customer; null otherwise. */
                            WebOrderView webOrder,
                            /** The delivery the customer typed for a website order, also on the documents derived from it. */
                            DeliveryView delivery) {
        /** The view as it stood before website orders of logged-in customers were added. */
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                         be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement,
                         be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents,
                         be.enrosed.sales.application.SalesSplits.Fulfillment fulfillment,
                         boolean customerRequestMessageReadonly, String customerRequestMessage,
                         Long creditedInvoiceId, String creditedInvoiceNumber,
                         be.enrosed.sales.domain.QuoteStatus creditedInvoiceStatus,
                         List<CreditNoteLink> creditNotes, BigDecimal creditedEur,
                         String partnerContainerName, String partnerContainerNumber,
                         be.enrosed.sales.application.SalesAdvanceBillingService.Billing advanceBilling,
                         List<be.enrosed.sales.application.SalesAdvanceBillingService.AdvanceInvoice> advanceInvoices,
                         List<be.enrosed.sales.application.SalesAdvanceBillingService.AdvanceDeduction> advanceDeductions) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur,
                    partnerContainerName, partnerContainerNumber, advanceBilling, advanceInvoices, advanceDeductions, null, null);
        }
        /** The same view with the website order state and its delivery filled in. */
        OrderView withWebOrder(WebOrderView webOrder, DeliveryView delivery) {
            return new OrderView(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur,
                    partnerContainerName, partnerContainerNumber, advanceBilling, advanceInvoices, advanceDeductions,
                    webOrder, delivery);
        }
        /** The view as it stood before the advance billing of regular quotes was added. */
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                         be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement,
                         be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents,
                         be.enrosed.sales.application.SalesSplits.Fulfillment fulfillment,
                         boolean customerRequestMessageReadonly, String customerRequestMessage,
                         Long creditedInvoiceId, String creditedInvoiceNumber,
                         be.enrosed.sales.domain.QuoteStatus creditedInvoiceStatus,
                         List<CreditNoteLink> creditNotes, BigDecimal creditedEur,
                         String partnerContainerName, String partnerContainerNumber) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur,
                    partnerContainerName, partnerContainerNumber, null, null, null);
        }
        /** The view as it stood before the container name was added. */
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                         be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement,
                         be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents,
                         be.enrosed.sales.application.SalesSplits.Fulfillment fulfillment,
                         boolean customerRequestMessageReadonly, String customerRequestMessage,
                         Long creditedInvoiceId, String creditedInvoiceNumber,
                         be.enrosed.sales.domain.QuoteStatus creditedInvoiceStatus,
                         List<CreditNoteLink> creditNotes, BigDecimal creditedEur) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur, null, null);
        }
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                         be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement,
                         be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents,
                         be.enrosed.sales.application.SalesSplits.Fulfillment fulfillment,
                         boolean customerRequestMessageReadonly, String customerRequestMessage) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage, null, null, null, null, null);
        }
        public OrderView(SalesOrder order, PricedOrder priced, boolean awaitingResend, String invoicedAs, Long invoicedAsId,
                         be.enrosed.sales.domain.QuoteStatus invoiceStatus, String sourceQuoteNumber,
                         be.enrosed.sales.domain.SalesPaymentSummary paymentSummary, be.enrosed.sales.domain.SalesAccounting accounting,
                         be.enrosed.sales.application.PartnerSettlements.Snapshot settlement,
                         be.enrosed.sales.application.PartnerAdvanceQuotes.Snapshot advanceAgreement,
                         be.enrosed.sales.application.PartnerAdvanceContents.Snapshot advanceContents) {
            this(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, null, false, null);
        }
        /** The same view with the credit-note links filled in. */
        OrderView withCreditLinks(Long creditedInvoiceId, String creditedInvoiceNumber,
                                  be.enrosed.sales.domain.QuoteStatus creditedInvoiceStatus,
                                  List<CreditNoteLink> creditNotes, BigDecimal creditedEur) {
            return new OrderView(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur,
                    partnerContainerName, partnerContainerNumber, advanceBilling, advanceInvoices, advanceDeductions,
                    webOrder, delivery);
        }
        /** The same view naming the container the document comes from; null leaves it unnamed. */
        OrderView withContainer(be.enrosed.sourcing.domain.PurchaseOrderName container) {
            return new OrderView(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur,
                    container == null ? null : container.displayName(), container == null ? null : container.number(),
                    advanceBilling, advanceInvoices, advanceDeductions, webOrder, delivery);
        }
        /** The same view with the advance billing of regular quotes filled in. */
        OrderView withAdvanceBilling(be.enrosed.sales.application.SalesAdvanceBillingService.Views views) {
            if (views == null) return this;
            return new OrderView(order, priced, awaitingResend, invoicedAs, invoicedAsId, invoiceStatus, sourceQuoteNumber,
                    paymentSummary, accounting, settlement, advanceAgreement, advanceContents, fulfillment,
                    customerRequestMessageReadonly, customerRequestMessage,
                    creditedInvoiceId, creditedInvoiceNumber, creditedInvoiceStatus, creditNotes, creditedEur,
                    partnerContainerName, partnerContainerNumber,
                    views.billing(order), views.advanceInvoices(order), views.deductions(order), webOrder, delivery);
        }
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

    /**
     * Where a website order of a logged-in customer stands: what the customer
     * did, who took it into processing, how its figures compare with what was
     * ordered, sent or approved, and the customer mails. The ordered totals
     * and the differences need the stored order and are filled on one
     * document only, never on the list.
     */
    public record WebOrderView(int revision, String accountEmail, java.time.Instant placedAt,
                               /** The customer may still change or cancel: nobody at Enrosed touched the order. */
                               boolean customerEditable,
                               java.time.Instant customerChangedAt, String customerChangeSummary,
                               java.time.Instant customerCancelledAt,
                               java.time.Instant processingStartedAt, String processingStartedBy, String processingTrigger,
                               be.enrosed.sales.application.WebOrders.TermsState termsState,
                               BigDecimal orderedTotalExclVat, BigDecimal orderedTotalInclVat, List<String> differences,
                               java.time.Instant receivedMailSentAt, java.time.Instant processingMailSentAt, String mailError,
                               /** A customer mail is due and has not left for more than a minute. */
                               boolean mailDue) {}

    /** The delivery of a website order as the customer typed it; staff read it, they do not edit it. */
    public record DeliveryView(String fulfillment, String address, String postalCode, String city, String countryCode,
                               String pickupLabel, String pickupAddress, String contactName, String phone,
                               boolean differsFromCustomerRecord) {}

    /** One credit note as seen from its invoice. */
    public record CreditNoteLink(long id, String number, be.enrosed.sales.domain.QuoteStatus status,
                                 BigDecimal totalInclVatEur, be.enrosed.sales.domain.CreditReason creditReason) {}

    /** The links between quotes and the invoices made from them, both ways, and between invoices and their credit notes. */
    private record Links(java.util.Map<Long, SalesOrder> invoiceByQuote, java.util.Map<Long, String> quoteNumberById,
                         java.util.Map<Long, SalesOrder> invoiceById, java.util.Map<Long, List<SalesOrder>> creditNotesByInvoice) {
        static Links of(List<SalesOrder> all) {
            java.util.Map<Long, SalesOrder> invoiceByQuote = new java.util.HashMap<>();
            java.util.Map<Long, String> quoteNumberById = new java.util.HashMap<>();
            java.util.Map<Long, SalesOrder> invoiceById = new java.util.HashMap<>();
            java.util.Map<Long, List<SalesOrder>> creditNotesByInvoice = new java.util.HashMap<>();
            for (SalesOrder order : all) {
                if (order.id() == null) continue;
                if (order.isInvoice()) {
                    invoiceById.put(order.id(), order);
                    if (order.sourceQuoteId() != null
                            && order.status() != be.enrosed.sales.domain.QuoteStatus.GEANNULEERD) {
                        invoiceByQuote.putIfAbsent(order.sourceQuoteId(), order);
                    }
                } else if (order.isCreditNote()) {
                    if (order.creditedInvoiceId() != null && be.enrosed.sales.application.PartnerFinancingService.live(order))
                        creditNotesByInvoice.computeIfAbsent(order.creditedInvoiceId(), key -> new java.util.ArrayList<>()).add(order);
                } else {
                    quoteNumberById.put(order.id(), order.number());
                }
            }
            creditNotesByInvoice.values().forEach(notes -> notes.sort(java.util.Comparator.comparing(SalesOrder::id)));
            return new Links(invoiceByQuote, quoteNumberById, invoiceById, creditNotesByInvoice);
        }

        OrderView view(SalesOrder order, PricedOrder priced, boolean awaitingResend) {
            SalesOrder invoice = order.isClaimDocument() || order.id() == null ? null : invoiceByQuote.get(order.id());
            String sourceQuote = order.isInvoice() && order.sourceQuoteId() != null ? quoteNumberById.get(order.sourceQuoteId()) : null;
            return new OrderView(order, priced, awaitingResend,
                    invoice == null ? null : invoice.number(), invoice == null ? null : invoice.id(),
                    invoice == null ? null : invoice.status(), sourceQuote);
        }

        /** Both directions of the credit link; pricing each credit note once, only where one exists. */
        OrderView withCreditLinks(OrderView view, java.util.function.Function<SalesOrder, PricedOrder> pricing) {
            SalesOrder order = view.order();
            if (order.isCreditNote()) {
                SalesOrder credited = order.creditedInvoiceId() == null ? null : invoiceById.get(order.creditedInvoiceId());
                return view.withCreditLinks(order.creditedInvoiceId(), credited == null ? null : credited.number(),
                        credited == null ? null : credited.status(), null, null);
            }
            if (!order.isInvoice() || order.id() == null) return view;
            List<SalesOrder> notes = creditNotesByInvoice.getOrDefault(order.id(), List.of());
            if (notes.isEmpty()) return view.withCreditLinks(null, null, null, List.of(), be.enrosed.shared.Money.money(BigDecimal.ZERO));
            List<CreditNoteLink> links = new java.util.ArrayList<>();
            BigDecimal credited = BigDecimal.ZERO;
            for (SalesOrder note : notes) {
                BigDecimal total = be.enrosed.shared.Money.money(pricing.apply(note).totals().totalInclVat());
                links.add(new CreditNoteLink(note.id(), note.number(), note.status(), total, note.creditReason()));
                if (be.enrosed.sales.application.PartnerFinancingService.issued(note)) credited = credited.add(total);
            }
            return view.withCreditLinks(null, null, null, List.copyOf(links), be.enrosed.shared.Money.money(credited));
        }
    }
    public record PortalLink(boolean available, String status, String url) {}

    @GET
    public List<OrderView> list() {
        List<SalesOrder> all = salesOrders.list();
        java.util.Set<Long> awaiting = quotes.awaitsResendIds(all);
        Links links = Links.of(all);
        /* One read names every container on the list; no purchase order is loaded with its lines. */
        var containers = salesOrders.containerNames(all.stream().map(SalesOrder::linkedPurchaseOrderId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        var advances = advanceBilling == null ? null : advanceBilling.views(all);
        /* Two reads cover every website order and delivery address on the list; no document looks its own up. */
        java.util.Map<Long, be.enrosed.sales.application.WebOrders.Row> webOrderRows =
                webOrders == null ? java.util.Map.of() : webOrders.indexStates();
        if (deliveries != null) deliveries.preloadForRequest();
        java.util.Set<Long> invoicedQuoteIds = invoicedQuoteIds(all);
        java.util.Map<Long, be.enrosed.sales.domain.Customer> customerRecords = new java.util.HashMap<>();
        return all.stream()
                .map(order -> webOrderBlocks(links.withCreditLinks(enrich(links.view(order, salesOrders.price(order), awaiting.contains(order.id()))), salesOrders::price)
                        .withContainer(order.linkedPurchaseOrderId() == null ? null : containers.get(order.linkedPurchaseOrderId()))
                        .withAdvanceBilling(advances),
                        order.id() == null ? null : webOrderRows.get(order.id()), false, invoicedQuoteIds, customerRecords))
                .toList();
    }

    private OrderView view(SalesOrder order) {
        /* Every persisted document may be linked: a quote to its invoice, an invoice to its credit notes. */
        List<SalesOrder> all = order.id() == null ? List.of() : salesOrders.list();
        Links links = Links.of(all);
        Long containerId = order.linkedPurchaseOrderId();
        var container = containerId == null ? null : salesOrders.containerNames(List.of(containerId)).get(containerId);
        OrderView view = links.withCreditLinks(enrich(links.view(order, salesOrders.price(order), quotes.awaitsResend(order))), salesOrders::price)
                .withContainer(container)
                .withAdvanceBilling(advanceBilling == null || order.id() == null ? null : advanceBilling.views(all));
        return webOrderBlocks(view, webOrders == null || order.id() == null ? null : webOrders.find(order.id()).orElse(null),
                true, invoicedQuoteIds(all), new java.util.HashMap<>());
    }

    /** Every quote an invoice was made from: such a website order is closed to its customer. */
    private static java.util.Set<Long> invoicedQuoteIds(List<SalesOrder> all) {
        return all.stream().filter(SalesOrder::isClaimDocument).map(SalesOrder::sourceQuoteId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Adds the website order state and the delivery of this document. The
     * state is computed with the same functions the server guards use, from
     * the pricing the view already holds; only one document at a time also
     * reads the stored order for its totals and differences.
     */
    private OrderView webOrderBlocks(OrderView view, be.enrosed.sales.application.WebOrders.Row row, boolean detail,
                                     java.util.Set<Long> invoicedQuoteIds,
                                     java.util.Map<Long, be.enrosed.sales.domain.Customer> customerRecords) {
        SalesOrder order = view.order();
        if (order.id() == null) return view;
        WebOrderView webOrder = null;
        if (row != null && !order.isClaimDocument()) {
            var state = view.priced() == null ? null : be.enrosed.sales.application.WebOrders.termsState(order, row,
                    be.enrosed.sales.application.WebOrderTerms.of(view.priced()));
            BigDecimal orderedExcl = null, orderedIncl = null;
            List<String> differences = List.of();
            if (detail) {
                var snapshot = be.enrosed.sales.application.WebOrderSnapshot.fromJson(row.orderSnapshot());
                if (snapshot != null && snapshot.totals() != null) {
                    orderedExcl = snapshot.totals().totalExclVat();
                    orderedIncl = snapshot.totals().totalInclVat();
                }
                if (state == be.enrosed.sales.application.WebOrders.TermsState.ORDER_DIFFERENT)
                    differences = be.enrosed.sales.application.WebOrderTerms.differences(snapshot, view.priced());
            }
            var due = be.enrosed.sales.application.WebOrderMails.due(order, row, java.time.Instant.now());
            webOrder = new WebOrderView(row.revision(), row.accountEmail(), row.placedAt(),
                    be.enrosed.sales.application.WebOrders.customerMayChange(order, row, invoicedQuoteIds.contains(order.id())),
                    row.customerChangedAt(), row.customerChangeSummary(), row.customerCancelledAt(),
                    row.processingStartedAt(), row.processingStartedBy(), row.processingTrigger(), state,
                    orderedExcl, orderedIncl, differences,
                    shownSentAt(order.id(), be.enrosed.sales.application.WebOrderMails.Kind.RECEIVED, row.receivedMailSentAt()),
                    shownSentAt(order.id(), be.enrosed.sales.application.WebOrderMails.Kind.PROCESSING, row.processingMailSentAt()),
                    row.mailError(), due != null && due.overdue());
        }
        var typed = deliveries == null ? null : deliveries.forDocument(order).orElse(null);
        DeliveryView delivery = null;
        if (typed != null) {
            boolean differs = false;
            if (be.enrosed.sales.application.WebOrderDeliveries.DELIVERY.equals(typed.fulfillment()) && order.customerId() != null && customers != null) {
                be.enrosed.sales.domain.Customer record = customerRecords.get(order.customerId());
                if (record == null) {
                    /* The list reads the customer records once, for the first document that has a delivery to compare. */
                    if (detail) customerRecords.put(order.customerId(), customers.get(order.customerId()));
                    else for (var customer : customers.list()) customerRecords.put(customer.id(), customer);
                    record = customerRecords.get(order.customerId());
                }
                differs = record != null && (!sameText(typed.address(), record.address())
                        || !sameText(typed.postalCode(), record.postalCode()) || !sameText(typed.city(), record.city()));
            }
            delivery = new DeliveryView(typed.fulfillment(), typed.address(), typed.postalCode(), typed.city(),
                    order.countryCode(), typed.pickupLabel(), typed.pickupAddress(), typed.contactName(),
                    typed.contactPhone(), differs);
        }
        return webOrder == null && delivery == null ? view : view.withWebOrder(webOrder, delivery);
    }

    /** A moment that is only the claim of a mail still on its way to the provider is not shown as sent. */
    private java.time.Instant shownSentAt(long orderId, be.enrosed.sales.application.WebOrderMails.Kind kind, java.time.Instant stored) {
        return webOrderMails == null ? stored : webOrderMails.shownSentAt(orderId, kind, stored);
    }

    private static boolean sameText(String left, String right) {
        return (left == null ? "" : left.strip()).equalsIgnoreCase(right == null ? "" : right.strip());
    }

    /**
     * "In verwerking nemen": from here the customer can no longer change or
     * cancel the website order; the customer is mailed after the commit.
     * There is no body, so a call without a content type is as good as one with.
     */
    @POST
    @Path("/{id}/take-into-processing")
    @Consumes(MediaType.WILDCARD)
    public OrderView takeIntoProcessing(@PathParam("id") long id) {
        return view(salesOrders.takeIntoProcessing(id));
    }

    /**
     * Sends the customer mail of a website order that is due and did not
     * leave; with repeat the last one that is marked sent goes out once more.
     */
    @POST
    @Path("/{id}/web-order/mails")
    @Consumes(MediaType.WILDCARD)
    public OrderView sendWebOrderMail(@PathParam("id") long id,
                                      @QueryParam("repeat") @DefaultValue("false") boolean repeat) {
        salesOrders.get(id);
        webOrderMails.resend(id, repeat);
        return view(salesOrders.get(id));
    }

    /* ------------------------------------------------------------ credit notes */

    /** What a credit note on this invoice could contain; read-only. */
    @GET @Path("/{id}/credit-note-proposal")
    public SalesOrderService.CreditNoteProposal creditNoteProposal(@PathParam("id") long id) {
        return salesOrders.proposeCreditNote(id);
    }

    /** A concept credit note on an issued invoice. */
    @POST @Path("/{id}/credit-note")
    public Response createCreditNote(@PathParam("id") long id, SalesOrderService.CreditNoteRequest request) {
        return Response.status(Response.Status.CREATED).entity(view(salesOrders.createCreditNote(id, request))).build();
    }

    public record ApplyCreditRequest(BigDecimal amountEur) {}

    /** Offsets the credit note against an open invoice; returns the credit note, the client refetches the invoice. */
    @POST @Path("/{creditId}/apply-to/{invoiceId}")
    public OrderView applyCredit(@PathParam("creditId") long creditId, @PathParam("invoiceId") long invoiceId, ApplyCreditRequest request) {
        return view(incoming.applyCredit(creditId, invoiceId, request == null ? null : request.amountEur()));
    }

    /** Books the credited goods back into stock. Explicit, never automatic. */
    @POST @Path("/{id}/return-goods")
    public OrderView returnGoods(@PathParam("id") long id) {
        return view(salesOrders.returnGoods(id));
    }


    private OrderView enrich(OrderView view) {
        if (incoming == null || partnerFinancing == null) return view;
        var agreement = advanceQuotes == null || view.order().id() == null ? null : advanceQuotes.find(view.order().id());
        var message = customerMessages == null ? null : customerMessages.find(view.order());
        // An arrangement is followed by several term invoices; one invoice never means the entire quote was billed.
        return new OrderView(view.order(), view.priced(), view.awaitingResend(), agreement == null ? view.invoicedAs() : null,
                agreement == null ? view.invoicedAsId() : null, agreement == null ? view.invoiceStatus() : null,
                view.sourceQuoteNumber(), incoming.summary(view.order(), view.priced()),
                partnerFinancing.accounting(view.order(), view.priced()), partnerFinancing.settlement(view.order()),
                agreement, advanceContents == null ? null : advanceContents.find(view.order()).orElse(null),
                splits == null ? null : splits.fulfillment(view.order()), message != null && message.readonly(), message == null ? null : message.text());
    }

    public record SplitResult(String groupId, OrderView current, OrderView later) {}
    @GET @Path("/{id}/split")
    public be.enrosed.sales.application.SalesSplits.Eligibility splitEligibility(@PathParam("id") long id) {
        return splits.eligibility(id);
    }
    @POST @Path("/{id}/split/preview")
    public be.enrosed.sales.application.SalesSplits.Preview splitPreview(@PathParam("id") long id, be.enrosed.sales.application.SalesSplits.Request request) {
        return splits.preview(id, request);
    }
    @POST @Path("/{id}/split")
    public SplitResult split(@PathParam("id") long id, be.enrosed.sales.application.SalesSplits.Request request) {
        var result = splits.split(id, request);
        return new SplitResult(result.groupId(), view(result.current()), view(result.later()));
    }
    @POST @Path("/{id}/fulfillment-ready")
    public OrderView fulfillmentReady(@PathParam("id") long id) { return view(splits.ready(id)); }

    @GET @Path("/{id}/payments")
    public List<be.enrosed.sales.domain.SalesPayment> payments(@PathParam("id") long id) { return incoming.forOrder(id); }

    @GET @Path("/{id}/invoice-declaration")
    public be.enrosed.sales.application.PartnerInvoiceDeclarations.Declaration invoiceDeclaration(@PathParam("id") long id) {
        return invoiceDeclarations.get(id);
    }

    @PUT @Path("/{id}/invoice-declaration")
    public be.enrosed.sales.application.PartnerInvoiceDeclarations.Declaration saveInvoiceDeclaration(@PathParam("id") long id,
            be.enrosed.sales.application.PartnerInvoiceDeclarations.Declaration request) {
        return invoiceDeclarations.save(id, request);
    }

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

    /** A concept advance invoice on a regular quote: a percentage or an amount excl. VAT, an optional due date. */
    @POST
    @Path("/{id}/advance-invoice")
    public OrderView createAdvanceInvoice(@PathParam("id") long id,
                                          be.enrosed.sales.application.SalesAdvanceBillingService.AdvanceRequest request) {
        return view(advanceBilling.createAdvanceInvoice(id, request));
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
        return view(salesOrders.preview(id, order));
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

    /** Complete transport-only update; commercial fields on a sent quote remain frozen. */
    @PUT
    @Path("/{id}/shipping")
    public OrderView updateShipping(@PathParam("id") long id, SalesOrderService.ShippingUpdate request) {
        return view(salesOrders.updateShipping(id, request));
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

    /** Withdraws an open quote; with notifyCustomer the customer gets a mail, with the portal link only when the quote was sent. */
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
