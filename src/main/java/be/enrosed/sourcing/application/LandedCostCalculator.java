package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.HsCodeService;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.domain.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calculates the landed cost per piece of a container.
 *
 * The build-up follows the road the goods travel:
 *
 *   1. GOODS         quantity x (EXW price + extra cost per piece), USD or RMB
 *   2. ORIGIN        factory -> Chinese port: pre-carriage, export documents,
 *                    origin THC, insurance
 *   3. SEA FREIGHT   port of loading -> port of discharge
 *   ---------------------------------------------------------- EU border
 *   4. IMPORT DUTY   over the customs value (1 + 2 + 3), at the rate of
 *                    that product's HS code
 *   5. DESTINATION   port of discharge -> warehouse: destination THC,
 *                    clearance, road transport, unloading - happens after
 *                    import and is therefore not taxed
 *   6. EXTRA         desired revenue folded into the cost price
 *
 * The origin/destination distinction is no bookkeeping nicety: everything
 * before the border is taxed along, everything after is not. Sorting a cost
 * into the wrong bin costs either too much or too little duty.
 *
 * Import duties are not spread like the other container costs: each line
 * applies its own HS code to its own share of the customs value, because
 * one container can carry several rates.
 */
@ApplicationScoped
public class LandedCostCalculator {

    private final CurrencyConverter currencies;
    private final HsCodeService hsCodes;

    public LandedCostCalculator(CurrencyConverter currencies, HsCodeService hsCodes) {
        this.currencies = currencies;
        this.hsCodes = hsCodes;
    }

    public LandedCost calculate(PurchaseOrder order, Map<Long, Product> productsById) {

        /* ---- 1. Goederenwaarde per regel ------------------------------- */
        List<Working> working = new ArrayList<>();

        for (PurchaseOrderLine line : order.lines()) {
            Product product = productsById.get(line.productId());
            if (product == null) continue;

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            int quantity = Math.max(0, line.quantity());

            BigDecimal exwPrice = line.exwPrice() != null ? line.exwPrice() : product.exwPrice();
            Currency exwCurrency = line.exwCurrency() != null ? line.exwCurrency() : product.exwCurrency();
            BigDecimal extraUnit = line.extraUnitCost() != null ? line.extraUnitCost() : product.extraUnitCost();
            if (exwCurrency == null) exwCurrency = Currency.USD;

            BigDecimal unitUsd = currencies
                    .toUsd(Money.nz(exwPrice), exwCurrency, order.cnyToUsd(), order.usdToEurGoods())
                    .add(currencies.toUsd(Money.nz(extraUnit), exwCurrency, order.cnyToUsd(), order.usdToEurGoods()));

            BigDecimal goodsUsd = unitUsd.multiply(BigDecimal.valueOf(quantity));
            BigDecimal goodsEur = goodsUsd.multiply(Money.nz(order.usdToEurGoods()));

            int cartons = carton.cartonsFor(quantity);
            BigDecimal cbm = carton.cbm().multiply(BigDecimal.valueOf(cartons))
                    .setScale(3, RoundingMode.HALF_UP);

            Working row = new Working();
            row.product = product;
            row.quantity = quantity;
            row.cartons = cartons;
            row.cbm = cbm;
            row.goodsUsd = goodsUsd;
            row.goodsEur = goodsEur;
            row.dutyRatePct = hsCodes.dutyRateFor(product.hsCode(), Money.nz(order.defaultDutyRatePct()));
            row.dutySource = product.hsCode() == null || product.hsCode().isBlank()
                    ? "standaardtarief order"
                    : product.hsCode();
            working.add(row);
        }

        /* ---- 2. Verdeelsleutels ---------------------------------------- */
        BigDecimal totalCbm = working.stream().map(r -> r.cbm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalValue = working.stream().map(r -> r.goodsEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalPieces = working.stream().mapToInt(r -> r.quantity).sum();
        BigDecimal totalPiecesDecimal = BigDecimal.valueOf(totalPieces);

        for (Working row : working) {
            row.cbmShare = Money.share(row.cbm, totalCbm);
            row.valueShare = Money.share(row.goodsEur, totalValue);
            row.pieceShare = Money.share(BigDecimal.valueOf(row.quantity), totalPiecesDecimal);
        }

        /* ---- 3. Containerkosten verdelen ------------------------------- */
        BigDecimal originEurTotal = currencies.toEur(Money.nz(order.originCosts()),
                order.originCurrency() == null ? Currency.USD : order.originCurrency(),
                order.cnyToUsd(), order.usdToEurTransport());
        BigDecimal freightEurTotal = Money.nz(order.freightUsd()).multiply(Money.nz(order.usdToEurTransport()));
        BigDecimal destinationEurTotal = Money.nz(order.destinationCostsEur());
        BigDecimal extraEurTotal = Money.nz(order.extraRevenueEur());

        for (Working row : working) {
            row.originEur = originEurTotal.multiply(shareFor(row, order.allocOrigin()));
            row.freightEur = freightEurTotal.multiply(shareFor(row, order.allocFreight()));
            row.destinationEur = destinationEurTotal.multiply(shareFor(row, order.allocDestination()));
            row.extraEur = extraEurTotal.multiply(shareFor(row, order.allocExtra()));

            /* Customs value at the EU border. */
            row.customsValueEur = row.goodsEur.add(row.originEur).add(row.freightEur);
            row.dutyEur = Money.percentOf(row.customsValueEur, row.dutyRatePct);

            row.totalEur = row.customsValueEur.add(row.dutyEur).add(row.destinationEur).add(row.extraEur);
            row.landedUnitEur = row.quantity > 0
                    ? Money.divide(row.totalEur, BigDecimal.valueOf(row.quantity))
                    : BigDecimal.ZERO;
        }

        /* ---- 4. Totalen ------------------------------------------------ */
        List<LandedCost.Line> lines = working.stream()
                .map(row -> new LandedCost.Line(
                        row.product.id(), row.product.describe(), row.quantity, row.cartons, row.cbm,
                        Money.money(row.goodsUsd), Money.money(row.goodsEur), Money.money(row.originEur),
                        Money.money(row.freightEur), Money.money(row.customsValueEur),
                        row.dutyRatePct, row.dutySource, Money.money(row.dutyEur),
                        Money.money(row.destinationEur), Money.money(row.extraEur),
                        Money.money(row.totalEur), Money.unit(row.landedUnitEur),
                        row.cbmShare, row.valueShare, row.pieceShare))
                .toList();

        BigDecimal customsValue = working.stream().map(r -> r.customsValueEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal duty = working.stream().map(r -> r.dutyEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = working.stream().map(r -> r.totalEur).reduce(BigDecimal.ZERO, BigDecimal::add);

        LandedCost.Totals totals = new LandedCost.Totals(
                totalPieces,
                working.stream().mapToInt(r -> r.cartons).sum(),
                totalCbm.setScale(3, RoundingMode.HALF_UP),
                Money.money(working.stream().map(r -> r.goodsUsd).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.money(totalValue),
                Money.money(originEurTotal),
                Money.money(freightEurTotal),
                Money.money(customsValue),
                Money.money(duty),
                Money.money(destinationEurTotal),
                Money.money(extraEurTotal),
                Money.money(total),
                totalPieces > 0 ? Money.unit(Money.divide(total, totalPiecesDecimal)) : BigDecimal.ZERO,
                customsValue.signum() > 0
                        ? Money.divide(duty.multiply(Money.HUNDRED), customsValue).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);

        return new LandedCost(lines, totals, fillFor(order.containerType(), totals.cbm()));
    }

    private LandedCost.ContainerFill fillFor(ContainerType type, BigDecimal usedCbm) {
        if (type == null || !type.hasCapacity()) return null;
        BigDecimal capacity = type.capacityCbm();
        BigDecimal fill = Money.divide(usedCbm.multiply(Money.HUNDRED), capacity).setScale(1, RoundingMode.HALF_UP);
        return new LandedCost.ContainerFill(
                type.code(),
                capacity,
                usedCbm,
                fill.min(BigDecimal.valueOf(100)),
                capacity.subtract(usedCbm).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP),
                usedCbm.subtract(capacity).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP));
    }

    private BigDecimal shareFor(Working row, Allocation allocation) {
        Allocation basis = allocation == null ? Allocation.CBM : allocation;
        return switch (basis) {
            case CBM -> row.cbmShare;
            case VALUE -> row.valueShare;
            case PIECES -> row.pieceShare;
        };
    }

    /** Per-line running state during the calculation. */
    private static final class Working {
        Product product;
        int quantity;
        int cartons;
        BigDecimal cbm = BigDecimal.ZERO;
        BigDecimal goodsUsd = BigDecimal.ZERO;
        BigDecimal goodsEur = BigDecimal.ZERO;
        BigDecimal originEur = BigDecimal.ZERO;
        BigDecimal freightEur = BigDecimal.ZERO;
        BigDecimal customsValueEur = BigDecimal.ZERO;
        BigDecimal dutyRatePct = BigDecimal.ZERO;
        String dutySource = "";
        BigDecimal dutyEur = BigDecimal.ZERO;
        BigDecimal destinationEur = BigDecimal.ZERO;
        BigDecimal extraEur = BigDecimal.ZERO;
        BigDecimal totalEur = BigDecimal.ZERO;
        BigDecimal landedUnitEur = BigDecimal.ZERO;
        BigDecimal cbmShare = BigDecimal.ZERO;
        BigDecimal valueShare = BigDecimal.ZERO;
        BigDecimal pieceShare = BigDecimal.ZERO;
    }
}
