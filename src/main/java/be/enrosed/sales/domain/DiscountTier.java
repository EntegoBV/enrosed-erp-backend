package be.enrosed.sales.domain;

import java.math.BigDecimal;

/** Tier: from this piece count onwards, this discount percentage applies. */
public record DiscountTier(
        Long id,
        TierScope scope,
        int minQuantity,
        BigDecimal percent,
        /** Required for line tiers; order tiers deliberately have no product target. */
        Long productId
) {

    /** Source compatibility for order tiers and callers written before product targeting. */
    public DiscountTier(Long id, TierScope scope, int minQuantity, BigDecimal percent) {
        this(id, scope, minQuantity, percent, null);
    }
}
