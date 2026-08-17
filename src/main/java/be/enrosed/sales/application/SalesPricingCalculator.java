package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Carton;
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

/**
 * Rekent een verkooporder door.
 *
 * De prijs vertrekt van de kostprijs uit de inkoopcalculatie plus een opslag.
 * Die opslag komt van het product zelf, of - als de order dat zo instelt -
 * van een percentage over de hele order. Daarna komen de staffelkortingen:
 * eerst per regel op het aantal van dat product, dan op het ordertotaal.
 *
 * Vracht wordt per pallet gerekend, niet per kubieke meter: verkoop gaat over
 * de weg en de vervoerder rekent per palletplaats.
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
            VatCalculator.Result vat
    ) {}

    private final DeliveryCalculator delivery;

    public PricedOrder price(SalesOrder order, Map<Long, Product> productsById, Context context) {

        List<PricedOrder.Line> lines = new ArrayList<>();
        List<int[]> palletInput = new ArrayList<>();
        List<String> withoutCost = new ArrayList<>();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal lineDiscountTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        BigDecimal cbmTotal = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        int pieces = 0;
        int cartonsTotal = 0;

        for (SalesOrderLine line : order.lines()) {
            Product product = productsById.get(line.productId());
            if (product == null) continue;

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            /* Aantallen worden in stuks ingegeven maar in volle dozen verscheept.
               Hier wordt er meteen naar boven afgerond, zodat wat je op het scherm
               ziet ook is wat er de deur uit gaat. */
            int requested = Math.max(0, line.quantity());
            int cartons = carton.cartonsFor(requested);
            int quantity = cartons * Math.max(1, carton.piecesPerCarton());

            PalletCalculator.Fit fit = pallets.fit(carton, context.pallet());
            int linePallets = pallets.palletsFor(cartons, fit.cartonsPerPallet());
            palletInput.add(new int[] { cartons, fit.cartonsPerPallet() });

            BigDecimal cbm = carton.cbm().multiply(BigDecimal.valueOf(cartons)).setScale(3, RoundingMode.HALF_UP);
            BigDecimal weight = Money.nz(carton.weightKg()).multiply(BigDecimal.valueOf(cartons))
                    .setScale(1, RoundingMode.HALF_UP);

            BigDecimal unitPrice = unitPriceFor(product, order, line.unitPriceEur());
            BigDecimal lineGross = unitPrice.multiply(BigDecimal.valueOf(quantity));

            BigDecimal tierPct = tierPercentFor(context.lineTiers(), quantity);
            BigDecimal manualPct = Money.nz(line.manualDiscountPct());
            BigDecimal discountPct = tierPct.add(manualPct).min(Money.HUNDRED);
            BigDecimal discountAmount = Money.percentOf(lineGross, discountPct);
            BigDecimal net = lineGross.subtract(discountAmount);

            BigDecimal landedUnit = Money.nz(product.landedCostEur());
            if (landedUnit.signum() == 0) withoutCost.add(product.sku());
            BigDecimal lineCost = landedUnit.multiply(BigDecimal.valueOf(quantity));

            DiscountTier next = nextTier(context.lineTiers(), quantity);

            /* Levertermijn: uit voorraad rekenen we vanaf de eerstvolgende werkdag
               plus de transittijd; anders geen datum, alleen wat er tekort is. */
            DeliveryCalculator.Estimate estimate =
                    delivery.estimate(context.country(), quantity, product.stockQuantity());
            String manualWeek = line.deliveryWeek();

            lines.add(new PricedOrder.Line(
                    product.id(), product.sku(), product.describe(),
                    /* Dezelfde regel in de taal van de klant; die gaat naar de
                       offerte en het portaal, terwijl onze schermen de eigen
                       omschrijving blijven tonen. */
                    product.describeIn(context.customer() == null
                            ? Language.NL : context.customer().language()),
                    product.primaryPhoto() == null ? null
                            : "/api/products/" + product.id() + "/photos/" + product.primaryPhoto().id(),
                    quantity, cartons, fit.cartonsPerPallet(), linePallets,
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
                    product.stockQuantity(),
                    estimate.fromStock(),
                    estimate.shortfall(),
                    estimate.earliestDate() == null ? null : estimate.earliestDate().toString(),
                    manualWeek != null && !manualWeek.isBlank() ? manualWeek : estimate.week(),
                    estimate.explanation()));

            gross = gross.add(lineGross);
            lineDiscountTotal = lineDiscountTotal.add(discountAmount);
            costTotal = costTotal.add(lineCost);
            cbmTotal = cbmTotal.add(cbm);
            weightTotal = weightTotal.add(weight);
            pieces += quantity;
            cartonsTotal += cartons;
        }

        /* ---- kortingen ------------------------------------------------- */
        BigDecimal subtotal = gross.subtract(lineDiscountTotal);
        BigDecimal orderTierPct = tierPercentFor(context.orderTiers(), pieces);
        BigDecimal orderDiscount = Money.percentOf(subtotal, orderTierPct);
        BigDecimal afterOrderTier = subtotal.subtract(orderDiscount);

        /* Extra korting komt ná de staffel en rekent over wat er dan nog staat,
           zodat de twee niet dubbel over hetzelfde bedrag lopen. */
        BigDecimal extraPct = Money.nz(order.extraDiscountPct());
        BigDecimal extraDiscount = Money.percentOf(afterOrderTier, extraPct);
        BigDecimal goodsTotal = afterOrderTier.subtract(extraDiscount);

        /* ---- vracht per pallet ----------------------------------------- */
        PalletCalculator.OrderPallets palletCounts = pallets.forOrder(palletInput);
        Country country = context.country();

        BigDecimal freight = BigDecimal.ZERO;
        BigDecimal handling = BigDecimal.ZERO;
        boolean freightIsMinimum = false;

        if (country != null && palletCounts.strict() > 0) {
            BigDecimal byPallet = Money.nz(country.freightPerPallet())
                    .multiply(BigDecimal.valueOf(palletCounts.strict()));
            BigDecimal minimum = Money.nz(country.minFreight());
            freight = byPallet.max(minimum);
            freightIsMinimum = freight.compareTo(byPallet) > 0;
            handling = Money.nz(country.handling());
        }

        /* Vracht die wij zelf invullen gaat voor op het landtarief: een
           bestemming buiten de gewone tarieven of een klant die zelf laat
           ophalen past niet in een tabel per pallet. */
        if (order.manualFreightEur() != null) {
            freight = Money.money(order.manualFreightEur());
            freightIsMinimum = false;
        }

        /* Staat de vracht op "nog te bepalen", dan telt er nog niets mee. Een
           bedrag verzinnen en later corrigeren is erger dan een open post: de
           klant rekent op het totaal dat er stond. */
        if (order.freight() == FreightState.TE_BEPALEN) {
            freight = BigDecimal.ZERO;
            freightIsMinimum = false;
        }

        BigDecimal shipping = freight.add(handling);
        BigDecimal total = goodsTotal.add(shipping);

        /* Het BTW-tarief komt uit het regime, niet rechtstreeks uit het land:
           bij een intracommunautaire levering of uitvoer staat het op nul. */
        BigDecimal vatRate = context.vat() == null
                ? (country == null ? BigDecimal.ZERO : Money.nz(country.vatRatePct()))
                : context.vat().ratePct();
        BigDecimal vat = Money.percentOf(total, vatRate);

        BigDecimal margin = goodsTotal.subtract(costTotal);
        BigDecimal minOrderValue = country == null ? BigDecimal.ZERO : Money.nz(country.minOrderValue());
        boolean meetsMinimum = goodsTotal.compareTo(minOrderValue) >= 0;

        PricedOrder.Totals totals = new PricedOrder.Totals(
                pieces, cartonsTotal, palletCounts.strict(), palletCounts.optimised(),
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
                !lines.isEmpty(), country != null, withoutCost);

        return new PricedOrder(lines, totals, validation);
    }

    /**
     * Prijs van een regel voor korting.
     *
     * Een handmatige prijs op de regel gaat voor. Daarna een vaste
     * catalogusprijs op het product - die is bewust vastgezet en blijft ook
     * gelden bij een opslag op ordertotaal. Anders: kostprijs plus opslag.
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

    /** Hoogste staffel waarvan de drempel gehaald is. */
    private BigDecimal tierPercentFor(List<DiscountTier> tiers, int quantity) {
        if (tiers == null) return BigDecimal.ZERO;
        return tiers.stream()
                .filter(tier -> quantity >= tier.minQuantity())
                .map(DiscountTier::percent)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    /** Eerstvolgende staffel, voor de "nog zoveel stuks tot"-hint. */
    private DiscountTier nextTier(List<DiscountTier> tiers, int quantity) {
        if (tiers == null) return null;
        return tiers.stream()
                .filter(tier -> tier.minQuantity() > quantity)
                .min(Comparator.comparingInt(DiscountTier::minQuantity))
                .orElse(null);
    }
}
