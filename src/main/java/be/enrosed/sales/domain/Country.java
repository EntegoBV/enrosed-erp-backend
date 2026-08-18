package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Sales country. Sales ships by road on pallets, so freight hangs on the
 * number of pallet positions, not on the volume.
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
