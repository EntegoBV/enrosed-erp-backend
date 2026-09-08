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
     * A new invoice with the quote's whole content frozen in.
     *
     * The quote keeps living its own life: it can be re-invoiced (partial
     * deliveries) and its history records that this invoice left from it.
     */
    @Transactional
    public SalesOrder createInvoiceFrom(long quoteId) {
        orders.lockById(quoteId);
        SalesOrder source = get(quoteId);
        if (source.isInvoice()) {
            throw new BusinessRuleException("Dit is al een factuur; maak facturen vanuit een offerte");
        }
        ActorRef creator = currentActor();
        LocalDate today = LocalDate.now();
        SalesOrder invoice = new SalesOrder(
                null, nextInvoiceNumber(), source.customerId(), source.countryCode(),
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
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        invoice = invoice.withExtraLines(source.extraLines())
                .withPartnerDeal(source.partnerPurchaseOrderId(), source.partnerSharePct())
                .withSalesChannel(source.rawSalesChannel());
        validateForSave(invoice);
        SalesOrder created = orders.save(invoice);

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), creator.displayName(), false,
                "Factuur opgemaakt vanuit " + source.number(), null));
        events.add(new QuoteEvent(null, source.id(), QuoteEvent.Type.GEFACTUREERD,
                java.time.Instant.now(), creator.displayName(), false,
                "Factuur " + created.number() + " aangemaakt", null));
        recordActivity(created, "Factuur aangemaakt vanuit offerte");
        fireCreationPush(SalesCreationPushNotifier.Ready.invoiceFromQuoteCreated(
                created.id(), created.number(), source.number(), creator));
        return created;
    }

    /** What the sheet asks for when a container becomes a quote: whose, at which prices, with which of its costs. */
    public record FromPurchaseOrderRequest(Long purchaseOrderId, Long customerId, String pricing, BigDecimal markupPct,
                                           boolean partner, BigDecimal sharePct, BigDecimal costPct,
                                           boolean includeInspection, List<Integer> otherCostIndexes, String salesChannel) {}

    /**
     * A container becomes a quote in one go: every product line with its
     * pieces, at the customer's prices or at the container's landed cost,
     * the inspection and other costs as lines of their own, and for a
     * partner the deal itself. One save, one diary line, one log entry on
     * both documents; nothing half-made is left behind when a rule fails.
     */
    @Transactional
    public SalesOrder createFromPurchaseOrder(FromPurchaseOrderRequest request) {
        if (request == null || request.purchaseOrderId() == null) throw new BusinessRuleException("Kies een inkooporder");
        if (request.customerId() == null) throw new BusinessRuleException("Kies de klant voor de offerte");
        if (purchaseOrders == null || !purchaseOrders.isResolvable()) {
            throw new BusinessRuleException("Inkoop is niet beschikbaar; probeer straks opnieuw");
        }
        be.enrosed.sourcing.application.PurchaseOrderService sourcing = purchaseOrders.get();
        PurchaseOrder container = sourcing.get(request.purchaseOrderId());
        if (container.lines().isEmpty()) throw new BusinessRuleException("Deze inkooporder heeft nog geen productregels");
        Customer customer = customers.get(request.customerId());
        boolean atCost = "COST".equalsIgnoreCase(request.pricing());
        BigDecimal markup = request.markupPct() == null ? BigDecimal.ZERO : request.markupPct();
        if (markup.signum() < 0) throw new BusinessRuleException("De opslag op de kostprijs kan niet negatief zijn");
        boolean partner = atCost && request.partner();
        BigDecimal share = partner
                ? percentage(request.sharePct() != null ? request.sharePct() : customer.partnerSharePctOrDefault(), "Ons deel van de winst")
                : null;
        BigDecimal costPct = partner
                ? percentage(request.costPct() != null ? request.costPct() : customer.partnerCostPctOrDefault(),
                        "Het deel van de kost dat de partner vooraf betaalt")
                : HUNDRED;
        BigDecimal factor = BigDecimal.ONE.add(markup.divide(HUNDRED, 6, java.math.RoundingMode.HALF_UP))
                .multiply(costPct).divide(HUNDRED, 6, java.math.RoundingMode.HALF_UP);

        Map<Long, LandedCost.Line> costLines = new HashMap<>();
        if (atCost) {
            for (LandedCost.Line line : sourcing.calculate(container).lines()) costLines.put(line.productId(), line);
        }
        List<SalesOrderLine> lines = new java.util.ArrayList<>();
        for (PurchaseOrderLine line : container.lines()) {
            if (line.quantity() <= 0) continue;
            BigDecimal unit = null;
            int quantity = line.quantity();
            if (atCost) {
                LandedCost.Line cost = costLines.get(line.productId());
                if (cost == null || cost.landedUnitEur() == null || cost.landedUnitEur().signum() <= 0) {
                    throw new BusinessRuleException("Geen gelande kost voor " + productName(line.productId())
                            + "; reken de calculatie van " + container.number() + " eerst door");
                }
                unit = cost.landedUnitEur().multiply(factor).setScale(4, java.math.RoundingMode.HALF_UP);
                /* The landed cost per piece is spread over the pieces the calculation counts; the same
                   pieces go on the quote, so the quote adds up to what the container cost us. */
                if (cost.quantity() > 0) quantity = cost.quantity();
            }
            lines.add(new SalesOrderLine(null, line.productId(), quantity, unit, null, null));
        }
        if (lines.isEmpty()) throw new BusinessRuleException("Deze inkooporder heeft geen regels met een aantal");

        List<SalesExtraLine> extras = new java.util.ArrayList<>();
        if (atCost) {
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
                null, nextNumber(), customer.id(), customer.countryCode(), today, BusinessDays.add(today, 30),
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
                DocumentType.OFFERTE, null, null, null, null, lines, List.of())
                .withExtraLines(extras)
                .withPartnerDeal(partner ? container.id() : null, share)
                .withSalesChannel(channel);
        validateForSave(draft);
        SalesOrder created = orders.save(draft);

        String how = partner
                ? " als partnercontainer: " + pct(share) + " % winstdeling, " + pct(costPct) + " % van de kost vooraf"
                : atCost ? " aan kostprijs" : " aan klantprijzen";
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), creator.displayName(), false,
                "Offerte opgemaakt vanuit inkooporder " + container.number() + how, null));
        recordActivity(created, "Offerte aangemaakt vanuit inkooporder " + container.number() + how);
        recordPurchaseActivity(container.id(), container.number(),
                "Verkoopofferte " + created.number() + " gemaakt voor " + customer.company() + how);
        fireCreationPush(SalesCreationPushNotifier.Ready.quoteCreated(created.id(), created.number(), creator));
        return created;
    }

    private static BigDecimal part(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP);
    }

    private static String pct(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String productName(Long productId) {
        return products.list().stream().filter(product -> product.id().equals(productId))
                .map(Product::name).findFirst().orElse("product " + productId);
    }

    /** The container's own diary line: what the partner side did with it. */
    private void recordPurchaseActivity(Long purchaseOrderId, String number, String summary) {
        if (activity == null || !activity.isResolvable() || purchaseOrderId == null) return;
        activity.get().record(ActivityLogService.ACTION_UPDATED, "PURCHASE_ORDER",
                purchaseOrderId.toString(), number, summary);
    }

    /** One product on the partner's auction statement: what it fetched, and what one piece cost us landed. */
    public record AuctionLine(Long productId, int quantity, BigDecimal proceedsEur, BigDecimal landedUnitCostEur) {}

    /**
     * The partner's auction statement and the deal behind it: which partner,
     * which container, what part of the landed cost we still recover and our
     * share of the profit. The source is the cost document when there is one;
     * the container can also be settled without one, when we financed it all.
     */
    public record AuctionSettlementRequest(Long customerId, Long purchaseOrderId, String reference, Long sourceId,
                                           BigDecimal costSharePct, BigDecimal profitSharePct,
                                           List<AuctionLine> lines, String note) {}

    /**
     * The auction settlement of a partner container: the partner sold the
     * goods at auction and reports what each product fetched. Per product we
     * invoice the part of the landed cost we financed plus our share of the
     * profit above the full landed cost. The invoice carries real product
     * lines, so shipping it writes the stock out and the margin shows in the
     * sales analysis; the calculation is written into its notes.
     */
    @Transactional
    public SalesOrder createAuctionSettlement(AuctionSettlementRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new BusinessRuleException("Vul de veilingopbrengst per product in");
        }
        BigDecimal costShare = percentage(request.costSharePct(), "Het deel van de kost dat wij terugvragen");
        BigDecimal profitShare = percentage(request.profitSharePct(), "Ons deel van de winst");
        SalesOrder source = request.sourceId() == null ? null : get(request.sourceId());
        Long customerId = request.customerId() != null ? request.customerId()
                : source != null ? source.customerId() : null;
        if (customerId == null) throw new BusinessRuleException("Kies de partner voor deze afrekening");
        Customer partner = customers.get(customerId);
        Long purchaseOrderId = request.purchaseOrderId() != null ? request.purchaseOrderId()
                : source != null ? source.partnerPurchaseOrderId() : null;
        String reference = !isBlank(request.reference()) ? request.reference().strip()
                : source != null ? source.number() : "container";
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));

        List<SalesOrderLine> lines = new java.util.ArrayList<>();
        StringBuilder table = new StringBuilder();
        BigDecimal proceedsSum = BigDecimal.ZERO;
        BigDecimal costSum = BigDecimal.ZERO;
        BigDecimal oursSum = BigDecimal.ZERO;
        for (AuctionLine line : request.lines()) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                throw new BusinessRuleException("Elke regel van de afrekening heeft een product en een aantal");
            }
            BigDecimal proceeds = line.proceedsEur() == null ? BigDecimal.ZERO : line.proceedsEur();
            if (proceeds.signum() < 0) throw new BusinessRuleException("Een veilingopbrengst kan niet negatief zijn");
            BigDecimal landedUnit = line.landedUnitCostEur() == null ? BigDecimal.ZERO : line.landedUnitCostEur();
            BigDecimal quantity = BigDecimal.valueOf(line.quantity());
            BigDecimal cost = landedUnit.multiply(quantity).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal profit = proceeds.subtract(cost);
            BigDecimal ours = cost.multiply(costShare).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP)
                    .add(profit.multiply(profitShare).divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP));
            if (ours.signum() < 0) ours = BigDecimal.ZERO;
            lines.add(new SalesOrderLine(null, line.productId(), line.quantity(),
                    ours.divide(quantity, 4, java.math.RoundingMode.HALF_UP), null, null));
            Product product = byId.get(line.productId());
            table.append(product == null ? "Product " + line.productId() : product.name())
                    .append(": ").append(line.quantity()).append(" st · veiling € ").append(money(proceeds))
                    .append(" − kost € ").append(money(cost)).append(" = ")
                    .append(profit.signum() < 0 ? "verlies" : "winst").append(" € ").append(money(profit.abs()))
                    .append(" · ons deel € ").append(money(ours)).append('\n');
            proceedsSum = proceedsSum.add(proceeds);
            costSum = costSum.add(cost);
            oursSum = oursSum.add(ours);
        }
        BigDecimal profitSum = proceedsSum.subtract(costSum);
        String notes = "Veilingafrekening " + reference + " · " + money(costShare).replace(",00", "")
                + " % van de gelande kost terug + " + money(profitShare).replace(",00", "") + " % van de winst\n"
                + table
                + "Totaal: veiling € " + money(proceedsSum) + " − kost € " + money(costSum) + " = "
                + (profitSum.signum() < 0 ? "verlies" : "winst") + " € " + money(profitSum.abs())
                + "; ons deel € " + money(oursSum) + ".";

        ActorRef creator = currentActor();
        LocalDate today = LocalDate.now();
        SalesOrder invoice = new SalesOrder(
                null, nextInvoiceNumber(), partner.id(), partner.countryCode(),
                today, BusinessDays.add(today, 30), QuoteStatus.CONCEPT,
                isBlank(partner.incoterm()) ? "DAP" : partner.incoterm(), partner.paymentTerms(), notes,
                MarkupMode.PRODUCT, BigDecimal.ZERO, null, null,
                null, null, null, 0, null, null, null,
                isBlank(request.note()) ? null : request.note().strip(),
                DeliveryTermsState.VOLLEDIG, FreightState.AANGEVULD, BigDecimal.ZERO,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.FACTUUR, BusinessDays.add(today, 30), null, null, null,
                lines, List.of())
                .withPartnerDeal(purchaseOrderId, profitShare)
                .withSalesChannel("PARTNER")
                .asPartnerSettlement();
        validateForSave(invoice);
        SalesOrder created = orders.save(invoice);
        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), creator.displayName(), false,
                "Veilingafrekening opgemaakt voor " + reference, null));
        if (source != null) {
            events.add(new QuoteEvent(null, source.id(), QuoteEvent.Type.GEFACTUREERD,
                    java.time.Instant.now(), creator.displayName(), false,
                    "Veilingafrekening " + created.number() + " aangemaakt: "
                            + (profitSum.signum() < 0 ? "verlies" : "winst") + " € " + money(profitSum.abs())
                            + " op veilingopbrengst € " + money(proceedsSum), null));
        }
        recordActivity(created, "Veilingafrekening aangemaakt voor " + reference);
        recordPurchaseActivity(purchaseOrderId, reference,
                "Veilingafrekening " + created.number() + " gemaakt: ons deel € " + money(oursSum)
                        + " op veilingopbrengst € " + money(proceedsSum));
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
    public record PartnerDealRequest(Long purchaseOrderId, BigDecimal sharePct, String reference) {}

    /**
     * Links a quote or invoice to a partner container after the fact, so a
     * container we first paid ourselves can still be split with the partner
     * who takes it over, and the analyses can tell our money from theirs.
     */
    @Transactional
    public SalesOrder setPartnerDeal(long id, PartnerDealRequest request) {
        SalesOrder order = get(id);
        Long purchaseOrderId = request == null ? null : request.purchaseOrderId();
        BigDecimal share = null;
        if (purchaseOrderId != null) {
            share = request.sharePct() != null ? request.sharePct()
                    : order.partnerSharePct() != null ? order.partnerSharePct() : new BigDecimal("50");
            if (share.signum() < 0 || share.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessRuleException("De winstdeling ligt tussen 0 en 100 procent");
            }
        }
        SalesOrder saved = orders.save(order.withPartnerDeal(purchaseOrderId, share));
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
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.status() != QuoteStatus.VERZONDEN && invoice.status() != QuoteStatus.BETAALD) {
            throw new BusinessRuleException("Verstuur eerst de factuur; daarna kan de bestelling afgepunt worden");
        }
        if (invoice.goodsShippedAt() != null) {
            throw new BusinessRuleException("De voorraad van deze bestelling is al afgepunt");
        }
        if (invoice.lines().isEmpty()) {
            throw new BusinessRuleException("Deze factuur heeft geen productregels om af te punten");
        }
        for (SalesOrderLine line : invoice.lines()) {
            if (line.quantity() > 0) {
                products.sellStock(line.productId(), line.quantity(), invoice.number());
            }
        }
        ActorRef actor = currentActor();
        SalesOrder shipped = orders.save(withGoodsShipped(invoice, java.time.Instant.now()));
        events.add(new QuoteEvent(null, id, QuoteEvent.Type.BESTELLING_VERZONDEN,
                java.time.Instant.now(), actor.displayName(), false,
                "Bestelling verzonden - voorraad afgepunt", null));
        recordActivity(SALES_ACTION_SHIPPED, shipped,
                "Bestelling verzonden en voorraad afgepunt");
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

    /** Invoices skip the portal: sending is a bookkeeping fact, not a mail flow. */
    @Transactional
    public SalesOrder markInvoiceSent(long id) {
        SalesOrder invoice = requireInvoice(get(id));
        if (invoice.status() != QuoteStatus.CONCEPT) {
            throw new BusinessRuleException("Alleen een conceptfactuur kan verstuurd worden");
        }
        validateInvoiceForSend(invoice);
        SalesOrder sent = withStatus(invoice, QuoteStatus.VERZONDEN, java.time.Instant.now(), null);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
                current.isPartnerDeal() || changes.partnerPurchaseOrderId() != null
                        ? changes.lines() : roundLinesToCartons(changes.lines()),
                changes.pallets())
                .withExtraLines(keptExtraLines(changes.extraLines()))
                /* Null means an update client that does not know the deal: keep it. */
                .withPartnerDeal(
                        changes.partnerPurchaseOrderId() == null
                                ? current.partnerPurchaseOrderId() : changes.partnerPurchaseOrderId(),
                        changes.partnerPurchaseOrderId() == null
                                ? current.partnerSharePct() : changes.partnerSharePct())
                /* The raw field: an update client that never heard of channels leaves it as it was. */
                .withSalesChannel(changes.rawSalesChannel() == null ? current.rawSalesChannel() : changes.rawSalesChannel());
        validateForSave(updated);
        SalesOrder saved = orders.save(updated);
        if (!saved.equals(current)) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved,
                    saved.isInvoice() ? "Factuur bijgewerkt" : "Offerte bijgewerkt",
                    salesChanges(current, saved));
        }
        return saved;
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
                                line.unitPriceEur(), line.manualDiscountPct(), weeks.get(line.productId()))
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
        boolean hasRevisions = !revisions.findByOrder(id).isEmpty();
        SalesLifecycle.requireDeletable(order, hasRevisions);
        boolean hasDerivedInvoice = !order.isInvoice() && orders.existsBySourceQuoteId(id);
        if (hasDerivedInvoice) {
            throw new BusinessRuleException(
                    "Deze offerte kan niet verwijderd worden omdat er een factuur uit is aangemaakt");
        }
        revisions.deleteByOrder(id);
        events.deleteByOrder(id);
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
        ActorRef actor = currentActor();
        LocalDate today = LocalDate.now();
        SalesOrder duplicate = new SalesOrder(
                null, source.isInvoice() ? nextInvoiceNumber() : nextNumber(),
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
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        duplicate = duplicate.withExtraLines(source.extraLines())
                .withPartnerDeal(source.partnerPurchaseOrderId(), source.partnerSharePct())
                .withSalesChannel(source.rawSalesChannel());
        validateForSave(duplicate);
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
            if (effectivePrice == null || effectivePrice.signum() <= 0) {
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
                            line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek());
        }).toList();
    }

    private String nextNumber() {
        return nextNumber("ENR-");
    }

    /** Invoices number their own gapless-enough series: F-2026-0001. */
    private String nextInvoiceNumber() {
        return nextNumber("F-");
    }

    private String nextNumber(String base) {
        int year = LocalDate.now().getYear();
        String prefix = base + year + "-";
        int highest = orders.findAll().stream()
                .map(SalesOrder::number)
                .filter(number -> number != null && number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(suffix -> suffix.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%04d", highest + 1);
    }
}
