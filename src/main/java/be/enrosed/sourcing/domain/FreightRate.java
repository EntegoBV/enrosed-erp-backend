package be.enrosed.sourcing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One quoted container rate on a China -> Rotterdam route.
 *
 * Hand-logged from forwarder quotes, deliberately not scraped: the public
 * spot indices (Drewry, FBX, SCFI) are licensed data without a free feed,
 * and the rates that matter are the ones actually offered to us. A few
 * entries per route and the dashboard draws the trend.
 */
public record FreightRate(
        Long id,
        /** Route code, e.g. "NINGBO", "GUANGZHOU", "SHENZHEN". */
        String route,
        LocalDate quotedOn,
        BigDecimal usdPerContainer
) {}
