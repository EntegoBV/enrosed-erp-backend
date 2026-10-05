package be.enrosed.catalog.adapter.in.rest;

/** Apply at the HTTP boundary, for every channel: no amount leaves an anonymous catalogue. */
final class PublicCatalogPriceVisibility {
    private PublicCatalogPriceVisibility() {}

    static PublicFamilyCatalogDto apply(PublicFamilyCatalogDto value, boolean visible) {
        if (value == null || visible) return value;
        return new PublicFamilyCatalogDto(value.channel(), value.language(), value.fallbackChain(),
                value.siteCopyRevision(), value.catalogRevision(), value.siteCopy(), value.categories(),
                value.families().stream().map(PublicCatalogPriceVisibility::withoutPrices).toList(), false);
    }

    static PublicCatalogDto apply(PublicCatalogDto value, boolean visible) {
        if (value == null || visible) return value;
        return new PublicCatalogDto(value.channel(), value.language(),
                value.products().stream().map(product -> new PublicCatalogDto.PublicProductDto(
                        product.id(), product.sku(), product.familyKey(), product.publicHandle(),
                        product.name(), product.description(), product.colour(), product.category(),
                        product.dimensions(), product.carton(), null, product.availability(),
                        product.photos())).toList());
    }

    private static PublicFamilyCatalogDto.FamilyDto withoutPrices(PublicFamilyCatalogDto.FamilyDto family) {
        return new PublicFamilyCatalogDto.FamilyDto(family.id(), family.familyKey(), family.publicHandle(),
                family.name(), family.summary(), family.description(), family.format(), family.highlights(),
                family.category(), family.productPosition(), family.cardFeaturedProductId(), family.tags(),
                family.status(), family.seo(), family.dimensions(), family.packages(), family.images(),
                family.variants().stream().map(variant -> new PublicFamilyCatalogDto.VariantDto(
                        variant.id(), variant.sku(), variant.barcode(), variant.color(), variant.size(),
                        variant.colorHex(), variant.name(), variant.position(), variant.availability(),
                        variant.primaryImageId(), null, variant.textSources(), variant.salesUnit(),
                        variant.piecesPerDisplay(), variant.unit())).toList(),
                family.textSources(), family.quoteImageId());
    }
}
