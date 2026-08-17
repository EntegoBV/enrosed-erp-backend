package be.enrosed.sales.domain;

import java.math.BigDecimal;

/**
 * Offerteregel.
 *
 * {@code unitPriceEur} en {@code manualDiscountPct} zijn handmatige ingrepen;
 * blijven ze leeg, dan rekent de prijsmotor ze zelf uit.
 */
public record SalesOrderLine(
        Long id,
        Long productId,
        int quantity,
        BigDecimal unitPriceEur,
        BigDecimal manualDiscountPct,

        /**
         * Zelf ingevulde leverweek, bv. "2026-W34". Optioneel: staat er niets,
         * dan wordt de schatting uit de voorraad en de transittijd gebruikt.
         */
        String deliveryWeek
) {}
