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
        /** ISO country code of the party: BE, IE, US; null when unknown. */
        String country,
        /** The party's VAT number without spaces, upper-case: BE0123456749, IE6388047V; null when unknown. */
        String vatNumber,
        /** The VAT regime a cost to this party usually takes, learned from the first confirmed cost; null when none. */
        VatRegime vatRegime,
        Instant createdAt
) {
    /** A party from before the regimes: country, VAT number and regime unknown. */
    public CostParty(Long id, String name, String category, BigDecimal vatPct, String salesChannel, String iban, Instant createdAt) {
        this(id, name, category, vatPct, salesChannel, iban, null, null, null, createdAt);
    }

    /** The name as the list matches it: trimmed, inner whitespace collapsed, lower-cased; null for a blank name. */
    public static String keyOf(String name) {
        if (name == null) return null;
        String key = name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
