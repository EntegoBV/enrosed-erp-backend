package be.enrosed.partnerportal.application;

import be.enrosed.sales.application.PartnerSettlementLedger;
import be.enrosed.shared.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The money of a melding (contract section 1), the one place it is computed. The frontend's live
 * totals and the invoice preview follow the same arithmetic:
 *
 * <ul>
 * <li>a priced quantity: gross = the typed total, else round2(quantity x unit price); the stored
 *     unit price is total / quantity at 4 decimals when a total was typed;</li>
 * <li>commission and transport = round2(gross x percent / 100) per sale entry, with the percents
 *     the melding snapshotted (0 on a direct sale);</li>
 * <li>extra cost PCT = round2(gross x pct / 100) per sale entry; AMOUNT = the whole amount split
 *     over the sale entries by gross with {@link PartnerSettlementLedger#allocate} (each share
 *     HALF_UP of what remains, the last entry takes the rest);</li>
 * <li>net = gross - commission - transport - extra; a sale entry with net below 0 is refused;</li>
 * <li>a buyback has only its gross (the terugkoopkost), net = -gross;</li>
 * <li>an entry split over sources: the quantity is split, then gross and each cost are allocated
 *     by quantity (residual on the last part); a part's net is its gross minus its costs.</li>
 * </ul>
 */
public final class PartnerSaleMoney {

    static final BigDecimal HUNDRED = new BigDecimal("100");
    static final BigDecimal ZERO = new BigDecimal("0.00");

    /** What one sale or buyback entry comes to before it is split over its sources. */
    public record Priced(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur) {}

    /** A sale entry with its costs, or one part of it. */
    public record Sale(int quantity, BigDecimal unitPriceEur, BigDecimal grossEur, BigDecimal commissionEur,
                       BigDecimal transportEur, BigDecimal extraCostEur, BigDecimal netEur) {}

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

    /** round2(gross x pct / 100). */
    public static BigDecimal pctOf(BigDecimal gross, BigDecimal pct) {
        if (pct == null || pct.signum() == 0) return ZERO;
        return gross.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * The sale entries of one melding with their costs, in the given order. extraMode is null,
     * "PCT" or "AMOUNT". Refuses an entry whose costs exceed its gross.
     */
    public static List<Sale> sales(List<Priced> entries, BigDecimal commissionPct, BigDecimal transportPct,
                                   String extraMode, BigDecimal extraValue) {
        List<BigDecimal> extras;
        if ("AMOUNT".equals(extraMode) && extraValue != null && !entries.isEmpty()) {
            extras = PartnerSettlementLedger.allocate(round2(extraValue),
                    entries.stream().map(Priced::grossEur).toList());
        } else {
            extras = new ArrayList<>();
            for (Priced entry : entries) {
                extras.add("PCT".equals(extraMode) ? pctOf(entry.grossEur(), extraValue) : ZERO);
            }
        }
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

    /** A buyback's gross split over parts of these quantities. */
    public static List<BigDecimal> splitGross(BigDecimal gross, List<Integer> quantities) {
        return PartnerSettlementLedger.allocate(gross, quantities.stream().map(BigDecimal::valueOf).toList());
    }
}
