package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * Customs tariff code with its import duty.
 *
 * The percentages should be checked in the EU's TARIC database; what is
 * here is configuration, not customs advice.
 */
public record HsCode(Long id, String code, String description, BigDecimal dutyRatePct) {
}
