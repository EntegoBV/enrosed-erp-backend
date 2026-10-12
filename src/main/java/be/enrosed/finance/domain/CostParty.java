package be.enrosed.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Someone the company pays: the fair organiser, the accountant, the
 * landlord. The list "Aan wie" behind the costs and the bank screen. A
 * party remembers the defaults a booking to it usually takes, so the next
 * cost fills itself in; a booking never changes a party it already knows.
 */
public record CostParty(
        Long id,
        String name,
        /** The usual category of a cost to this party: BEURS, HUUR, ...; null when none. */
        String category,
        /** The usual VAT rate; null when none. */
        BigDecimal vatPct,
        /** The usual channel or fair: TICA, EDE, ...; null when none. */
        String salesChannel,
        /** Without spaces, upper-case; null when unknown. Kept for the bank reconciliation to come. */
        String iban,
        Instant createdAt
) {
    /** The name as the list matches it: trimmed, inner whitespace collapsed, lower-cased; null for a blank name. */
    public static String keyOf(String name) {
        if (name == null) return null;
        String key = name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
