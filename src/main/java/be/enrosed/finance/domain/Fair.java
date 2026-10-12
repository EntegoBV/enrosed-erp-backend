package be.enrosed.finance.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * A fair the company stands on: TICA, Ede, the next one. Its code is a
 * sales channel, shared with the sales side, so a cost booked on it lands
 * in "Per kanaal" next to what the fair brought in. A fair that is over
 * is switched off, never deleted while a cost still carries its code.
 */
public record Fair(
        Long id,
        /** The channel code: upper-case letters, digits and underscores, at most 40. Never changes. */
        String code,
        String label,
        boolean active,
        Instant createdAt
) {
    /** The channels that are not fairs; a fair can never take one of these codes. */
    public static final Set<String> FIXED_CHANNELS = Set.of("DIRECT", "WEBSITE", "PARTNER", "FAIR");
    public static final int CODE_LENGTH = 40;

    /** The channel code for a label: "Ede" becomes EDE, "Trends & Trade Ede" becomes TRENDS_TRADE_EDE; null when nothing is left. */
    public static String codeOf(String label) {
        if (label == null) return null;
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String code = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (code.length() > CODE_LENGTH) code = code.substring(0, CODE_LENGTH).replaceAll("_+$", "");
        return code.isEmpty() ? null : code;
    }
}
