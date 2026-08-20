package be.enrosed.sales.domain;

/** How the order physically leaves the warehouse. */
public enum LoadMode {
    /** Cartons are stacked on pallets, calculated or laid out by hand. */
    PALLETS,
    /** Cartons travel loose; volume still comes from each product's outer carton. */
    LOOSE_CARTONS
}
