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
         * Pallet type, e.g. "Europallet" or "Blokpallet 100×120".
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
        List<Item> items
) {
    public record Item(long productId, int cartons) {}

    /** The default is the standard of European road freight. */
    public String type() {
        return type == null || type.isBlank() ? "Europallet" : type;
    }

    public List<Item> items() {
        return items == null ? List.of() : items;
    }

    /** Total cartons on this pallet. */
    public int cartons() {
        return items().stream().mapToInt(Item::cartons).sum();
    }
}
