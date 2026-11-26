package be.enrosed.sales.domain;

import java.util.List;

/**
 * A hand-built pallet on a sales order.
 *
 * The pallet calculator gives a good default, but the warehouse floor has
 * opinions a formula does not know: fragile glass on top, one customer's
 * display boxes together, a half pallet that must travel last. When the
 * seller lays out pallets by hand, those pallets become the truth - the
 * freight counts them instead of the calculated stack.
 *
 * Items reference the product, not the order line: lines can be replaced
 * when a revision is adopted, but the product on the pallet stays the same
 * physical box.
 */
public record OrderPallet(
        Long id,
        /** Free label, e.g. "Pallet 1 - glas" or a customer reference. */
        String label,
        /**
         * Pallet type, e.g. "Europallet" or "Blokpallet 120×100" (B × D).
         *
         * Informational: freight counts pallet positions regardless of the
         * wood underneath, but the transporter and the warehouse want to
         * know what to expect on the truck.
         */
        String type,
        /**
         * Stacked height in centimetres, hand-measured or estimated.
         *
         * The truck has a ceiling and so does double-stacking; the
         * transporter asks for this number on every booking.
         */
        Integer heightCm,
        List<Item> items,
        /**
         * What "Zelf indelen" copied this pallet from on a PACKED document (2026-11-25), written by the ERP
         * (pallet-hand-load.ts copySources), e.g. "PACKED|EURO_120X80|260|12:24,15:8|0|12:20,15:8": the rule, the
         * pallet profile, the loaded height, the run's cartons per product in line order, the pallet's place in it and
         * its own cartons.
         * The ERP loads that run again to judge the pallet's height after later edits; the server only keeps it,
         * nothing here counts or prices with it. Null on a pallet built by hand; text longer than
         * {@link #SOURCE_MAX} or with other characters than the format uses is dropped.
         */
        String source
) {
    public record Item(long productId, int cartons) {}

    /** The longest source kept (sales_pallet.copy_source). */
    public static final int SOURCE_MAX = 4000;

    private static final java.util.regex.Pattern SOURCE_TEXT = java.util.regex.Pattern.compile("[A-Za-z0-9_|:,.\\-]+");

    /*
     * No constructor without the source on purpose: a pallet the server copies, trims or renumbers passes its
     * source on (pallet.source()), so no path drops it unseen.
     */
    public OrderPallet {
        if (source != null && (source.length() > SOURCE_MAX || !SOURCE_TEXT.matcher(source).matches())) source = null;
    }

    /** The default is the standard of European road freight. */
    public String type() {
        if (type == null || type.isBlank()) return "Europallet";
        return switch (type.strip()) {
            case "Blokpallet 100×120" -> "Blokpallet 120×100";
            case "Halve pallet 60×80" -> "Halve pallet 80×60";
            default -> type.strip();
        };
    }

    public List<Item> items() {
        return items == null ? List.of() : items;
    }

    /** Total cartons on this pallet. */
    public int cartons() {
        return items().stream().mapToInt(Item::cartons).sum();
    }
}
