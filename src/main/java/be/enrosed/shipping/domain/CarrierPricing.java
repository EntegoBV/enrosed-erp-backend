package be.enrosed.shipping.domain;

import be.enrosed.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Prices one shipment against a carrier tariff: resolve the zone from the
 * postcode, walk the tier ladder, add the surcharges.
 *
 * Zone resolution is deliberately forgiving. The user picks a country and
 * types a postcode; when the postcode is not in any zone list the nearest
 * listed prefix wins, because a rate sheet that skips a prefix means "same
 * as its neighbours", not "we do not deliver there".
 */
public final class CarrierPricing {

    private CarrierPricing() {}

    /** How the pallets on the order map onto the tariff's columns. */
    public enum PalletKind { EUROPALLET, BLOCKPALLET }

    public static CarrierQuote quote(Carrier carrier, String countryCode, String postcode,
                                     int pallets, PalletKind kind, BigDecimal weightKg) {
        if (carrier == null) return null;
        CarrierLane lane = carrier.lane(countryCode);
        if (lane == null || lane.zones().isEmpty() || lane.tiers().isEmpty()) return null;
        if (pallets <= 0) return null;

        ZoneMatch zone = resolveZone(lane.zones(), postcode);
        if (zone == null) return null;
        CarrierTier tier = pickTier(lane.tiers(), pallets, kind, weightKg);
        if (tier == null) return null;

        int zoneIndex = lane.zones().indexOf(zone.zone());
        if (zoneIndex < 0 || zoneIndex >= tier.prices().size()) return null;
        BigDecimal base = tier.prices().get(zoneIndex);
        if (base == null) return null;

        BigDecimal dieselPct = Money.nz(carrier.dieselSurchargePct());
        BigDecimal diesel = pct(base, dieselPct);
        BigDecimal lanePct = Money.nz(lane.surchargePct());
        BigDecimal laneEur = pct(base, lanePct);
        BigDecimal fixed = Money.nz(lane.surchargeFixedEur());
        BigDecimal total = base.add(diesel).add(laneEur).add(fixed);

        return new CarrierQuote(Money.money(base), dieselPct, diesel, lanePct, laneEur,
                Money.money(fixed), Money.money(total), zone.zone().name(), label(tier, kind),
                zone.exact(), lane.surchargeNote());
    }

    private record ZoneMatch(CarrierZone zone, boolean exact) {}

    /** Exact prefix match first; otherwise the numerically/alphabetically nearest token. */
    private static ZoneMatch resolveZone(List<CarrierZone> zones, String postcode) {
        /* A carrier that prices one flat ladder for the whole country has a
           single zone without postcode tokens: it always matches. */
        if (zones.size() == 1
                && (zones.get(0).postcodes() == null || zones.get(0).postcodes().isBlank())) {
            return new ZoneMatch(zones.get(0), true);
        }
        String cleaned = postcode == null
                ? "" : postcode.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (cleaned.isEmpty()) {
            return zones.size() == 1 ? new ZoneMatch(zones.get(0), true) : null;
        }
        String digits = cleaned.replaceAll("\\D.*$", "");
        String letters = cleaned.replaceAll("\\d.*$", "");

        CarrierZone best = null;
        long bestDistance = Long.MAX_VALUE;
        int bestLength = -1;
        for (CarrierZone zone : zones) {
            if (zone.postcodes() == null) continue;
            for (String token : zone.postcodes().split(",")) {
                token = token.trim().toUpperCase(Locale.ROOT);
                if (token.isEmpty()) continue;
                boolean numeric = Character.isDigit(token.charAt(0));
                String subject = numeric ? digits : letters;
                if (subject.isEmpty()) continue;
                long distance;
                int length;
                if (numeric) {
                    String[] ends = token.split("-", 2);
                    length = ends[0].length();
                    if (subject.length() < length) continue;
                    long value = Long.parseLong(subject.substring(0, length));
                    long low = Long.parseLong(ends[0]);
                    long high = Long.parseLong(ends.length > 1 ? ends[1] : ends[0]);
                    distance = value < low ? low - value : value > high ? value - high : 0;
                } else {
                    length = token.length();
                    String head = subject.length() < length ? subject : subject.substring(0, length);
                    distance = 0;
                    for (int i = 0; i < length; i++) {
                        char a = i < head.length() ? head.charAt(i) : 'A';
                        distance = distance * 26 + Math.abs(a - token.charAt(i));
                        if (head.length() < length) distance += 1;
                    }
                }
                if (distance < bestDistance
                        || (distance == bestDistance && length > bestLength)) {
                    bestDistance = distance;
                    bestLength = length;
                    best = zone;
                }
            }
        }
        return best == null ? null : new ZoneMatch(best, bestDistance == 0);
    }

    /**
     * The lowest rung that carries the whole shipment: enough pallet
     * capacity of the right sort and, when the ladder caps weight, enough
     * kilos. Rows without a capacity for the shipment's pallet kind are
     * someone else's rungs.
     */
    private static CarrierTier pickTier(List<CarrierTier> tiers, int pallets,
                                        PalletKind kind, BigDecimal weightKg) {
        CarrierTier best = null;
        for (CarrierTier tier : tiers) {
            BigDecimal capacity = kind == PalletKind.BLOCKPALLET ? tier.bpMax() : tier.epMax();
            if (capacity == null) continue;
            if (capacity.compareTo(BigDecimal.valueOf(pallets)) < 0) continue;
            if (weightKg != null && weightKg.signum() > 0 && tier.kgMax() != null
                    && tier.kgMax().compareTo(weightKg) < 0) continue;
            if (best == null || compareCapacity(tier, best, kind) < 0) best = tier;
        }
        return best;
    }

    private static int compareCapacity(CarrierTier a, CarrierTier b, PalletKind kind) {
        BigDecimal ca = kind == PalletKind.BLOCKPALLET ? a.bpMax() : a.epMax();
        BigDecimal cb = kind == PalletKind.BLOCKPALLET ? b.bpMax() : b.epMax();
        int byCapacity = ca.compareTo(cb);
        return byCapacity != 0 ? byCapacity : Integer.compare(a.position(), b.position());
    }

    private static String label(CarrierTier tier, PalletKind kind) {
        BigDecimal capacity = kind == PalletKind.BLOCKPALLET ? tier.bpMax() : tier.epMax();
        String sort = kind == PalletKind.BLOCKPALLET ? "blokpallet(s)" : "europallet(s)";
        StringBuilder text = new StringBuilder("t/m ").append(plain(capacity)).append(' ').append(sort);
        if (tier.kgMax() != null) text.append(" · max ").append(plain(tier.kgMax())).append(" kg");
        return text.toString();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal percentage) {
        return Money.money(base.multiply(percentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
    }
}
