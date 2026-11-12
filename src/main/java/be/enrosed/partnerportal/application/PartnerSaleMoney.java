package be.enrosed.partnerportal.application;

import be.enrosed.sales.application.PartnerSettlementLedger;
import be.enrosed.shared.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The money of a melding (contract sections 1 and 2 of both rounds), the one place it is
 * computed. The frontend's live totals ({@code partner-money.ts}) and the invoice preview follow
 * the same arithmetic to the cent:
 *
 * <ul>
 * <li>a priced quantity: gross = the typed total, else round2(quantity x unit price); the stored
 *     unit price is total / quantity at 4 decimals when a total was typed. Round 2 types a price
 *     per piece: it is scaled to 4 dp first, unit price = piece price x pieces per sales unit, then
 *     gross = round2(quantity x unit price) (= round2(pieces x piece price));</li>
 * <li>commission and transport = round2(gross x percent / 100) per sale entry, with the percents
 *     the melding snapshotted (0 on a direct sale);</li>
 * <li>extra cost lines: per sale entry e, extra_e = the sum over PCT lines of round2(gross_e x pct /
 *     100) plus e's share of the sum of the AMOUNT lines, that sum allocated once over the sale
 *     entries by gross with {@link PartnerSettlementLedger#allocate} (each share HALF_UP of what
 *     remains, the last entry takes the rest). A round-1 single extra cost is one such line;</li>
 * <li>net = gross - commission - transport - extra; a sale entry with net below 0 is refused;</li>
 * <li>a buyback (auction only) carries the melding's commission and transport too:
 *     commission_b = round2(gross_b x pct / 100), transport_b likewise, net_b = -(gross_b +
 *     commission_b + transport_b), the terugkoopkost is -net_b; no extra kost on a buyback;</li>
 * <li>an entry split over sources: the quantity is split, then gross and each cost are allocated by
 *     quantity (residual on the last part); a sale part's net is its gross minus its costs, a
 *     buyback part's net is -(its gross + its costs).</li>
 * </ul>
 */
public final class PartnerSaleMoney {

    static final BigDecimal HUNDRED = new BigDecimal("100");
    static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final String AMOUNT = "AMOUNT";
    public static final String PCT = "PCT";

    /** What one sale or buyback entry comes to before it is split over its sources. */
    public record Priced(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur) {}

    /** A sale entry with its costs, or one part of it. */
    public record Sale(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur, BigDecimal commissionEur,
                       BigDecimal transportEur, BigDecimal extraCostEur, BigDecimal netEur) {}

    /** A buyback entry with its costs, or one part of it; netEur = -(gross + commission + transport). */
    public record Buyback(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur, BigDecimal commissionEur,
                          BigDecimal transportEur, BigDecimal netEur) {
        /** The terugkoopkost: -net. */
        public BigDecimal costEur() {
            return netEur.negate();
        }
    }

    /** One extra cost line: mode AMOUNT (2 dp) or PCT (4 dp), as validated. */
    public record CostLine(String mode, BigDecimal value) {}

    private PartnerSaleMoney() {}

    public static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** A quantity with either a unit price or a total (exactly one, both >= 0; checked by the caller). */
    public static Priced price(int quantity, BigDecimal unitPriceEur, BigDecimal totalEur) {
        if (totalEur != null) {
            BigDecimal gross = round2(totalEur);
            return new Priced(quantity, gross.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP), gross);
        }
        BigDecimal unit = unitPriceEur.setScale(4, RoundingMode.HALF_UP);
        return new Priced(quantity, unit, round2(unit.multiply(BigDecimal.valueOf(quantity))));
    }

    /** The price per piece as stored (4 dp). */
    public static BigDecimal piecePrice(BigDecimal typed) {
        return typed.setScale(4, RoundingMode.HALF_UP);
    }

    /** The unit price per sales unit of a price per piece: piece price (4 dp) x pieces per sales unit. */
    public static BigDecimal unitPriceOfPiece(BigDecimal piecePriceEur, int piecesPerSalesUnit) {
        return piecePrice(piecePriceEur).multiply(BigDecimal.valueOf(Math.max(1, piecesPerSalesUnit)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** round2(gross x pct / 100). */
    public static BigDecimal pctOf(BigDecimal gross, BigDecimal pct) {
        if (pct == null || pct.signum() == 0) return ZERO;
        return gross.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * Per sale entry the extra cost of these lines (in the order of the entries): PCT lines per entry,
     * the AMOUNT lines' sum allocated once by gross.
     */
    public static List<BigDecimal> extras(List<BigDecimal> grossPerEntry, List<CostLine> costs) {
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        boolean anyAmount = false;
        for (CostLine line : costs == null ? List.<CostLine>of() : costs) {
            if (AMOUNT.equals(line.mode()) && line.value() != null) {
                amount = amount.add(round2(line.value()));
                anyAmount = true;
            }
        }
        List<BigDecimal> allocated = anyAmount && !grossPerEntry.isEmpty()
                ? PartnerSettlementLedger.allocate(amount, grossPerEntry) : null;
        for (int index = 0; index < grossPerEntry.size(); index++) {
            BigDecimal extra = allocated == null ? ZERO : allocated.get(index);
            for (CostLine line : costs == null ? List.<CostLine>of() : costs) {
                if (PCT.equals(line.mode())) extra = extra.add(pctOf(grossPerEntry.get(index), line.value()));
            }
            result.add(round2(extra));
        }
        return result;
    }

    /**
     * The sale entries of one melding with their costs, in the given order. Refuses an entry whose
     * costs exceed its gross.
     */
    public static List<Sale> sales(List<Priced> entries, BigDecimal commissionPct, BigDecimal transportPct,
                                   List<CostLine> costs) {
        List<BigDecimal> extras = extras(entries.stream().map(Priced::grossEur).toList(), costs);
        List<Sale> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Priced entry = entries.get(index);
            BigDecimal commission = pctOf(entry.grossEur(), commissionPct);
            BigDecimal transport = pctOf(entry.grossEur(), transportPct);
            BigDecimal extra = extras.get(index);
            BigDecimal net = entry.grossEur().subtract(commission).subtract(transport).subtract(extra);
            if (net.signum() < 0) throw new BusinessRuleException("De kosten zijn hoger dan de opbrengst");
            result.add(new Sale(entry.quantity(), entry.unitPriceEur(), entry.grossEur(), commission, transport,
                    extra, net));
        }
        return result;
    }

    /** A buyback entry with the melding's commission and transport (0 on a direct sale, where it cannot occur). */
    public static Buyback buyback(Priced entry, BigDecimal commissionPct, BigDecimal transportPct) {
        BigDecimal commission = pctOf(entry.grossEur(), commissionPct);
        BigDecimal transport = pctOf(entry.grossEur(), transportPct);
        return new Buyback(entry.quantity(), entry.unitPriceEur(), entry.grossEur(), commission, transport,
                entry.grossEur().add(commission).add(transport).negate());
    }

    /** One sale entry split over parts of these quantities (sum = the entry's quantity). */
    public static List<Sale> split(Sale entry, List<Integer> quantities) {
        List<BigDecimal> weights = quantities.stream().map(BigDecimal::valueOf).toList();
        List<BigDecimal> gross = PartnerSettlementLedger.allocate(entry.grossEur(), weights);
        List<BigDecimal> commission = PartnerSettlementLedger.allocate(entry.commissionEur(), weights);
        List<BigDecimal> transport = PartnerSettlementLedger.allocate(entry.transportEur(), weights);
        List<BigDecimal> extra = PartnerSettlementLedger.allocate(entry.extraCostEur(), weights);
        List<Sale> parts = new ArrayList<>();
        for (int index = 0; index < quantities.size(); index++) {
            BigDecimal net = gross.get(index).subtract(commission.get(index)).subtract(transport.get(index))
                    .subtract(extra.get(index));
            parts.add(new Sale(quantities.get(index), entry.unitPriceEur(), gross.get(index), commission.get(index),
                    transport.get(index), extra.get(index), net));
        }
        return parts;
    }

    /**
     * One buyback entry split over parts of these quantities: gross, commission and transport each
     * allocated by quantity, net_p = -(gross_p + commission_p + transport_p).
     */
    public static List<Buyback> splitBuyback(Buyback entry, List<Integer> quantities) {
        List<BigDecimal> weights = quantities.stream().map(BigDecimal::valueOf).toList();
        List<BigDecimal> gross = PartnerSettlementLedger.allocate(entry.grossEur(), weights);
        List<BigDecimal> commission = PartnerSettlementLedger.allocate(entry.commissionEur(), weights);
        List<BigDecimal> transport = PartnerSettlementLedger.allocate(entry.transportEur(), weights);
        List<Buyback> parts = new ArrayList<>();
        for (int index = 0; index < quantities.size(); index++) {
            BigDecimal net = gross.get(index).add(commission.get(index)).add(transport.get(index)).negate();
            parts.add(new Buyback(quantities.get(index), entry.unitPriceEur(), gross.get(index), commission.get(index),
                    transport.get(index), net));
        }
        return parts;
    }

    /** What a buyback part costs: -net, or its gross for a row without a net. */
    public static BigDecimal buybackCost(BigDecimal grossEur, BigDecimal netEur) {
        if (netEur != null) return netEur.negate();
        return grossEur == null ? BigDecimal.ZERO : grossEur;
    }
}
