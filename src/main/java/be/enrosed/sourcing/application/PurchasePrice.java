package be.enrosed.sourcing.application;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;

/**
 * What one purchase line calculates with: the factory price, its currency and the extra cost per piece (read in
 * that currency). See {@link PurchasePriceResolver#price}.
 */
public record PurchasePrice(BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost) {}
