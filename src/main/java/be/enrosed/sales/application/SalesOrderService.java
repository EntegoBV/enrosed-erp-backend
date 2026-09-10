package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.OtherCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.BusinessDays;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drafting and pricing sales orders.
 *
 * Sending, the customer view and the change proposals live in
 * {@link QuoteService}; only composing the order stays here.
 */
@ApplicationScoped
public class SalesOrderService {
    @Inject Instance<IncomingPaymentService> incomingPayments;
    @Inject Instance<PartnerSettlements> partnerSettlements;
    @Inject Instance<PartnerAdvanceScheduleService> advanceSchedules;
    @Inject Instance<PartnerAdvanceQuotes> advanceQuotes;
    static final String WEBSITE_REQUEST_MARKER = "[WEBSITE_AANVRAAG]";
    static final String WEBSITE_CARTON_UNRESOLVED_MARKER = "[DOOSINHOUD_TE_BEPALEN]";
    static final String SALES_ORDER_ACTIVITY_TYPE = "SALES_ORDER";
    static final String SALES_ACTION_SENT = "SENT";
    static final String SALES_ACTION_SHIPPED = "SHIPPED";
    static final String SALES_ACTION_PAID = "PAID";

    private final SalesRepositories.Orders orders;
    private final ProductService products;
    private final CountryService countries;
    private final DiscountTierService tiers;
    private final SalesPricingCalculator pricing;
    private final PalletCalculator palletCalculator;
    private final SalesSettings settings;
    private final SalesRepositories.Events events;
    private final SalesRepositories.Revisions revisions;
    private final CustomerService customers;
    private final VatCalculator vat;
    private final be.enrosed.shipping.application.CarrierRepository shippingCarriers;
    /* Optional only for pure unit tests which construct the service directly. */
    @Inject
    Instance<CurrentActor> actor;
    @Inject
    Instance<ActivityLogService> activity;

    /** The container side, for a quote made straight from a purchase order. */
    @Inject
    Instance<be.enrosed.sourcing.application.PurchaseOrderService> purchaseOrders;
    /** The letters in front of document numbers live in the company settings. */
    @Inject
    Instance<be.enrosed.shared.company.CompanyProfileService> companyProfile;
    @Inject
    Event<SalesCreationPushNotifier.Ready> salesCreationPush;
    @Inject
    Event<SalesActivityPushNotifier.Ready> salesActivityPush;

    public SalesOrderService(SalesRepositories.Orders orders, ProductService products,
                             CountryService countries, DiscountTierService tiers,
                             SalesPricingCalculator pricing, PalletCalculator palletCalculator,
                             SalesSettings settings,
                             CustomerService customers, VatCalculator vat,
                             SalesRepositories.Events events,
                             SalesRepositories.Revisions revisions,
                             be.enrosed.shipping.application.CarrierRepository shippingCarriers) {
        this.shippingCarriers = shippingCarriers;
        this.orders = orders;
        this.products = products;
        this.countries = countries;
        this.tiers = tiers;
        this.pricing = pricing;
        this.palletCalculator = palletCalculator;
        this.settings = settings;
        this.customers = customers;
        this.events = events;
        this.revisions = revisions;
        this.vat = vat;
    }

    public List<SalesOrder> list() {
        return orders.findAll();
    }

    public SalesOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Verkooporder", id));
    }

    /** Prices an order with the current prices, tiers and rates. */
    public PricedOrder price(SalesOrder order) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Country country = countries.find(order.countryCode());
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());

        be.enrosed.shipping.domain.Carrier carrier = order.freightCarrierId() == null
                ? null : shippingCarriers.findById(order.freightCarrierId()).orElse(null);

        return pricing.price(order, byId, new SalesPricingCalculator.Context(
                country,
                customer,
                settings.pallet(order.palletProfile(), order.maxPalletHeightCm()),
                tiers.list(TierScope.LINE),
                tiers.list(TierScope.ORDER),
                vat.determine(country, customer),
                carrier));
    }

    /** Price this order uses for this product; the portal uses it too. */
    public BigDecimal unitPriceFor(be.enrosed.catalog.domain.Product product, SalesOrder order) {
        return pricing.unitPriceFor(product, order, null);
    }

    @Transactional
    public SalesOrder create(long customerId, String countryCode, String incoterm) {
        return create(customerId, countryCode, incoterm, DocumentType.OFFERTE);
    }

    @Transactional
    public SalesOrder create(long customerId, String countryCode, String incoterm,
                             DocumentType docType) {
        return create(customerId, countryCode, incoterm, docType, true);
    }

    /** Public website intake: same ERP draft; its push is sent after the final draft commits. */
    @Transactional
    public SalesOrder createWebsiteRequest(long customerId, String countryCode, String incoterm) {
        return create(customerId, countryCode, incoterm, DocumentType.OFFERTE, false);
    }

    private SalesOrder create(long customerId, String countryCode, String incoterm,
                              DocumentType docType, boolean staffAction) {
        boolean invoice = docType == DocumentType.FACTUUR;
        ActorRef creator = staffAction ? currentActor() : null;
        LocalDate today = LocalDate.now();
        /* The staffel of the shipping organisation is the house standard;
           without one configured, the country tariff steps in. */
        Long defaultCarrierId = shippingCarriers.findAll().stream()
                .filter(be.enrosed.shipping.domain.Carrier::active)
                .map(be.enrosed.shipping.domain.Carrier::id)
                .findFirst().orElse(null);
        SalesOrder draft = new SalesOrder(
                null, invoice ? nextInvoiceNumber() : nextNumber(),
                customerId, countryCode, today, BusinessDays.add(today, 30),
                QuoteStatus.CONCEPT, incoterm == null ? "DAP" : incoterm, null, "",
                MarkupMode.PRODUCT, settings.defaultMarkupPct(),
                null, null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                defaultCarrierId == null
                        ? FreightPricingStrategy.COUNTRY_PALLET : FreightPricingStrategy.CARRIER,
                null, defaultCarrierId, null,
                docType, invoice ? BusinessDays.add(today, 30) : null, null, null, null,
                List.of(), List.of())
                .withSalesChannel(staffAction ? null : "WEBSITE");
        validateForSave(draft);
        SalesOrder created = orders.save(draft);

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), creator == null ? null : creator.displayName(), false,
                invoice ? "Factuur opgemaakt" : "Offerte opgemaakt", null));
        if (staffAction) {
            recordActivity(created, invoice ? "Factuur aangemaakt" : "Offerte aangemaakt");
            fireCreationPush(invoice
                    ? SalesCreationPushNotifier.Ready.invoiceCreated(created.id(), created.number(), creator)
                    : SalesCreationPushNotifier.Ready.quoteCreated(created.id(), created.number(), creator));
        }
        return created;
    }

    /**
     * Converts a quote into an unsent draft invoice and archives its source.
     * Repeated requests return the active linked invoice under the same source
     * lock; scheduled advance terms have their own separate creation flow.
     */
    @Transactional
    public SalesOrder createInvoiceFrom(long quoteId) {
        orders.lockById(quoteId);
        SalesOrder source = get(quoteId);
        if (source.isInvoice()) {
            throw new BusinessRuleException("Dit is al een factuur; maak facturen vanuit een offerte");
        }
        if (hasAdvanceAgreement(source))
            throw new BusinessRuleException("Deze offerte legt de voorschotafspraken vast. Maak de voorschotfacturen per afgesproken termijn vanuit de inkooporder; de eindafrekening volgt afzonderlijk");
        if (source.isPartnerAdvance() && purchaseOrders != null && purchaseOrders.isResolvable())
            purchaseOrders.get().lockForPartnerSettlement(source.linkedPurchaseOrderId());
        SalesOrder existing = orders.findAll().stream().filter(order -> order.isInvoice()
                && source.id().equals(order.sourceQuoteId()) && order.status() != QuoteStatus.GEANNULEERD)
                .findFirst().orElse(null);
        if (existing != null) {
            archive(quoteId);
            return existing;
        }
        if (source.isPartnerAdvance() && orders.findAll().stream().anyMatch(order -> order.isInvoice()
                && order.isPartnerAdvance() && PartnerFinancingService.live(order)
                && Objects.equals(order.linkedPurchaseOrderId(), source.linkedPurchaseOrderId())))
            throw new BusinessRuleException("Er bestaat al een voorschotfactuur voor deze inkooporder zonder deze bronkoppeling; controleer eerst de bestaande facturen om dubbele financiering te voorkomen");
        ActorRef creator = currentActor();
        LocalDate today = LocalDate.now();
        SalesOrder invoice = new SalesOrder(
                null, source.isPartnerDeal() ? nextPartnerInvoiceNumber() : nextInvoiceNumber(), source.customerId(), source.countryCode(),
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT, source.incoterm(),
                source.paymentTerms(), source.notes(),
                source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(),
                null, null, null, 0, null, null, null, source.internalNotes(),
                DeliveryTermsState.VOLLEDIG, source.freight(), source.manualFreightEur(),
                source.loadMode(), source.palletProfile(), source.maxPalletHeightCm(),
                source.freightPricingStrategy(), source.freightRatePerCbmEur(),
                source.freightCarrierId(), source.freightCarrierExtraEur(),
                DocumentType.FACTUUR, BusinessDays.add(today, 30), null, source.id(), null,
                source.lines().stream()
                        .map(line -> new SalesOrderLine(null, line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek(), line.unitCostEur()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        invoice = invoice.withExtraLines(source.extraLines())
                .withPartnerDeal(source.partnerPurchaseOrderId(), source.partnerSharePct())
                .withSalesChannel(source.rawSalesChannel())
                .withPurpose(source.purpose(), source.linkedPurchaseOrderId(), source.paymentPlan());
        validatePartnerAdvanceReservation(invoice, null);
        validateForSave(invoice);
        SalesOrder created = orders.save(invoice);

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), creator.displayName(), false,
                "Factuur opgemaakt vanuit " + source.number(), null));
        events.add(new QuoteEvent(null, source.id(), QuoteEvent.Type.GEFACTUREERD,
                java.time.Instant.now(), creator.displayName(), false,
                "Factuur " + created.number() + " aangemaakt", null));
        recordActivity(created, "Factuur aangemaakt vanuit offerte");
        archive(quoteId);
        fireCreationPush(SalesCreationPushNotifier.Ready.invoiceFromQuoteCreated(
                created.id(), created.number(), source.number(), creator));
        return created;
    }

    /** What the sheet asks for when a container becomes a quote: whose, at which prices, with which of its costs. */
    public record FromPurchaseOrderRequest(Long purchaseOrderId, Long customerId, String pricing, BigDecimal markupPct,
                                           boolean partner, BigDecimal sharePct, BigDecimal costPct,
                                           boolean includeInspection, List<Integer> otherCostIndexes, String salesChannel,
                                           /** The week every line promises ("2026-W36"); empty takes the container's expected arrival. */
                                           String deliveryWeek, SalesPurpose purpose, SalesPaymentPlan paymentPlan,
                                           PartnerAdvanceScheduleService.Request advanceSchedule) {
        public FromPurchaseOrderRequest(Long purchaseOrderId, Long customerId, String pricing, BigDecimal markupPct,
                boolean partner, BigDecimal sharePct, BigDecimal costPct, boolean includeInspection, List<Integer> otherCostIndexes,
                String salesChannel, String deliveryWeek, SalesPurpose purpose, SalesPaymentPlan paymentPlan) {
            this(purchaseOrderId, customerId, pricing, markupPct, partner, sharePct, costPct, includeInspection,
                    otherCostIndexes, salesChannel, deliveryWeek, purpose, paymentPlan, null);
        }
        public FromPurchaseOrderRequest(Long purchaseOrderId, Long customerId, String pricing, BigDecimal markupPct,
                boolean partner, BigDecimal sharePct, BigDecimal costPct, boolean includeInspection, List<Integer> otherCostIndexes,
                String salesChannel, String deliveryWeek) {
            this(purchaseOrderId, customerId, pricing, markupPct, partner, sharePct, costPct, includeInspection, otherCostIndexes, salesChannel, deliveryWeek, null, null);
        }
        /** Compatibility for callers written before the delivery week could be chosen. */
        public FromPurchaseOrderRequest(Long purchaseOrderId, Long customerId, String pricing, BigDecimal markupPct,
                                        boolean partner, BigDecimal sharePct, BigDecimal costPct,
                                        boolean includeInspection, List<Integer> otherCostIndexes, String salesChannel) {
            this(purchaseOrderId, customerId, pricing, markupPct, partner, sharePct, costPct,
                    includeInspection, otherCostIndexes, salesChannel, null);
        }
    }

    /**
     * A container becomes a sales document in one transaction: ordinary sales
     * remain quotations; partner financing creates unsent draft invoices.
     * Every product line carries its
     * pieces, at the customer's prices or at the container's landed cost,
     * the inspection and other costs as lines of their own, and for a
     * partner the deal itself. One save, one diary line, one log entry on
     * both documents; nothing half-made is left behind when a rule fails.
     */
    @Transactional
    public SalesOrder createFromPurchaseOrder(FromPurchaseOrderRequest request) {
        if (request == null || request.purchaseOrderId() == null) throw new BusinessRuleException("Kies een inkooporder");
        if (purchaseOrders == null || !purchaseOrders.isResolvable()) {
            throw new BusinessRuleException("Inkoop is niet beschikbaar; probeer straks opnieuw");
        }
        be.enrosed.sourcing.application.PurchaseOrderService sourcing = purchaseOrders.get();
        PurchaseOrder container = sourcing.get(request.purchaseOrderId());
        if (container.lines().isEmpty()) throw new BusinessRuleException("Deze inkooporder heeft nog geen productregels");
        /* Without a customer the quote goes to the container's own partner: no searching. */
        Long customerId = request.customerId() != null ? request.customerId() : container.partnerCustomerId();
        if (customerId == null) throw new BusinessRuleException("Kies de klant voor de offerte");
        Customer customer = customers.get(customerId);
        boolean atCost = "COST".equalsIgnoreCase(request.pricing());
        BigDecimal markup = request.markupPct() == null ? BigDecimal.ZERO : request.markupPct();
        if (markup.signum() < 0) throw new BusinessRuleException("De opslag op de kostprijs kan niet negatief zijn");
        boolean containersPartner = customerId.equals(container.partnerCustomerId());
        boolean partner = request.purpose() == SalesPurpose.STANDARD ? false
                : request.purpose() == SalesPurpose.PARTNER_ADVANCE || atCost && (request.partner() || containersPartner);
        if (request.purpose() == SalesPurpose.PARTNER_SETTLEMENT) throw new BusinessRuleException("Maak de slotfactuur via de veilingafrekening");
        if (partner && container.partnerCustomerId() != null && !containersPartner)
            throw new BusinessRuleException("Deze container is aan een andere partner gekoppeld");
        if (partner) atCost = true;
        /* The container's own deal comes first, then the customer's standing agreement. */
        BigDecimal share = partner
                ? percentage(request.sharePct() != null ? request.sharePct()
                        : containersPartner ? container.partnerSharePctOrDefault() : customer.partnerSharePctOrDefault(), "Ons deel van de winst")
                : null;
        BigDecimal costPct = partner
                ? percentage(request.costPct() != null ? request.costPct()
                        : containersPartner ? container.partnerCostPctOrDefault() : customer.partnerCostPctOrDefault(),
                        "Het deel van de kost dat de partner vooraf betaalt")
                : HUNDRED;
        if (!partner && request.advanceSchedule() != null)
            throw new BusinessRuleException("Voorschotafspraken horen bij een partnerfactuur vanuit de inkooporder");
        if (partner) {
            if (costPct.signum() == 0)
                throw new BusinessRuleException("Bij 0 % financiering is geen voorschotfactuur nodig; bewaar de partnerafspraak op de inkooporder");
            container = sourcing.lockForPartnerSettlement(container.id());
            if (container.partnerCustomerId() != null && !Objects.equals(container.partnerCustomerId(), customer.id()))
                throw new BusinessRuleException("Deze inkooporder is aan een andere klant gekoppeld");
            boolean financingChanged = !samePercentage(container.partnerCostPctOrDefault(), costPct);
            if (container.partnerCustomerId() == null || financingChanged
                    || !samePercentage(container.partnerSharePctOrDefault(), share))
                container = sourcing.setPartner(container.id(), new be.enrosed.sourcing.application.PurchaseOrderService.PartnerRequest(customer.id(), costPct, share));
            if (advanceSchedules != null && advanceSchedules.isResolvable()
                    && (request.advanceSchedule() != null || advanceSchedules.get().hasRows(container.id()))) {
                var scheduleRequest = request.advanceSchedule();
                if (financingChanged) {
                    if (scheduleRequest == null)
                        throw new BusinessRuleException("Geef de voorschottermijnen opnieuw op wanneer het financieringspercentage wijzigt");
                    scheduleRequest = new PartnerAdvanceScheduleService.Request(scheduleRequest.rows(), true);
                }
                return advanceSchedules.get().createInvoices(container.id(), scheduleRequest).getFirst();
            }
        }
        BigDecimal factor = BigDecimal.ONE.add(markup.divide(HUNDRED, 6, java.math.RoundingMode.HALF_UP))
                .multiply(costPct).divide(HUNDRED, 6, java.math.RoundingMode.HALF_UP);

        if (partner) factor = costPct.divide(HUNDRED, 8, java.math.RoundingMode.HALF_UP);

        /* The container's costing gives every line its cost, whatever the customer pays. */
        Map<Long, LandedCost.Line> costLines = new HashMap<>();
        LandedCost costing = sourcing.calculate(container);
        if (costing != null && costing.lines() != null) {
            for (LandedCost.Line line : costing.lines()) costLines.put(line.productId(), line);
        }
        be.enrosed.sourcing.domain.PurchaseReconciliation reconciliation = partner ? sourcing.reconciliation(container, costing) : null;
        Map<Long, be.enrosed.sourcing.domain.PurchaseReconciliation.Line> externalLines = reconciliation == null ? Map.of()
                : reconciliation.lines().stream().collect(Collectors.toMap(be.enrosed.sourcing.domain.PurchaseReconciliation.Line::productId, Function.identity()));
        if (partner && reconciliation == null) throw new BusinessRuleException("Bereken eerst de externe containerkosten voor het partnervoorschot");
        /* The goods are still on their way: every line promises the week asked for, else the week the container arrives. */
        String deliveryWeek = requestedDeliveryWeek(request.deliveryWeek(), containerArrivalWeek(container));
        List<SalesOrderLine> lines = new java.util.ArrayList<>();
        for (PurchaseOrderLine line : container.lines()) {
            if (line.quantity() <= 0) continue;
            BigDecimal unit = null;
            BigDecimal snapshot = null;
            int quantity = line.quantity();
            LandedCost.Line cost = costLines.get(line.productId());
            if (partner) {
                var external = externalLines.get(line.productId());
                if (external == null || external.unitCostQuantity() <= 0 || external.forecastExternalUnitEur() == null) continue;
                quantity = external.unitCostQuantity();
                unit = external.forecastExternalUnitEur().multiply(factor).setScale(4, java.math.RoundingMode.HALF_UP);
                snapshot = external.forecastExternalUnitEur();
            } else if (atCost) {
                if (cost == null || cost.landedUnitEur() == null || cost.landedUnitEur().signum() <= 0) {
                    throw new BusinessRuleException("Geen gelande kost voor " + productName(line.productId())
                            + "; reken de calculatie van " + container.number() + " eerst door");
                }
                unit = cost.landedUnitEur().multiply(factor).setScale(4, java.math.RoundingMode.HALF_UP);
                /* The landed cost per piece is spread over the pieces the calculation counts; the same
                   pieces go on the quote, so the quote adds up to what the container cost us. */
                if (cost.quantity() > 0) quantity = cost.quantity();
            }
            /* The container's own landed cost is what this line cost us, whatever the product's
               cost says later; the inspection and other costs are inside it since the calculation
               spreads them over the pieces. */
            if (!partner && cost != null && cost.landedUnitEur() != null && cost.landedUnitEur().signum() > 0) snapshot = cost.landedUnitEur();
            lines.add(new SalesOrderLine(null, line.productId(), quantity, unit, null, deliveryWeek, snapshot));
        }
        if (lines.isEmpty()) throw new BusinessRuleException("Deze inkooporder heeft geen regels met een aantal");
        lines = withCostSnapshots(lines, null);

        /* The inspection and the other named costs already sit in every landed piece price, so a
           cost quote carries them in its lines; the request's flags from before that are ignored. */
        List<SalesExtraLine> extras = new java.util.ArrayList<>();
        /* Apart from the piece price, the inspection and other named costs travel as lines of their own;
           spread by a key they are inside every piece price already and must not travel twice. */
        if (atCost && !partner && !container.separateInPiecePrice()) {
            String suffix = " · " + container.number();
            if (request.includeInspection() && container.inspectionCostEur() != null && container.inspectionCostEur().signum() > 0) {
                extras.add(new SalesExtraLine("Inspectie" + suffix, BigDecimal.ONE, part(container.inspectionCostEur(), costPct)));
            }
            for (Integer index : request.otherCostIndexes() == null ? List.<Integer>of() : request.otherCostIndexes()) {
                if (index == null || index < 0 || index >= container.otherCosts().size()) continue;
                OtherCost other = container.otherCosts().get(index);
                if (other.label() == null || other.label().isBlank() || other.amountEur() == null || other.amountEur().signum() <= 0) continue;
                extras.add(new SalesExtraLine(other.label().strip() + suffix, BigDecimal.ONE, part(other.amountEur(), costPct)));
            }
        }

        if (partner) {
            BigDecimal commitment = reconciliation.totals().forecastExternalEur().multiply(costPct)
                    .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
            if (commitment.signum() <= 0) throw new BusinessRuleException("Bereken eerst een positief voorschotbedrag voor deze inkooporder");
            BigDecimal piecesTotal = lines.stream().map(line -> line.unitPriceEur().multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal rounding = commitment.subtract(piecesTotal);
            if (rounding.signum() != 0) extras.add(new SalesExtraLine("Afronding voorschot container", BigDecimal.ONE, rounding));
        }
        String channel = !isBlank(request.salesChannel()) ? request.salesChannel() : partner ? "PARTNER" : null;
        String internalNotes = partner
                ? "Partnercontainer " + container.number() + ": goederen aan " + pct(costPct)
                        + " % van onze gelande kostprijs (fabriek, zeevracht, invoerrechten en afhandeling). Na de veiling volgt de veilingafrekening: "
                        + pct(HUNDRED.subtract(costPct)) + " % van de kost terug en " + pct(share) + " % van de winst."
                : "Offerte gemaakt vanuit inkooporder " + container.number() + (atCost ? " aan kostprijs." : ".");
        Long defaultCarrierId = shippingCarriers.findAll().stream()
                .filter(be.enrosed.shipping.domain.Carrier::active)
                .map(be.enrosed.shipping.domain.Carrier::id)
                .findFirst().orElse(null);
        ActorRef creator = currentActor();
        LocalDate today = LocalDate.now();
        SalesOrder draft = new SalesOrder(
                null, partner ? nextPartnerInvoiceNumber() : nextNumber(), customer.id(), customer.countryCode(), today, BusinessDays.add(today, 30),
                QuoteStatus.CONCEPT, isBlank(customer.incoterm()) ? "DAP" : customer.incoterm(), null, "",
                MarkupMode.PRODUCT, settings.defaultMarkupPct(), null, null,
                null, null, null, 0, null, null, null, internalNotes,
                DeliveryTermsState.VOLLEDIG,
                /* The container's freight is already inside the landed cost; a cost quote adds none of its own. */
                atCost ? FreightState.AANGEVULD : FreightState.BEREKEND, atCost ? BigDecimal.ZERO : null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                /* At cost the freight is a fixed zero, whatever carrier the house normally uses. */
                atCost ? FreightPricingStrategy.FIXED
                        : defaultCarrierId == null ? FreightPricingStrategy.COUNTRY_PALLET : FreightPricingStrategy.CARRIER,
                null, atCost ? null : defaultCarrierId, null,
                partner ? DocumentType.FACTUUR : DocumentType.OFFERTE,
                partner ? BusinessDays.add(today, 30) : null, null, null, null, lines, List.of())
                .withExtraLines(extras)
                .withPartnerDeal(partner ? container.id() : null, share)
                .withSalesChannel(channel)
                .withPurpose(partner ? SalesPurpose.PARTNER_ADVANCE : SalesPurpose.STANDARD, container.id(),
                        request.paymentPlan() != null ? request.paymentPlan()
                                : partner ? SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION : SalesPaymentPlan.FULL);
        if (partner) {
            List<SalesOrder> existing = orders.findAll().stream().filter(order -> order.isInvoice() && order.isPartnerAdvance()
                    && Objects.equals(order.linkedPurchaseOrderId(), request.purchaseOrderId()) && PartnerFinancingService.live(order)).toList();
            if (!existing.isEmpty()) {
                if (existing.size() == 1) {
                    SalesOrder previous = existing.getFirst();
                    if (Objects.equals(previous.customerId(), draft.customerId())
                            && samePercentage(previous.partnerSharePct(), draft.partnerSharePct())
                            && previous.paymentPlan() == draft.paymentPlan()
                            && sameQuotedLines(previous.lines(), draft.lines())
                            && sameQuotedExtras(previous.extraLines(), draft.extraLines())) return previous;
                }
                throw new BusinessRuleException("Er bestaat al een voorschotfactuur voor deze inkooporder; open die factuur of het bestaande termijnplan");
            }
            requireAdvanceBeforeSettlement(draft);
            validatePartnerAdvanceReservation(draft, null);
        }
        validateForSave(draft);
        SalesOrder created = orders.save(draft);

        String how = partner
                ? " als partnercontainer: " + pct(share) + " % winstdeling, " + pct(costPct) + " % van de kost vooraf"
                : atCost ? " aan kostprijs" : " aan klantprijzen";
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), creator.displayName(), false,
                (partner ? "Conceptfactuur" : "Offerte") + " opgemaakt vanuit inkooporder " + container.number() + how, null));
        recordActivity(created, (partner ? "Conceptfactuur" : "Offerte") + " aangemaakt vanuit inkooporder " + container.number() + how);
        if (partner) adoptPartnerContainer(container.id(), customer.id(), costPct, share);
        recordPurchaseActivity(container.id(), container.number(),
                (partner ? "Conceptfactuur " : "Verkoopofferte ") + created.number() + " gemaakt voor " + customer.company() + how);
        fireCreationPush(partner ? SalesCreationPushNotifier.Ready.invoiceCreated(created.id(), created.number(), creator)
                : SalesCreationPushNotifier.Ready.quoteCreated(created.id(), created.number(), creator));
        return created;
    }

    private static BigDecimal part(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
    }

    private static final java.util.regex.Pattern ISO_WEEK = java.util.regex.Pattern.compile("^\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])$");

    /** The week the caller chose, checked; blank falls back to what the container promises. */
    static String requestedDeliveryWeek(String requested, String fallback) {
        if (requested == null || requested.isBlank()) return fallback;
        String week = requested.strip().toUpperCase();
        if (!ISO_WEEK.matcher(week).matches()) throw new BusinessRuleException("Kies een geldige leverweek, zoals 2026-W36");
        return week;
    }

    /**
     * The delivery week a quote from a container can promise: the ISO week the
     * container is expected, as logistics writes it ("2026-W36"). A container
     * that has already been received is stock, and stock needs no promise; a
     * container without an expected arrival leaves the week open.
     */
    static String containerArrivalWeek(PurchaseOrder container) {
        if (container == null || container.expectedArrival() == null || container.receivedOn() != null) return null;
        java.time.temporal.WeekFields week = java.time.temporal.WeekFields.ISO;
        return String.format("%d-W%02d",
                container.expectedArrival().get(week.weekBasedYear()),
                container.expectedArrival().get(week.weekOfWeekBasedYear()));
    }

    private static String pct(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String productName(Long productId) {
        return products.list().stream().filter(product -> product.id().equals(productId))
                .map(Product::name).findFirst().orElse("product " + productId);
    }

    /** The container's own diary line: what the partner side did with it. */
    /**
     * A container that gets its first partner document becomes a partner
     * container with that customer's deal; one that already has a partner keeps it.
     */
    private void adoptPartnerContainer(Long purchaseOrderId, Long customerId, BigDecimal costPct, BigDecimal sharePct) {
        if (purchaseOrderId == null || customerId == null || purchaseOrders == null || !purchaseOrders.isResolvable()) return;
        try {
            purchaseOrders.get().adoptPartner(purchaseOrderId, customerId, costPct, sharePct);
        } catch (NotFoundException gone) {
            /* A container that no longer exists cannot take a partner; the sales document keeps its own link. */
        }
    }

    /** Inspection and other costs booked apart from the piece price, spread per piece for the settlement. */
    private record SeparateCosts(BigDecimal total, int pieces, BigDecimal perPiece) {
        static final SeparateCosts NONE = new SeparateCosts(BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    /**
     * The inspection and other named costs of the container that no key spread into the
     * piece prices. They cost us money all the same, so the settlement counts them per
     * piece over the whole container, as a PIECES key would; spread ones already sit in
     * the landed unit the request carries.
     */
    private SeparateCosts separateCostsApart(Long purchaseOrderId) {
        if (purchaseOrderId == null || purchaseOrders == null || !purchaseOrders.isResolvable()) return SeparateCosts.NONE;
        try {
            be.enrosed.sourcing.application.PurchaseOrderService sourcing = purchaseOrders.get();
            PurchaseOrder container = sourcing.get(purchaseOrderId);
            LandedCost costing = container == null ? null : sourcing.calculate(container);
            LandedCost.Totals totals = costing == null ? null : costing.totals();
            if (totals == null || totals.separateCostsInPiecePrice() || totals.pieces() <= 0
                    || totals.separateCostsEur() == null || totals.separateCostsEur().signum() <= 0) {
                return SeparateCosts.NONE;
            }
            return new SeparateCosts(totals.separateCostsEur(), totals.pieces(),
                    totals.separateCostsEur().divide(BigDecimal.valueOf(totals.pieces()), 4, java.math.RoundingMode.HALF_UP));
        } catch (NotFoundException gone) {
            return SeparateCosts.NONE;
        }
    }

    /** The part of the cost this customer pays up front by standing agreement; the whole cost when unknown. */
    private BigDecimal partnerCostPctOf(Long customerId) {
        try {
            return customers.get(customerId).partnerCostPctOrDefault();
        } catch (NotFoundException gone) {
            return HUNDRED;
        }
    }

    private void recordPurchaseActivity(Long purchaseOrderId, String number, String summary) {
        if (activity == null || !activity.isResolvable() || purchaseOrderId == null) return;
        activity.get().record(ActivityLogService.ACTION_UPDATED, "PURCHASE_ORDER",
                purchaseOrderId.toString(), number, summary);
    }

    /** A single plan milestone is one invoice and never a second financing percentage. */
    @Transactional
    public SalesOrder createScheduledPartnerAdvance(PurchaseOrder purchase, long rowId, String label,
                                                    BigDecimal amount, LocalDate dueDate, String notes) {
        return createScheduledPartnerAdvance(purchase, rowId, label, amount, dueDate, notes, null);
    }

    @Transactional
    public SalesOrder createScheduledPartnerAdvance(PurchaseOrder purchase, long rowId, String label,
                                                    BigDecimal amount, LocalDate dueDate, String notes, Long sourceQuoteId) {
        Customer partner = customers.get(purchase.partnerCustomerId());
        LocalDate today = LocalDate.now();
        SalesOrder invoice = new SalesOrder(null, nextPartnerInvoiceNumber(), partner.id(), partner.countryCode(),
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT,
                isBlank(partner.incoterm()) ? "DAP" : partner.incoterm(), null, notes,
                MarkupMode.PRODUCT, BigDecimal.ZERO, null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.AANGEVULD, BigDecimal.ZERO, LoadMode.PALLETS,
                PalletProfile.EURO_120X80, null, FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.FACTUUR, dueDate == null ? BusinessDays.add(today, 30) : dueDate, null, sourceQuoteId, null, List.of(), List.of())
                .withExtraLines(List.of(new SalesExtraLine("Voorschot · " + label, BigDecimal.ONE, amount)))
                .withPartnerDeal(purchase.id(), purchase.partnerSharePctOrDefault()).withSalesChannel("PARTNER")
                .withPurpose(SalesPurpose.PARTNER_ADVANCE, purchase.id(), SalesPaymentPlan.FULL);
        requireAdvanceBeforeSettlement(invoice);
        validatePartnerAdvanceReservation(invoice, rowId);
        validateForSave(invoice);
        SalesOrder saved = orders.save(invoice);
        events.add(new QuoteEvent(null, saved.id(), QuoteEvent.Type.OPGEMAAKT, Instant.now(), currentActor().displayName(), false,
                "Voorschottermijn " + label + " aangemaakt", null));
        recordActivity(saved, "Voorschottermijn " + label + " aangemaakt");
        if (sourceQuoteId != null) events.add(new QuoteEvent(null, sourceQuoteId, QuoteEvent.Type.GEFACTUREERD,
                Instant.now(), currentActor().displayName(), false, "Voorschotfactuur " + saved.number() + " aangemaakt voor " + label, null));
        return saved;
    }

    private void validatePartnerAdvanceReservation(SalesOrder invoice, Long scheduleRowId) {
        if (advanceSchedules != null && advanceSchedules.isResolvable()) advanceSchedules.get().validateReservation(invoice, scheduleRowId);
    }

    /** Net auction proceeds after auction fees; legacy client unit cost is ignored. */
    public record AuctionLine(Long productId, int quantity, BigDecimal proceedsEur, BigDecimal landedUnitCostEur) {}

    public record AuctionSettlementRequest(Long customerId, Long purchaseOrderId, String reference, Long sourceId,
                                           BigDecimal costSharePct, BigDecimal profitSharePct,
                                           List<AuctionLine> lines, String note, Boolean finalSettlement) {
        public AuctionSettlementRequest(Long customerId, Long purchaseOrderId, String reference, Long sourceId,
                                        BigDecimal costSharePct, BigDecimal profitSharePct, List<AuctionLine> lines, String note) {
            this(customerId, purchaseOrderId, reference, sourceId, costSharePct, profitSharePct, lines, note, null);
        }
    }

    public PartnerSettlementLedger.Availability partnerSettlementAvailability(long purchaseId) {
        PurchaseOrder purchase = purchaseOrders.get().get(purchaseId);
        var linked = orders.findAll().stream().filter(order -> Objects.equals(purchaseId, order.linkedPurchaseOrderId())).toList();
        return PartnerSettlementLedger.calculate(purchaseId, purchase.partnerCustomerId(), purchaseOrders.get().reconciliation(purchaseId),
                linked, this::price, order -> partnerSettlements != null && partnerSettlements.isResolvable()
                        ? partnerSettlements.get().find(order.id()) : null);
    }

    /**
     * Settles every usable piece once at external container cost plus our agreed
     * share of the net profit or loss. Issued advances are credited in full,
     * independently of their cash receipts. The container lock serializes drafts;
     * cancelled documents keep their immutable snapshots and may be replaced.
     */
    @Transactional
    public SalesOrder createAuctionSettlement(AuctionSettlementRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty())
            throw new BusinessRuleException("Vul de netto veilingopbrengst per product in");
        if (purchaseOrders == null || !purchaseOrders.isResolvable()) throw new BusinessRuleException("Inkoop is niet beschikbaar");
        SalesOrder source = request.sourceId() == null ? null : get(request.sourceId());
        Long purchaseId = request.purchaseOrderId() != null ? request.purchaseOrderId()
                : source == null ? null : source.linkedPurchaseOrderId();
        if (purchaseId == null) throw new BusinessRuleException("Koppel de afrekening aan een container");
        PurchaseOrder container = purchaseOrders.get().lockForPartnerSettlement(purchaseId);
        if (advanceSchedules != null && advanceSchedules.isResolvable()) advanceSchedules.get().requireReadyForSettlement(purchaseId);
        Long customerId = request.customerId() != null ? request.customerId()
                : source != null ? source.customerId() : container.partnerCustomerId();
        if (customerId == null) throw new BusinessRuleException("Kies de partner voor deze afrekening");
        if (container.partnerCustomerId() != null && !container.partnerCustomerId().equals(customerId))
            throw new BusinessRuleException("Deze container is aan een andere partner gekoppeld");
        if (source != null && (!source.isPartnerDeal() || !customerId.equals(source.customerId())
                || !purchaseId.equals(source.linkedPurchaseOrderId())))
            throw new BusinessRuleException("Het voorschotdocument hoort niet bij deze partner en container");
        List<SalesOrder> linked = orders.findAll().stream().filter(order -> purchaseId.equals(order.linkedPurchaseOrderId())).toList();
        if (linked.stream().anyMatch(order -> order.isPartnerAdvance() && PartnerFinancingService.issued(order)
                && !customerId.equals(order.customerId())))
            throw new BusinessRuleException("Er staat een uitgereikt voorschot van een andere partner op deze container; corrigeer eerst de koppeling");
        BigDecimal profitShare = percentage(request.profitSharePct() == null
                ? container.partnerSharePctOrDefault() : request.profitSharePct(), "Ons deel van de winst");
        if (container.partnerCustomerId() != null && profitShare.compareTo(container.partnerSharePctOrDefault()) != 0)
            throw new BusinessRuleException("De winstdeling van de afrekening moet overeenkomen met de partnerafspraak op de inkooporder");
        if (request.costSharePct() != null) percentage(request.costSharePct(), "Eigen aandeel in de kost");
        var availability = partnerSettlementAvailability(purchaseId);
        Map<Long, PartnerSettlementLedger.Line> external = availability.lines().stream()
                .collect(Collectors.toMap(PartnerSettlementLedger.Line::productId, Function.identity()));
        if (availability.lines().stream().anyMatch(line -> line.settledQuantity() > line.totalQuantity()
                || line.remainingCostEur().signum() < 0) || availability.creditedAdvanceEur().compareTo(availability.issuedAdvanceEur()) > 0)
            throw new BusinessRuleException("Eerdere afrekeningen overschrijden de huidige aantallen, kosten of voorschotten; controleer eerst de bestaande afrekeningen");
        List<SalesOrder> previous = linked.stream().filter(order -> order.isInvoice()
                && order.purpose() == SalesPurpose.PARTNER_SETTLEMENT && PartnerFinancingService.live(order)).toList();
        if (previous.stream().anyMatch(order -> order.partnerSharePct() != null && order.partnerSharePct().compareTo(profitShare) != 0))
            throw new BusinessRuleException("Gebruik dezelfde winstdeling als de eerdere deelafrekeningen van deze container");
        List<SalesOrderLine> lines = new java.util.ArrayList<>();
        List<PartnerSettlements.Line> snapshotLines = new java.util.ArrayList<>();
        BigDecimal costTotal = BigDecimal.ZERO;
        BigDecimal proceedsTotal = BigDecimal.ZERO;
        BigDecimal fullTotal = BigDecimal.ZERO;
        Set<Long> seen = new HashSet<>();
        StringBuilder detail = new StringBuilder();
        for (AuctionLine input : request.lines()) {
            if (input == null || input.productId() == null || input.quantity() <= 0 || !seen.add(input.productId()))
                throw new BusinessRuleException("Elke veilingregel heeft één uniek product en een positief aantal");
            var basis = external.get(input.productId());
            if (basis == null || basis.remainingQuantity() <= 0 || input.quantity() > basis.remainingQuantity())
                throw new BusinessRuleException("Het veilingaantal overschrijdt de nog af te rekenen containerstukken voor product " + input.productId());
            if (input.proceedsEur() == null) throw new BusinessRuleException("Vul voor elk product een netto veilingopbrengst in, ook bij nul");
            BigDecimal proceeds = input.proceedsEur();
            if (proceeds.signum() < 0) throw new BusinessRuleException("Netto veilingopbrengst kan niet negatief zijn");
            proceeds = proceeds.setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal quantity = BigDecimal.valueOf(input.quantity());
            BigDecimal cost = input.quantity() == basis.remainingQuantity() ? basis.remainingCostEur()
                    : basis.remainingCostEur().multiply(quantity)
                    .divide(BigDecimal.valueOf(basis.remainingQuantity()), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal profit = proceeds.subtract(cost);
            BigDecimal full = cost.add(profit.multiply(profitShare).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP));
            lines.add(new SalesOrderLine(null, input.productId(), input.quantity(),
                    full.divide(quantity, 4, java.math.RoundingMode.HALF_UP), null, null,
                    cost.divide(quantity, 4, java.math.RoundingMode.HALF_UP)));
            snapshotLines.add(new PartnerSettlements.Line(input.productId(), input.quantity(), proceeds, cost, full, BigDecimal.ZERO));
            costTotal = costTotal.add(cost); proceedsTotal = proceedsTotal.add(proceeds); fullTotal = fullTotal.add(full);
            detail.append(basis.productName()).append(": ").append(input.quantity()).append(" stuks; netto veiling € ")
                    .append(money(proceeds)).append("; externe kost € ").append(money(cost))
                    .append("; resultaat € ").append(money(profit)).append("; onze waarde € ").append(money(full)).append('\n');
        }
        boolean finalSettlement = external.values().stream().filter(row -> row.remainingQuantity() > 0)
                .allMatch(row -> request.lines().stream().anyMatch(line -> row.productId().equals(line.productId())
                        && line.quantity() == row.remainingQuantity()));
        if (Boolean.TRUE.equals(request.finalSettlement()) && !finalSettlement)
            throw new BusinessRuleException("Een slotfactuur rekent alle resterende bruikbare stukken af; kies een deelafrekening voor een kleiner aantal");
        BigDecimal remainingCost = availability.lines().stream().map(PartnerSettlementLedger.Line::remainingCostEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal advance;
        if (finalSettlement) advance = availability.remainingAdvanceEur();
        else if (remainingCost.signum() > 0) advance = availability.remainingAdvanceEur().multiply(costTotal)
                .divide(remainingCost, 2, java.math.RoundingMode.HALF_UP).min(availability.remainingAdvanceEur());
        else advance = availability.remainingAdvanceEur().multiply(BigDecimal.valueOf(lines.stream().mapToInt(SalesOrderLine::quantity).sum()))
                .divide(BigDecimal.valueOf(availability.lines().stream().mapToInt(PartnerSettlementLedger.Line::remainingQuantity).sum()), 2, java.math.RoundingMode.HALF_UP);
        if (finalSettlement && !previous.isEmpty()) {
            BigDecimal previousRevenue = BigDecimal.ZERO, previousCost = BigDecimal.ZERO, previousProceeds = BigDecimal.ZERO;
            for (var earlier : previous) {
                var snapshot = PartnerSettlementLedger.normalizedSnapshot(earlier, price(earlier),
                        partnerSettlements != null && partnerSettlements.isResolvable() ? partnerSettlements.get().find(earlier.id()) : null);
                previousRevenue = previousRevenue.add(snapshot.revenueEur()); previousCost = previousCost.add(snapshot.costEur());
                previousProceeds = previousProceeds.add(snapshot.lines().stream().map(PartnerSettlements.Line::proceedsEur).reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            BigDecimal allCost = previousCost.add(costTotal);
            BigDecimal allValue = allCost.add(previousProceeds.add(proceedsTotal).subtract(allCost)
                    .multiply(profitShare).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP));
            BigDecimal adjusted = allValue.subtract(previousRevenue);
            var last = snapshotLines.removeLast();
            snapshotLines.add(new PartnerSettlements.Line(last.productId(), last.quantity(), last.proceedsEur(), last.costEur(),
                    last.revenueEur().add(adjusted.subtract(fullTotal)), BigDecimal.ZERO));
            fullTotal = adjusted;
        }
        List<BigDecimal> advanceParts = PartnerSettlementLedger.allocate(advance,
                snapshotLines.stream().map(PartnerSettlements.Line::costEur).toList());
        for (int i = 0; i < snapshotLines.size(); i++) {
            var row = snapshotLines.get(i);
            snapshotLines.set(i, new PartnerSettlements.Line(row.productId(), row.quantity(), row.proceedsEur(), row.costEur(), row.revenueEur(), advanceParts.get(i)));
        }
        List<SalesExtraLine> extras = new java.util.ArrayList<>();
        BigDecimal roundedLines = lines.stream().map(line -> line.unitPriceEur().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
        if (fullTotal.compareTo(roundedLines) != 0) extras.add(new SalesExtraLine("Afronding slotafrekening", BigDecimal.ONE, fullTotal.subtract(roundedLines)));
        if (advance.signum() > 0) extras.add(new SalesExtraLine("Voorschot verrekend · uitgereikte voorschotfacturen", BigDecimal.ONE, advance.negate()));
        Customer partner = customers.get(customerId);
        String reference = container.number();
        String documentName = finalSettlement ? "Slotfactuur" : "Deelfactuur";
        String notes = documentName + " partnercontainer " + reference + " · " + pct(profitShare)
                + " % van netto veilingresultaat (ook verlies).\n" + detail
                + "Externe kost € " + money(costTotal) + "; netto opbrengst € " + money(proceedsTotal)
                + "; volledige waarde € " + money(fullTotal) + "; uitgereikte voorschotten verrekend € " + money(advance)
                + "; saldo € " + money(fullTotal.subtract(advance)) + "."
                + (fullTotal.compareTo(advance) < 0 ? " Negatief saldo: tegoed voor de partner, geen inkomende betaling." : "")
                + " Open voorschotfacturen blijven afzonderlijk te betalen; ontvangen bedragen staan in het betaalregister.";
        LocalDate today = LocalDate.now();
        SalesOrder invoice = new SalesOrder(null, nextPartnerInvoiceNumber(), partner.id(), partner.countryCode(),
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT,
                isBlank(partner.incoterm()) ? "DAP" : partner.incoterm(), partner.paymentTerms(), notes,
                MarkupMode.PRODUCT, BigDecimal.ZERO, null, null,
                null, null, null, 0, null, null, null, request.note(),
                DeliveryTermsState.VOLLEDIG, FreightState.AANGEVULD, BigDecimal.ZERO,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.FIXED, null, null, null,
                DocumentType.FACTUUR, BusinessDays.add(today, 30), null, null, null, lines, List.of())
                .withExtraLines(extras).withPartnerDeal(purchaseId, profitShare).withSalesChannel("PARTNER").asPartnerSettlement()
                .withPurpose(SalesPurpose.PARTNER_SETTLEMENT, purchaseId, SalesPaymentPlan.FULL);
        validateForSave(invoice);
        SalesOrder created = orders.save(invoice);
        if (partnerSettlements != null && partnerSettlements.isResolvable())
            partnerSettlements.get().save(created.id(), purchaseId, new PartnerSettlements.Snapshot(fullTotal, costTotal, advance,
                    finalSettlement, List.copyOf(snapshotLines)));
        adoptPartnerContainer(purchaseId, partner.id(), request.costSharePct() == null ? container.partnerCostPctOrDefault()
                : HUNDRED.subtract(percentage(request.costSharePct(), "Eigen aandeel in de kost")), profitShare);
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT, Instant.now(), currentActor().displayName(), false,
                documentName + " partnercontainer " + reference, null));
        if (source != null) events.add(new QuoteEvent(null, source.id(), QuoteEvent.Type.GEFACTUREERD, Instant.now(),
                currentActor().displayName(), false, documentName + " " + created.number() + " aangemaakt", null));
        recordActivity(created, documentName + " partnercontainer " + reference + " aangemaakt");
        recordPurchaseActivity(purchaseId, reference, documentName + " " + created.number() + ": € " + money(fullTotal.subtract(advance)));
        return created;
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static BigDecimal percentage(BigDecimal value, String what) {
        if (value == null || value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new BusinessRuleException(what + " ligt tussen 0 en 100 procent");
        }
        return value;
    }

    /** Ties a document to the container a partner co-finances, or cuts that tie with a null container. */
    public record PartnerDealRequest(Long purchaseOrderId, BigDecimal sharePct, String reference,
                                     SalesPurpose purpose, SalesPaymentPlan paymentPlan) {
        public PartnerDealRequest(Long purchaseOrderId, BigDecimal sharePct, String reference) {
            this(purchaseOrderId, sharePct, reference, null, null);
        }
    }

    /**
     * Links a quote or invoice to a partner container after the fact, so a
     * container we first paid ourselves can still be split with the partner
     * who takes it over, and the analyses can tell our money from theirs.
     */
    @Transactional
    public SalesOrder setPartnerDeal(long id, PartnerDealRequest request) {
        orders.lockById(id);
        SalesOrder order = get(id);
        if (hasAdvanceAgreement(order))
            throw new BusinessRuleException("De klant, inkooporder en betalingsafspraken van deze offerte staan vast; maak een nieuwe offerte vanuit de inkooporder voor een gewijzigde afspraak");
        Long purchaseOrderId = request == null ? null : request.purchaseOrderId();
        BigDecimal share = null;
        if (purchaseOrderId != null) {
            share = request.sharePct() != null ? request.sharePct()
                    : order.partnerSharePct() != null ? order.partnerSharePct() : new BigDecimal("50");
            if (share.signum() < 0 || share.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessRuleException("De winstdeling ligt tussen 0 en 100 procent");
            }
        }
        SalesPurpose purpose = request != null && request.purpose() != null ? request.purpose()
                : purchaseOrderId == null ? SalesPurpose.STANDARD : SalesPurpose.PARTNER_ADVANCE;
        if (!order.isInvoice() && purpose != SalesPurpose.STANDARD)
            throw new BusinessRuleException("Partnervoorschotten zijn conceptfacturen; maak de facturen vanuit het termijnplan van de inkooporder");
        if (purpose == SalesPurpose.PARTNER_SETTLEMENT) throw new BusinessRuleException("Maak de slotfactuur via de veilingafrekening");
        if (hasSettlementSnapshot(order))
            throw new BusinessRuleException("De partner en container van deze slotafrekening zijn vastgelegd; maak het concept opnieuw voor een correctie");
        if (purpose != SalesPurpose.STANDARD && purchaseOrderId == null)
            throw new BusinessRuleException("Koppel een partnervoorschot aan een inkooporder");
        if (order.status() != QuoteStatus.CONCEPT && (purpose != order.purpose()
                || !Objects.equals(purchaseOrderId, order.linkedPurchaseOrderId())
                || !samePercentage(purpose == SalesPurpose.STANDARD ? null : share, order.partnerSharePct())))
            throw new BusinessRuleException("Het documenttype, de container en de winstdeling kunnen alleen op een concept worden gewijzigd");
        if (purchaseOrderId != null && purchaseOrders != null && purchaseOrders.isResolvable()) {
            PurchaseOrder container = purchaseOrders.get().lockForPartnerSettlement(purchaseOrderId);
            if (purpose != SalesPurpose.STANDARD && container.partnerCustomerId() != null
                    && !Objects.equals(container.partnerCustomerId(), order.customerId()))
                throw new BusinessRuleException("Deze container is aan een andere partner gekoppeld");
        }
        SalesOrder changed = order.withPartnerDeal(purchaseOrderId, share).withPurpose(purpose, purchaseOrderId,
                request != null && request.paymentPlan() != null ? request.paymentPlan() : order.paymentPlan());
        if (purchaseOrderId != null && purpose != SalesPurpose.STANDARD)
            adoptPartnerContainer(purchaseOrderId, order.customerId(), partnerCostPctOf(order.customerId()), share);
        if (changed.status() == QuoteStatus.CONCEPT) validatePartnerAdvanceReservation(changed, null);
        SalesOrder saved = orders.save(changed);
        String reference = request != null && !isBlank(request.reference())
                ? request.reference().strip() : "inkooporder " + purchaseOrderId;
        String summary = purchaseOrderId == null
                ? "Losgekoppeld van de partnercontainer"
                : "Gekoppeld aan partnercontainer " + reference + " · "
                        + money(share).replace(",00", "") + " % winstdeling";
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.PARTNER_GEKOPPELD,
                java.time.Instant.now(), currentActor().displayName(), false, summary, null));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, summary);
        Long containerId = purchaseOrderId != null ? purchaseOrderId : order.partnerPurchaseOrderId();
        recordPurchaseActivity(containerId, request != null && !isBlank(request.reference()) ? request.reference().strip() : null,
                purchaseOrderId == null
                        ? saved.number() + " losgekoppeld als partnerdocument"
                        : saved.number() + " gekoppeld als partnerdocument · " + money(share).replace(",00", "") + " % winstdeling");
        return saved;
    }

    private static String money(BigDecimal amount) {
        return String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f",
                amount.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * The goods physically leave: every line is written out of the stock
     * book, once. Nothing calls this automatically - the app asks for an
     * explicit confirmation first, because an unbooked shipment is easy to
     * fix and a double booking is not.
     */
    @Transactional
    public SalesOrder shipGoods(long id) {
        orders.lockById(id);
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.isPartnerAdvance()) throw new BusinessRuleException("Een voorschotfactuur boekt geen voorraad af; gebruik de slotfactuur voor de levering");
        if (invoice.status() != QuoteStatus.VERZONDEN && invoice.status() != QuoteStatus.UITGEREIKT && invoice.status() != QuoteStatus.BETAALD) {
            throw new BusinessRuleException("Verstuur eerst de factuur; daarna kan de bestelling afgepunt worden");
        }
        if (invoice.goodsShippedAt() != null) {
            throw new BusinessRuleException("De voorraad van deze bestelling is al afgepunt");
        }
        if (invoice.lines().isEmpty()) {
            throw new BusinessRuleException("Deze factuur heeft geen productregels om af te punten");
        }
        Instant shippedAt = Instant.now();
        boolean historicalShipment = false;
        boolean entirelyHistorical = false;
        Map<Long, Integer> quantitiesToBook = invoice.lines().stream().filter(line -> line.quantity() > 0)
                .collect(Collectors.toMap(SalesOrderLine::productId, SalesOrderLine::quantity, Integer::sum));
        if (invoice.purpose() == SalesPurpose.PARTNER_SETTLEMENT) {
            if (purchaseOrders != null && purchaseOrders.isResolvable())
                purchaseOrders.get().lockForPartnerSettlement(invoice.linkedPurchaseOrderId());
            List<SalesOrder> shippedAdvances = orders.findAll().stream().filter(order -> order.isPartnerAdvance()
                    && order.goodsShippedAt() != null
                    && Objects.equals(invoice.linkedPurchaseOrderId(), order.linkedPurchaseOrderId())).toList();
            if (!shippedAdvances.isEmpty()) {
                Map<Long, Integer> previouslyShipped = shippedAdvances.stream().flatMap(order -> order.lines().stream())
                        .filter(line -> line.quantity() > 0).collect(Collectors.toMap(SalesOrderLine::productId,
                                SalesOrderLine::quantity, Integer::sum));
                Map<Long, Integer> containerQuantities = purchaseOrders != null && purchaseOrders.isResolvable()
                        ? purchaseOrders.get().reconciliation(invoice.linkedPurchaseOrderId()).lines().stream().collect(Collectors.toMap(
                            be.enrosed.sourcing.domain.PurchaseReconciliation.Line::productId,
                            be.enrosed.sourcing.domain.PurchaseReconciliation.Line::unitCostQuantity))
                        : Map.copyOf(quantitiesToBook);
                if (previouslyShipped.entrySet().stream().anyMatch(entry -> entry.getValue() > containerQuantities.getOrDefault(entry.getKey(), 0)))
                    throw new BusinessRuleException("Op deze container is al voorraad afgepunt via historische voorschotfacturen, met andere aantallen; controleer eerst de voorraadboeking");
                Map<Long, Integer> settledShipments = orders.findAll().stream().filter(order -> !Objects.equals(order.id(), invoice.id())
                        && order.purpose() == SalesPurpose.PARTNER_SETTLEMENT && order.goodsShippedAt() != null
                        && Objects.equals(order.linkedPurchaseOrderId(), invoice.linkedPurchaseOrderId()))
                        .flatMap(order -> order.lines().stream()).filter(line -> line.quantity() > 0)
                        .collect(Collectors.toMap(SalesOrderLine::productId, SalesOrderLine::quantity, Integer::sum));
                for (var entry : quantitiesToBook.entrySet()) {
                    int historicRemaining = Math.max(0, previouslyShipped.getOrDefault(entry.getKey(), 0) - settledShipments.getOrDefault(entry.getKey(), 0));
                    int reused = Math.min(entry.getValue(), historicRemaining);
                    if (reused > 0) historicalShipment = true;
                    entry.setValue(entry.getValue() - reused);
                }
                entirelyHistorical = quantitiesToBook.values().stream().allMatch(quantity -> quantity == 0);
                if (entirelyHistorical) shippedAt = shippedAdvances.stream().map(SalesOrder::goodsShippedAt).max(Instant::compareTo).orElseThrow();
            }
        }
        for (var entry : quantitiesToBook.entrySet()) if (entry.getValue() > 0)
            products.sellStock(entry.getKey(), entry.getValue(), invoice.number());
        ActorRef actor = currentActor();
        SalesOrder shipped = orders.save(withGoodsShipped(invoice, shippedAt));
        String summary = entirelyHistorical ? "Levering overgenomen uit historische voorschotfacturen; voorraad was al afgepunt"
                : historicalShipment ? "Historische voorraadboeking verrekend; resterende stukken afgepunt" : "Bestelling verzonden - voorraad afgepunt";
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.BESTELLING_VERZONDEN,
                java.time.Instant.now(), actor.displayName(), false,
                summary, null));
        recordActivity(SALES_ACTION_SHIPPED, shipped, historicalShipment ? summary : "Bestelling verzonden en voorraad afgepunt");
        return shipped;
    }

    private static SalesOrder withGoodsShipped(SalesOrder order, java.time.Instant at) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(),
                order.freightCarrierId(), order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                at, order.lines(), order.pallets()).carrying(order);
    }

    /** Records an issued invoice without claiming that a customer message was sent. */
    @Transactional
    public SalesOrder issueInvoice(long id) {
        orders.lockById(id);
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.status() == QuoteStatus.UITGEREIKT || invoice.status() == QuoteStatus.BETAALD) return invoice;
        if (invoice.status() != QuoteStatus.CONCEPT)
            throw new BusinessRuleException("Alleen een conceptfactuur kan uitgereikt worden");
        requireAdvanceBeforeSettlement(invoice);
        validatePartnerAdvanceReservation(invoice, null);
        validateInvoiceForSend(invoice);
        SalesOrder saved = orders.save(withStatus(invoice, QuoteStatus.UITGEREIKT, null, null));
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.UITGEREIKT, Instant.now(),
                currentActor().displayName(), false, "Factuur uitgereikt zonder verzending", null));
        recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Factuur uitgereikt zonder verzending");
        return saved;
    }

    /** Invoices skip the portal: sending is a bookkeeping fact, not a mail flow. */
    @Transactional
    public SalesOrder markInvoiceSent(long id) {
        orders.lockById(id);
        SalesOrder invoice = requireInvoice(get(id));
        if ((invoice.status() == QuoteStatus.VERZONDEN || invoice.status() == QuoteStatus.BETAALD)
                && invoice.sentAt() != null) return invoice;
        validateInvoiceDispatch(invoice);
        SalesOrder sent = withStatus(invoice, invoice.status() == QuoteStatus.BETAALD ? QuoteStatus.BETAALD : QuoteStatus.VERZONDEN,
                java.time.Instant.now(), invoice.paidAt());
        SalesOrder saved = orders.save(sent);
        ActorRef actor = currentActor();
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.VERSTUURD,
                java.time.Instant.now(), actor.displayName(), false,
                "Factuur verstuurd", null));
        recordActivity(SALES_ACTION_SENT, saved, "Factuur verstuurd");
        fireActivityPush(SalesActivityPushNotifier.Ready.staffInvoiceSent(
                saved.id(), saved.number(), actor));
        return saved;
    }

    @Transactional
    public SalesOrder markInvoicePaid(long id) {
        if (incomingPayments != null && incomingPayments.isResolvable()) return incomingPayments.get().markPaid(id);
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.status() == QuoteStatus.BETAALD) return invoice;
        if (invoice.status() == QuoteStatus.CONCEPT) {
            throw new BusinessRuleException("Verstuur de factuur voor je ze betaald meldt");
        }
        SalesOrder paid = withStatus(invoice, QuoteStatus.BETAALD,
                invoice.sentAt(), java.time.Instant.now());
        SalesOrder saved = orders.save(paid);
        ActorRef actor = currentActor();
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.BETAALD,
                java.time.Instant.now(), actor.displayName(), false, "Factuur betaald", null));
        recordActivity(SALES_ACTION_PAID, saved, "Factuur als betaald gemarkeerd");
        return saved;
    }

    /**
     * A quote may leave with open ends - freight to be determined, delivery
     * in consultation. An invoice may not: it is a payment claim, and every
     * open end becomes a discussion about the amount. This is the line
     * between the two document sorts.
     */
    public void validateInvoiceForSend(SalesOrder invoice) {
        /* A settlement invoice carries only its own line; that is a full document too. */
        if (invoice.lines().isEmpty() && invoice.extraLines().isEmpty()) {
            throw new BusinessRuleException("Een factuur zonder regels kan niet verstuurd worden");
        }
        if (invoice.customerId() == null) {
            throw new BusinessRuleException("Koppel eerst een klant aan de factuur");
        }
        if (invoice.freight() == FreightState.TE_BEPALEN) {
            throw new BusinessRuleException(
                    "De vracht staat nog op 'later bepalen' - een factuur moet volledig geprijsd zijn");
        }
        if (invoice.invoiceDueDate() == null) {
            throw new BusinessRuleException("Vul de vervaldatum van de factuur in");
        }
        if (!invoice.isPartnerDeal()) {
            PricedOrder priced = price(invoice);
            if (priced != null && !priced.validation().meetsMinimum())
                throw new BusinessRuleException("De factuur haalt de minimum orderwaarde niet - er ontbreekt nog " + priced.validation().shortfall() + " EUR");
        }
        Customer customer = customers.get(invoice.customerId());
        if (isBlank(customer.address()) || isBlank(customer.postalCode()) || isBlank(customer.city())) {
            throw new BusinessRuleException("Vul het volledige adres van " + customer.company()
                    + " in - een factuur zonder adres is niet geldig");
        }
        Country country = countries.find(invoice.countryCode());
        boolean intraEu = country != null && country.euMember()
                && !"BE".equalsIgnoreCase(invoice.countryCode());
        if (intraEu && isBlank(customer.vatNumber())) {
            throw new BusinessRuleException("Vul het BTW-nummer van " + customer.company()
                    + " in - zonder geldig BTW-nummer kan de BTW niet verlegd worden");
        }
    }

    /** Called before an email leaves as well as before a manual sent marker. */
    public void validateInvoiceDispatch(SalesOrder invoice) {
        if (invoice.status() != QuoteStatus.CONCEPT && invoice.status() != QuoteStatus.UITGEREIKT
                && invoice.status() != QuoteStatus.BETAALD)
            throw new BusinessRuleException("Alleen een actieve conceptfactuur of uitgereikte factuur kan verstuurd worden");
        if (invoice.status() == QuoteStatus.CONCEPT) {
            requireAdvanceBeforeSettlement(invoice);
            validatePartnerAdvanceReservation(invoice, null);
        }
        validateInvoiceForSend(invoice);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean samePercentage(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private boolean hasSettlementSnapshot(SalesOrder order) {
        return order.id() != null && partnerSettlements != null && partnerSettlements.isResolvable()
                && partnerSettlements.get().find(order.id()) != null;
    }

    public boolean hasAdvanceAgreement(SalesOrder order) {
        return order != null && order.id() != null && advanceQuotes != null && advanceQuotes.isResolvable()
                && advanceQuotes.get().find(order.id()) != null;
    }

    /** The settlement has already fixed the advance credit; later issuance would collect it twice. */
    private void requireAdvanceBeforeSettlement(SalesOrder invoice) {
        if (!invoice.isPartnerAdvance()) return;
        Long purchaseId = invoice.linkedPurchaseOrderId();
        if (purchaseOrders != null && purchaseOrders.isResolvable())
            purchaseOrders.get().lockForPartnerSettlement(purchaseId);
        SalesOrder settlement = orders.findAll().stream().filter(order -> order.isInvoice()
                && order.purpose() == SalesPurpose.PARTNER_SETTLEMENT
                && Objects.equals(purchaseId, order.linkedPurchaseOrderId())
                && PartnerFinancingService.live(order)).findFirst().orElse(null);
        if (settlement != null)
            throw new BusinessRuleException("Afrekening " + settlement.number()
                    + " heeft de voorschotten al verrekend; nieuwe voorschotfacturen zijn niet meer mogelijk");
    }

    private static SalesOrder requireInvoice(SalesOrder order) {
        if (!order.isInvoice()) {
            throw new BusinessRuleException("Dit document is geen factuur");
        }
        return order;
    }

    private static SalesOrder withStatus(SalesOrder order, QuoteStatus status,
                                         java.time.Instant sentAt, java.time.Instant paidAt) {
        return new SalesOrder(order.id(), order.number(), order.customerId(),
                order.countryCode(), order.orderDate(), order.validUntil(), status,
                order.incoterm(), order.paymentTerms(), order.notes(), order.markupMode(),
                order.orderMarkupPct(), order.extraDiscountPct(), order.extraDiscountLabel(),
                order.portalToken(), sentAt, order.viewedAt(), order.viewCount(),
                order.decidedAt(), order.signedByName(), order.customerMessage(),
                order.internalNotes(), order.deliveryTerms(), order.freight(),
                order.manualFreightEur(), order.loadMode(), order.palletProfile(),
                order.maxPalletHeightCm(), order.freightPricingStrategy(),
                order.freightRatePerCbmEur(), order.freightCarrierId(),
                order.freightCarrierExtraEur(), order.docType(),
                order.invoiceDueDate(), paidAt, order.sourceQuoteId(),
                order.goodsShippedAt(),
                order.lines(), order.pallets()).carrying(order);
    }

    @Transactional
    public SalesOrder update(long id, SalesOrder changes) {
        if (changes == null) throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        SalesOrder beforeEdit = get(id);
        if (hasAdvanceAgreement(beforeEdit) && (!sameQuotedLines(beforeEdit.lines(), changes.lines())
                || !sameQuotedExtras(beforeEdit.extraLines(), changes.extraLines())
                || !Objects.equals(beforeEdit.customerId(), changes.customerId())
                || !Objects.equals(beforeEdit.countryCode(), changes.countryCode())
                || !samePercentage(beforeEdit.extraDiscountPct(), changes.extraDiscountPct())
                || !Objects.equals(beforeEdit.paymentTerms(), changes.paymentTerms())
                || (changes.paymentPlanOrNull() != null && beforeEdit.paymentPlan() != changes.paymentPlan())
                || (changes.markupMode() != null && beforeEdit.markupMode() != changes.markupMode())
                || (changes.orderMarkupPct() != null && !samePercentage(beforeEdit.orderMarkupPct(), changes.orderMarkupPct()))
                || (changes.manualFreightEur() != null && changes.manualFreightEur().signum() != 0)
                || (changes.freightCarrierExtraEur() != null && changes.freightCarrierExtraEur().signum() != 0)
                || (changes.freightPricingStrategyOrNull() != null && changes.freightPricingStrategy() != FreightPricingStrategy.FIXED)))
            throw new BusinessRuleException("De producten en betalingsafspraken van deze offerte staan vast; maak een nieuwe offerte vanuit de inkooporder voor een gewijzigde afspraak");
        if (changes.partnerPurchaseOrderId() != null
                && (!Objects.equals(changes.partnerPurchaseOrderId(), beforeEdit.partnerPurchaseOrderId())
                    || !samePercentage(changes.partnerSharePct(), beforeEdit.partnerSharePct())))
            throw new BusinessRuleException("Wijzig de partnerkoppeling via de partnergegevens van het document");
        if (hasSettlementSnapshot(beforeEdit)) {
            if (!beforeEdit.lines().equals(changes.lines()) || !beforeEdit.extraLines().equals(changes.extraLines())
                    || !Objects.equals(beforeEdit.customerId(), changes.customerId())
                    || !Objects.equals(beforeEdit.countryCode(), changes.countryCode())
                    || !Objects.equals(beforeEdit.extraDiscountPct(), changes.extraDiscountPct())
                    || (changes.manualFreightEur() != null && changes.manualFreightEur().signum() != 0)
                    || (changes.freightCarrierExtraEur() != null && changes.freightCarrierExtraEur().signum() != 0)
                    || (changes.freightPricingStrategyOrNull() != null && changes.freightPricingStrategy() != FreightPricingStrategy.FIXED))
                throw new BusinessRuleException("De bedragen van deze slotafrekening zijn vastgelegd; verwijder het concept en maak de afrekening opnieuw voor een correctie");
        }
        SalesOrder current = get(id);
        SalesLifecycle.requireEditable(current);
        if (changes == null) {
            throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        }

        FreightPricingStrategy freightStrategy = freightStrategyForUpdate(current, changes);
        BigDecimal manualFreight = freightStrategy == FreightPricingStrategy.FIXED
                ? changes.manualFreightEur() : null;
        BigDecimal cbmRate = freightStrategy == FreightPricingStrategy.PER_CBM
                ? changes.freightRatePerCbmEur()
                : null;
        /* The chosen organisation is remembered even while another strategy
           is selected, so switching back does not lose the pick. */
        Long carrierId = changes.freightCarrierId() == null
                ? current.freightCarrierId() : changes.freightCarrierId();

        SalesOrder updated = new SalesOrder(
                current.id(), numberFor(current, changes),
                changes.customerId(), changes.countryCode(),
                changes.orderDate(), changes.validUntil(),
                /* The status is driven by the quote workflow, not by a plain update. */
                current.status(),
                changes.incoterm(), changes.paymentTerms(), changes.notes(),
                changes.markupMode() == null ? current.markupMode() : changes.markupMode(),
                changes.orderMarkupPct() == null ? current.orderMarkupPct() : changes.orderMarkupPct(),
                changes.extraDiscountPct(), changes.extraDiscountLabel(),
                current.portalToken(), current.sentAt(), current.viewedAt(), current.viewCount(),
                current.decidedAt(), current.signedByName(), current.customerMessage(),
                /* Internal notes DO come from the form. */
                changes.internalNotes(),
                /* The delivery-terms state is driven by the quote workflow: it
                   follows from what was sent, not from what is filled in. */
                current.deliveryTerms(),
                /* The freight state is ours to set on screen ("to be
                   determined later"), so it DOES come from the form. When not
                   sent along, what was there stays. */
                changes.freightOrNull() == null ? current.freight() : changes.freight(),
                manualFreight,
                changes.loadModeOrNull() == null ? current.loadMode() : changes.loadMode(),
                changes.palletProfileOrNull() == null
                        ? current.palletProfile() : changes.palletProfile(),
                /* Null deliberately means: return to the configured default. */
                changes.maxPalletHeightCm(),
                freightStrategy, cbmRate, carrierId,
                changes.freightCarrierExtraEur() == null
                        ? current.freightCarrierExtraEur() : changes.freightCarrierExtraEur(),
                current.docType(),
                changes.invoiceDueDate() == null ? current.invoiceDueDate() : changes.invoiceDueDate(),
                current.paidAt(), current.sourceQuoteId(), current.goodsShippedAt(),
                /* A partner deal keeps the container's exact pieces; other documents ship full cartons. */
                withCostSnapshots(current.isPartnerDeal() || changes.partnerPurchaseOrderId() != null
                        ? changes.lines() : roundLinesToCartons(changes.lines()), current.lines()),
                changes.pallets())
                .withExtraLines(keptExtraLines(changes.extraLines()))
                /* Null means an update client that does not know the deal: keep it. */
                .withPartnerDeal(
                        changes.partnerPurchaseOrderId() == null
                                ? current.partnerPurchaseOrderId() : changes.partnerPurchaseOrderId(),
                        changes.partnerPurchaseOrderId() == null
                                ? current.partnerSharePct() : changes.partnerSharePct())
                /* The raw field: an update client that never heard of channels leaves it as it was. */
                .withSalesChannel(changes.rawSalesChannel() == null ? current.rawSalesChannel() : changes.rawSalesChannel())
                .withPurpose(current.purpose(), current.linkedPurchaseOrderId(),
                        changes.paymentPlanOrNull() == null ? current.paymentPlan() : changes.paymentPlan());
        validateForSave(updated);
        validatePartnerAdvanceReservation(updated, null);
        SalesOrder saved = orders.save(updated);
        if (!saved.equals(current)) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved,
                    saved.isInvoice() ? "Factuur bijgewerkt" : "Offerte bijgewerkt",
                    salesChanges(current, saved));
        }
        return saved;
    }

    private static boolean sameQuotedLines(List<SalesOrderLine> before, List<SalesOrderLine> after) {
        if (after == null || before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            if (b == null || !Objects.equals(a.productId(), b.productId()) || a.quantity() != b.quantity()
                    || !samePercentage(a.unitPriceEur(), b.unitPriceEur())
                    || !samePercentage(a.manualDiscountPct(), b.manualDiscountPct())
                    || (b.unitCostEur() != null && !samePercentage(a.unitCostEur(), b.unitCostEur()))) return false;
        }
        return true;
    }

    private static boolean sameQuotedExtras(List<SalesExtraLine> before, List<SalesExtraLine> after) {
        if (after == null || before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            if (b == null || !Objects.equals(a.description(), b.description())
                    || !samePercentage(a.quantity(), b.quantity()) || !samePercentage(a.unitPriceEur(), b.unitPriceEur())) return false;
        }
        return true;
    }

    /** One line whose promised delivery week may be filled in separately. */
    public record DeliveryWeekChange(Long productId, String deliveryWeek) {}

    /**
     * Narrow update for a promise that was left open on a sent quotation.
     * Prices, quantities, customer data and every other field stay untouched.
     */
    @Transactional
    public SalesOrder updateDeliveryWeeks(long id, List<DeliveryWeekChange> requested) {
        SalesOrder current = get(id);
        SalesLifecycle.requireTermsEditable(current);
        if (requested == null || requested.isEmpty()) {
            throw new BusinessRuleException("Geef minstens een levertermijn mee");
        }

        Map<Long, String> weeks = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        Set<Long> productsOnOrder = current.lines().stream()
                .map(SalesOrderLine::productId)
                .collect(Collectors.toSet());
        for (DeliveryWeekChange change : requested) {
            if (change == null || change.productId() == null) {
                throw new BusinessRuleException("Elke levertermijn moet bij een product horen");
            }
            if (!productsOnOrder.contains(change.productId())) {
                throw new BusinessRuleException(
                        "Product " + change.productId() + " staat niet op deze offerte");
            }
            if (!seen.add(change.productId())) {
                throw new BusinessRuleException(
                        "Product " + change.productId() + " staat dubbel in de levertermijnen");
            }
            weeks.put(change.productId(), clean(change.deliveryWeek()));
        }

        List<SalesOrderLine> lines = current.lines().stream()
                .map(line -> weeks.containsKey(line.productId())
                        ? new SalesOrderLine(line.id(), line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), weeks.get(line.productId()), line.unitCostEur())
                        : line)
                .toList();
        SalesOrder saved = orders.save(copyWithTerms(current, current.freight(), current.manualFreightEur(),
                current.freightPricingStrategy(), current.freightRatePerCbmEur(), lines));
        if (!saved.equals(current)) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Levertermijnen bijgewerkt",
                    salesLineChanges(current, saved, true));
        }
        return saved;
    }

    /**
     * Narrow freight update for a sent quotation. Moving away from an open
     * freight item records AANGEVULD so the following mail can announce it.
     */
    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur) {
        return updateFreight(id, requestedState, manualFreightEur, null, null);
    }

    /**
     * Narrow freight update including its calculation basis. Older clients
     * omit the two new fields: a supplied fixed amount keeps its old meaning.
     */
    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur,
                                    FreightPricingStrategy requestedStrategy,
                                    BigDecimal freightRatePerCbmEur) {
        return updateFreight(id, requestedState, manualFreightEur, requestedStrategy,
                freightRatePerCbmEur, null);
    }

    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur,
                                    FreightPricingStrategy requestedStrategy,
                                    BigDecimal freightRatePerCbmEur, Long freightCarrierId) {
        SalesOrder current = get(id);
        if (hasAdvanceAgreement(current))
            throw new BusinessRuleException("De betalingsafspraken van deze offerte staan vast; maak een nieuwe offerte vanuit de inkooporder om kosten toe te voegen");
        if (hasSettlementSnapshot(current))
            throw new BusinessRuleException("De bedragen van deze slotafrekening zijn vastgelegd; maak het concept opnieuw voor een correctie");
        if (current.isInvoice() && current.status() != QuoteStatus.CONCEPT)
            throw new BusinessRuleException("De bedragen van een uitgereikte factuur kunnen niet meer worden gewijzigd");
        SalesLifecycle.requireTermsEditable(current);
        if (requestedState == null) {
            throw new BusinessRuleException("Kies of de vracht berekend of nog te bepalen is");
        }
        requireNonNegative(manualFreightEur, "Handmatige vracht");
        requireNonNegative(freightRatePerCbmEur, "CBM-vrachttarief");

        FreightState state = current.freight() == FreightState.TE_BEPALEN
                && requestedState != FreightState.TE_BEPALEN
                ? FreightState.AANGEVULD
                : requestedState;
        FreightPricingStrategy strategy = requestedStrategy;
        if (strategy == null) {
            if (manualFreightEur != null) strategy = FreightPricingStrategy.FIXED;
            else if (current.freightPricingStrategy() == FreightPricingStrategy.FIXED) {
                strategy = FreightPricingStrategy.COUNTRY_PALLET;
            } else strategy = current.freightPricingStrategy();
        }
        BigDecimal fixedTotal = strategy == FreightPricingStrategy.FIXED
                ? manualFreightEur : null;
        BigDecimal cbmRate = strategy == FreightPricingStrategy.PER_CBM
                ? (freightRatePerCbmEur == null
                        ? current.freightRatePerCbmEur() : freightRatePerCbmEur)
                : null;
        SalesOrder updated = copyWithTerms(current, state, fixedTotal,
                strategy, cbmRate, current.lines());
        if (freightCarrierId != null) {
            updated = withCarrier(updated, freightCarrierId);
        }
        validateNarrowFreightUpdate(updated);
        validatePartnerAdvanceReservation(updated, null);
        SalesOrder saved = orders.save(updated);
        if (!saved.equals(current)) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Vrachtgegevens bijgewerkt",
                    freightChanges(current, saved));
        }
        return saved;
    }

    /**
     * A sent legacy quote is commercially frozen. Filling its open freight
     * item must therefore validate only the selected tariff basis, not newly
     * reject historic pallet layouts or unrelated product data that the
     * narrow endpoint cannot repair.
     */
    private void validateNarrowFreightUpdate(SalesOrder order) {
        if (order.freight() == FreightState.TE_BEPALEN) return;

        switch (order.freightPricingStrategy()) {
            case FIXED -> {
                if (order.manualFreightEur() == null) {
                    throw new BusinessRuleException("Vul het vaste vrachtbedrag in");
                }
            }
            case PER_CBM -> {
                if (order.freightRatePerCbmEur() == null
                        || order.freightRatePerCbmEur().signum() <= 0) {
                    throw new BusinessRuleException("De vracht staat op een m3-tarief zonder bedrag"
                        + " - open Transport & levering en kies een andere vrachtberekening");
                }
                requireOuterCartonsForFreight(order, false);
            }
            case COUNTRY_PALLET -> {
                if (order.loadMode() == LoadMode.LOOSE_CARTONS) {
                    throw new BusinessRuleException(
                            "Kies bij losse dozen vracht per CBM of een vast vrachtbedrag");
                }
                /* A stored manual layout already supplies the billed number
                   of positions. Do not reopen its historic assignments. */
                if (order.pallets().isEmpty()) requireOuterCartonsForFreight(order, true);
            }
        }
    }

    private void requireOuterCartonsForFreight(SalesOrder order, boolean requirePalletFit) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        PalletSpec palletSpec = settings.pallet(order.palletProfile(), order.maxPalletHeightCm());
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.quantity() <= 0) continue;
            Product product = byId.get(line.productId());
            if (product == null || !hasValidOuterCarton(product.carton())) {
                String description = product == null ? "product " + line.productId() : product.describe();
                throw new BusinessRuleException(
                        "Vul geldige omdoosafmetingen en stuks per doos in voor " + description);
            }
            if (requirePalletFit
                    && palletCalculator.fit(product.carton(), palletSpec).cartonsPerPallet() <= 0) {
                throw new BusinessRuleException(product.describe()
                        + " past niet binnen het gekozen palletprofiel, de hoogte of het gewicht");
            }
        }
    }

    /**
     * The order number as it should be after an update.
     *
     * Being able to set a number by hand matters: numbering carries on after
     * migrating from another system, and sometimes a quote belongs to an
     * existing file. But two orders with the same number make every reference
     * ambiguous - in a mail, on an invoice, in the books - so that is
     * refused. Leaving it empty means: keep what was there.
     */
    private String numberFor(SalesOrder current, SalesOrder changes) {
        String wanted = changes.number() == null ? null : changes.number().trim();
        if (wanted == null || wanted.isBlank() || wanted.equals(current.number())) {
            return current.number();
        }
        boolean taken = orders.findAll().stream()
                .anyMatch(other -> !other.id().equals(current.id())
                        && wanted.equalsIgnoreCase(other.number()));
        if (taken) {
            throw new BusinessRuleException("Er bestaat al een verkooporder met nummer " + wanted);
        }
        return wanted;
    }

    @Transactional
    public void delete(long id) {
        orders.lockById(id);
        SalesOrder order = get(id);
        if (order.isPartnerDeal() && purchaseOrders != null && purchaseOrders.isResolvable())
            purchaseOrders.get().lockForPartnerSettlement(order.linkedPurchaseOrderId());
        if (incomingPayments != null && incomingPayments.isResolvable() && incomingPayments.get().hasHistory(id))
            throw new BusinessRuleException("Een factuur met een betaalhistoriek kan niet worden verwijderd, ook niet na intrekking van betalingen");
        boolean hasRevisions = !revisions.findByOrder(id).isEmpty();
        SalesLifecycle.requireDeletable(order, hasRevisions);
        boolean hasDerivedInvoice = !order.isInvoice() && orders.existsBySourceQuoteId(id);
        if (hasDerivedInvoice) {
            throw new BusinessRuleException(
                    "Deze offerte kan niet verwijderd worden omdat er een factuur uit is aangemaakt");
        }
        revisions.deleteByOrder(id);
        events.deleteByOrder(id);
        if (partnerSettlements != null && partnerSettlements.isResolvable()) partnerSettlements.get().delete(id);
        if (advanceQuotes != null && advanceQuotes.isResolvable()) advanceQuotes.get().delete(id);
        if (advanceSchedules != null && advanceSchedules.isResolvable()) advanceSchedules.get().detachInvoice(id);
        orders.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, order,
                order.isInvoice() ? "Factuur verwijderd" : "Offerte verwijderd");
    }

    /**
     * Puts a document away: it leaves the working list for the archive tab
     * and stays exactly as it was, links and history included.
     */
    @Transactional
    public SalesOrder archive(long id) {
        SalesOrder order = get(id);
        if (order.isArchived()) return order;
        orders.setArchivedAt(id, Instant.now());
        SalesOrder archived = get(id);
        recordActivity(ActivityLogService.ACTION_UPDATED, archived,
                archived.isInvoice() ? "Factuur gearchiveerd" : "Offerte gearchiveerd");
        return archived;
    }

    /** Back on the working list, where it left off. */
    @Transactional
    public SalesOrder unarchive(long id) {
        SalesOrder order = get(id);
        if (!order.isArchived()) return order;
        orders.setArchivedAt(id, null);
        SalesOrder restored = get(id);
        recordActivity(ActivityLogService.ACTION_UPDATED, restored,
                restored.isInvoice() ? "Factuur uit het archief gehaald" : "Offerte uit het archief gehaald");
        return restored;
    }

    @Transactional
    public SalesOrder duplicate(long id) {
        SalesOrder source = get(id);
        if (source.isPartnerAdvance())
            throw new BusinessRuleException("Een partnervoorschot kan niet worden gekopieerd; beheer de conceptfacturen via het termijnplan van de inkooporder");
        if (hasAdvanceAgreement(source))
            throw new BusinessRuleException("Maak een nieuwe offerte vanuit de inkooporder om de juiste voorschotafspraken over te nemen");
        if (source.purpose() == SalesPurpose.PARTNER_SETTLEMENT)
            throw new BusinessRuleException("Een slotafrekening kan niet worden gekopieerd; maak de afrekening vanuit de inkooporder zodat kosten en voorschotten opnieuw worden gecontroleerd");
        ActorRef actor = currentActor();
        LocalDate today = LocalDate.now();
        SalesOrder duplicate = new SalesOrder(
                null, source.isInvoice()
                        ? (source.isPartnerDeal() ? nextPartnerInvoiceNumber() : nextInvoiceNumber())
                        : (source.isPartnerDeal() ? nextPartnerQuoteNumber() : nextNumber()),
                source.customerId(), source.countryCode(),
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT, source.incoterm(),
                source.paymentTerms(), source.notes(),
                source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(),
                null, null, null, 0, null, null, null, source.internalNotes(),
                /* A copy starts clean: that quote has not left yet. */
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, source.manualFreightEur(),
                source.loadMode(), source.palletProfile(), source.maxPalletHeightCm(),
                source.freightPricingStrategy(), source.freightRatePerCbmEur(),
                source.freightCarrierId(), source.freightCarrierExtraEur(),
                source.docType(), source.isInvoice() ? BusinessDays.add(today, 30) : null, null, null, null,
                source.lines().stream()
                        .map(line -> new SalesOrderLine(null, line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek(), line.unitCostEur()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        duplicate = duplicate.withExtraLines(source.extraLines())
                .withPartnerDeal(source.partnerPurchaseOrderId(), source.partnerSharePct())
                .withSalesChannel(source.rawSalesChannel())
                .withPurpose(source.purpose(), source.linkedPurchaseOrderId(), source.paymentPlan());
        validateForSave(duplicate);
        validatePartnerAdvanceReservation(duplicate, null);
        SalesOrder created = orders.save(duplicate);
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), actor.displayName(), false,
                (created.isInvoice() ? "Factuur" : "Offerte")
                        + " gekopieerd vanuit " + source.number(), null));
        recordActivity(ActivityLogService.ACTION_DUPLICATED, created,
                (created.isInvoice() ? "Factuur" : "Offerte") + " gedupliceerd");
        fireCreationPush(created.isInvoice()
                ? SalesCreationPushNotifier.Ready.invoiceDuplicated(
                        created.id(), created.number(), source.number(), actor)
                : SalesCreationPushNotifier.Ready.quoteDuplicated(
                        created.id(), created.number(), source.number(), actor));
        return created;
    }

    /** Rechecks an existing draft/open quotation before a document leaves. */
    public void validateForSend(SalesOrder order) {
        validateForSave(order);
        validateCommercialLinesReady(order);
        validateLogisticsReady(order);
    }

    /**
     * Drafts may contain temporary zero quantities or products whose selling price still
     * needs a decision. Neither may silently disappear as a zero-value line in a customer PDF.
     */
    private void validateCommercialLinesReady(SalesOrder order) {
        boolean arrangementOnly = hasAdvanceAgreement(order);
        boolean websiteRequest = order.internalNotes() != null
                && order.internalNotes().stripLeading().startsWith(WEBSITE_REQUEST_MARKER);
        boolean hasZeroQuantity = order.lines().stream().anyMatch(line -> line.quantity() <= 0);
        if (websiteRequest && hasZeroQuantity
                && order.internalNotes().contains(WEBSITE_CARTON_UNRESOLVED_MARKER)) {
            throw new BusinessRuleException(
                    "Los eerst de aangevraagde dozen met onbekende doosinhoud op voordat je de offerte verstuurt");
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        for (SalesOrderLine line : order.lines()) {
            if (line.quantity() <= 0) {
                throw new BusinessRuleException(
                        "Vul voor elke offerteregel een positief productaantal in");
            }
            Product product = byId.get(line.productId());
            if (product == null) continue; // validateForSave already reports the missing product.
            BigDecimal effectivePrice = pricing.unitPriceFor(
                    product, order, line.unitPriceEur());
            if (!arrangementOnly && (effectivePrice == null || effectivePrice.signum() <= 0)) {
                throw new BusinessRuleException(
                        "Vul een geldige stukprijs groter dan 0 EUR in voor " + product.describe());
            }
        }
    }

    private void validateForSave(SalesOrder order) {
        if (order == null) {
            throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        }
        if (order.customerId() == null || order.customerId() <= 0) {
            throw new BusinessRuleException("Koppel een geldige klant aan de offerte");
        }
        try {
            customers.get(order.customerId());
        } catch (NotFoundException exception) {
            throw new BusinessRuleException("De gekozen klant bestaat niet meer");
        }
        if (order.countryCode() == null || order.countryCode().isBlank()) {
            throw new BusinessRuleException("Kies een geldig bestemmingsland");
        }
        if (countries.find(order.countryCode()) == null) {
            /* Say which code is the problem: nine times out of ten the customer
               carries a country that Landen & vracht does not ship to yet. */
            throw new BusinessRuleException("Bestemmingsland " + order.countryCode()
                    + " staat niet bij Landen & vracht; voeg het daar toe of pas het land van de klant aan");
        }
        if (order.orderDate() == null || order.validUntil() == null) {
            throw new BusinessRuleException("Orderdatum en geldigheidsdatum zijn verplicht");
        }
        if (order.validUntil().isBefore(order.orderDate())) {
            throw new BusinessRuleException("De geldigheidsdatum kan niet vóór de orderdatum liggen");
        }
        if (order.partnerSharePct() != null
                && (order.partnerSharePct().signum() < 0
                        || order.partnerSharePct().compareTo(new BigDecimal("100")) > 0)) {
            throw new BusinessRuleException("De winstdeling ligt tussen 0 en 100 procent");
        }
        if (order.incoterm() == null || order.incoterm().isBlank()) {
            throw new BusinessRuleException("Incoterm is verplicht");
        }
        requireValidExtraLines(order.extraLines());
        if (order.markupMode() == null) {
            throw new BusinessRuleException("Kies hoe de opslag wordt berekend");
        }
        requireNonNegative(order.orderMarkupPct(), "Opslagpercentage");
        requirePercentage(order.extraDiscountPct(), "Extra korting");
        requireNonNegative(order.manualFreightEur(), "Handmatige vracht");
        requireNonNegative(order.freightRatePerCbmEur(), "CBM-vrachttarief");

        PalletSpec palletSpec = settings.pallet(order.palletProfile(), order.maxPalletHeightCm());
        if (order.maxPalletHeightCm() != null) {
            if (order.maxPalletHeightCm().compareTo(palletSpec.baseHeightCm()) <= 0) {
                throw new BusinessRuleException(
                        "Maximale pallethoogte moet hoger zijn dan de palletbasis van "
                                + palletSpec.baseHeightCm().stripTrailingZeros().toPlainString() + " cm");
            }
            if (order.maxPalletHeightCm().compareTo(BigDecimal.valueOf(300)) > 0) {
                throw new BusinessRuleException("Maximale pallethoogte kan niet hoger zijn dan 300 cm");
            }
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke offerteregel moet bij een product horen");
            }
            if (!seen.add(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId() + " staat dubbel op de offerte");
            }
            if (!byId.containsKey(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId() + " bestaat niet meer");
            }
            if (line.quantity() < 0) {
                throw new BusinessRuleException("Een productaantal kan niet negatief zijn");
            }
            requireNonNegative(line.unitPriceEur(), "Handmatige stukprijs");
            requirePercentage(line.manualDiscountPct(), "Regelkorting");
        }
        validatePallets(order, byId, palletSpec);
    }

    /**
     * Draft autosave may be incomplete while a seller picks a strategy and
     * then types its amount. A quote may only leave once logistics is fully
     * priceable and every shipping carton can be measured.
     */
    private void validateLogisticsReady(SalesOrder order) {
        if (order.freight() != FreightState.TE_BEPALEN) {
            if (order.loadMode() == LoadMode.LOOSE_CARTONS
                    && order.freightPricingStrategy() == FreightPricingStrategy.COUNTRY_PALLET) {
                throw new BusinessRuleException(
                        "Kies bij losse dozen vracht per CBM of een vast vrachtbedrag");
            }
            if (order.freightPricingStrategy() == FreightPricingStrategy.PER_CBM
                    && (order.freightRatePerCbmEur() == null
                        || order.freightRatePerCbmEur().signum() <= 0)) {
                throw new BusinessRuleException("De vracht staat op een m3-tarief zonder bedrag"
                        + " - open Transport & levering en kies een andere vrachtberekening");
            }
            if (order.freightPricingStrategy() == FreightPricingStrategy.FIXED
                    && order.manualFreightEur() == null) {
                throw new BusinessRuleException("Vul het vaste vrachtbedrag in");
            }
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        PalletSpec palletSpec = settings.pallet(order.palletProfile(), order.maxPalletHeightCm());
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.quantity() <= 0) continue;
            Product product = byId.get(line.productId());
            if (product == null) continue; // validateForSave reports the clearer missing-product error.
            if (!hasValidOuterCarton(product.carton())) {
                throw new BusinessRuleException("Vul geldige omdoosafmetingen en stuks per doos in voor "
                        + product.describe());
            }
            if (order.loadMode() == LoadMode.PALLETS
                    && palletCalculator.fit(product.carton(), palletSpec).cartonsPerPallet() <= 0) {
                throw new BusinessRuleException(product.describe()
                        + " past niet binnen het gekozen palletprofiel, de hoogte of het gewicht");
            }
        }

        if (order.loadMode() == LoadMode.PALLETS && !order.pallets().isEmpty()) {
            Map<Long, Integer> assigned = new HashMap<>();
            for (OrderPallet pallet : order.pallets()) {
                if (pallet.items().isEmpty()) {
                    throw new BusinessRuleException(
                            "Verwijder lege pallets of zet er minstens één doos op");
                }
                for (OrderPallet.Item item : pallet.items()) {
                    assigned.merge(item.productId(), item.cartons(), Integer::sum);
                }
            }
            for (SalesOrderLine line : order.lines()) {
                Product product = byId.get(line.productId());
                if (product == null) continue;
                int ordered = product.carton() == null
                        ? Math.max(0, line.quantity())
                        : product.carton().cartonsFor(line.quantity());
                if (assigned.getOrDefault(line.productId(), 0) != ordered) {
                    throw new BusinessRuleException("Verdeel alle " + ordered
                            + " dozen van " + product.describe() + " over de pallets");
                }
            }
        }
    }

    private void validatePallets(SalesOrder order, Map<Long, Product> byId, PalletSpec palletSpec) {
        /* A loose-carton draft keeps its previous warehouse layout so the
           seller can switch back without rebuilding it. While loose, that
           hidden layout is deliberately ignored and may temporarily be stale. */
        if (order.loadMode() == LoadMode.LOOSE_CARTONS) return;

        Set<Long> productsOnOrder = order.lines().stream()
                .map(SalesOrderLine::productId)
                .collect(Collectors.toSet());
        Map<Long, Integer> assigned = new HashMap<>();

        for (OrderPallet pallet : order.pallets()) {
            if (pallet == null) {
                throw new BusinessRuleException("Een pallet mag niet leeg zijn");
            }
            if (pallet.heightCm() != null
                    && BigDecimal.valueOf(pallet.heightCm()).compareTo(palletSpec.baseHeightCm()) < 0) {
                throw new BusinessRuleException("Pallethoogte kan niet lager zijn dan de palletbasis");
            }
            if (pallet.heightCm() != null
                    && BigDecimal.valueOf(pallet.heightCm()).compareTo(palletSpec.maxHeightCm()) > 0) {
                throw new BusinessRuleException("Pallethoogte kan niet hoger zijn dan "
                        + palletSpec.maxHeightCm().stripTrailingZeros().toPlainString() + " cm");
            }
            for (OrderPallet.Item item : pallet.items()) {
                if (item == null || item.productId() <= 0 || item.cartons() <= 0) {
                    throw new BusinessRuleException("Elke palletregel moet een product en positief aantal dozen hebben");
                }
                if (!productsOnOrder.contains(item.productId()) || !byId.containsKey(item.productId())) {
                    throw new BusinessRuleException(
                            "Product " + item.productId() + " staat niet op deze offerte");
                }
                assigned.merge(item.productId(), item.cartons(), Integer::sum);
            }
        }

        for (SalesOrderLine line : order.lines()) {
            Product product = byId.get(line.productId());
            int orderedCartons = product.carton() == null
                    ? Math.max(0, line.quantity())
                    : product.carton().cartonsFor(line.quantity());
            if (assigned.getOrDefault(line.productId(), 0) > orderedCartons) {
                throw new BusinessRuleException(
                        "Er staan meer dozen van " + product.describe() + " op pallets dan op de offerte");
            }
        }
    }

    private static SalesOrder withCarrier(SalesOrder order, Long freightCarrierId) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), order.freight(), order.manualFreightEur(),
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                order.freightPricingStrategy(), order.freightRatePerCbmEur(), freightCarrierId,
                order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(),
                order.lines(), order.pallets()).carrying(order);
    }

    private static SalesOrder copyWithTerms(SalesOrder order, FreightState freight,
                                            BigDecimal manualFreightEur,
                                            FreightPricingStrategy freightPricingStrategy,
                                            BigDecimal freightRatePerCbmEur,
                                            List<SalesOrderLine> lines) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), freight, manualFreightEur,
                order.loadMode(), order.palletProfile(), order.maxPalletHeightCm(),
                freightPricingStrategy, freightRatePerCbmEur, order.freightCarrierId(),
                order.freightCarrierExtraEur(),
                order.docType(), order.invoiceDueDate(), order.paidAt(), order.sourceQuoteId(),
                order.goodsShippedAt(),
                lines, order.pallets()).carrying(order);
    }

    private static FreightPricingStrategy freightStrategyForUpdate(SalesOrder current,
                                                                    SalesOrder changes) {
        if (changes.freightPricingStrategyOrNull() != null) {
            return changes.freightPricingStrategy();
        }
        if (changes.manualFreightEur() != null) return FreightPricingStrategy.FIXED;
        if (current.freightPricingStrategy() == FreightPricingStrategy.FIXED) {
            return FreightPricingStrategy.COUNTRY_PALLET;
        }
        return current.freightPricingStrategy();
    }

    private static boolean hasValidOuterCarton(Carton carton) {
        if (carton == null || carton.piecesPerCarton() <= 0 || carton.dimensions() == null) {
            return false;
        }
        Dimensions size = carton.dimensions();
        return positive(size.lengthCm()) && positive(size.widthCm()) && positive(size.heightCm());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void requirePercentage(BigDecimal value, String label) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessRuleException(label + " moet tussen 0 en 100% liggen");
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    private ActorRef currentActor() {
        return actor != null && actor.isResolvable() ? actor.get().current() : ActorRef.SYSTEM;
    }

    /** Audit and order creation share one transaction, so neither can survive the other failing. */
    private void recordActivity(SalesOrder order, String summary) {
        recordActivity(ActivityLogService.ACTION_CREATED, order, summary);
    }

    private void recordActivity(String action, SalesOrder order, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, SALES_ORDER_ACTIVITY_TYPE,
                order.id() == null ? null : order.id().toString(), order.number(), summary);
    }

    private void recordActivity(String action, SalesOrder order, String summary,
                                List<ActivityChangeDto> changes) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, SALES_ORDER_ACTIVITY_TYPE,
                order.id() == null ? null : order.id().toString(), order.number(), summary, changes);
    }

    private static List<ActivityChangeDto> salesChanges(SalesOrder before, SalesOrder after) {
        ActivityChangeSet changes = ActivityChangeSet.create()
                .add("customerId", "Klant", before.customerId(), after.customerId())
                .add("countryCode", "Land", before.countryCode(), after.countryCode())
                .add("orderDate", "Datum", before.orderDate(), after.orderDate())
                .add("validUntil", "Geldig tot", before.validUntil(), after.validUntil())
                .add("incoterm", "Incoterm", before.incoterm(), after.incoterm())
                .add("paymentTerms", "Betaalvoorwaarden", before.paymentTerms(), after.paymentTerms())
                .add("markupMode", "Margeberekening", before.markupMode(), after.markupMode())
                .add("orderMarkupPct", "Ordermarge", before.orderMarkupPct(), after.orderMarkupPct())
                .add("extraDiscountPct", "Extra korting", before.extraDiscountPct(), after.extraDiscountPct())
                .privateValue("extraDiscountLabel", "Reden extra korting",
                        before.extraDiscountLabel(), after.extraDiscountLabel())
                .add("freight", "Vrachtstatus", before.freight(), after.freight())
                .add("manualFreightEur", "Vrachtbedrag", before.manualFreightEur(), after.manualFreightEur())
                .add("freightPricingStrategy", "Vrachtberekening",
                        before.freightPricingStrategy(), after.freightPricingStrategy())
                .add("freightRatePerCbmEur", "Vrachttarief per m³",
                        before.freightRatePerCbmEur(), after.freightRatePerCbmEur())
                .add("freightCarrierId", "Vervoerder", before.freightCarrierId(), after.freightCarrierId())
                .add("loadMode", "Laadwijze", before.loadMode(), after.loadMode())
                .add("palletProfile", "Pallettype", before.palletProfile(), after.palletProfile())
                .add("maxPalletHeightCm", "Maximale pallethoogte",
                        before.maxPalletHeightCm(), after.maxPalletHeightCm())
                .add("freightCarrierExtraEur", "Extra vervoerderskost",
                        before.freightCarrierExtraEur(), after.freightCarrierExtraEur())
                .add("palletCount", "Aantal handmatige pallets",
                        before.pallets().size(), after.pallets().size())
                .privateValue("palletLayout", "Handmatige palletindeling",
                        before.pallets(), after.pallets())
                .add("invoiceDueDate", "Vervaldatum", before.invoiceDueDate(), after.invoiceDueDate())
                .add("salesChannel", "Verkoopkanaal", before.salesChannel(), after.salesChannel())
                .add("partnerPurchaseOrderId", "Partnercontainer", before.partnerPurchaseOrderId(), after.partnerPurchaseOrderId())
                .add("partnerSharePct", "Winstdeling partner", before.partnerSharePct(), after.partnerSharePct())
                .add("lineCount", "Aantal productregels", before.lines().size(), after.lines().size())
                .add("pieceCount", "Totaal aantal stuks", totalSalesPieces(before), totalSalesPieces(after))
                .privateValue("notes", "Notitie voor klant", before.notes(), after.notes())
                .privateValue("internalNotes", "Interne notitie", before.internalNotes(), after.internalNotes());
        salesLineChanges(before, after, false).forEach(change ->
                changes.add(change.field(), change.label(), change.beforeValue(), change.afterValue()));
        return changes.build();
    }

    private static List<ActivityChangeDto> freightChanges(SalesOrder before, SalesOrder after) {
        return ActivityChangeSet.create()
                .add("freight", "Vrachtstatus", before.freight(), after.freight())
                .add("manualFreightEur", "Vrachtbedrag", before.manualFreightEur(), after.manualFreightEur())
                .add("freightPricingStrategy", "Vrachtberekening",
                        before.freightPricingStrategy(), after.freightPricingStrategy())
                .add("freightRatePerCbmEur", "Vrachttarief per m³",
                        before.freightRatePerCbmEur(), after.freightRatePerCbmEur())
                .add("freightCarrierId", "Vervoerder", before.freightCarrierId(), after.freightCarrierId())
                .build();
    }

    private static List<ActivityChangeDto> salesLineChanges(
            SalesOrder before, SalesOrder after, boolean deliveryOnly) {
        ActivityChangeSet changes = ActivityChangeSet.create();
        Map<Long, SalesOrderLine> beforeLines = before.lines().stream()
                .filter(line -> line.productId() != null)
                .collect(Collectors.toMap(SalesOrderLine::productId, Function.identity(), (left, right) -> right));
        Map<Long, SalesOrderLine> afterLines = after.lines().stream()
                .filter(line -> line.productId() != null)
                .collect(Collectors.toMap(SalesOrderLine::productId, Function.identity(), (left, right) -> right));
        Set<Long> productIds = new java.util.TreeSet<>();
        productIds.addAll(beforeLines.keySet());
        productIds.addAll(afterLines.keySet());
        for (Long productId : productIds) {
            SalesOrderLine oldLine = beforeLines.get(productId);
            SalesOrderLine newLine = afterLines.get(productId);
            String suffix = " · Product " + productId;
            if (!deliveryOnly) {
                changes.add("line." + productId + ".quantity", "Aantal" + suffix,
                        oldLine == null ? null : oldLine.quantity(), newLine == null ? null : newLine.quantity());
                changes.add("line." + productId + ".unitPrice", "Stukprijs" + suffix,
                        oldLine == null ? null : oldLine.unitPriceEur(), newLine == null ? null : newLine.unitPriceEur());
                changes.add("line." + productId + ".discount", "Korting" + suffix,
                        oldLine == null ? null : oldLine.manualDiscountPct(),
                        newLine == null ? null : newLine.manualDiscountPct());
            }
            changes.add("line." + productId + ".deliveryWeek", "Levertermijn" + suffix,
                    oldLine == null ? null : oldLine.deliveryWeek(),
                    newLine == null ? null : newLine.deliveryWeek());
        }
        return changes.build();
    }

    private static int totalSalesPieces(SalesOrder order) {
        return order.lines().stream().mapToInt(SalesOrderLine::quantity).sum();
    }

    /** CDI delivers this payload only after the transaction has committed successfully. */
    private void fireCreationPush(SalesCreationPushNotifier.Ready ready) {
        if (salesCreationPush != null) salesCreationPush.fire(ready);
    }

    private void fireActivityPush(SalesActivityPushNotifier.Ready ready) {
        if (salesActivityPush != null) salesActivityPush.fire(ready);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * We never sell half a carton: every quantity rounds up to whole outer
     * cartons on save. The screen announces the correction; this makes it
     * true no matter which client wrote the order.
     */
    /** A free line the seller added and never filled in is dropped, not rejected. */
    static List<SalesExtraLine> keptExtraLines(List<SalesExtraLine> lines) {
        if (lines == null) return List.of();
        return lines.stream()
                .filter(line -> line != null && !line.blank())
                .map(line -> new SalesExtraLine(line.description().strip(), line.quantity(), line.unitPriceEur()))
                .toList();
    }

    /** Every free line needs words the document can print, a quantity and a price. */
    private static void requireValidExtraLines(List<SalesExtraLine> lines) {
        if (lines.size() > 20) {
            throw new BusinessRuleException("Maximaal 20 extra regels per document");
        }
        for (SalesExtraLine line : lines) {
            if (line.description().isBlank()) {
                throw new BusinessRuleException("Geef elke extra regel een omschrijving");
            }
            if (line.description().length() > 120) {
                throw new BusinessRuleException("De omschrijving van een extra regel mag hoogstens 120 tekens lang zijn");
            }
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                throw new BusinessRuleException("Extra regel " + line.description() + " heeft een aantal boven nul nodig");
            }
            if (line.unitPriceEur() == null) {
                throw new BusinessRuleException("Extra regel " + line.description() + " heeft een prijs nodig");
            }
        }
    }

    private List<SalesOrderLine> roundLinesToCartons(List<SalesOrderLine> lines) {
        if (lines == null || lines.isEmpty()) return lines;
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        return lines.stream().map(line -> {
            if (line == null || line.quantity() <= 0) return line;
            Product product = byId.get(line.productId());
            int per = product == null || product.carton() == null ? 1
                    : Math.max(1, product.carton().piecesPerCarton());
            int rounded = (int) Math.ceil(line.quantity() / (double) per) * per;
            return rounded == line.quantity() ? line
                    : new SalesOrderLine(line.id(), line.productId(), rounded,
                            line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek(), line.unitCostEur());
        }).toList();
    }

    /**
     * Every line remembers what a piece cost us when it was written, so an
     * old quote's margin does not drift with the product's later containers.
     * A line that carries a cost keeps it; one the client sends back without
     * it keeps what was stored; a new one takes the product's cost of today.
     */
    private List<SalesOrderLine> withCostSnapshots(List<SalesOrderLine> lines, List<SalesOrderLine> stored) {
        if (lines == null || lines.isEmpty()) return lines;
        Map<Long, SalesOrderLine> storedById = stored == null ? Map.of() : stored.stream()
                .filter(line -> line.id() != null)
                .collect(Collectors.toMap(SalesOrderLine::id, Function.identity(), (left, right) -> left));
        Map<Long, Product> byId = null;
        List<SalesOrderLine> remembered = new java.util.ArrayList<>();
        for (SalesOrderLine line : lines) {
            if (line == null || line.hasUnitCost()) {
                remembered.add(line);
                continue;
            }
            SalesOrderLine known = line.id() == null ? null : storedById.get(line.id());
            if (known != null && known.hasUnitCost()) {
                remembered.add(line.withUnitCost(known.unitCostEur()));
                continue;
            }
            if (byId == null) {
                byId = products.list().stream().collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
            }
            Product product = byId.get(line.productId());
            BigDecimal cost = product == null ? null : product.landedCostEur();
            remembered.add(cost == null || cost.signum() <= 0 ? line
                    : line.withUnitCost(cost.setScale(4, java.math.RoundingMode.HALF_UP)));
        }
        return remembered;
    }

    private String nextNumber() {
        return nextNumber(profile().quotePrefix() + "-{jaar}-{nr}", false, null);
    }

    /** Invoices number their own gapless-enough series: F-2026-0001. */
    private String nextInvoiceNumber() {
        return nextNumber(profile().invoicePrefix() + "-{jaar}-{nr}", true, null);
    }

    /** Container quotes carry their own series, retaining sequence continuity with legacy numbers. */
    private String nextPartnerQuoteNumber() {
        return nextNumber(profile().partnerQuotePattern(), false, profile().partnerQuoteNextNumber());
    }

    /** Advance and final invoices share a neutral container series. */
    private String nextPartnerInvoiceNumber() {
        return nextNumber(profile().partnerInvoicePattern(), true, profile().partnerInvoiceNextNumber());
    }

    private be.enrosed.shared.company.CompanyProfile profile() {
        return companyProfile == null || !companyProfile.isResolvable()
                ? be.enrosed.shared.company.CompanyProfile.empty() : companyProfile.get().get();
    }

    /**
     * The next number in this year's series of quotes or of invoices. The
     * series counts on whatever the letters in front were: change the prefix
     * in settings and the numbering simply carries on under the new one.
     */
    private String nextNumber(String pattern, boolean invoices, Integer floor) {
        int year = LocalDate.now().getYear();
        /* The plain series accept any letters in front, so a changed prefix carries on; a partner
           pattern is matched as written. */
        java.util.regex.Pattern series = pattern.matches("^[A-Z0-9]+-\\{jaar\\}-\\{nr\\}$")
                ? java.util.regex.Pattern.compile("^[A-Za-z0-9]+-" + year + "-(\\d+)$")
                : NumberSeries.continuingContainerSeries(pattern, year);
        int highest = orders.findAll().stream()
                .filter(order -> order.isInvoice() == invoices)
                .map(SalesOrder::number)
                .filter(java.util.Objects::nonNull)
                .map(series::matcher)
                .filter(java.util.regex.Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElse(0);
        int next = Math.max(highest + 1, floor == null ? 1 : floor);
        return NumberSeries.format(pattern, year, next);
    }
}
