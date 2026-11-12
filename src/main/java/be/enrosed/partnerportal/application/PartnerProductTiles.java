package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.SalesPhotoService;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.domain.PartnerCountUnits;
import be.enrosed.partnerportal.domain.PartnerProductNames;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Builds product tiles: the catalogue is read once per request, family names in one query, the
 * partner's default count units (partner_product_unit) once per partner that is asked for.
 */
@ApplicationScoped
public class PartnerProductTiles {

    private final ProductService products;
    private final ProductRepository productRows;
    private final PartnerPortalQueries queries;
    private final PartnerPortalRepositories.ProductUnits units;

    public PartnerProductTiles(ProductService products, ProductRepository productRows, PartnerPortalQueries queries,
                               PartnerPortalRepositories.ProductUnits units) {
        this.products = products;
        this.productRows = productRows;
        this.queries = queries;
        this.units = units;
    }

    /**
     * Pieces per sales unit of these products only (a display of 12 rozen counts 12, a product sold per piece 1,
     * one deleted since 1), the factor every tile carries: for a cheap reader (the summary) that needs no tile.
     */
    public Map<Long, Integer> piecesPerSalesUnit(Collection<Long> productIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (productIds == null || productIds.isEmpty()) return result;
        for (Product product : productRows.findAllById(productIds.stream().filter(Objects::nonNull).distinct().toList())) {
            if (product.id() != null) result.put(product.id(), Math.max(1, PartnerCountUnits.of(product).piecesPerSalesUnit()));
        }
        return result;
    }

    /** A snapshot of the catalogue for one answer; tiles carry the default unit STUK unless a partner is named. */
    public Catalog load() {
        return load(null);
    }

    /** As {@link #load()}, with this partner's default count units on {@link Catalog#tile(long)}. */
    public Catalog load(Long customerId) {
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : products.list()) {
            if (product.id() != null) byId.put(product.id(), product);
        }
        Map<Long, String> families = queries.familyNames(byId.values().stream()
                .map(Product::familyId).filter(Objects::nonNull).distinct().toList());
        return new Catalog(byId, families, customerId, units::unitsOf);
    }

    public static final class Catalog {
        private final Map<Long, Product> products;
        private final Map<Long, String> families;
        private final Long partnerId;
        private final LongFunction<Map<Long, String>> unitsOf;
        private final Map<Long, PartnerPortalViews.ProductTile> tiles = new HashMap<>();
        private final Map<Long, Map<Long, String>> defaults = new HashMap<>();

        Catalog(Map<Long, Product> products, Map<Long, String> families, Long partnerId,
                LongFunction<Map<Long, String>> unitsOf) {
            this.products = products;
            this.families = families;
            this.partnerId = partnerId;
            this.unitsOf = unitsOf;
        }

        public Optional<Product> product(long productId) {
            return Optional.ofNullable(products.get(productId));
        }

        public Map<Long, Product> products() {
            return products;
        }

        /** How a product counts (a deleted product counts per "stuk" only). */
        public PartnerCountUnits.Counting counting(long productId) {
            return PartnerCountUnits.of(products.get(productId));
        }

        /** The tile of a product with the default unit of the partner this catalogue was loaded for. */
        public PartnerPortalViews.ProductTile tile(long productId) {
            return partnerId == null ? base(productId) : tile(productId, partnerId);
        }

        /** The tile with this partner's default count unit (STUK when none is stored or no longer offered). */
        public PartnerPortalViews.ProductTile tile(long productId, Long customerId) {
            PartnerPortalViews.ProductTile tile = base(productId);
            if (customerId == null) return tile;
            String stored = defaults.computeIfAbsent(customerId, unitsOf::apply).get(productId);
            return stored == null ? tile : tile.withDefaultUnit(counting(productId).effectiveDefault(stored));
        }

        /** The stored default units of a partner, by product (as staff set them; may name units no longer offered). */
        public Map<Long, String> storedUnits(long customerId) {
            return defaults.computeIfAbsent(customerId, unitsOf::apply);
        }

        /** The tile of a product; a product deleted since reads as "Product #id" without a photo. */
        private PartnerPortalViews.ProductTile base(long productId) {
            return tiles.computeIfAbsent(productId, id -> {
                Product product = products.get(id);
                PartnerCountUnits.Counting counting = PartnerCountUnits.of(product);
                List<PartnerCountUnits.CountUnit> offered = counting.units();
                if (product == null) {
                    return new PartnerPortalViews.ProductTile(id, null, "Product #" + id, null, null, null, null,
                            "stuk", "stuks", 1, false, null, counting.salesUnit(), counting.piecesPerSalesUnit(),
                            counting.pieceOne(), counting.pieceOther(), offered, PartnerCountUnits.STUK);
                }
                PartnerProductNames.Names names = PartnerProductNames.of(product);
                Optional<SalesPhotoService.Choice> photo = SalesPhotoService.choose(product);
                return new PartnerPortalViews.ProductTile(id, product.sku(), names.name(), names.variant(),
                        names.colourHex(), product.familyId(),
                        product.familyId() == null ? null : families.get(product.familyId()),
                        names.unitOne(), names.unitOther(), names.piecesPerUnit(),
                        photo.isPresent(), photo.map(SalesPhotoService.Choice::version).orElse(null),
                        counting.salesUnit(), counting.piecesPerSalesUnit(), counting.pieceOne(), counting.pieceOther(),
                        offered, PartnerCountUnits.STUK);
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
