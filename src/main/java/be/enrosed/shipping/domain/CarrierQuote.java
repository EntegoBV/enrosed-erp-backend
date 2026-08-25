package be.enrosed.shipping.domain;

import java.math.BigDecimal;

/** The outcome of pricing one shipment against a carrier's tariff. */
public record CarrierQuote(
        BigDecimal baseEur,
        BigDecimal dieselPct,
        BigDecimal dieselEur,
        BigDecimal surchargePct,
        BigDecimal surchargePctEur,
        BigDecimal surchargeFixedEur,
        BigDecimal totalEur,
        String zoneName,
        /** Human description of the tier that matched, e.g. "t/m 5 europallets". */
        String tierLabel,
        /** False when the postcode missed every zone and the nearest one was used. */
        boolean postcodeMatched,
        String surchargeNote
) {}
