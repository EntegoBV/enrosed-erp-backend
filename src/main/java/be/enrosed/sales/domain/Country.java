package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Verkoopland. Verkoop gaat over de weg op pallets, dus de vracht hangt aan
 * het aantal palletplaatsen en niet aan het volume.
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
        /** Lidstaat van de EU? Bepaalt of een levering intracommunautair kan zijn. */
        boolean euMember
) {}
