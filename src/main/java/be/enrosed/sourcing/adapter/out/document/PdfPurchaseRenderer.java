package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.catalog.adapter.out.document.PdfImageEncoder;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.ProductSupplierAgreementPhotoService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.Language;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.sourcing.application.CurrencyConverter;
import be.enrosed.sourcing.application.LandedCostCalculator;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseCostLabels;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.awt.Color;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Two explicit paper jobs for one purchase dossier.
 *
 * <p>Both layouts can render the same supplier-facing purchase order. The
 * landscape layout can additionally carry Enrosed's internal summary when
 * explicitly requested. The standard portrait layout can show one combined
 * Enrosed cost per product line without exposing its individual freight,
 * duty or handling components. Both orientations use the ordered quantity
 * and agreed purchase-line price/currency.</p>
 */
@ApplicationScoped
public class PdfPurchaseRenderer {

    private static final Logger LOG = Logger.getLogger(PdfPurchaseRenderer.class);

    /** Two deliberately different paper jobs, not one table rotated ninety degrees. */
    public enum Layout {
        /** Wide purchase order, optionally followed by Enrosed's internal summary. */
        LANDSCAPE,
        /** Compact purchase order, optionally with one combined Enrosed cost column. */
        PORTRAIT;

        public static Layout parse(String value) {
            if (value == null || value.isBlank()) return LANDSCAPE;
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("Onbekende PDF-layout. Kies PORTRAIT of LANDSCAPE");
            }
        }
    }

    /**
     * Who the generated document is meant for.
     *
     * <p>{@link #INTERNAL} and {@link #STANDARD} deliberately retain the
     * historical {@code showRevenue + layout} behaviour. {@link #SUPPLIER}
     * is a separate, minimal contract and can only use the portrait sheet.</p>
     */
    public enum Audience {
        INTERNAL,
        STANDARD,
        SUPPLIER;

        public static Audience parse(String value) {
            if (value == null || value.isBlank()) return STANDARD;
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException(
                        "Onbekend PDF-publiek. Kies INTERNAL, STANDARD of SUPPLIER");
            }
        }

        public void validate(Layout layout) {
            if (this == SUPPLIER && layout != Layout.PORTRAIT) {
                throw new BadRequestException(
                        "De leveranciers-PDF is alleen beschikbaar in PORTRAIT-layout");
            }
        }
    }

    private final Template landscapeTemplate;
    private final Template portraitTemplate;
    private final Brand brand;
    private final CompanyProfileService company;
    private final PdfFonts fonts;
    private final ProductService products;
    private final ProductSupplierAgreementPhotoService supplierAgreementPhotos;
    private final PdfImageEncoder imageEncoder;
    private final CurrencyConverter currencies;
    private final LandedCostCalculator landedCosts;

    /** Optional in pure tests; production resolves names such as Zaltbommel. */
    @Inject
    Instance<StockService> locations;

    public PdfPurchaseRenderer(@Location("purchase.html") Template landscapeTemplate,
                               @Location("purchase-portrait.html") Template portraitTemplate,
                               Brand brand, CompanyProfileService company, PdfFonts fonts,
                               ProductService products, PdfImageEncoder imageEncoder,
                               CurrencyConverter currencies, LandedCostCalculator landedCosts,
                               ProductSupplierAgreementPhotoService supplierAgreementPhotos) {
        this.landscapeTemplate = landscapeTemplate;
        this.portraitTemplate = portraitTemplate;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
        this.products = products;
        this.supplierAgreementPhotos = supplierAgreementPhotos;
        this.imageEncoder = imageEncoder;
        this.currencies = currencies;
        this.landedCosts = landedCosts;
    }

    public record Document(String filename, byte[] content, String contentType) {}

    /** One shipping moment on the sheet: what happened (or is expected) when. */
    public record Moment(String label, String date) {}

    /** One instalment of the payment plan, with whether its moment has come. */
    public record ScheduleRow(String label, String amount, boolean done) {}

    /** One registered payment, formatted for paper. */
    public record PaymentRow(String date, String label, String payee,
                             String amount, String original) {}

    /** The supplier/logistics/Enrosed split plus where the payments stand. */
    public record PayableView(String supplier, String logistics, String enrosed,
                              boolean freightInSupplierPrice, boolean ddp,
                              String paidSupplier, String paidLogistics,
                              String openSupplier, boolean overpaid) {}

    /**
     * Optional fields on the normal portrait export. Supplier and landscape
     * documents intentionally retain their fixed historical contracts.
     */
    public record PdfOptions(boolean showSupplier, boolean showPrices, boolean showEur,
                             boolean eurOnly, boolean showFreight, boolean includeFreight,
                             boolean includeEnrosedCost, boolean includeUnitPrice) {
        /** Compatibility for callers written before the unit-price switch. */
        public PdfOptions(boolean showSupplier, boolean showPrices, boolean showEur,
                          boolean eurOnly, boolean showFreight, boolean includeFreight,
                          boolean includeEnrosedCost) {
            this(showSupplier, showPrices, showEur, eurOnly, showFreight, includeFreight,
                    includeEnrosedCost, true);
        }

        /** Compatibility for callers written before the EUR-only option. */
        public PdfOptions(boolean showSupplier, boolean showPrices, boolean showEur,
                          boolean showFreight, boolean includeFreight,
                          boolean includeEnrosedCost) {
            this(showSupplier, showPrices, showEur, false,
                    showFreight, includeFreight, includeEnrosedCost);
        }

        /** Compatibility for callers written before the combined-cost option. */
        public PdfOptions(boolean showSupplier, boolean showPrices, boolean showEur,
                          boolean showFreight, boolean includeFreight) {
            this(showSupplier, showPrices, showEur, false,
                    showFreight, includeFreight, false);
        }

        public static PdfOptions defaults() {
            return new PdfOptions(true, true, false, false, false, false, false, true);
        }

        PdfOptions normalized(Layout layout, Audience audience) {
            if (layout != Layout.PORTRAIT || audience != Audience.STANDARD) return defaults();
            boolean prices = showPrices;
            boolean unitPrice = prices && includeUnitPrice;
            /* Freight components are never printed separately anymore. The
               legacy flags stay in the API contract but normalize off. */
            boolean onlyEur = eurOnly && prices;
            return new PdfOptions(showSupplier, prices, showEur && prices && !onlyEur,
                    onlyEur, false, false, includeEnrosedCost, unitPrice);
        }
    }

    /** One compact, explicitly labelled product fact in a purchase-order row. */
    public record ProductSpec(String label, String value) {}

    /**
     * One PDF line enriched with print-safe catalogue data.
     *
     * <p>The thumbnail is embedded in the server-rendered HTML as a data URI.
     * No browser token, private API URL or external image host is written into
     * the PDF. Purchase price and currency come from the line override first,
     * and only then from the current product master data.</p>
     */
    public record LineView(
            Long productId, String sku, String productName, String photoDataUri,
            List<ProductSpec> productSpecs, Integer piecesPerCarton,
            int quantity, int cartons, int purchaseQuantity, int purchaseCartons, BigDecimal cbm,
            BigDecimal goodsUsd, BigDecimal goodsEur, BigDecimal originEur,
            BigDecimal freightEur, BigDecimal customsValueEur,
            BigDecimal dutyRatePct, BigDecimal dutyEur,
            BigDecimal destinationEur, BigDecimal extraRevenueEur,
            BigDecimal totalEur, BigDecimal landedUnitEur, BigDecimal totalDeliveryCostEur,
            BigDecimal purchaseUnitPrice, BigDecimal purchaseLineTotal,
            BigDecimal purchaseUnitEur, BigDecimal purchaseLineTotalEur,
            Currency purchaseCurrency, String priceBasis, boolean purchasePriceAvailable,
            boolean eurPriceAvailable, boolean eurEquivalentAvailable) {}

    /**
     * Deliberately small supplier contract. No landed costs, revenue,
     * payment figures or calculated line totals can enter this view.
     */
    public record SupplierLineView(
            Long productId, String sku, String productName, String photoDataUri,
            List<ProductSpec> productSpecs, Integer piecesPerCarton, String ean,
            List<String> supplierNoteChunks,
            List<SupplierAgreementPhotoView> agreementPhotos,
            int orderedQuantity, int orderedCartons,
            String cartonCbm, BigDecimal agreedUnitPrice, Currency currency,
            String priceBasis, boolean priceAvailable) {}

    /** One private, supplier-facing visual instruction embedded into the PDF. */
    public record SupplierAgreementPhotoView(
            int position, String caption, String dataUri) {}

    record AgreedUnitPrice(BigDecimal amount, Currency currency) {
        boolean available() {
            return amount != null && currency != null;
        }
    }

    /** Only order fields that may enter the external supplier template context. */
    public record SupplierOrderView(
            String number, ContainerType containerType, LocalDate expectedArrival,
            String trackingReference) {
        static SupplierOrderView from(PurchaseOrder order) {
            return new SupplierOrderView(order.number(), order.containerType(),
                    order.expectedArrival(), order.trackingReference());
        }
    }

    /** One portrait-order total; separate currencies are never silently combined. */
    public record PurchaseTotal(String currency, String amount) {}

    private record Prepared(List<LineView> lines, List<PurchaseTotal> purchaseTotals,
                            BigDecimal purchaseTotalEur, int missingPurchasePrices,
                            int missingEurPrices, int purchasePieces,
                            int purchaseCartons, BigDecimal totalDeliveryCostEur) {}

    private record SupplierPrepared(List<SupplierLineView> lines) {}

    /**
     * @param showRevenue shows the desired extra revenue as its own line.
     *                    Off, it stays in the total but out of sight.
     */
    public Document render(PurchaseOrder order, LandedCost costing, Supplier supplier,
                           boolean showRevenue, List<PurchasePayment> payments,
                           PurchaseOrderService.Payable payable) {
        return render(order, costing, supplier, showRevenue, payments, payable, Layout.LANDSCAPE);
    }

    /**
     * Builds either the wide internal calculation or the portrait read copy.
     * Portrait is always supplier-safe: a stray {@code showRevenue=true}
     * cannot make landed cost, margin or payment history appear on it.
     */
    public Document render(PurchaseOrder order, LandedCost costing, Supplier supplier,
                           boolean showRevenue, List<PurchasePayment> payments,
                           PurchaseOrderService.Payable payable, Layout requestedLayout) {
        return render(order, costing, supplier, showRevenue, payments, payable,
                requestedLayout, Audience.STANDARD);
    }

    /**
     * Renders an explicit audience. Supplier output uses a distinct minimal
     * data model even though it reuses the portrait paper design.
     */
    public Document render(PurchaseOrder order, LandedCost costing, Supplier supplier,
                           boolean showRevenue, List<PurchasePayment> payments,
                           PurchaseOrderService.Payable payable, Layout requestedLayout,
                           Audience requestedAudience) {
        return render(order, costing, supplier, showRevenue, payments, payable,
                requestedLayout, requestedAudience, PdfOptions.defaults());
    }

    /** Renders the optional normal-portrait fields without changing the other contracts. */
    public Document render(PurchaseOrder order, LandedCost costing, Supplier supplier,
                           boolean showRevenue, List<PurchasePayment> payments,
                           PurchaseOrderService.Payable payable, Layout requestedLayout,
                           Audience requestedAudience, PdfOptions requestedOptions) {
        Layout layout = requestedLayout == null ? Layout.LANDSCAPE : requestedLayout;
        Audience audience = requestedAudience == null ? Audience.STANDARD : requestedAudience;
        audience.validate(layout);
        PdfOptions options = (requestedOptions == null ? PdfOptions.defaults() : requestedOptions)
                .normalized(layout, audience);
        boolean supplierAudience = audience == Audience.SUPPLIER;
        boolean internal = !supplierAudience && layout == Layout.LANDSCAPE && showRevenue;
        PurchaseCostLabels costLabels = PurchaseCostLabels.forOrder(
                order, supplier, receivingLocationName(order));
        String notes = order.notes() == null || order.notes().isBlank()
                ? null : order.notes().strip();
        Template template = layout == Layout.PORTRAIT ? portraitTemplate : landscapeTemplate;
        TemplateInstance instance = template
                .data("order", supplierAudience ? SupplierOrderView.from(order) : order)
                .data("supplierName", supplier == null ? "-" : supplier.name())
                .data("supplierAddressLines", supplierAddress(supplier))
                .data("supplierContactLine", contactLine(supplier))
                .data("costLabels", costLabels)
                .data("orderDate", DocumentFormat.be(order.orderDate()))
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("portrait", layout == Layout.PORTRAIT)
                .data("supplierMode", supplierAudience)
                .data("showSupplier", options.showSupplier())
                .data("showPrices", options.showPrices())
                .data("showUnitPrice", options.showPrices() && options.includeUnitPrice())
                .data("showEur", options.showEur())
                .data("eurOnly", options.eurOnly())
                .data("supplierIncoterm", supplier == null ? null : supplier.incoterm())
                .data("showEnrosedCost", options.includeEnrosedCost());

        if (supplierAudience) {
            SupplierPrepared prepared = prepareSupplier(order, costing);
            instance.data("supplierLines", prepared.lines())
                    .data("supplierTradeTerm", supplierTradeTerm(prepared.lines()));
        } else {
            Prepared prepared = prepare(order, costing, options.includeEnrosedCost());
            instance.data("costing", costing)
                    .data("lines", prepared.lines())
                    .data("purchaseTotals", prepared.purchaseTotals())
                    .data("purchaseTotalEur", prepared.purchaseTotalEur())
                    .data("purchaseTotalEurAvailable", prepared.missingEurPrices() == 0)
                    .data("missingPurchasePrices", prepared.missingPurchasePrices())
                    .data("missingDisplayedPrices", options.eurOnly()
                            ? prepared.missingEurPrices() : prepared.missingPurchasePrices())
                    .data("purchasePieces", prepared.purchasePieces())
                    .data("purchaseCartons", prepared.purchaseCartons())
                    .data("totalDeliveryCostEur", prepared.totalDeliveryCostEur())
                    .data("statusLabel", statusLabel(order.status()))
                    .data("unifiedUsdToEur", sameRate(order))
                    .data("timeline", timeline(order))
                    .data("createdBy", internal ? order.createdBy() : null)
                    .data("paymentTermsLabel", order.paymentTerms().dutchLabel())
                    .data("schedule", schedule(order, payable))
                    .data("paymentRows", paymentRows(payments))
                    .data("payableView", payableView(payments, payable))
                    .data("notesText", internal ? notes : null)
                    .data("usdRateGoods", rate(order.usdToEurGoods()))
                    .data("usdRateTransport", rate(order.usdToEurTransport()))
                    .data("cnyRate", rate(order.cnyToUsd()))
                    .data("showRevenue", internal);
        }
        String html = instance.render();

        String suffix = supplierAudience
                ? "-supplier"
                : layout == Layout.PORTRAIT
                ? "-inkooporder-verticaal"
                : internal ? "-interne-calculatie" : "-inkooporder-horizontaal";
        return new Document(order.number() + suffix + ".pdf", fonts.render(html),
                "application/pdf");
    }

    private SupplierPrepared prepareSupplier(PurchaseOrder order, LandedCost costing) {
        Map<Long, Product> byId = products.list().stream()
                .filter(product -> product.id() != null)
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
        Map<Long, PurchaseOrderLine> orderLines = order.lines().stream()
                .filter(line -> line != null && line.productId() != null)
                .collect(Collectors.toMap(PurchaseOrderLine::productId, Function.identity(),
                        (left, right) -> left));
        Map<String, String> photoCache = new HashMap<>();
        Map<Long, List<SupplierAgreementPhotoView>> agreementPhotoCache = new HashMap<>();
        List<SupplierLineView> lines = new ArrayList<>();

        for (LandedCost.Line costingLine : costing.lines()) {
            Product product = byId.get(costingLine.productId());
            PurchaseOrderLine orderLine = orderLines.get(costingLine.productId());
            AgreedUnitPrice price = agreedUnitPrice(orderLine, product);
            int orderedQuantity = orderLine == null
                    ? Math.max(0, costingLine.quantity()) : orderLine.ordered();
            int orderedCartons = product == null || product.carton() == null
                    ? Math.max(0, costingLine.cartons())
                    : product.carton().cartonsFor(orderedQuantity);
            boolean matchingSupplier = product != null && order.supplierId() != null
                    && Objects.equals(product.supplierId(), order.supplierId());

            lines.add(new SupplierLineView(
                    costingLine.productId(), product == null ? null : product.sku(),
                    product == null ? costingLine.productName() : supplierProductName(product),
                    photo(product, photoCache, true), supplierProductSpecs(product),
                    piecesPerCarton(product), ean(product),
                    supplierNoteChunks(matchingSupplier ? product : null),
                    matchingSupplier
                            ? agreementPhotoCache.computeIfAbsent(costingLine.productId(),
                                    this::supplierAgreementPhotoViews)
                            : List.of(),
                    orderedQuantity, orderedCartons, cartonCbm(product),
                    price.amount(), price.currency(),
                    orderLine == null ? "EXW" : orderLine.priceBasis().name(),
                    price.available()));
        }
        return new SupplierPrepared(List.copyOf(lines));
    }

    private List<SupplierAgreementPhotoView> supplierAgreementPhotoViews(long productId) {
        List<SupplierAgreementPhotoView> views = new ArrayList<>();
        for (ProductSupplierAgreementPhotoService.AgreementPhoto photo
                : supplierAgreementPhotos.list(productId)) {
            String dataUri = null;
            try (InputStream data = supplierAgreementPhotos.open(productId, photo.id()).data()) {
                dataUri = imageEncoder.encodeContained(data.readAllBytes(), 900, 600, Color.WHITE);
            } catch (Exception exception) {
                LOG.warnf("Afspraakfoto %d kon niet in leveranciers-PDF: %s",
                        photo.id(), exception.getMessage());
            }
            if (dataUri != null && !dataUri.isBlank()) {
                views.add(new SupplierAgreementPhotoView(
                        photo.position() + 1, photo.caption(), dataUri));
            }
        }
        return List.copyOf(views);
    }

    private static String supplierTradeTerm(List<SupplierLineView> lines) {
        List<String> bases = lines.stream()
                .filter(SupplierLineView::priceAvailable)
                .map(SupplierLineView::priceBasis)
                .filter(PdfPurchaseRenderer::notBlank)
                .map(String::strip)
                .distinct()
                .toList();
        return bases.size() == 1 ? bases.getFirst() : "Per line";
    }

    private Prepared prepare(PurchaseOrder order, LandedCost costing,
                             boolean includeEnrosedCost) {
        Map<Long, Product> byId = products.list().stream()
                .filter(product -> product.id() != null)
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
        LandedCost orderedCosting = includeEnrosedCost
                ? landedCosts.calculateForOrderedQuantities(order, byId) : null;
        Map<Long, LandedCost.Line> orderedCostsByProduct = orderedCosting == null
                ? Map.of()
                : orderedCosting.lines().stream().collect(Collectors.toMap(
                        LandedCost.Line::productId, Function.identity(), (left, right) -> left));
        Map<Long, PurchaseOrderLine> orderLines = order.lines().stream()
                .filter(line -> line != null && line.productId() != null)
                .collect(Collectors.toMap(PurchaseOrderLine::productId, Function.identity(),
                        (left, right) -> left));
        Map<String, String> photoCache = new HashMap<>();
        Map<Currency, BigDecimal> totals = new EnumMap<>(Currency.class);
        List<LineView> lines = new ArrayList<>();
        int missingPrices = 0;
        int missingEurPrices = 0;
        int purchasePieces = 0;
        int purchaseCartonCount = 0;
        BigDecimal purchaseTotalEur = BigDecimal.ZERO;
        BigDecimal totalDeliveryCostEur = orderedCosting == null
                ? BigDecimal.ZERO : orderedCosting.totals().totalEur();

        for (LandedCost.Line costingLine : costing.lines()) {
            Product product = byId.get(costingLine.productId());
            PurchaseOrderLine orderLine = orderLines.get(costingLine.productId());
            AgreedUnitPrice price = agreedUnitPrice(orderLine, product);
            BigDecimal unitPrice = price.amount();
            Currency currency = price.currency();
            int purchaseQuantity = orderLine != null && orderLine.orderedQuantity() != null
                    ? orderLine.orderedQuantity() : costingLine.quantity();
            int purchaseCartons = product == null || product.carton() == null
                    ? costingLine.cartons() : product.carton().cartonsFor(purchaseQuantity);
            purchasePieces += purchaseQuantity;
            purchaseCartonCount += purchaseCartons;
            LandedCost.Line orderedCostLine = orderedCostsByProduct.get(costingLine.productId());
            boolean priceAvailable = price.available();
            BigDecimal lineTotal = priceAvailable
                    ? unitPrice.multiply(BigDecimal.valueOf(purchaseQuantity)) : null;
            BigDecimal unitEur = priceAvailable
                    ? purchasePriceEur(unitPrice, currency, order) : null;
            BigDecimal lineTotalEur = unitEur == null
                    ? null : unitEur.multiply(BigDecimal.valueOf(purchaseQuantity));
            if (priceAvailable) {
                totals.merge(currency, lineTotal, BigDecimal::add);
            } else {
                missingPrices++;
            }
            boolean eurPriceAvailable = unitEur != null && lineTotalEur != null;
            if (eurPriceAvailable) {
                purchaseTotalEur = purchaseTotalEur.add(lineTotalEur);
            } else {
                missingEurPrices++;
            }
            String priceBasis = orderLine == null
                    ? "EXW" : orderLine.priceBasis().name();
            lines.add(new LineView(
                    costingLine.productId(), product == null ? null : product.sku(),
                    costingLine.productName(), photo(product, photoCache),
                    productSpecs(product), piecesPerCarton(product),
                    costingLine.quantity(), costingLine.cartons(), purchaseQuantity, purchaseCartons,
                    costingLine.cbm(),
                    costingLine.goodsUsd(), costingLine.goodsEur(), costingLine.originEur(),
                    costingLine.freightEur(), costingLine.customsValueEur(),
                    costingLine.dutyRatePct(), costingLine.dutyEur(),
                    costingLine.destinationEur(), costingLine.extraRevenueEur(),
                    costingLine.totalEur(), costingLine.landedUnitEur(),
                    orderedCostLine == null ? null : orderedCostLine.totalEur(),
                    unitPrice, lineTotal, unitEur, lineTotalEur,
                    currency, priceBasis, priceAvailable,
                    eurPriceAvailable,
                    priceAvailable && currency != Currency.EUR && unitEur != null));
        }

        List<PurchaseTotal> purchaseTotals = new ArrayList<>();
        totals.forEach((currency, amount) -> purchaseTotals.add(
                new PurchaseTotal(currency.name(), DocumentFormat.money(amount))));
        return new Prepared(List.copyOf(lines), List.copyOf(purchaseTotals), purchaseTotalEur,
                missingPrices, missingEurPrices, purchasePieces,
                purchaseCartonCount, totalDeliveryCostEur);
    }

    /**
     * Converts the agreed purchase snapshot with the order's pinned goods
     * rate. It deliberately does not reuse landed-cost goodsEur: that figure
     * can contain extra unit costs and received rather than ordered quantity.
     */
    private BigDecimal purchasePriceEur(BigDecimal amount, Currency currency,
                                        PurchaseOrder order) {
        if (amount == null || currency == null) return null;
        if (currency == Currency.EUR) return amount;
        if (!positive(order.usdToEurGoods())) return null;
        if (currency == Currency.CNY && !positive(order.cnyToUsd())) return null;
        return currencies.toEur(amount, currency, order.cnyToUsd(), order.usdToEurGoods());
    }

    private String receivingLocationName(PurchaseOrder order) {
        if (order == null || order.receivingLocationId() == null
                || locations == null || !locations.isResolvable()) return null;
        try {
            String name = locations.get().location(order.receivingLocationId()).name();
            return name == null || name.isBlank() ? null : name.strip();
        } catch (RuntimeException unavailable) {
            LOG.warnf("Ontvangstlocatie %s kon niet in inkoop-PDF worden benoemd: %s",
                    order.receivingLocationId(), unavailable.getMessage());
            return null;
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * A purchase amount and its currency are one value. An explicit line pair
     * wins; a wholly empty legacy line falls back to the complete product pair.
     * A partial pair is treated as unavailable instead of inventing a mixture.
     */
    static AgreedUnitPrice agreedUnitPrice(PurchaseOrderLine line, Product product) {
        if (line != null) {
            boolean hasAmount = line.exwPrice() != null;
            boolean hasCurrency = line.exwCurrency() != null;
            if (hasAmount && hasCurrency) {
                return new AgreedUnitPrice(line.exwPrice(), line.exwCurrency());
            }
            if (hasAmount || hasCurrency) {
                return new AgreedUnitPrice(null, null);
            }
        }
        if (product != null && product.exwPrice() != null && product.exwCurrency() != null) {
            return new AgreedUnitPrice(product.exwPrice(), product.exwCurrency());
        }
        return new AgreedUnitPrice(null, null);
    }

    private String photo(Product product, Map<String, String> cache) {
        return photo(product, cache, false);
    }

    private String photo(Product product, Map<String, String> cache, boolean contained) {
        Photo photo = product == null ? null : product.primaryPhoto();
        if (photo == null || photo.storageKey() == null || photo.storageKey().isBlank()) return null;
        String encoded = cache.computeIfAbsent(photo.storageKey(), storageKey -> {
            try (InputStream data = products.photoData(storageKey)) {
                byte[] bytes = data.readAllBytes();
                String value = contained
                        ? imageEncoder.encodeContained(bytes, 360, 360, Color.WHITE)
                        : imageEncoder.encode(bytes, 320);
                return value == null ? "" : value;
            } catch (Exception exception) {
                LOG.warnf("Productfoto %s kon niet in inkoop-PDF: %s",
                        storageKey, exception.getMessage());
                return "";
            }
        });
        return encoded.isBlank() ? null : encoded;
    }

    private static List<String> supplierAddress(Supplier supplier) {
        return supplier == null ? List.of() : supplier.documentAddressLines();
    }

    /** Compact, factual product and packing detail; no long marketing copy. */
    static List<ProductSpec> productSpecs(Product product) {
        return productSpecs(product, false);
    }

    /** English product and packing detail for the supplier agreement. */
    static List<ProductSpec> supplierProductSpecs(Product product) {
        return productSpecs(product, true);
    }

    private static List<ProductSpec> productSpecs(Product product, boolean supplierFacing) {
        if (product == null) return List.of();
        List<ProductSpec> details = new ArrayList<>();
        String variant = supplierFacing ? product.variantSizeIn(Language.EN) : product.variantSize();
        if (notBlank(variant)) details.add(new ProductSpec("Variant", variant.strip()));

        Dimensions dimensions = product.dimensions();
        String productDimensions = dimensionLabel(dimensions, supplierFacing);
        String pieceBarcode = ean(product);
        if (productDimensions != null || pieceBarcode != null) {
            details.add(new ProductSpec("Product", detailValue(
                    productDimensions, null, pieceBarcode, supplierFacing, null)));
        }

        Packaging packaging = product.packaging();
        if (packaging != null && packaging.isPresent()) {
            Integer packagedPieces = packaging.unitPieces() > 1
                    ? packaging.unitPieces() : null;
            String packagingLabel = supplierFacing
                    ? switch (packaging.kind()) {
                        case GIFT_BOX -> "Gift packaging";
                        case DISPLAY -> "Display";
                        case NONE -> "Packaging";
                    }
                    : packaging.kind().dutchLabel();
            String packagingDimensions = dimensionLabel(packaging.dimensions(), supplierFacing);
            if (packagingDimensions != null || packagedPieces != null
                    || notBlank(packaging.barcode())) {
                details.add(new ProductSpec(packagingLabel, detailValue(
                        packagingDimensions, packagedPieces, packaging.barcode(), supplierFacing,
                        packaging.kind() == be.enrosed.catalog.domain.PackagingKind.DISPLAY
                                ? (supplierFacing ? "pcs/display" : "stuks/display")
                                : null)));
            }
        }

        Carton carton = product.carton();
        String cartonDimensions = carton == null
                ? null : dimensionLabel(carton.dimensions(), supplierFacing);
        String outerBarcode = product.barcodes() == null ? null : product.barcodes().outer();
        boolean cartonKnown = cartonDimensions != null || notBlank(outerBarcode)
                || carton != null && carton.piecesPerCarton() > 1;
        if (cartonKnown) {
            Integer cartonPieces = carton != null && carton.piecesPerCarton() > 0
                    ? carton.piecesPerCarton() : null;
            details.add(new ProductSpec(supplierFacing ? "Outer carton" : "Omdoos", detailValue(
                    cartonDimensions, cartonPieces, outerBarcode, supplierFacing,
                    supplierFacing ? "pcs/carton" : "stuks/karton")));
        }
        return List.copyOf(details);
    }

    private static String dimensionLabel(Dimensions dimensions, boolean supplierFacing) {
        if (dimensions == null || dimensions.isBlank()) return null;
        return supplierFacing ? supplierDimensionLabel(dimensions) : dimensions.label();
    }

    private static String detailValue(String dimensions, Integer pieces, String barcode,
                                      boolean supplierFacing, String piecesSuffix) {
        List<String> values = new ArrayList<>();
        if (notBlank(dimensions)) values.add(dimensions);
        if (pieces != null && pieces > 0) {
            values.add(pieces + " " + (piecesSuffix == null
                    ? (supplierFacing ? "pcs" : "stuks") : piecesSuffix));
        }
        if (notBlank(barcode)) values.add("Barcode " + barcode.strip());
        return String.join(" · ", values);
    }

    /** Supplier-facing product label resolved from the English document translation. */
    static String supplierProductName(Product product) {
        if (product == null) return null;
        String name = product.nameIn(Language.EN);
        String colour = product.colourIn(Language.EN);
        if (!notBlank(colour)) return name;
        return name + " - " + colour;
    }

    private static String supplierDimensionLabel(Dimensions dimensions) {
        return "W × D × H: " + dimension(dimensions.lengthCm())
                + " × " + dimension(dimensions.widthCm())
                + " × " + dimension(dimensions.heightCm()) + " cm";
    }

    private static String dimension(BigDecimal value) {
        return value == null || value.signum() <= 0
                ? "—" : value.stripTrailingZeros().toPlainString();
    }

    /** Null means unknown; never turn missing carton master data into '1'. */
    static Integer piecesPerCarton(Product product) {
        if (product == null || product.carton() == null
                || product.carton().piecesPerCarton() < 1) return null;
        return product.carton().piecesPerCarton();
    }

    /** Product EAN, with the legacy editable piece barcode as a truthful fallback. */
    static String ean(Product product) {
        if (product == null) return null;
        if (notBlank(product.canonicalBarcode())) return product.canonicalBarcode().strip();
        if (product.barcodes() == null || !notBlank(product.barcodes().inner())) return null;
        return product.barcodes().inner().strip();
    }

    /** Supplier instructions are trimmed, but line breaks remain meaningful on paper. */
    static String supplierNote(Product product) {
        if (product == null || !notBlank(product.supplierNote())) return null;
        return product.supplierNote().strip();
    }

    /**
     * Keeps a long note complete without creating one unbreakable table row.
     * The product validator permits 4,000 characters, so one note may span
     * several pages in a supplier order.
     */
    static List<String> supplierNoteChunks(Product product) {
        String note = supplierNote(product);
        if (note == null) return List.of();
        final int targetSize = 700;
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < note.length()) {
            int end = Math.min(note.length(), start + targetSize);
            if (end < note.length()) {
                int whitespace = -1;
                for (int index = end; index > start + targetSize / 2; index--) {
                    if (Character.isWhitespace(note.charAt(index - 1))) {
                        whitespace = index - 1;
                        break;
                    }
                }
                if (whitespace > start) end = whitespace;
            }
            String chunk = note.substring(start, end).strip();
            if (!chunk.isEmpty()) chunks.add(chunk);
            start = end;
            while (start < note.length() && Character.isWhitespace(note.charAt(start))) start++;
        }
        return List.copyOf(chunks);
    }

    /** One outer carton's volume, not the full order-line volume. */
    static String cartonCbm(Product product) {
        if (product == null || product.carton() == null || product.carton().cbm() == null
                || product.carton().cbm().signum() <= 0) return null;
        return product.carton().cbm().stripTrailingZeros().toPlainString().replace('.', ',');
    }

    static boolean sameRate(PurchaseOrder order) {
        return order != null && order.usdToEurGoods() != null
                && order.usdToEurTransport() != null
                && order.usdToEurGoods().compareTo(order.usdToEurTransport()) == 0;
    }

    static String statusLabel(PurchaseOrderStatus status) {
        return switch (status) {
            case CONCEPT -> "Concept";
            case BESTELD -> "Besteld";
            case ONDERWEG -> "Onderweg";
            case ONTVANGEN -> "Ontvangen";
        };
    }

    static String contactLine(Supplier supplier) {
        if (supplier == null) return null;
        List<String> parts = new ArrayList<>();
        if (notBlank(supplier.contact())) parts.add(supplier.contact());
        if (notBlank(supplier.email())) parts.add(supplier.email());
        if (notBlank(supplier.phone())) parts.add(supplier.phone());
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** The life of the box, in the order it happens. */
    static List<Moment> timeline(PurchaseOrder order) {
        List<Moment> moments = new ArrayList<>();
        if (order.orderDate() != null) {
            moments.add(new Moment("Besteld", DocumentFormat.be(order.orderDate())));
        }
        if (order.shippedOn() != null) {
            moments.add(new Moment("Vertrokken", DocumentFormat.be(order.shippedOn())));
        }
        if (order.receivedOn() != null) {
            moments.add(new Moment("Ontvangen", DocumentFormat.be(order.receivedOn())));
        } else if (order.expectedArrival() != null) {
            moments.add(new Moment("Verwacht", DocumentFormat.be(order.expectedArrival())));
        }
        return moments;
    }

    /** The agreed instalments, priced against the supplier's goods value. */
    static List<ScheduleRow> schedule(PurchaseOrder order, PurchaseOrderService.Payable payable) {
        if (payable == null || payable.supplierEur() == null) return List.of();
        List<ScheduleRow> rows = new ArrayList<>();
        for (PaymentTerms.Instalment instalment : order.paymentTerms().instalments()) {
            BigDecimal amount = payable.supplierEur().multiply(instalment.share())
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(new ScheduleRow(instalment.label(), DocumentFormat.eur(amount),
                    momentReached(order, instalment.due())));
        }
        return rows;
    }

    static boolean momentReached(PurchaseOrder order, PaymentTerms.Moment due) {
        return switch (due) {
            case ORDERED -> order.status() != PurchaseOrderStatus.CONCEPT;
            case SHIPPED -> order.shippedOn() != null
                    || order.status() == PurchaseOrderStatus.ONDERWEG
                    || order.status() == PurchaseOrderStatus.ONTVANGEN;
            case ARRIVED -> order.receivedOn() != null
                    || order.status() == PurchaseOrderStatus.ONTVANGEN;
        };
    }

    static List<PaymentRow> paymentRows(List<PurchasePayment> payments) {
        if (payments == null) return List.of();
        List<PaymentRow> rows = new ArrayList<>();
        for (PurchasePayment payment : payments) {
            String original = payment.currency() == null || payment.currency() == Currency.EUR
                    || payment.amount() == null
                    ? null
                    : DocumentFormat.amount(payment.amount()) + " " + payment.currency();
            rows.add(new PaymentRow(
                    payment.paidOn() == null ? "—" : DocumentFormat.be(payment.paidOn()),
                    notBlank(payment.label()) ? payment.label() : "Betaling",
                    payment.payee().dutchLabel(),
                    DocumentFormat.eur(payment.amountEur()),
                    original));
        }
        return rows;
    }

    static PayableView payableView(List<PurchasePayment> payments,
                                   PurchaseOrderService.Payable payable) {
        if (payable == null) return null;
        BigDecimal paidSupplier = paidTo(payments, PurchasePayment.Payee.SUPPLIER);
        BigDecimal paidLogistics = paidTo(payments, PurchasePayment.Payee.LOGISTICS);
        BigDecimal open = payable.supplierEur() == null
                ? BigDecimal.ZERO : payable.supplierEur().subtract(paidSupplier);
        return new PayableView(
                DocumentFormat.eur(payable.supplierEur()),
                DocumentFormat.eur(payable.logisticsEur()),
                DocumentFormat.eur(payable.enrosedEur()),
                payable.freightInSupplierPrice(), payable.ddp(),
                DocumentFormat.eur(paidSupplier), DocumentFormat.eur(paidLogistics),
                DocumentFormat.eur(open.abs()), open.signum() < 0);
    }

    static BigDecimal paidTo(List<PurchasePayment> payments, PurchasePayment.Payee payee) {
        if (payments == null) return BigDecimal.ZERO;
        return payments.stream()
                .filter(payment -> payment.payee() == payee)
                .map(PurchasePayment::amountEur)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** An exchange rate exactly as entered: 0,8900 - never rounded. */
    static String rate(BigDecimal value) {
        return value == null ? null : value.toPlainString().replace('.', ',');
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
