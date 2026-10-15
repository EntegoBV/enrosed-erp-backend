package be.enrosed.catalog.domain;

import java.util.Locale;

/**
 * What one sale of a product moves at a time ("Verkocht per"): the full outer
 * carton, the product's own packaging (a display of several pieces) or one
 * unit. Quantities stay in sales units whatever the step; only the multiple
 * they round up to changes. See {@link OrderSteps} for the one rule.
 */
public enum OrderStep {
    /** A full outer carton: today's behaviour and the default for every product. */
    CARTON,
    /** One display of pieces; a gift box holds one piece, so it moves one at a time. */
    PACKAGE,
    /** One piece in the product's own unit (per stolp, per hart), or one display on a display basis. */
    UNIT;

    /** A stored or sent name; null, blank or unknown (older rows, frozen JSON) reads as null, which is CARTON. */
    public static OrderStep parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** The step a stored name stands for; anything unreadable is the default carton. */
    public static OrderStep effective(String value) {
        OrderStep step = parse(value);
        return step == null ? CARTON : step;
    }
}
