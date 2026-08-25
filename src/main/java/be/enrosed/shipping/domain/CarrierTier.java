package be.enrosed.shipping.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * One rung of the price ladder: up to so many europallets or blockpallets
 * (or loading metres / kilos), these are the prices per zone.
 *
 * Any capacity that does not apply stays null: a France europallet row has
 * only {@code epMax}, a Dutch row carries all four. The prices list is
 * aligned with the lane's zones by position.
 */
public record CarrierTier(
        Long id,
        BigDecimal epMax,
        BigDecimal bpMax,
        BigDecimal ldmMax,
        BigDecimal kgMax,
        int position,
        List<BigDecimal> prices
) {
    public List<BigDecimal> prices() {
        return prices == null ? List.of() : prices;
    }
}
