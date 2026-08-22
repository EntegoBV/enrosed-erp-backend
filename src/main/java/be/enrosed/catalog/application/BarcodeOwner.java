package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Product;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a barcode already lives: which product, and at which level - the
 * piece, the gift box or display, or the outer carton.
 *
 * One code must mean one thing when scanned; a duplicate anywhere in the
 * catalogue is refused, and the refusal names the place so it can be
 * found and fixed.
 */
public record BarcodeOwner(Long productId, String productName, String sku, String level) {

    /** Dutch sentence for a form hint or a save error. */
    public String describe(String barcode) {
        String who = sku == null || sku.isBlank() ? productName : productName + " (" + sku + ")";
        return "Barcode " + barcode + " staat al op " + who + " als " + level;
    }

    /** Every code a product carries, with the level it sits at. */
    public static List<Carried> carriedBy(Product product) {
        List<Carried> codes = new ArrayList<>();
        add(codes, product.barcodes() == null ? null : product.barcodes().inner(), "stukbarcode");
        add(codes, product.canonicalBarcode(), "stukbarcode");
        add(codes, product.packaging().barcode(),
                "barcode op de " + product.packaging().kind().dutchLabel().toLowerCase());
        add(codes, product.barcodes() == null ? null : product.barcodes().outer(), "omdoosbarcode");
        return codes;
    }

    private static void add(List<Carried> codes, String value, String level) {
        String code = normalize(value);
        if (code != null && codes.stream().noneMatch(item -> item.code().equals(code))) {
            codes.add(new Carried(code, level));
        }
    }

    public static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Looks the code up across the catalogue, skipping the product being edited. */
    public static BarcodeOwner find(String barcode, List<Product> catalogue, Long excludeProductId) {
        String code = normalize(barcode);
        if (code == null) return null;
        for (Product other : catalogue) {
            if (excludeProductId != null && Objects.equals(other.id(), excludeProductId)) continue;
            for (Carried carried : carriedBy(other)) {
                if (carried.code().equals(code)) {
                    return new BarcodeOwner(other.id(), other.name(), other.sku(), carried.level());
                }
            }
        }
        return null;
    }

    public record Carried(String code, String level) {}
}
