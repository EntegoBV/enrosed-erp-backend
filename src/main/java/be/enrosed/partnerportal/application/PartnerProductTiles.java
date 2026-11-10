package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.SalesPhotoService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.domain.PartnerProductNames;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds product tiles: the catalogue is read once per request, family names in one query. */
@ApplicationScoped
public class PartnerProductTiles {

    private final ProductService products;
    private final PartnerPortalQueries queries;

    public PartnerProductTiles(ProductService products, PartnerPortalQueries queries) {
        this.products = products;
        this.queries = queries;
    }

    /** A snapshot of the catalogue for one answer. */
    public Catalog load() {
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : products.list()) {
            if (product.id() != null) byId.put(product.id(), product);
        }
        Map<Long, String> families = queries.familyNames(byId.values().stream()
                .map(Product::familyId).filter(Objects::nonNull).distinct().toList());
        return new Catalog(byId, families);
    }

    public static final class Catalog {
        private final Map<Long, Product> products;
        private final Map<Long, String> families;
        private final Map<Long, PartnerPortalViews.ProductTile> tiles = new HashMap<>();

        Catalog(Map<Long, Product> products, Map<Long, String> families) {
            this.products = products;
            this.families = families;
        }

        public Optional<Product> product(long productId) {
            return Optional.ofNullable(products.get(productId));
        }

        public Map<Long, Product> products() {
            return products;
        }

        /** The tile of a product; a product deleted since reads as "Product #id" without a photo. */
        public PartnerPortalViews.ProductTile tile(long productId) {
            return tiles.computeIfAbsent(productId, id -> {
                Product product = products.get(id);
                if (product == null) {
                    return new PartnerPortalViews.ProductTile(id, null, "Product #" + id, null, null, null, null,
                            "stuk", "stuks", 1, false, null);
                }
                PartnerProductNames.Names names = PartnerProductNames.of(product);
                Optional<SalesPhotoService.Choice> photo = SalesPhotoService.choose(product);
                return new PartnerPortalViews.ProductTile(id, product.sku(), names.name(), names.variant(),
                        names.colourHex(), product.familyId(),
                        product.familyId() == null ? null : families.get(product.familyId()),
                        names.unitOne(), names.unitOther(), names.piecesPerUnit(),
                        photo.isPresent(), photo.map(SalesPhotoService.Choice::version).orElse(null));
            });
        }

        /** Family first (a product without one sorts under its own name), then name, variant, id. */
        public Comparator<PartnerPortalViews.ProductTile> order() {
            return Comparator
                    .comparing((PartnerPortalViews.ProductTile tile) -> PartnerProductNames.sortKey(
                            tile.familyName() != null ? tile.familyName() : tile.name()))
                    .thenComparing(tile -> PartnerProductNames.sortKey(tile.name()))
                    .thenComparing(tile -> PartnerProductNames.sortKey(tile.variant()))
                    .thenComparingLong(PartnerPortalViews.ProductTile::productId);
        }
    }
}
