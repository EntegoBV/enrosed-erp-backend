package be.enrosed.sales.domain;

/** How road freight is priced before the optional "to be determined" overlay. */
public enum FreightPricingStrategy {
    /** Destination-country tariff per pallet, including that country's minimum. */
    COUNTRY_PALLET,
    /** Order volume multiplied by the order's own euro-per-CBM rate. */
    PER_CBM,
    /** One fixed total stored in {@code manualFreightEur}. */
    FIXED,
    /** A shipping organisation's staffel: zone by postcode, tier by pallets. */
    CARRIER,
    /** The customer collects at the warehouse: no freight at all. */
    PICKUP
}
