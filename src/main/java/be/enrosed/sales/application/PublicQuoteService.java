package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import be.enrosed.shared.BusinessDays;
import be.enrosed.shipping.application.CarrierRepository;
import be.enrosed.shipping.domain.Carrier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.transaction.Transactional;

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
    private static final Pattern VAT = Pattern.compile("^[A-Z]{2}[0-9A-Z]{2,12}$");

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
        requireLanguage(languageCode, errors);
        if (!errors.isEmpty()) throw new PublicQuoteValidationException(errors);
        SalesOrder priceTemplate = draft(null, null, null, Fulfillment.DELIVERY,
                List.of(), null, FreightState.TE_BEPALEN);
        List<ProductPrice> publicPrices = products.websiteOrderableProducts().stream()
                .sorted(Comparator.comparing(Product::sku, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(product -> {
                    BigDecimal amount = pricing.unitPriceFor(product, priceTemplate, null);
                    boolean available = amount != null && amount.signum() > 0;
                    return new ProductPrice(product.id(), available ? amount : null, available,
                            piecesPerCarton(product));
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

        SalesOrder created = salesOrders.createWebsiteRequest(buyer.id(), prepared.country.code(),
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
                        : "\n" + missingCartons);
        SalesOrder changes = new SalesOrder(
                created.id(), created.number(), buyer.id(), prepared.country.code(),
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
        websiteQuoteReady.fire(new WebsiteQuotePushNotifier.Ready(
                created.id(), created.number()));
        return new SubmissionResponse(created.number(), "RECEIVED",
                "REQUEST_RECEIVED_NOT_BINDING", "FINAL_QUOTE_FOLLOWS", toResponse(prepared));
    }

    private Prepared validateAndPrepareSubmission(SubmitRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateContact(request, errors);
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
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null) {
            errors.put("request", "REQUIRED");
            throw new PublicQuoteValidationException(errors);
        }
        Language language = requireLanguage(request.language(), errors);
        Fulfillment fulfillment = fulfillment(request.fulfillment(), errors);
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
        checkLength(destination == null ? null : destination.address(), 200,
                "destination.address", errors);
        validateVat(request.vatNumber(), errors);

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

        Customer estimateCustomer = new Customer(null, "Website preview", null, null, null,
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
                        null, null, null))
                .toList();
        SalesOrder order = draft(country.code(), carrier, strategy, fulfillment, lines,
                estimateCustomer, freightState);
        Map<Long, Product> byId = preparedItems.stream()
                .map(item -> item.product).collect(Collectors.toMap(Product::id, Function.identity()));
        PricedOrder priced = pricing.price(order, byId, new SalesPricingCalculator.Context(
                country, estimateCustomer, settings.pallet(), tiers.list(TierScope.LINE),
                tiers.list(TierScope.ORDER), vat.determine(country, estimateCustomer), carrier));
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

    private static void validateVat(String value, Map<String, String> errors) {
        if (isBlank(value)) return;
        if (value.length() > 32 || containsControl(value)) {
            errors.put("vatNumber", "INVALID");
            return;
        }
        String normalized = normalizedVat(value);
        if (normalized.length() > 14 || !VAT.matcher(normalized).matches()) {
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum Fulfillment { DELIVERY, PICKUP }

    private record PreparedItem(Product product, int cartons, int quantityPieces,
                                int piecesPerCarton) {}

    private record Prepared(Language language, Fulfillment fulfillment, Country country,
                            Carrier carrier, FreightPricingStrategy strategy,
                            StockLocation pickupLocation,
                            List<PreparedItem> items, PricedOrder priced,
                            boolean shippingAvailable) {}
}
