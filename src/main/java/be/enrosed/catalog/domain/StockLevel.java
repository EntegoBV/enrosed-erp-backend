package be.enrosed.catalog.domain;

/** How many pieces of one product lie at one location. */
public record StockLevel(long productId, StockLocation location, int quantity) {}
