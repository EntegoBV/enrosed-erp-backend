package be.enrosed.catalog.adapter.in.rest;

/** Selects the existing product that must become a colour and/or size variant. */
public record ProductVariantLinkRequest(Long variantProductId) {}
