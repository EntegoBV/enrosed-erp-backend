package be.enrosed.sales.domain;

import java.math.BigDecimal;

/** Staffel: vanaf dit aantal stuks geldt dit kortingspercentage. */
public record DiscountTier(Long id, TierScope scope, int minQuantity, BigDecimal percent) {
}
