package be.enrosed.shipping.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A road-freight organisation and its published tariff.
 *
 * The model mirrors how forwarders publish rates: per destination country a
 * lane, per lane a set of zones (postcode groups) and a ladder of tiers -
 * so many pallets, loading metres or kilos cost so much per zone. A carrier
 * that prices simpler (one rate per pallet, one fixed amount) is a lane with
 * a single zone and a single tier.
 */
public record Carrier(
        Long id,
        String name,
        String fullName,
        boolean active,
        /** Variable diesel surcharge in percent, applied on top of the zone price. */
        BigDecimal dieselSurchargePct,
        LocalDate validUntil,
        String notes,
        List<CarrierLane> lanes
) {
    public List<CarrierLane> lanes() {
        return lanes == null ? List.of() : lanes;
    }

    public CarrierLane lane(String countryCode) {
        if (countryCode == null) return null;
        return lanes().stream()
                .filter(lane -> countryCode.equalsIgnoreCase(lane.countryCode()))
                .findFirst().orElse(null);
    }
}
