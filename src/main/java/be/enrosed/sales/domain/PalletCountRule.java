package be.enrosed.sales.domain;

/**
 * How a sales document counts its automatic pallets, the count its per-pallet freight bills
 * while no hand layout exists.
 *
 * STRICT puts every product on pallets of its own: the count every document had until
 * 2026-11-04. MIXED lets products share a pallet in whole layers of their own, the way the
 * Palletberekening screen stacks them (Emre, 2026-11-04: "Ja, gemengd tellen"). A document keeps
 * the rule it was created with for good: no edit, send, reopen or in-memory copy changes it, so a
 * document that left concept keeps the pallet count and freight it was sent with.
 */
public enum PalletCountRule {
    STRICT,
    MIXED;

    /**
     * The stored value read leniently: a row from before the rule that PalletCountRuleBackfill has not decided yet,
     * or a value this version does not know, keeps the strict count every document had before the
     * rule existed.
     */
    public static PalletCountRule stored(String value) {
        if (value == null) return STRICT;
        try {
            return valueOf(value.strip());
        } catch (IllegalArgumentException unknown) {
            return STRICT;
        }
    }
}
