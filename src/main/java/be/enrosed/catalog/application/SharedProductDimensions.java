package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.Dimensions;

import java.math.BigDecimal;
import java.util.List;

/** A shared size exists only when every public ERP variant has the same complete product dimensions. */
public final class SharedProductDimensions {
    private SharedProductDimensions() {}

    public static Dimensions resolve(List<ProductEntity> members) {
        List<ProductEntity> variants = members.stream()
                .filter(product -> product.active && !product.demo).toList();
        if (variants.isEmpty()) return null;
        ProductEntity first = variants.getFirst();
        if (!positive(first.productLengthCm) || !positive(first.productWidthCm)
                || !positive(first.productHeightCm)) return null;
        boolean shared = variants.stream().allMatch(product ->
                sameMeasurement(first.productLengthCm, product.productLengthCm)
                        && sameMeasurement(first.productWidthCm, product.productWidthCm)
                        && sameMeasurement(first.productHeightCm, product.productHeightCm));
        return shared ? new Dimensions(
                first.productLengthCm, first.productWidthCm, first.productHeightCm) : null;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean sameMeasurement(BigDecimal left, BigDecimal right) {
        return right != null && left.compareTo(right) == 0;
    }
}
