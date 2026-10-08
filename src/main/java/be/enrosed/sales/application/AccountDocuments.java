package be.enrosed.sales.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DeliveryDefaults;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DetailExtraLine;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DetailLine;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DetailTotals;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DocumentPage;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.DocumentRow;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderDetail;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.PickupView;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.Destination;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a logged-in customer may read: their website orders, every quotation
 * Enrosed ever sent them, and their issued invoices and credit notes. One
 * predicate decides what is visible, for the list, the detail and the PDF
 * alike, and one function gives the status the customer reads.
 *
 * A website order is shown as the customer ordered it, from its stored
 * snapshot, until Enrosed has sent a version: what staff are still working
 * on never leaves. Nothing here takes a lock, counts a view or says anything
 * about payments.
 */
@ApplicationScoped
public class AccountDocuments {
    private static final Logger LOG = Logger.getLogger(AccountDocuments.class);

    public static final String ORDER = "ORDER";
    public static final String QUOTE = "QUOTE";
    public static final String INVOICE = "INVOICE";
    public static final String CREDIT_NOTE = "CREDIT_NOTE";

    public static final String AS_ORDERED = "AS_ORDERED";
    public static final String CURRENT = "CURRENT";

    /** The customer reads dates and "expired" by the Belgian day; the server's own zone is UTC. */
    static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    public static final int DEFAULT_PAGE = 20;
    public static final int MAX_PAGE = 50;

    /** The two tabs of "my orders". */
    public enum ListKind { ORDERS, INVOICES }

    /** The status a customer reads, and who cancelled when it is CANCELLED. */
    public record Status(String code, String cancelledBy) {}

    /**
     * A claim document that is no draft and was never withdrawn: the one
     * definition of "issued" for the customer's history. Narrow it here if
     * only mailed invoices should show.
     */
    private static final List<QuoteStatus> NOT_ISSUED = List.of(QuoteStatus.CONCEPT, QuoteStatus.GEANNULEERD,
            QuoteStatus.AFGEWEZEN, QuoteStatus.VERLOPEN);
    private static final List<DocumentType> CLAIMS = List.of(DocumentType.FACTUUR, DocumentType.CREDITNOTA);

    /* The visible set in the query language; visible(...) below is the same rule and has the last word. */
    private static final String OWN_STANDARD = "e.customerId = :customer and (e.purpose is null or e.purpose = :standard)"
            + " and e.partnerPurchaseOrderId is null and (e.partnerSettlement is null or e.partnerSettlement = false)";
    private static final String ORDERS_RULE = "(e.docType is null or e.docType = :quote)"
            + " and ((w.salesOrderId is not null and w.customerId = :customer)"
            + " or (e.status <> :concept and e.sentAt is not null))";
    private static final String INVOICES_RULE = "e.docType in (:claims) and e.status not in (:notIssued)";

    /** Newest orders looked at for the prefill; older ones only matter when all of these were trashed. */
    private static final int PREFILL_CANDIDATES = 50;

    @Inject EntityManager entities;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesOrderService salesOrders;
    @Inject QuoteService quotes;
    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject SalesCustomerMessages customerMessages;
    @Inject CustomerService customers;
    @Inject CountryService countries;
    @Inject StockService stock;

    /**
     * The switch that keeps ordering off until every screen is live. Read on
     * every call, so the variable decides and no bean keeps an old answer.
     */
    public boolean orderingEnabled() {
        return ConfigProvider.getConfig().getOptionalValue("enrosed.website.ordering.enabled", Boolean.class)
                .orElse(true);
    }

    /* ============================================================ rules */

    /**
     * Whether this customer may see the document at all. A website order is
     * theirs in every state; a quotation only once it was sent and is not
     * back in concept; an invoice or credit note only once issued. Partner
     * documents and documents of another customer never.
     */
    public static boolean visible(SalesOrder order, WebOrders.Row row, long customerId) {
        if (order == null || order.customerId() == null || order.customerId() != customerId) return false;
        if (order.purpose() != SalesPurpose.STANDARD || order.partnerPurchaseOrderId() != null
                || order.partnerSettlement()) return false;
        if (order.isClaimDocument()) return issued(order);
        return WebOrders.owns(order, row, customerId)
                || order.status() != QuoteStatus.CONCEPT && order.sentAt() != null;
    }

    public static String kindOf(SalesOrder order, WebOrders.Row row, long customerId) {
        if (order.isCreditNote()) return CREDIT_NOTE;
        if (order.isInvoice()) return INVOICE;
        return WebOrders.owns(order, row, customerId) ? ORDER : QUOTE;
    }

    /** A PDF exists for a version Enrosed sent and for an issued claim document; never for a concept. */
    public static boolean hasPdf(SalesOrder order) {
        return order.isClaimDocument() ? issued(order)
                : order.sentAt() != null && order.status() != QuoteStatus.CONCEPT;
    }

    /**
     * The status the customer reads; the first rule that matches wins.
     *
     * @param row            the website-order row when the document is this customer's order, else null
     * @param issuedInvoice  an issued invoice was made from this document
     * @param derivedInvoice any invoice, a draft included, was made from it
     */
    public static Status statusOf(SalesOrder order, WebOrders.Row row, boolean issuedInvoice, boolean derivedInvoice,
                                  LocalDate today) {
        if (order.isClaimDocument()) return new Status("ISSUED", null);
        QuoteStatus status = order.status();
        if (status == QuoteStatus.GEANNULEERD)
            return new Status("CANCELLED", row != null && row.customerCancelledAt() != null ? "CUSTOMER" : "ENROSED");
        if (issuedInvoice) return new Status("CONFIRMED", null);
        if (status == QuoteStatus.GEACCEPTEERD) return new Status(row != null ? "CONFIRMED" : "ACCEPTED", null);
        if (status == QuoteStatus.AFGEWEZEN) return new Status("DECLINED", null);
        boolean open = status == QuoteStatus.VERZONDEN || status == QuoteStatus.BEKEKEN;
        if (status == QuoteStatus.VERLOPEN
                || open && order.validUntil() != null && today != null && order.validUntil().isBefore(today))
            return new Status("EXPIRED", null);
        if (open) return new Status("AWAITING_APPROVAL", null);
        if (status == QuoteStatus.WIJZIGING_GEVRAAGD) return new Status("CHANGE_REQUESTED", null);
        /* A concept: only a website order gets here. A draft invoice shows as being processed, never as itself. */
        return new Status(WebOrders.customerMayChange(order, row, derivedInvoice) ? "RECEIVED" : "IN_PROCESSING", null);
    }

    private static boolean issued(SalesOrder order) {
        return order.isClaimDocument() && !NOT_ISSUED.contains(order.status());
    }

    /** A website order shows what was ordered until Enrosed sent a version; a quotation is always the sent document. */
    private static boolean asOrdered(SalesOrder order, WebOrders.Row ownedRow) {
        return ownedRow != null && !(order.sentAt() != null && order.status() != QuoteStatus.CONCEPT);
    }

    /* ============================================================= list */

    /**
     * One page, newest first, by id. Amounts of a website order as ordered
     * come from its snapshot; every other row is priced now, the page with
     * one read of the catalogue. A row that cannot be priced is still
     * listed, without totals.
     */
    public DocumentPage page(ListKind kind, Long cursor, int limit, long customerId) {
        TypedQuery<Long> query = entities.createQuery("select e.id from " + orderEntity() + " e"
                + " left join SalesWebOrderEntity w on w.salesOrderId = e.id where " + OWN_STANDARD
                + " and (" + (kind == ListKind.ORDERS ? ORDERS_RULE : INVOICES_RULE) + ")"
                + (cursor == null ? "" : " and e.id < :cursor") + " order by e.id desc", Long.class);
        bind(query, kind == ListKind.ORDERS, kind == ListKind.INVOICES, customerId);
        if (cursor != null) query.setParameter("cursor", cursor);
        List<Long> found = query.setMaxResults(limit + 1).getResultList();
        List<Long> ids = found.size() > limit ? found.subList(0, limit) : found;
        Long nextCursor = found.size() > limit ? ids.getLast() : null;

        Map<Long, WebOrders.Row> rows = kind == ListKind.ORDERS ? webOrders.index(ids) : Map.of();
        List<SalesOrder> documents = new ArrayList<>();
        for (long id : ids) {
            SalesOrder order = orders.findById(id).orElse(null);
            if (visible(order, rows.get(id), customerId)) documents.add(order);
        }
        Map<Long, Derived> derived = kind == ListKind.ORDERS ? derivedInvoices(documents.stream().map(SalesOrder::id).toList(),
                customerId) : Map.of();
        Map<Long, String> related = kind == ListKind.INVOICES ? relatedNumbers(documents, customerId) : Map.of();

        List<SalesOrder> live = documents.stream().filter(order -> !asOrdered(order, owned(order, rows, customerId))).toList();
        Map<Long, PricedOrder> priced = priceLive(live);

        LocalDate today = LocalDate.now(BRUSSELS);
        List<DocumentRow> items = new ArrayList<>();
        for (SalesOrder order : documents) {
            WebOrders.Row row = owned(order, rows, customerId);
            Derived invoices = derived.getOrDefault(order.id(), Derived.NONE);
            BigDecimal excl = null;
            BigDecimal incl = null;
            if (asOrdered(order, row)) {
                WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
                if (snapshot != null && snapshot.totals() != null) {
                    excl = snapshot.totals().totalExclVat();
                    incl = snapshot.totals().totalInclVat();
                }
            } else if (priced.get(order.id()) != null) {
                excl = Money.money(priced.get(order.id()).totals().total());
                incl = Money.money(priced.get(order.id()).totals().totalInclVat());
            }
            Status status = statusOf(order, row, invoices.issued(), invoices.any(), today);
            boolean changeable = row != null && WebOrders.customerMayChange(order, row, invoices.any());
            items.add(new DocumentRow(order.id(), kindOf(order, row, customerId), order.number(), order.orderDate(),
                    status.code(), status.cancelledBy(), excl, incl, "EUR", validUntil(order, status),
                    changeable, changeable, !order.isClaimDocument(), hasPdf(order),
                    order.isClaimDocument() ? related.get(order.id()) : invoices.issuedNumber()));
        }
        return new DocumentPage(items, nextCursor);
    }

    /** The whole page at once; when one document breaks that, each on its own and the broken ones without a price. */
    private Map<Long, PricedOrder> priceLive(List<SalesOrder> live) {
        Map<Long, PricedOrder> priced = new LinkedHashMap<>();
        if (live.isEmpty()) return priced;
        try {
            List<PricedOrder> all = salesOrders.priceAll(live);
            for (int i = 0; i < live.size(); i++) priced.put(live.get(i).id(), all.get(i));
        } catch (RuntimeException page) {
            priced.clear();
            for (SalesOrder order : live) {
                try {
                    priced.put(order.id(), salesOrders.price(order));
                } catch (RuntimeException one) {
                    LOG.warnf(one, "Document %d kon niet doorgerekend worden voor het klantoverzicht", order.id());
                }
            }
        }
        return priced;
    }

    /* =========================================================== detail */

    /**
     * An order or quotation in full, also the prefill when the customer
     * changes an order. Empty for everything this customer may not see and
     * for invoices and credit notes, which have no detail.
     */
    public Optional<OrderDetail> detail(long id, long customerId) {
        SalesOrder order = orders.findById(id).orElse(null);
        WebOrders.Row found = order == null ? null : webOrders.find(id).orElse(null);
        if (!visible(order, found, customerId) || order.isClaimDocument()) return Optional.empty();
        WebOrders.Row row = WebOrders.owns(order, found, customerId) ? found : null;
        Derived invoices = derivedInvoices(List.of(id), customerId).getOrDefault(id, Derived.NONE);
        Status status = statusOf(order, row, invoices.issued(), invoices.any(),
                LocalDate.now(BRUSSELS));
        boolean changeable = row != null && WebOrders.customerMayChange(order, row, invoices.any());
        boolean ordered = asOrdered(order, row);

        String country;
        String notes;
        LocalDate basisDate;
        List<DetailLine> lines;
        List<DetailExtraLine> extraLines;
        DetailTotals totals;
        if (ordered) {
            WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
            if (snapshot == null || snapshot.totals() == null)
                throw new IllegalStateException("De bestelling van document " + id + " is niet leesbaar");
            country = snapshot.countryCode();
            notes = snapshot.notes();
            basisDate = date(snapshot.takenAt() != null ? snapshot.takenAt() : row.placedAt());
            lines = snapshot.lines().stream().map(line -> new DetailLine(line.productId(), line.sku(), line.description(),
                    line.cartons(), line.piecesPerCarton(), line.quantity(), line.unitPrice(), line.discountPct(),
                    line.net())).toList();
            extraLines = List.of();
            WebOrderSnapshot.Totals frozen = snapshot.totals();
            totals = new DetailTotals(frozen.goods(), null, frozen.shipping(), frozen.shippingStatus(),
                    frozen.totalExclVat(), frozen.vatRatePct(), frozen.vatAmount(), frozen.totalInclVat(),
                    frozen.vatTreatment());
        } else {
            PricedOrder priced = salesOrders.price(order);
            country = order.countryCode();
            notes = customerMessages.find(order).readonly() ? order.notes() : null;
            basisDate = date(order.sentAt());
            lines = priced.lines().stream().map(AccountDocuments::currentLine).toList();
            extraLines = priced.extraLines().stream()
                    .map(line -> new DetailExtraLine(line.description(), Money.money(line.total()))).toList();
            totals = currentTotals(order, priced);
        }

        String fulfillment = null;
        PickupView pickup = null;
        Destination destination = null;
        String contactName = null;
        String phone = null;
        if (row != null) {
            WebOrderDeliveries.Delivery delivery = deliveries.find(id)
                    .filter(typed -> typed.customerId() == null || typed.customerId() == customerId).orElse(null);
            if (delivery != null) {
                fulfillment = delivery.fulfillment();
                if (WebOrderDeliveries.PICKUP.equals(fulfillment))
                    pickup = new PickupView(delivery.pickupLocationId(), delivery.pickupLabel(), delivery.pickupAddress());
                destination = new Destination(country, delivery.postalCode(), delivery.city(), delivery.address());
                contactName = delivery.contactName();
                phone = delivery.contactPhone();
            } else {
                destination = new Destination(country, null, null, null);
            }
        }
        String cancellation = "ENROSED".equals(status.cancelledBy()) ? quotes.cancellationMessage(order).orElse(null) : null;
        return Optional.of(new OrderDetail(order.id(), kindOf(order, row, customerId), order.number(), order.orderDate(),
                status.code(), status.cancelledBy(), cancellation, row == null ? null : row.revision(),
                changeable, changeable, hasPdf(order), ordered ? AS_ORDERED : CURRENT, basisDate,
                validUntil(order, status), fulfillment, pickup, destination, contactName, phone,
                notes == null || notes.isBlank() ? null : notes, lines, extraLines, totals, invoices.issuedNumber()));
    }

    private static DetailLine currentLine(PricedOrder.Line line) {
        boolean hasPrice = line.unitPrice() != null && line.unitPrice().signum() > 0;
        /* Whole cartons of more than one piece are a known carton content; anything else is left open. */
        Integer perCarton = line.cartons() > 0 && line.quantity() % line.cartons() == 0
                && line.quantity() / line.cartons() > 1 ? line.quantity() / line.cartons() : null;
        String description = line.customerDescription() == null || line.customerDescription().isBlank()
                ? line.description() : line.customerDescription();
        return new DetailLine(line.productId(), line.sku(), description, perCarton == null ? null : line.cartons(),
                perCarton, line.quantity(), hasPrice ? line.unitPrice() : null,
                hasPrice ? Money.money(line.discountPct()) : null, hasPrice ? Money.money(line.net()) : null);
    }

    private static DetailTotals currentTotals(SalesOrder order, PricedOrder priced) {
        PricedOrder.Totals totals = priced.totals();
        String issue = priced.validation() == null ? null : priced.validation().freightPricingIssue();
        boolean positiveShipping = totals.shippingTotal() != null && totals.shippingTotal().signum() > 0;
        String shippingStatus = "EXW".equalsIgnoreCase(order.incoterm()) ? "PICKUP"
                : order.freight() == FreightState.TE_BEPALEN || issue != null && !issue.isBlank() || !positiveShipping
                ? "TO_CONFIRM" : "CALCULATED";
        return new DetailTotals(Money.money(totals.goodsTotal()),
                priced.extraLines().isEmpty() ? null : Money.money(totals.extraLinesTotal()),
                "TO_CONFIRM".equals(shippingStatus) ? null : Money.money(totals.shippingTotal()), shippingStatus,
                Money.money(totals.total()), Money.money(totals.vatRatePct()), Money.money(totals.vatAmount()),
                Money.money(totals.totalInclVat()), totals.vatTreatment() == null ? null : totals.vatTreatment().name());
    }

    /* ============================================================== pdf */

    /**
     * The PDF of a sent version or of an issued invoice or credit note,
     * rendered from the very instance that passed the visibility test.
     * Without the signing link, and for a claim document without anything
     * about payments.
     *
     * @param language null for the language of the customer record
     */
    public Optional<QuoteDocumentRenderer.Document> pdf(long id, long customerId, Language language) {
        SalesOrder order = orders.findById(id).orElse(null);
        WebOrders.Row row = order == null ? null : webOrders.find(id).orElse(null);
        if (!visible(order, row, customerId) || !hasPdf(order)) return Optional.empty();
        return Optional.of(quotes.documentForAccount(order, language));
    }

    /* ========================================================= defaults */

    /**
     * What the delivery step starts from: the customer's last website order,
     * else the address on the customer record, else nothing. A value the
     * order form would refuse is left out, never cut, so the prefill alone
     * can never cause a field error.
     */
    public DeliveryDefaults deliveryDefaults(long customerId) {
        List<Long> newest = entities.createQuery("select w.salesOrderId from SalesWebOrderEntity w"
                        + " where w.customerId = :customer order by w.placedAt desc, w.salesOrderId desc", Long.class)
                .setParameter("customer", customerId).setMaxResults(PREFILL_CANDIDATES).getResultList();
        for (long id : newest) {
            SalesOrder order = orders.findById(id).orElse(null);
            WebOrders.Row row = order == null ? null : webOrders.find(id).orElse(null);
            if (order == null || !WebOrders.owns(order, row, customerId)) continue;
            WebOrderDeliveries.Delivery delivery = deliveries.find(id)
                    .filter(typed -> typed.customerId() == null || typed.customerId() == customerId).orElse(null);
            if (delivery == null) continue;
            /* The country of the order as placed: staff may have changed it on the document without sending it. */
            WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
            String fulfillment = WebOrderDeliveries.DELIVERY.equals(delivery.fulfillment())
                    || WebOrderDeliveries.PICKUP.equals(delivery.fulfillment()) ? delivery.fulfillment() : null;
            Long pickupLocationId = null;
            if (WebOrderDeliveries.PICKUP.equals(fulfillment)) {
                pickupLocationId = publicPickup(delivery.pickupLocationId());
                if (pickupLocationId == null) fulfillment = null;
            }
            return new DeliveryDefaults("LAST_ORDER", fulfillment, pickupLocationId,
                    destination(snapshot == null ? null : snapshot.countryCode(), delivery.postalCode(), delivery.city(),
                            delivery.address()),
                    singleLine(delivery.contactName(), 120), singleLine(delivery.contactPhone(), 50));
        }
        Customer customer = customers.get(customerId);
        if (!blank(customer.address()) || !blank(customer.postalCode()) || !blank(customer.city()))
            return new DeliveryDefaults("CUSTOMER_RECORD", WebOrderDeliveries.DELIVERY, null,
                    destination(customer.countryCode(), customer.postalCode(), customer.city(), customer.address()),
                    null, null);
        return new DeliveryDefaults("NONE", null, null, null, null, null);
    }

    private Destination destination(String countryCode, String postalCode, String city, String address) {
        return new Destination(deliverable(countryCode), singleLine(postalCode, 24), singleLine(city, 100),
                singleLine(address, 200));
    }

    /** The code as the public country list spells it, or null when Enrosed does not deliver there. */
    private String deliverable(String countryCode) {
        if (blank(countryCode)) return null;
        Country country = countries.find(countryCode.strip().toUpperCase(java.util.Locale.ROOT));
        if (country == null) return null;
        return countries.list().stream().map(Country::code).filter(country.code()::equals).findFirst().orElse(null);
    }

    private Long publicPickup(Long locationId) {
        if (locationId == null) return null;
        return stock.publicPickupLocations().stream().map(StockLocation::id).filter(locationId::equals)
                .findFirst().orElse(null);
    }

    /** The rule the order form applies to a one-line field; a value that fails it is not offered. */
    private static String singleLine(String value, int max) {
        if (blank(value) || value.length() > max) return null;
        return value.codePoints().anyMatch(character -> character < 0x20 || character == 0x7f) ? null : value;
    }

    /* ========================================================== queries */

    /** What was invoiced from a document: any invoice at all, an issued one, and the number the customer may read. */
    private record Derived(boolean any, boolean issued, String issuedNumber) {
        static final Derived NONE = new Derived(false, false, null);
    }

    /** One query for a page: the invoices made from these documents. */
    private Map<Long, Derived> derivedInvoices(Collection<Long> ids, long customerId) {
        Map<Long, Derived> derived = new LinkedHashMap<>();
        if (ids.isEmpty()) return derived;
        List<Object[]> found = entities.createQuery("select e.sourceQuoteId, e.number, e.status, e.docType, e.customerId"
                        + " from " + orderEntity() + " e where e.sourceQuoteId in (:ids) order by e.id", Object[].class)
                .setParameter("ids", ids).getResultList();
        for (Object[] columns : found) {
            Long source = (Long) columns[0];
            boolean issued = columns[3] == DocumentType.FACTUUR && !NOT_ISSUED.contains((QuoteStatus) columns[2]);
            /* The number only of an invoice this customer finds under their invoices. */
            String number = issued && Objects.equals(columns[4], customerId) ? (String) columns[1] : null;
            Derived before = derived.getOrDefault(source, Derived.NONE);
            derived.put(source, new Derived(true, before.issued() || issued,
                    before.issuedNumber() != null ? before.issuedNumber() : number));
        }
        return derived;
    }

    /**
     * One query for a page of invoices and credit notes: the number of the
     * order or quotation an invoice was made from and of the invoice a
     * credit note corrects, only when the customer can see that document too.
     */
    private Map<Long, String> relatedNumbers(List<SalesOrder> documents, long customerId) {
        Map<Long, Long> targetByDocument = new LinkedHashMap<>();
        for (SalesOrder order : documents) {
            Long target = order.isCreditNote() ? order.creditedInvoiceId() : order.sourceQuoteId();
            if (target != null) targetByDocument.put(order.id(), target);
        }
        Map<Long, String> related = new LinkedHashMap<>();
        if (targetByDocument.isEmpty()) return related;
        Set<Long> targets = new LinkedHashSet<>(targetByDocument.values());
        TypedQuery<Object[]> query = entities.createQuery("select e.id, e.number from " + orderEntity() + " e"
                + " left join SalesWebOrderEntity w on w.salesOrderId = e.id where e.id in (:ids) and " + OWN_STANDARD
                + " and ((" + ORDERS_RULE + ") or (" + INVOICES_RULE + "))", Object[].class);
        bind(query, true, true, customerId);
        Map<Long, String> numbers = new LinkedHashMap<>();
        for (Object[] columns : query.setParameter("ids", targets).getResultList())
            numbers.put((Long) columns[0], (String) columns[1]);
        targetByDocument.forEach((document, target) -> {
            if (numbers.get(target) != null) related.put(document, numbers.get(target));
        });
        return related;
    }

    private static void bind(TypedQuery<?> query, boolean ordersRule, boolean invoicesRule, long customerId) {
        query.setParameter("customer", customerId).setParameter("standard", SalesPurpose.STANDARD);
        if (ordersRule) query.setParameter("quote", DocumentType.OFFERTE).setParameter("concept", QuoteStatus.CONCEPT);
        if (invoicesRule) query.setParameter("claims", CLAIMS).setParameter("notIssued", NOT_ISSUED);
    }

    /** The sales document entity is a nested class and does not resolve by its simple name. */
    private String orderEntity() {
        return entities.getMetamodel().entity(SalesEntities.SalesOrderEntity.class).getName();
    }

    /* ============================================================ small */

    private static WebOrders.Row owned(SalesOrder order, Map<Long, WebOrders.Row> rows, long customerId) {
        WebOrders.Row row = rows.get(order.id());
        return WebOrders.owns(order, row, customerId) ? row : null;
    }

    /** The validity matters to the customer only while an approval is asked or has run out. */
    private static LocalDate validUntil(SalesOrder order, Status status) {
        return "AWAITING_APPROVAL".equals(status.code()) || "EXPIRED".equals(status.code()) ? order.validUntil() : null;
    }

    static LocalDate date(Instant moment) {
        return moment == null ? null : LocalDate.ofInstant(moment, BRUSSELS);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
