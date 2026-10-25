package be.enrosed.catalog.application;

import be.enrosed.shared.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the row sheet sends for one supplier of a product. {@code supplierId} only counts on add;
 * {@code expectedUpdatedAt} (optional) refuses a save over a row changed meanwhile.
 */
public record ProductSupplierWrite(Long supplierId, String versionLabel, String supplierReference,
        BigDecimal exwPrice, Currency exwCurrency, BigDecimal extraUnitCost, Integer moq, Integer leadTimeDays,
        String supplierNote, Instant expectedUpdatedAt) {}
