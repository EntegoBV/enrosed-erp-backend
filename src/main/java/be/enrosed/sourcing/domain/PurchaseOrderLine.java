package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;

/**
 * Regel op een inkooporder. De prijsvelden mogen leeg zijn; dan geldt de
 * prijs die op het product staat.
 */
public record PurchaseOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost
) {}
