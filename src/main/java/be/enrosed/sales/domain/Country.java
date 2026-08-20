package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Sales country. Its standard road tariff is per pallet; an order may
 * deliberately choose a per-CBM rate or a fixed freight total instead.
 */
public record Country(
        String code,
        String name,
        BigDecimal minOrderValue,
        BigDecimal freightPerPallet,
        BigDecimal minFreight,
        BigDecimal handling,
        BigDecimal vatRatePct,
        int transitDays,
        /** EU member state? Decides whether a delivery can be intra-community. */
        boolean euMember
) {}
