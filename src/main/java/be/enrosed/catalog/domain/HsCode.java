package be.enrosed.catalog.domain;

import java.math.BigDecimal;

/**
 * Douanetariefcode met het bijhorende invoerrecht.
 *
 * De percentages horen nagekeken te worden in de TARIC-databank van de EU;
 * wat hier staat is configuratie, geen douaneadvies.
 */
public record HsCode(Long id, String code, String description, BigDecimal dutyRatePct) {
}
