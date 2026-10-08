package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderPreviewRequest;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderRequest;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import be.enrosed.shared.BusinessDays;
import be.enrosed.shipping.application.CarrierRepository;
import be.enrosed.shipping.domain.Carrier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static be.enrosed.sales.adapter.in.rest.PublicQuoteDtos.*;

/** Builds anonymous estimates and turns a submitted website request into an ERP draft quote. */
@ApplicationScoped
public class PublicQuoteService {
    private static final int MAX_LINES = 100;
    private static final int MAX_CARTONS_PER_LINE = 10_000;
    private static final int MAX_TOTAL_CARTONS = 20_000;
    private static final int MAX_PIECES_PER_LINE = 1_000_000;
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);

    private final ProductService products;
    private final StockService stock;
    private final CountryService countries;
    private final CustomerService customers;
    private final SalesOrderService salesOrders;
    private final DiscountTierService tiers;
    private final SalesPricingCalculator pricing;
    private final SalesSettings settings;
    private final VatCalculator vat;
    private final CarrierRepository carriers;
    private final Event<WebsiteQuotePushNotifier.Ready> websiteQuoteReady;

    /** A field, not a constructor argument: tests build this service by hand and may leave it null. */
    @Inject
    Event<WebsiteQuoteLoginRequested> loginRequestedEvent;

    public PublicQuoteService(ProductService products, StockService stock,
                              CountryService countries,
                              CustomerService customers, SalesOrderService salesOrders,
                              DiscountTierService tiers, SalesPricingCalculator pricing,
                              SalesSettings settings, VatCalculator vat,
                              CarrierRepository carriers,
                              Event<WebsiteQuotePushNotifier.Ready> websiteQuoteReady) {
        this.products = products;
        this.stock = stock;
        this.countries = countries;
        this.customers = customers;
        this.salesOrders = salesOrders;
        this.tiers = tiers;
        this.pricing = pricing;
        this.settings = settings;
        this.vat = vat;
        this.carriers = carriers;
        this.websiteQuoteReady = websiteQuoteReady;
    }

    public ConfigurationResponse configuration(String languageCode) {
        Map<String, String> errors = new LinkedHashMap<>();
        Language language = requireLanguage(languageCode, errors);
        if (!errors.isEmpty()) throw new PublicQuoteValidationException(errors);
        SalesOrder priceTemplate = draft(null, null, null, Fulfillment.DELIVERY,
                List.of(), null, FreightState.TE_BEPALEN);
        List<ProductPrice> publicPrices = products.websiteOrderableProducts().stream()
                .sorted(Comparator.comparing(Product::sku, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(product -> {
                    BigDecimal amount = pricing.unitPriceFor(product, priceTemplate, null);
                    boolean available = amount != null && amount.signum() > 0;
                    return new ProductPrice(product.id(), available ? amount : null, available,
                            piecesPerCarton(product), product.packaging().salesUnit().name(),
                            product.packaging().kind() == be.enrosed.catalog.domain.PackagingKind.DISPLAY
                                    ? product.packaging().piecesPerUnit() : null,
                            be.enrosed.catalog.adapter.in.rest.UnitDto.of(product.packaging().unitKey(), language));
                }).toList();
        List<CountryOption> destinations = countries.list().stream()
                .map(country -> new CountryOption(country.code(), country.name(),
                        country.minOrderValue(), country.transitDays()))
                .toList();
        List<PickupLocation> pickupLocations = stock.publicPickupLocations().stream()
                .map(PublicQuoteService::publicPickupLocation)
                .toList();
        List<String> fulfillmentMethods = pickupLocations.isEmpty()
                ? List.of("DELIVERY") : List.of("DELIVERY", "PICKUP");
        return new ConfigurationResponse("EUR", "NET_EXCL_VAT", "FULL_CARTONS",
                fulfillmentMethods, "ESTIMATE_NOT_BINDING",
                destinations, publicPrices, pickupLocations);
    }

    public EstimateResponse preview(PreviewRequest request) {
        return toResponse(prepare(request));
    }

    /**
     * The estimate of a logged-in customer. The VAT number is the customer record's and is not
     * judged, exactly as in submitForCustomer: the website shows that number read-only, so a
     * placeholder staff once typed there must not close the estimate for this customer, and
     * the estimate equals what the submission will store.
     */
    public EstimateResponse previewForCustomer(PreviewRequest request, long customerId) {
        Customer customer = customers.get(customerId);
        return toResponse(prepare(request == null ? null : new PreviewRequest(
                request.language(), request.fulfillment(), customer.vatNumber(),
                request.destination(), request.items(), request.pickupLocationId()), false));
    }

    /** Read-only validation used before challenge verification and e-mail rate consumption. */
    public void validateSubmission(SubmitRequest request) {
        validateAndPrepareSubmission(request);
    }

    @Transactional
    public SubmissionResponse submit(SubmitRequest request) {
        Prepared prepared = validateAndPrepareSubmission(request);

        String companyCountryCode = upper(request.companyCountryCode());
        Customer buyer = customers.create(new Customer(null,
                clean(request.companyName()), clean(request.contactName()), clean(request.email()),
                clean(request.phone()), normalizedVat(request.vatNumber()), companyCountryCode,
                prepared.language, request.destination() == null ? null
                        : clean(request.destination().address()),
                request.destination() == null ? null : clean(request.destination().postalCode()),
                request.destination() == null ? null : clean(request.destination().city()),
                prepared.fulfillment == Fulfillment.PICKUP ? "EXW" : "DAP",
                null, "Aangemaakt via het publieke offerteformulier", null));

        boolean loginRequested = Boolean.TRUE.equals(request.loginRequested());
        Stored stored = store(request, prepared, buyer.id(), loginRequested
                ? List.of(WebsiteQuoteLoginRequested.NOTE_MARKER
                        + " De klant vraagt ook een login; goedkeuren bij Login-aanvragen.")
                : List.of());
        /* Observed after the commit only: a login request never rolls back the quote, and a
           rolled-back quote leaves no login request. */
        if (loginRequested && loginRequestedEvent != null) {
            loginRequestedEvent.fire(new WebsiteQuoteLoginRequested(
                    buyer.id(), stored.order.id(), stored.order.number(), prepared.language.name(),
                    clean(request.companyName()), companyCountryCode,
                    normalizedVat(request.vatNumber()), clean(request.contactName()),
                    clean(request.email()), clean(request.phone())));
        }
        return stored.response;
    }

    /** Read-only validation of a logged-in customer's request; the identity fields of the body are not looked at. */
    public void validateSubmissionForCustomer(SubmitRequest request, long customerId) {
        Prepared prepared = validateAndPrepareForCustomer(request, customers.get(customerId));
        /* While customers can order, the old route holds the same minimum: a page from before
           ordering, or a hand-made call, cannot send what an order would refuse. */
        if (orderingEnabled() && !prepared.priced.validation().meetsMinimum()) {
            throw new PublicQuoteValidationException(Map.of("items", "MINIMUM_NOT_MET"));
        }
    }

    /**
     * The request of a logged-in customer: no customer is created or changed, the quote hangs on
     * the customer of the login, and the prices are the public list, as for any visitor.
     */
    @Transactional
    public SubmissionResponse submitForCustomer(SubmitRequest request, long customerId,
                                                String accountEmail) {
        Customer buyer = customers.get(customerId);
        Prepared prepared = validateAndPrepareForCustomer(request, buyer);

        List<String> notes = new ArrayList<>();
        notes.add("Aangevraagd via klantlogin " + accountEmail);
        String contact = isBlank(request.contactName()) ? clean(buyer.contact())
                : clean(request.contactName());
        String phone = isBlank(request.phone()) ? null : clean(request.phone());
        boolean otherContact = !isBlank(contact) && !contact.equals(clean(buyer.contact()));
        boolean otherPhone = phone != null && !phone.equals(clean(buyer.phone()));
        if (otherContact || otherPhone) {
            /* Customer-typed text in a note where staff and code read bracket markers. */
            notes.add("Contact op aanvraag: " + withoutBrackets(contact)
                    + (phone == null ? "" : " · " + withoutBrackets(phone)));
        }
        return store(request, prepared, buyer.id(), notes).response;
    }

    /**
     * The estimate of an order. It is priced as the ERP will price the stored order: on the
     * customer record, delivered at the typed address. Products in {@code keptUnitPrices}
     * (those already in the order being changed) keep that price.
     */
    public EstimateResponse previewOrder(OrderPreviewRequest request, long customerId,
                                         Map<Long, BigDecimal> keptUnitPrices) {
        if (request == null) throw new PublicQuoteValidationException(Map.of("request", "REQUIRED"));
        return toResponse(prepareOrder(request.language(), request.fulfillment(),
                request.pickupLocationId(), request.destination(), request.items(),
                customers.get(customerId), keptUnitPrices));
    }

    /** Read-only validation of an order, the minimum order value included. */
    public void validateOrder(OrderRequest request, long customerId,
                              Map<Long, BigDecimal> keptUnitPrices) {
        validateAndPrepareOrder(request, customers.get(customerId), keptUnitPrices);
    }

    /**
     * What the caller needs beside the stored document to write the delivery row, the snapshot
     * and the change summary. Validates as validateOrder does.
     */
    public OrderFacts orderFacts(OrderRequest request, long customerId,
                                 Map<Long, BigDecimal> keptUnitPrices) {
        Customer buyer = customers.get(customerId);
        Prepared prepared = validateAndPrepareOrder(request, buyer, keptUnitPrices);
        Map<Long, Integer> piecesPerCarton = new LinkedHashMap<>();
        Map<Long, Integer> cartons = new LinkedHashMap<>();
        Map<Long, String> skus = new LinkedHashMap<>();
        Map<Long, BigDecimal> listPrices = new LinkedHashMap<>();
        SalesOrder priceTemplate = draft(null, null, null, Fulfillment.DELIVERY,
                List.of(), null, FreightState.TE_BEPALEN);
        for (PreparedItem item : prepared.items) {
            long productId = item.product.id();
            piecesPerCarton.put(productId, item.piecesPerCarton);
            cartons.put(productId, item.cartons);
            skus.put(productId, item.product.sku());
            if (keptUnitPrices != null && keptUnitPrices.get(productId) != null) {
                BigDecimal today = pricing.unitPriceFor(item.product, priceTemplate, null);
                if (today != null && today.signum() > 0) listPrices.put(productId, today);
            }
        }
        boolean pickup = prepared.fulfillment == Fulfillment.PICKUP;
        Destination destination = request.destination();
        String contact = isBlank(request.contactName()) ? clean(buyer.contact())
                : clean(request.contactName());
        return new OrderFacts(prepared.language.name(), prepared.fulfillment.name(),
                pickup || destination == null ? null : clean(destination.address()),
                pickup || destination == null ? null : clean(destination.postalCode()),
                pickup || destination == null ? null : clean(destination.city()),
                prepared.pickupLocation == null ? null : prepared.pickupLocation.id(),
                prepared.pickupLocation == null ? null : prepared.pickupLocation.publicPickupLabel(),
                prepared.pickupLocation == null ? null : prepared.pickupLocation.publicPickupAddress(),
                contact, isBlank(request.phone()) ? null : clean(request.phone()),
                piecesPerCarton, cartons, skus, listPrices);
    }

    /**
     * The order of a logged-in customer as an ERP document: the draft of a website request,
     * filled through the customer entry of the sales core. The caller writes the delivery row
     * and the order row and tells staff; nothing is announced here.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public SalesOrder storeOrder(OrderRequest request, long customerId, String accountEmail) {
        Customer buyer = customers.get(customerId);
        Prepared prepared = validateAndPrepareOrder(request, buyer, Map.of());
        SalesOrder created = salesOrders.createWebsiteRequest(customerId, prepared.country.code(),
                prepared.fulfillment == Fulfillment.PICKUP ? "EXW" : "DAP");
        SalesOrder saved = salesOrders.updateByCustomer(created.id(),
                orderDocument(created, request, prepared, buyer, accountEmail, Map.of()));
        salesOrders.captureCustomerRequest(created.id());
        return saved;
    }

    /**
     * The customer's new version of their order. A product that stays keeps its line and the
     * unit price it was ordered at; a new product gets today's price. Order date and validity
     * stay those of the placement.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public SalesOrder replaceOrder(SalesOrder stored, OrderRequest request, String accountEmail) {
        Customer buyer = customers.get(stored.customerId());
        Prepared prepared = validateAndPrepareOrder(request, buyer, keptUnitPrices(stored));
        Map<Long, SalesOrderLine> storedLines = stored.lines().stream()
                .filter(line -> line.productId() != null)
                .collect(Collectors.toMap(SalesOrderLine::productId, Function.identity(),
                        (first, second) -> first));
        return salesOrders.updateByCustomer(stored.id(),
                orderDocument(stored, request, prepared, buyer, accountEmail, storedLines));
    }

    /** The unit prices a customer change keeps: those stored on the lines of the order. */
    public static Map<Long, BigDecimal> keptUnitPrices(SalesOrder stored) {
        Map<Long, BigDecimal> kept = new LinkedHashMap<>();
        if (stored == null) return kept;
        for (SalesOrderLine line : stored.lines()) {
            if (line.productId() != null && line.unitPriceEur() != null) {
                kept.putIfAbsent(line.productId(), line.unitPriceEur());
            }
        }
        return kept;
    }

    /** The document of an order as the sales core stores it; {@code base} gives id, number and dates. */
    private SalesOrder orderDocument(SalesOrder base, OrderRequest request, Prepared prepared,
                                     Customer buyer, String accountEmail,
                                     Map<Long, SalesOrderLine> storedLines) {
        Map<Long, PricedOrder.Line> pricedLines = prepared.priced.lines().stream()
                .collect(Collectors.toMap(PricedOrder.Line::productId, Function.identity()));
        List<SalesOrderLine> lines = prepared.items.stream().map(item -> {
            SalesOrderLine kept = storedLines.get(item.product.id());
            PricedOrder.Line priced = pricedLines.get(item.product.id());
            BigDecimal serverPrice = kept != null && kept.unitPriceEur() != null ? kept.unitPriceEur()
                    : priced != null && priced.unitPrice().signum() > 0 ? priced.unitPrice() : null;
            return new SalesOrderLine(kept == null ? null : kept.id(), item.product.id(),
                    item.quantityPieces, serverPrice, null, null);
        }).toList();
        StringBuilder internal = new StringBuilder(SalesOrderService.WEBSITE_REQUEST_MARKER)
                .append(' ').append(base.number())
                .append("\nWebsitebestelling van een ingelogde klant; bindend na bevestiging door Enrosed.");
        prepared.items.stream().filter(item -> item.piecesPerCarton <= 0)
                .forEach(item -> internal.append('\n').append(SalesOrderService.WEBSITE_CARTON_UNRESOLVED_MARKER)
                        .append(" productId=").append(item.product.id())
                        .append("; sku=").append(item.product.sku())
                        .append("; cartons=").append(item.cartons)
                        .append("; quantityPieces=TE_BEPALEN"));
        internal.append("\nBesteld via klantlogin ").append(accountEmail);
        String contact = isBlank(request.contactName()) ? clean(buyer.contact())
                : clean(request.contactName());
        String phone = isBlank(request.phone()) ? null : clean(request.phone());
        boolean otherContact = !isBlank(contact) && !contact.equals(clean(buyer.contact()));
        boolean otherPhone = phone != null && !phone.equals(clean(buyer.phone()));
        if (otherContact || otherPhone) {
            /* Customer-typed text in a note where staff and code read bracket markers. */
            internal.append("\nContact op bestelling: ").append(withoutBrackets(contact))
                    .append(phone == null ? "" : " · " + withoutBrackets(phone));
        }
        return new SalesOrder(
                base.id(), base.number(), buyer.id(), prepared.country.code(),
                base.orderDate(), base.validUntil(), base.status(),
                prepared.fulfillment == Fulfillment.PICKUP ? "EXW" : "DAP",
                base.paymentTerms(), clean(request.notes()), base.markupMode(),
                base.orderMarkupPct(), null, null, null, null, null, 0,
                null, null, null,
                internal.toString(),
                base.deliveryTerms(),
                prepared.shippingAvailable ? FreightState.BEREKEND : FreightState.TE_BEPALEN,
                null, LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                prepared.strategy, null,
                prepared.carrier == null ? null : prepared.carrier.id(), null,
                DocumentType.OFFERTE, null, null, null, null, lines, List.of(),
                pickupSnapshot(prepared.pickupLocation));
    }

    /**
     * The checks of an order, in one field map: the contact fields, the basket and delivery as
     * prepareOrder judges them, and then the minimum order value of the delivery country.
     */
    private Prepared validateAndPrepareOrder(OrderRequest request, Customer customer,
                                             Map<Long, BigDecimal> keptUnitPrices) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null) {
            errors.put("request", "REQUIRED");
            throw new PublicQuoteValidationException(errors);
        }
        checkSingleLine(request.contactName(), 120, "contactName", errors);
        checkSingleLine(request.phone(), 50, "phone", errors);
        checkLength(request.notes(), 2000, "notes", errors);
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            errors.put("privacyAccepted", "REQUIRED");
        }
        if (!isBlank(request.website())) errors.put("request", "INVALID");
        Prepared prepared = null;
        try {
            prepared = prepareOrder(request.language(), request.fulfillment(),
                    request.pickupLocationId(), request.destination(), request.items(),
                    customer, keptUnitPrices);
        } catch (PublicQuoteValidationException validation) {
            validation.fieldErrors().forEach(errors::putIfAbsent);
        }
        if (!errors.isEmpty()) throw new PublicQuoteValidationException(errors);
        if (prepared == null) throw new IllegalStateException("Order preparation yielded no result");
        /* Only what the ERP can price counts: a line still to be confirmed adds nothing. */
        if (!prepared.priced.validation().meetsMinimum()) {
            throw new PublicQuoteValidationException(Map.of("items", "MINIMUM_NOT_MET"));
        }
        return prepared;
    }

    /** The one preparation of every order call; the VAT number is the record's and is not judged. */
    private Prepared prepareOrder(String language, String fulfillment, Long pickupLocationId,
                                  Destination destination, List<ItemRequest> items,
                                  Customer customer, Map<Long, BigDecimal> keptUnitPrices) {
        return prepare(new PreviewRequest(language, fulfillment, customer.vatNumber(),
                        destination, items, pickupLocationId), false,
                new OrderPricing(customer, keptUnitPrices == null ? Map.of() : keptUnitPrices));
    }

    /** Whether customers can order on the website; read on every call, as AccountDocuments does. */
    private static boolean orderingEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("enrosed.website.ordering.enabled", Boolean.class).orElse(true);
    }

    /** Turns a validated request into the ERP draft quote of one customer, with frozen public prices. */
    private Stored store(SubmitRequest request, Prepared prepared, long customerId,
                         List<String> extraInternalNotes) {
        SalesOrder created = salesOrders.createWebsiteRequest(customerId, prepared.country.code(),
                prepared.fulfillment == Fulfillment.PICKUP ? "EXW" : "DAP");
        Map<Long, PricedOrder.Line> pricedLines = prepared.priced.lines().stream()
                .collect(Collectors.toMap(PricedOrder.Line::productId, Function.identity()));
        List<SalesOrderLine> frozenLines = prepared.items.stream().map(item -> {
            PricedOrder.Line priced = pricedLines.get(item.product.id());
            BigDecimal serverPrice = priced != null && priced.unitPrice().signum() > 0
                    ? priced.unitPrice() : null;
            return new SalesOrderLine(null, item.product.id(), item.quantityPieces,
                    serverPrice, null, null);
        }).toList();
        String marker = SalesOrderService.WEBSITE_REQUEST_MARKER + " " + created.number();
        String missingCartons = prepared.items.stream()
                .filter(item -> item.piecesPerCarton <= 0)
                .map(item -> SalesOrderService.WEBSITE_CARTON_UNRESOLVED_MARKER
                        + " productId=" + item.product.id()
                        + "; sku=" + item.product.sku() + "; cartons=" + item.cartons
                        + "; quantityPieces=TE_BEPALEN")
                .collect(Collectors.joining("\n"));
        String internal = marker
                + "\nNiet-bindende aanvraag; prijzen en logistiek door Enrosed te bevestigen."
                + (missingCartons.isBlank() ? ""
                        : "\n" + missingCartons)
                + extraInternalNotes.stream().map(line -> "\n" + line).collect(Collectors.joining());
        SalesOrder changes = new SalesOrder(
                created.id(), created.number(), customerId, prepared.country.code(),
                created.orderDate(), created.validUntil(), created.status(), created.incoterm(),
                created.paymentTerms(), clean(request.notes()), created.markupMode(),
                created.orderMarkupPct(), null, null, null, null, null, 0,
                null, null, null,
                internal,
                created.deliveryTerms(),
                prepared.shippingAvailable ? FreightState.BEREKEND : FreightState.TE_BEPALEN,
                null, LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                prepared.strategy, null,
                prepared.carrier == null ? null : prepared.carrier.id(), null,
                DocumentType.OFFERTE, null, null, null, null, frozenLines, List.of(),
                pickupSnapshot(prepared.pickupLocation));
        salesOrders.update(created.id(), changes);
        salesOrders.captureCustomerRequest(created.id());
        websiteQuoteReady.fire(new WebsiteQuotePushNotifier.Ready(
                created.id(), created.number()));
        return new Stored(created, new SubmissionResponse(created.number(), "RECEIVED",
                "REQUEST_RECEIVED_NOT_BINDING", "FINAL_QUOTE_FOLLOWS", toResponse(prepared)));
    }

    /**
     * Company name, company country, e-mail and VAT number of the body are ignored here: they
     * are the customer record's. A blank contact name is fine, the customer's contact stands in.
     */
    private Prepared validateAndPrepareForCustomer(SubmitRequest request, Customer customer) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null) {
            errors.put("request", "REQUIRED");
            throw new PublicQuoteValidationException(errors);
        }
        checkSingleLine(request.contactName(), 120, "contactName", errors);
        validateRequestDetails(request, errors);
        if (!isBlank(request.website())) errors.put("request", "INVALID");
        Prepared prepared = null;
        try {
            /* The number on the customer record was entered or checked by staff; it is not judged again. */
            prepared = prepare(new PreviewRequest(
                    request.language(), request.fulfillment(), customer.vatNumber(),
                    request.destination(), request.items(), request.pickupLocationId()), false);
        } catch (PublicQuoteValidationException validation) {
            validation.fieldErrors().forEach(errors::putIfAbsent);
        }
        if (!errors.isEmpty()) throw new PublicQuoteValidationException(errors);
        if (prepared == null) throw new IllegalStateException("Quote preparation yielded no result");
        return prepared;
    }

    private Prepared validateAndPrepareSubmission(SubmitRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateContact(request, errors);
        /* Required when the request is sent, not while the visitor is still
           looking at prices: the preview runs without a VAT number. */
        if (request != null && isBlank(request.vatNumber())) errors.put("vatNumber", "REQUIRED");
        if (request != null && !isBlank(request.website())) {
            /* A filled honeypot receives the same generic validation response as any bad input. */
            errors.put("request", "INVALID");
        }
        Prepared prepared = null;
        try {
            prepared = prepare(request == null ? null : new PreviewRequest(
                    request.language(), request.fulfillment(), request.vatNumber(),
                    request.destination(), request.items(), request.pickupLocationId()));
        } catch (PublicQuoteValidationException validation) {
            /* Return one actionable field map: correcting the contact block
               should not reveal a second, previously hidden address/product
               error on the next click. This path remains read-only. */
            validation.fieldErrors().forEach(errors::putIfAbsent);
        }
        if (!errors.isEmpty()) throw new PublicQuoteValidationException(errors);
        if (prepared == null) throw new IllegalStateException("Quote preparation yielded no result");
        return prepared;
    }

    private Prepared prepare(PreviewRequest request) {
        return prepare(request, true);
    }

    private Prepared prepare(PreviewRequest request, boolean judgeVatNumber) {
        return prepare(request, judgeVatNumber, null);
    }

    /**
     * {@code order} is null for a quote request. For an order it carries the customer record
     * and the unit prices to keep, and two rules are stricter: the delivery choice is read
     * once and every address rule follows that reading, and the address is one line.
     */
    private Prepared prepare(PreviewRequest request, boolean judgeVatNumber, OrderPricing order) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null) {
            errors.put("request", "REQUIRED");
            throw new PublicQuoteValidationException(errors);
        }
        Language language = requireLanguage(request.language(), errors);
        Fulfillment fulfillment = order == null ? fulfillment(request.fulfillment(), errors)
                : orderFulfillment(request.fulfillment(), errors);
        StockLocation pickupLocation = fulfillment == Fulfillment.PICKUP
                ? selectedPickupLocation(request.pickupLocationId(), errors) : null;
        Destination destination = request.destination();
        String countryCode = destination == null || isBlank(destination.countryCode())
                ? fulfillment == Fulfillment.PICKUP ? "BE" : null
                : upper(destination.countryCode());
        Country country = countryCode == null ? null : countries.find(countryCode);
        if (country == null) errors.put("destination.countryCode", "UNSUPPORTED");
        if (fulfillment == Fulfillment.DELIVERY
                && (destination == null || isBlank(destination.postalCode()))) {
            errors.put("destination.postalCode", "REQUIRED");
        }
        checkSingleLine(destination == null ? null : destination.postalCode(), 24,
                "destination.postalCode", errors);
        checkSingleLine(destination == null ? null : destination.city(), 100,
                "destination.city", errors);
        if (order == null) {
            checkLength(destination == null ? null : destination.address(), 200,
                    "destination.address", errors);
        } else {
            /* A line break here would end up in the packing slip and in the mails. */
            checkSingleLine(destination == null ? null : destination.address(), 200,
                    "destination.address", errors);
            if (fulfillment == Fulfillment.DELIVERY) {
                if (destination == null || isBlank(destination.address())) {
                    errors.put("destination.address", "REQUIRED");
                }
                if (destination == null || isBlank(destination.city())) {
                    errors.put("destination.city", "REQUIRED");
                }
            }
        }
        if (judgeVatNumber) validateVat(request.vatNumber(), errors);

        List<ItemRequest> requested = request.items();
        if (requested == null || requested.isEmpty()) errors.put("items", "REQUIRED");
        if (requested != null && requested.size() > MAX_LINES) errors.put("items", "TOO_MANY");
        Map<Long, Product> publicProducts = products.websiteOrderableProducts().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        List<PreparedItem> preparedItems = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int totalCartons = 0;
        if (requested != null && requested.size() <= MAX_LINES) {
            for (int index = 0; index < requested.size(); index++) {
                ItemRequest item = requested.get(index);
                String path = "items[" + index + "]";
                if (item == null || item.productId() == null || item.productId() <= 0) {
                    errors.put(path + ".productId", "REQUIRED");
                    continue;
                }
                if (!seen.add(item.productId())) {
                    errors.put(path + ".productId", "DUPLICATE");
                    continue;
                }
                Product product = publicProducts.get(item.productId());
                if (product == null) {
                    errors.put(path + ".productId", "NOT_ORDERABLE");
                    continue;
                }
                int cartonCount = item.cartons() == null ? 0 : item.cartons();
                if (cartonCount < 1 || cartonCount > MAX_CARTONS_PER_LINE) {
                    errors.put(path + ".cartons", "OUT_OF_RANGE");
                    continue;
                }
                int perCarton = piecesPerCarton(product);
                long quantity = (long) cartonCount * perCarton;
                if (quantity > MAX_PIECES_PER_LINE) {
                    errors.put(path + ".cartons", "TOO_LARGE");
                    continue;
                }
                totalCartons += cartonCount;
                preparedItems.add(new PreparedItem(product, cartonCount, (int) quantity, perCarton));
            }
        }
        if (totalCartons > MAX_TOTAL_CARTONS) errors.put("items", "TOO_MANY_CARTONS");
        if (!errors.isEmpty()) throw new PublicQuoteValidationException(errors);

        /* An order is priced on the customer record, delivered where it was ordered: exactly
           what the sales core does with the stored order and its delivery row. */
        Customer estimateCustomer = order != null
                ? fulfillment == Fulfillment.DELIVERY && destination != null
                        ? order.customer.withDeliveryAddress(clean(destination.address()),
                                clean(destination.postalCode()), clean(destination.city()))
                        : order.customer
                : new Customer(null, "Website preview", null, null, null,
                normalizedVat(request.vatNumber()), country.code(), language,
                destination == null ? null : clean(destination.address()),
                destination == null ? null : clean(destination.postalCode()),
                destination == null ? null : clean(destination.city()), null, null, null, null);
        Carrier carrier = fulfillment == Fulfillment.DELIVERY
                ? currentCarrier(country.code()).orElse(null) : null;
        boolean countryTariff = country.freightPerPallet() != null
                && country.minFreight() != null && country.handling() != null
                && (country.freightPerPallet().signum() > 0
                    || country.minFreight().signum() > 0
                    || country.handling().signum() > 0);
        FreightPricingStrategy strategy = fulfillment == Fulfillment.PICKUP
                ? FreightPricingStrategy.PICKUP
                : carrier != null ? FreightPricingStrategy.CARRIER
                : FreightPricingStrategy.COUNTRY_PALLET;
        FreightState freightState = fulfillment == Fulfillment.DELIVERY
                && carrier == null && !countryTariff ? FreightState.TE_BEPALEN : FreightState.BEREKEND;
        List<SalesOrderLine> lines = preparedItems.stream()
                .map(item -> new SalesOrderLine(null, item.product.id(), item.quantityPieces,
                        order == null ? null : order.keptUnitPrices.get(item.product.id()),
                        null, null))
                .toList();
        SalesOrder estimate = draft(country.code(), carrier, strategy, fulfillment, lines,
                estimateCustomer, freightState);
        Map<Long, Product> byId = preparedItems.stream()
                .map(item -> item.product).collect(Collectors.toMap(Product::id, Function.identity()));
        PricedOrder priced = pricing.price(estimate, byId, new SalesPricingCalculator.Context(
                country, estimateCustomer, settings.pallet(), tiers.list(TierScope.LINE),
                tiers.list(TierScope.ORDER),
                vat.determine(country, order == null ? estimateCustomer : order.customer), carrier));
        boolean hasAllCartonData = preparedItems.stream()
                .allMatch(item -> item.piecesPerCarton > 0);
        boolean shippingAvailable = fulfillment == Fulfillment.PICKUP
                || freightState != FreightState.TE_BEPALEN
                && hasAllCartonData
                && priced.validation().freightPricingIssue() == null
                && priced.validation().productsWithoutCartonDimensions().isEmpty()
                && priced.validation().productsWithoutPalletFit().isEmpty()
                && priced.totals().shippingTotal().signum() > 0;
        return new Prepared(language, fulfillment, country, carrier, strategy,
                pickupLocation, preparedItems, priced, shippingAvailable);
    }

    private EstimateResponse toResponse(Prepared prepared) {
        Map<Long, PreparedItem> requested = prepared.items.stream()
                .collect(Collectors.toMap(item -> item.product.id(), Function.identity()));
        boolean pricesComplete = prepared.priced.lines().size() == prepared.items.size()
                && prepared.items.stream().allMatch(item -> item.piecesPerCarton > 0)
                && prepared.priced.lines().stream().allMatch(line -> line.unitPrice().signum() > 0);
        List<LineEstimate> lines = prepared.priced.lines().stream().map(line -> {
            PreparedItem item = requested.get(line.productId());
            boolean available = line.unitPrice().signum() > 0;
            boolean lineCanTotal = available && item.piecesPerCarton > 0;
            return new LineEstimate(line.productId(), line.sku(), item.cartons,
                    line.quantity(), item.piecesPerCarton,
                    available ? line.unitPrice() : null,
                    lineCanTotal ? line.discountPct() : null,
                    lineCanTotal ? line.net() : null, available);
        }).toList();
        PricedOrder.Totals totals = prepared.priced.totals();
        String shippingStatus = prepared.fulfillment == Fulfillment.PICKUP
                ? "PICKUP" : prepared.shippingAvailable ? "CALCULATED" : "TO_CONFIRM";
        String source = prepared.fulfillment == Fulfillment.PICKUP ? "PICKUP"
                : !prepared.shippingAvailable ? null
                : prepared.carrier == null ? "COUNTRY_TARIFF" : "CARRIER_TARIFF";
        boolean pickup = prepared.fulfillment == Fulfillment.PICKUP;
        BigDecimal freightNet = pickup ? decimalZero() : prepared.shippingAvailable ? totals.freight() : null;
        BigDecimal handlingNet = pickup ? decimalZero() : prepared.shippingAvailable ? totals.handling() : null;
        BigDecimal shippingNet = pickup ? decimalZero() : prepared.shippingAvailable ? totals.shippingTotal() : null;
        ShippingEstimate shipping = new ShippingEstimate(shippingStatus, source,
                freightNet, handlingNet, shippingNet,
                totals.palletsStrict(), totals.cartons());
        boolean complete = pricesComplete && prepared.shippingAvailable;
        TotalsEstimate publicTotals = new TotalsEstimate(
                pricesComplete ? totals.gross() : null,
                pricesComplete ? totals.lineDiscountTotal() : null,
                pricesComplete ? totals.subtotal() : null,
                pricesComplete ? totals.orderDiscountPercent() : null,
                pricesComplete ? totals.orderDiscountAmount() : null,
                pricesComplete ? totals.goodsTotal() : null,
                prepared.shippingAvailable ? totals.shippingTotal() : null,
                complete ? totals.total() : null,
                totals.vatRatePct(), complete ? totals.vatAmount() : null,
                complete ? totals.totalInclVat() : null,
                totals.vatTreatment().name(), true);
        List<String> messages = new ArrayList<>();
        if (!pricesComplete) messages.add("PRICE_TO_CONFIRM");
        if (!prepared.shippingAvailable) messages.add("SHIPPING_TO_CONFIRM");
        if (prepared.items.stream().anyMatch(item -> item.piecesPerCarton <= 0)
                || !prepared.priced.validation().productsWithoutCartonDimensions().isEmpty()) {
            messages.add("CARTON_DATA_TO_CONFIRM");
        }
        if (!prepared.priced.validation().productsWithoutPalletFit().isEmpty()) {
            messages.add("PALLET_FIT_TO_CONFIRM");
        }
        if (!prepared.priced.validation().meetsMinimum()) messages.add("MINIMUM_NOT_MET");
        ValidationSummary validation = new ValidationSummary(true, !messages.isEmpty(),
                prepared.priced.validation().meetsMinimum(),
                prepared.priced.validation().minOrderValue(),
                prepared.priced.validation().shortfall(), List.copyOf(messages));
        return new EstimateResponse("EUR", "NET_EXCL_VAT", prepared.fulfillment.name(),
                prepared.pickupLocation == null ? null : publicPickupLocation(prepared.pickupLocation),
                "ESTIMATE_NOT_BINDING", "FINAL_QUOTE_FOLLOWS", lines, shipping,
                publicTotals, validation);
    }

    private StockLocation selectedPickupLocation(Long requestedId,
                                                 Map<String, String> errors) {
        List<StockLocation> available = stock.publicPickupLocations();
        if (requestedId == null) {
            /* One configured location keeps older clients working without making
               an ambiguous choice when the administrator exposes several. */
            if (available.size() == 1) return available.getFirst();
            errors.put("pickupLocationId", available.isEmpty() ? "UNAVAILABLE" : "REQUIRED");
            return null;
        }
        return available.stream()
                .filter(location -> Objects.equals(location.id(), requestedId))
                .findFirst()
                .orElseGet(() -> {
                    errors.put("pickupLocationId", "UNAVAILABLE");
                    return null;
                });
    }

    private static PickupLocation publicPickupLocation(StockLocation location) {
        return new PickupLocation(location.id(), location.publicPickupLabel(),
                location.publicPickupAddress(), location.publicPickupInstructions(),
                location.publicPickupPosition());
    }

    private static PickupLocationSnapshot pickupSnapshot(StockLocation location) {
        if (location == null) return null;
        return new PickupLocationSnapshot(location.id(), location.publicPickupLabel(),
                location.publicPickupAddress(), location.publicPickupInstructions());
    }

    private static BigDecimal decimalZero() {
        return new BigDecimal("0.00");
    }

    private void validateContact(SubmitRequest request, Map<String, String> errors) {
        if (request == null) {
            errors.put("request", "REQUIRED");
            return;
        }
        requiredSingleLine(request.companyName(), 160, "companyName", errors);
        requiredSingleLine(request.companyCountryCode(), 2, "companyCountryCode", errors);
        if (!isBlank(request.companyCountryCode())
                && countries.find(upper(request.companyCountryCode())) == null) {
            errors.put("companyCountryCode", "UNSUPPORTED");
        }
        requiredSingleLine(request.contactName(), 120, "contactName", errors);
        requiredSingleLine(request.email(), 254, "email", errors);
        if (!isBlank(request.email()) && !EMAIL.matcher(request.email().trim()).matches()) {
            errors.put("email", "INVALID");
        }
        validateRequestDetails(request, errors);
    }

    /** What every request must carry, whoever sends it: phone, notes, consent and a delivery address. */
    private static void validateRequestDetails(SubmitRequest request, Map<String, String> errors) {
        checkSingleLine(request.phone(), 50, "phone", errors);
        checkLength(request.notes(), 2000, "notes", errors);
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            errors.put("privacyAccepted", "REQUIRED");
        }
        if ("DELIVERY".equalsIgnoreCase(request.fulfillment())) {
            Destination destination = request.destination();
            required(destination == null ? null : destination.address(), 200,
                    "destination.address", errors);
            required(destination == null ? null : destination.city(), 100,
                    "destination.city", errors);
        }
    }

    /**
     * A VAT number is not judged: a number that does not fit the country or
     * the usual shape still goes through, and we check it by hand afterwards.
     * Only nonsense is refused - control characters, far too long, or too
     * short to be any number at all. Whether it may be blank is decided by
     * the caller: the preview tolerates it, the submission requires it.
     */
    private static void validateVat(String value, Map<String, String> errors) {
        if (isBlank(value)) return;
        String compact = value.replaceAll("[\\s.\\-]", "");
        if (value.length() > 32 || containsControl(value) || compact.length() < 4) {
            errors.put("vatNumber", "INVALID");
        }
    }

    private Optional<Carrier> currentCarrier(String countryCode) {
        LocalDate today = LocalDate.now();
        return carriers.findAll().stream()
                .filter(Carrier::active)
                .filter(carrier -> carrier.validUntil() == null || !carrier.validUntil().isBefore(today))
                .filter(carrier -> carrier.lane(countryCode) != null)
                .findFirst();
    }

    private SalesOrder draft(String countryCode, Carrier carrier,
                             FreightPricingStrategy strategy, Fulfillment fulfillment,
                             List<SalesOrderLine> lines, Customer customer,
                             FreightState freightState) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(null, "PUBLIC-PREVIEW", null, countryCode,
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT,
                fulfillment == Fulfillment.PICKUP ? "EXW" : "DAP", null, null,
                MarkupMode.PRODUCT, settings.defaultMarkupPct(), null, null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, freightState, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                strategy, null, carrier == null ? null : carrier.id(), null,
                DocumentType.OFFERTE, null, null, null, null, lines, List.of());
    }

    private static int piecesPerCarton(Product product) {
        Carton carton = product.carton();
        /* Carton content determines the commercial quantity. Dimensions only
           determine freight and pallet fit, and are deliberately validated by
           SalesPricingCalculator. A known 12 pieces/carton must therefore
           still produce 3 x 12 = 36 pieces when logistics dimensions need
           review. Never invent a one-piece carton when the content is absent. */
        if (carton == null || carton.piecesPerCarton() <= 0) return 0;

        /* Old product rows defaulted to Carton.empty(): one piece with no
           measurements, weight or capacity. That is a persistence default,
           not evidence that the commercial box really contains one piece.
           Treat that exact shape as unknown so a public request cannot turn
           four requested boxes into four priced pieces by accident. */
        if (carton.piecesPerCarton() == 1 && hasNoCartonEvidence(carton)) return 0;
        return carton.piecesPerCarton();
    }

    private static boolean hasNoCartonEvidence(Carton carton) {
        var dimensions = carton.dimensions();
        boolean noDimensions = dimensions == null
                || nonPositive(dimensions.lengthCm())
                && nonPositive(dimensions.widthCm())
                && nonPositive(dimensions.heightCm());
        return noDimensions
                && nonPositive(carton.weightKg())
                && (carton.piecesPerHc() == null || carton.piecesPerHc() <= 0);
    }

    private static boolean nonPositive(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static Language requireLanguage(String value, Map<String, String> errors) {
        try {
            return Language.requireSupported(value, Language.EN);
        } catch (IllegalArgumentException exception) {
            errors.put("language", "UNSUPPORTED");
            return Language.EN;
        }
    }

    private static Fulfillment fulfillment(String value, Map<String, String> errors) {
        try {
            return isBlank(value) ? Fulfillment.DELIVERY
                    : Fulfillment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.put("fulfillment", "UNSUPPORTED");
            return Fulfillment.DELIVERY;
        }
    }

    /** Null or blank is a delivery; anything else must be one of the two, however it is padded. */
    private static Fulfillment orderFulfillment(String value, Map<String, String> errors) {
        try {
            return isBlank(value) ? Fulfillment.DELIVERY
                    : Fulfillment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.put("fulfillment", "INVALID");
            return Fulfillment.DELIVERY;
        }
    }

    private static void required(String value, int max, String path, Map<String, String> errors) {
        if (isBlank(value)) errors.put(path, "REQUIRED");
        else checkLength(value, max, path, errors);
    }

    private static void requiredSingleLine(String value, int max, String path,
                                           Map<String, String> errors) {
        if (isBlank(value)) errors.put(path, "REQUIRED");
        else checkSingleLine(value, max, path, errors);
    }

    private static void checkSingleLine(String value, int max, String path,
                                        Map<String, String> errors) {
        checkLength(value, max, path, errors);
        if (value != null && containsControl(value)) errors.put(path, "INVALID");
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static void checkLength(String value, int max, String path,
                                    Map<String, String> errors) {
        if (value != null && value.length() > max) errors.put(path, "TOO_LONG");
    }

    private static String normalizedVat(String value) {
        return isBlank(value) ? null
                : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String upper(String value) {
        return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null) return null;
        return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
    }

    private static String withoutBrackets(String value) {
        return value == null ? "" : value.replace("[", "").replace("]", "").strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum Fulfillment { DELIVERY, PICKUP }

    /** The customer record an order is priced on, and the unit prices its stored lines keep. */
    private record OrderPricing(Customer customer, Map<Long, BigDecimal> keptUnitPrices) {}

    /**
     * What an order request says beside its document. {@code fulfillment} is DELIVERY or
     * PICKUP; the address is null for a collection. {@code piecesPerCarton} is 0 where the
     * carton content is unknown. {@code listPrices} holds today's public price of the products
     * whose price is kept, where there is one.
     */
    public record OrderFacts(String language, String fulfillment, String address, String postalCode,
                             String city, Long pickupLocationId, String pickupLabel,
                             String pickupAddress, String contactName, String phone,
                             Map<Long, Integer> piecesPerCarton, Map<Long, Integer> cartons,
                             Map<Long, String> skus, Map<Long, BigDecimal> listPrices) {}

    private record PreparedItem(Product product, int cartons, int quantityPieces,
                                int piecesPerCarton) {}

    private record Stored(SalesOrder order, SubmissionResponse response) {}

    private record Prepared(Language language, Fulfillment fulfillment, Country country,
                            Carrier carrier, FreightPricingStrategy strategy,
                            StockLocation pickupLocation,
                            List<PreparedItem> items, PricedOrder priced,
                            boolean shippingAvailable) {}
}
