package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prices a sales order.
 *
 * The price starts from the cost price out of the purchase calculation plus
 * a markup. That markup comes from the product itself, or - when the order
 * says so - from a percentage over the whole order. Then the tier discounts:
 * first per line on that product's quantity, then on the order total.
 *
 * Freight follows the order's explicit strategy: the destination-country
 * tariff per pallet, an own rate per cubic metre, or one fixed total.
 * Physical packing stays separate: palletised and loose-carton loads both
 * retain their outer-carton CBM.
 */
@ApplicationScoped
public class SalesPricingCalculator {

    private final PalletCalculator pallets;

    public SalesPricingCalculator(PalletCalculator pallets, DeliveryCalculator delivery) {
        this.pallets = pallets;
        this.delivery = delivery;
    }

    public record Context(
            Country country,
            Customer customer,
            PalletSpec pallet,
            List<DiscountTier> lineTiers,
            List<DiscountTier> orderTiers,
            VatCalculator.Result vat,
            /** The shipping organisation, loaded when the order prices freight by staffel. */
            be.enrosed.shipping.domain.Carrier carrier
    ) {
        /** Pre-carrier signature for callers without a staffel. */
        public Context(Country country, Customer customer, PalletSpec pallet,
                       List<DiscountTier> lineTiers, List<DiscountTier> orderTiers,
                       VatCalculator.Result vat) {
            this(country, customer, pallet, lineTiers, orderTiers, vat, null);
        }
    }

    private final DeliveryCalculator delivery;

    public PricedOrder price(SalesOrder order, Map<Long, Product> productsById, Context context) {

        List<PricedOrder.Line> lines = new ArrayList<>();
        List<int[]> palletInput = new ArrayList<>();
        List<String> withoutCost = new ArrayList<>();
        List<String> withoutCartonDimensions = new ArrayList<>();
        List<String> withoutPalletFit = new ArrayList<>();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal lineDiscountTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        BigDecimal cbmTotal = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        int pieces = 0;
        int cartonsTotal = 0;
        boolean palletised = order.loadMode() == LoadMode.PALLETS;

        for (SalesOrderLine line : order.lines()) {
            Product product = productsById.get(line.productId());
            if (product == null) continue;

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            /* Quantities are entered in pieces but shipped in full cartons.
               Rounding up happens right here, so what you see on screen is
               what actually goes out the door. */
            int requested = Math.max(0, line.quantity());
            int cartons = carton.cartonsFor(requested);
            int quantity = cartons * Math.max(1, carton.piecesPerCarton());

            boolean validOuterCarton = hasValidOuterCarton(carton);
            if (quantity > 0 && !validOuterCarton) withoutCartonDimensions.add(product.sku());

            PalletCalculator.Fit fit = palletised
                    ? pallets.fit(carton, context.pallet())
                    : PalletCalculator.Fit.none("losse dozen");
            if (palletised && quantity > 0 && fit.cartonsPerPallet() <= 0) {
                withoutPalletFit.add(product.sku());
            }
            int linePallets = palletised
                    ? pallets.palletsFor(cartons, fit.cartonsPerPallet()) : 0;
            if (palletised) palletInput.add(new int[] { cartons, fit.cartonsPerPallet() });
            int usedPalletLayers = palletised ? usedPalletLayers(fit, cartons) : 0;
            BigDecimal calculatedPalletHeight = palletised
                    ? calculatedPalletHeight(carton, context.pallet(), usedPalletLayers)
                    : BigDecimal.ZERO;

            /* Keep exact carton volume for totals and per-CBM freight. Only
               the response field is rounded; rounding every line first can
               underprice a load made from many small cartons. */
            BigDecimal exactCbm = carton.cbm().multiply(BigDecimal.valueOf(cartons));
            BigDecimal cbm = exactCbm.setScale(3, RoundingMode.HALF_UP);
            BigDecimal exactWeight = Money.nz(carton.weightKg()).multiply(BigDecimal.valueOf(cartons));
            BigDecimal weight = exactWeight.setScale(1, RoundingMode.HALF_UP);

            BigDecimal unitPrice = unitPriceFor(product, order, line.unitPriceEur());
            BigDecimal lineGross = unitPrice.multiply(BigDecimal.valueOf(quantity));

            List<DiscountTier> productLineTiers = lineTiersForProduct(
                    context.lineTiers(), product.id());
            BigDecimal tierPct = tierPercentFor(productLineTiers, quantity);
            BigDecimal manualPct = Money.nz(line.manualDiscountPct());
            BigDecimal discountPct = tierPct.add(manualPct).min(Money.HUNDRED);
            BigDecimal discountAmount = Money.percentOf(lineGross, discountPct);
            BigDecimal net = lineGross.subtract(discountAmount);

            BigDecimal landedUnit = Money.nz(product.landedCostEur());
            if (landedUnit.signum() == 0) withoutCost.add(product.sku());
            BigDecimal lineCost = landedUnit.multiply(BigDecimal.valueOf(quantity));

            DiscountTier next = nextTier(productLineTiers, quantity);

            /* Delivery term: from stock we count from the next working day
               plus transit time; otherwise no date, only what is short. */
            DeliveryCalculator.Estimate estimate =
                    delivery.estimate(context.country(), quantity, product.stockQuantity(),
                            product.inventoryKnown());
            String manualWeek = line.deliveryWeek();

            lines.add(new PricedOrder.Line(
                    /* Internal screens read the plain name with colour; the
                       dimensions live in their own columns. */
                    product.id(), product.sku(), product.nameWithColour(),
                    /* The same line in the customer's language; that goes to
                       the quote and the portal, while our screens keep showing
                       our own description. */
                    product.describeIn(context.customer() == null
                            ? Language.NL : context.customer().language()),
                    product.primaryPhoto() == null ? null
                            : "/api/products/" + product.id() + "/photos/" + product.primaryPhoto().id(),
                    quantity, cartons, fit.cartonsPerPallet(), linePallets,
                    fit.perLayer(), usedPalletLayers, calculatedPalletHeight,
                    cbm, weight,
                    Money.unit(unitPrice), Money.money(lineGross),
                    tierPct, manualPct, discountPct, Money.money(discountAmount),
                    Money.money(net),
                    quantity > 0 ? Money.unit(Money.divide(net, BigDecimal.valueOf(quantity))) : BigDecimal.ZERO,
                    Money.unit(landedUnit), Money.money(lineCost),
                    Money.money(net.subtract(lineCost)),
                    net.signum() > 0
                            ? Money.divide(net.subtract(lineCost).multiply(Money.HUNDRED), net)
                                    .setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO,
                    next == null ? null : next.minQuantity(),
                    next == null ? null : next.percent(),
                    product.inventoryKnown() ? product.stockQuantity() : null,
                    product.inventoryKnown(),
                    estimate.fromStock(),
                    estimate.shortfall(),
                    estimate.earliestDate() == null ? null : estimate.earliestDate().toString(),
                    manualWeek != null && !manualWeek.isBlank() ? manualWeek : estimate.week(),
                    estimate.explanation()));

            gross = gross.add(lineGross);
            lineDiscountTotal = lineDiscountTotal.add(discountAmount);
            costTotal = costTotal.add(lineCost);
            cbmTotal = cbmTotal.add(exactCbm);
            weightTotal = weightTotal.add(exactWeight);
            pieces += quantity;
            cartonsTotal += cartons;
        }

        /* ---- kortingen ------------------------------------------------- */
        BigDecimal subtotal = gross.subtract(lineDiscountTotal);
        BigDecimal orderTierPct = tierPercentFor(context.orderTiers(), pieces);
        BigDecimal orderDiscount = Money.percentOf(subtotal, orderTierPct);
        BigDecimal afterOrderTier = subtotal.subtract(orderDiscount);

        /* The extra discount comes after the tier and computes over what is
           left, so the two never run double over the same amount. */
        BigDecimal extraPct = Money.nz(order.extraDiscountPct());
        BigDecimal extraDiscount = Money.percentOf(afterOrderTier, extraPct);
        BigDecimal goodsTotal = afterOrderTier.subtract(extraDiscount);

        /* ---- vracht per pallet ----------------------------------------- */
        PalletCalculator.OrderPallets palletCounts = palletised
                ? pallets.forOrder(palletInput) : new PalletCalculator.OrderPallets(0, 0);
        Country country = context.country();

        /* Hand-built pallets take over the freight count the moment they
           exist: the seller laid out the load and knows better than the
           formula. Cartons left off any pallet are reported, not silently
           re-added - the warning on screen is the guard rail. */
        int manualPallets = palletised ? order.pallets().size() : 0;
        int assignedCartons = order.pallets().stream()
                .flatMap(pallet -> pallet.items().stream())
                .mapToInt(be.enrosed.sales.domain.OrderPallet.Item::cartons)
                .sum();
        int unassignedCartons = manualPallets == 0 ? 0
                : Math.max(0, cartonsTotal - assignedCartons);
        int palletsForFreight = manualPallets > 0 ? manualPallets : palletCounts.strict();

        BigDecimal freight = BigDecimal.ZERO;
        BigDecimal handling = BigDecimal.ZERO;
        boolean freightIsMinimum = false;
        String carrierFreightIssue = null;

        boolean hasShipment = cartonsTotal > 0;
        if (country != null && hasShipment) handling = Money.nz(country.handling());

        switch (order.freightPricingStrategy()) {
            case COUNTRY_PALLET -> {
                if (country != null && palletsForFreight > 0) {
                    BigDecimal byPallet = Money.nz(country.freightPerPallet())
                            .multiply(BigDecimal.valueOf(palletsForFreight));
                    BigDecimal minimum = Money.nz(country.minFreight());
                    freight = byPallet.max(minimum);
                    freightIsMinimum = freight.compareTo(byPallet) > 0;
                }
            }
            case PER_CBM -> freight = Money.money(
                    cbmTotal.multiply(Money.nz(order.freightRatePerCbmEur())));
            case FIXED -> freight = Money.money(order.manualFreightEur());
            case PICKUP -> {
                freight = BigDecimal.ZERO;
                handling = BigDecimal.ZERO;
            }
            case CARRIER -> {
                /* The staffel prices per zone (postcode) and per pallet rung.
                   Whatever cannot be resolved becomes a named validation
                   point, never a silently invented amount. */
                be.enrosed.shipping.domain.Carrier carrier = context.carrier();
                String postcode = context.customer() == null
                        ? null : context.customer().postalCode();
                if (carrier == null) {
                    carrierFreightIssue = "Kies een verzendorganisatie voor de staffelvracht";
                } else if (palletsForFreight <= 0) {
                    carrierFreightIssue = "De staffel rekent per pallet; dit order heeft er geen";
                } else {
                    be.enrosed.shipping.domain.CarrierPricing.PalletKind kind =
                            switch (order.palletProfile()) {
                                case BLOCK_120X100 -> be.enrosed.shipping.domain
                                        .CarrierPricing.PalletKind.BLOCKPALLET;
                                case HALF_80X60 -> be.enrosed.shipping.domain
                                        .CarrierPricing.PalletKind.HALF_PALLET;
                                default -> be.enrosed.shipping.domain
                                        .CarrierPricing.PalletKind.EUROPALLET;
                            };
                    be.enrosed.shipping.domain.CarrierQuote quote =
                            be.enrosed.shipping.domain.CarrierPricing.quote(carrier,
                                    order.countryCode(), postcode, palletsForFreight, kind,
                                    weightTotal);
                    if (quote == null) {
                        carrierFreightIssue = carrier.lane(order.countryCode()) == null
                                ? carrier.name() + " heeft geen tarief voor dit land"
                                : "De zending past niet in de staffel van " + carrier.name()
                                        + " - vraag een prijs op en vul die vast in";
                    } else {
                        /* The internal top-up dissolves into the customer's
                           freight amount; our screens show it separately. */
                        freight = quote.totalEur()
                                .add(Money.nz(order.freightCarrierExtraEur()));
                    }
                }
            }
        }

        /* While the freight is "to be determined", nothing counts yet.
           Inventing an amount and correcting later is worse than an open
           item: the customer counts on the total that was shown. */
        if (order.freight() == FreightState.TE_BEPALEN) {
            freight = BigDecimal.ZERO;
            handling = BigDecimal.ZERO;
            freightIsMinimum = false;
        }

        BigDecimal shipping = freight.add(handling);
        BigDecimal total = goodsTotal.add(shipping);

        /* The VAT rate comes from the regime, not directly from the country:
           for an intra-community supply or export it is zero. */
        BigDecimal vatRate = context.vat() == null
                ? (country == null ? BigDecimal.ZERO : Money.nz(country.vatRatePct()))
                : context.vat().ratePct();
        BigDecimal vat = Money.percentOf(total, vatRate);

        BigDecimal margin = goodsTotal.subtract(costTotal);
        BigDecimal minOrderValue = country == null ? BigDecimal.ZERO : Money.nz(country.minOrderValue());
        boolean meetsMinimum = goodsTotal.compareTo(minOrderValue) >= 0;

        PricedOrder.Totals totals = new PricedOrder.Totals(
                pieces, cartonsTotal, palletCounts.strict(), palletCounts.optimised(),
                manualPallets, unassignedCartons,
                context.pallet().baseHeightCm(), context.pallet().maxHeightCm(),
                cbmTotal.setScale(3, RoundingMode.HALF_UP), weightTotal.setScale(1, RoundingMode.HALF_UP),
                Money.money(gross), Money.money(lineDiscountTotal), Money.money(subtotal),
                orderTierPct, Money.money(orderDiscount),
                extraPct, order.extraDiscountLabel(), Money.money(extraDiscount),
                Money.money(goodsTotal),
                Money.money(freight), freightIsMinimum, Money.money(handling), Money.money(shipping),
                Money.money(total), vatRate, Money.money(vat), Money.money(total.add(vat)),
                context.vat() == null ? VatTreatment.BINNENLAND : context.vat().treatment(),
                context.vat() == null ? null : context.vat().treatment().legalMention(),
                context.vat() == null ? null : context.vat().reason(),
                Money.money(costTotal), Money.money(margin),
                goodsTotal.signum() > 0
                        ? Money.divide(margin.multiply(Money.HUNDRED), goodsTotal).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO,
                Money.money(margin.subtract(shipping)));

        PricedOrder.Validation validation = new PricedOrder.Validation(
                Money.money(minOrderValue), meetsMinimum,
                meetsMinimum ? BigDecimal.ZERO : Money.money(minOrderValue.subtract(goodsTotal)),
                !lines.isEmpty(), country != null, withoutCost,
                withoutCartonDimensions, withoutPalletFit,
                carrierFreightIssue != null ? carrierFreightIssue : freightPricingIssue(order));

        return new PricedOrder(lines, totals, validation);
    }

    private static boolean hasValidOuterCarton(Carton carton) {
        if (carton == null || carton.piecesPerCarton() <= 0 || carton.dimensions() == null) {
            return false;
        }
        Dimensions size = carton.dimensions();
        return positive(size.lengthCm()) && positive(size.widthCm()) && positive(size.heightCm());
    }

    private static String freightPricingIssue(SalesOrder order) {
        if (order.freight() == FreightState.TE_BEPALEN) return null;
        if (order.loadMode() == LoadMode.LOOSE_CARTONS
                && order.freightPricingStrategy() == FreightPricingStrategy.COUNTRY_PALLET) {
            return "Kies bij losse dozen vracht per CBM of een vast vrachtbedrag";
        }
        if (order.freightPricingStrategy() == FreightPricingStrategy.PER_CBM
                && (order.freightRatePerCbmEur() == null
                    || order.freightRatePerCbmEur().signum() <= 0)) {
            return "De vracht staat op 'tarief per m\u00b3' maar er is geen tarief ingevuld"
                    + " - open Transport & levering en kies hoe de vracht berekend wordt";
        }
        if (order.freightPricingStrategy() == FreightPricingStrategy.FIXED
                && order.manualFreightEur() == null) {
            return "Vul het vaste vrachtbedrag in";
        }
        if (order.freightPricingStrategy() == FreightPricingStrategy.CARRIER
                && order.freightCarrierId() == null) {
            return "Kies een verzendorganisatie voor de staffelvracht";
        }
        return null;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static int usedPalletLayers(PalletCalculator.Fit fit, int cartons) {
        if (cartons <= 0 || fit.perLayer() <= 0 || fit.cartonsPerPallet() <= 0) return 0;
        int onTallestPallet = Math.min(cartons, fit.cartonsPerPallet());
        return (onTallestPallet + fit.perLayer() - 1) / fit.perLayer();
    }

    /** Tallest pallet needed by this line, not always a completely full pallet. */
    private static BigDecimal calculatedPalletHeight(Carton carton, PalletSpec pallet,
                                                     int usedLayers) {
        if (usedLayers <= 0 || carton == null || carton.dimensions() == null
                || !positive(carton.dimensions().heightCm())) {
            return BigDecimal.ZERO;
        }
        return pallet.baseHeightCm().add(carton.dimensions().heightCm()
                .multiply(BigDecimal.valueOf(usedLayers)));
    }

    /**
     * Price of a line before discount.
     *
     * A manual price on the line wins. Then a fixed catalogue price on the
     * product - deliberately pinned, and it keeps holding under an
     * order-total markup. Otherwise: cost price plus markup.
     */
    public BigDecimal unitPriceFor(Product product, SalesOrder order, BigDecimal manualPrice) {
        if (manualPrice != null && manualPrice.signum() > 0) return manualPrice;
        if (product.fixedSalesPriceEur() != null && product.fixedSalesPriceEur().signum() > 0) {
            return product.fixedSalesPriceEur();
        }
        BigDecimal cost = Money.nz(product.landedCostEur());
        BigDecimal markup = order.markupMode() == MarkupMode.ORDER
                ? Money.nz(order.orderMarkupPct())
                : Money.nz(product.markupPct());
        return Money.addPercent(cost, markup).setScale(2, RoundingMode.HALF_UP);
    }

    /** Highest tier whose threshold has been reached. */
    private BigDecimal tierPercentFor(List<DiscountTier> tiers, int quantity) {
        if (tiers == null) return BigDecimal.ZERO;
        return tiers.stream()
                .filter(tier -> quantity >= tier.minQuantity())
                .map(DiscountTier::percent)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    /** Product-specific line tiers only; null-target rows are inert legacy global rules. */
    private List<DiscountTier> lineTiersForProduct(List<DiscountTier> tiers, Long productId) {
        if (tiers == null || productId == null) return List.of();
        return tiers.stream()
                .filter(Objects::nonNull)
                .filter(tier -> tier.scope() == TierScope.LINE)
                .filter(tier -> Objects.equals(tier.productId(), productId))
                .toList();
    }

    /** Next tier up, for the "this many pieces to go" hint. */
    private DiscountTier nextTier(List<DiscountTier> tiers, int quantity) {
        if (tiers == null) return null;
        return tiers.stream()
                .filter(tier -> tier.minQuantity() > quantity)
                .min(Comparator.comparingInt(DiscountTier::minQuantity))
                .orElse(null);
    }
}
