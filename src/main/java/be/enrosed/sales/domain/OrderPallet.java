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
        List<Item> items
) {
    public record Item(long productId, int cartons) {}

    public List<Item> items() {
        return items == null ? List.of() : items;
    }

    /** Total cartons on this pallet. */
    public int cartons() {
        return items().stream().mapToInt(Item::cartons).sum();
    }
}
