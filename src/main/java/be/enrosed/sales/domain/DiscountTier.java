package be.enrosed.sales.domain;

import java.math.BigDecimal;

/** Tier: from this piece count onwards, this discount percentage applies. */
public record DiscountTier(Long id, TierScope scope, int minQuantity, BigDecimal percent) {
}
