package be.enrosed.inventory.application;

import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountLineEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Boundary adapters: inventory works in physical pieces, the stock book retains complete selling units plus loose pieces. */
public final class InventoryUnits {
    public static final String BASE_PIECE = "BASE_PIECE";
    private InventoryUnits() {}

    public static int factor(Product product) {
        return product == null ? 1 : factor(product.packaging().salesUnit().name(), product.packaging().unitPieces());
    }

    public static int factor(String salesUnit, Integer pieces) {
        if (!"DISPLAY".equals(salesUnit)) return 1;
        if (pieces == null || pieces < 1) throw new InventoryRefusal("EENHEID_ONTBREEKT",
                "De vastgelegde verpakkingseenheid ontbreekt; controleer de inventarisbron");
        return pieces;
    }

    public static int countFactor(StockCountLineEntity line) {
        return BASE_PIECE.equals(line.quantityBasis) ? 1 : factor(line.salesUnit, line.piecesPerUnit);
    }

    public static Integer quantity(Integer quantity, int factor) {
        return quantity == null ? null : Math.multiplyExact(quantity, factor);
    }

    public static int physical(int complete, int loose, int factor) {
        return Math.addExact(Math.multiplyExact(complete, factor), loose);
    }

    public static BigDecimal unitCost(BigDecimal value, int factor) {
        return value == null || factor == 1 ? value : value.divide(BigDecimal.valueOf(factor), 12, RoundingMode.HALF_UP);
    }

    /** An adapter record only: the stored ledger and its timestamps/source identifiers are unchanged. */
    public static StockMovement movement(StockMovement source, int legacyFactor) {
        int factor = source.unitPieces() == null ? legacyFactor : source.unitPieces();
        if (factor < 1) throw new InventoryRefusal("EENHEID_ONTBREEKT", "De verpakkingseenheid van een voorraadbeweging ontbreekt");
        return new StockMovement(source.id(), source.productId(), source.locationId(), source.at(),
                physical(source.delta(), source.looseDelta(), factor),
                physical(source.quantityAfter(), source.loosePiecesAfter(), factor), source.kind(), source.reference(),
                source.actor(), source.purchaseOrderId(), source.ticaSaleId(), source.ticaRevisionId(), source.sourceSoldAt(),
                0, 0, 1, source.sourceQuantity(), source.sourceUnit());
    }

    /** A detached copy prevents conversion of a carried layer from altering the previous FINAL snapshot. */
    public static StockClosingLayerEntity layer(StockClosingLayerEntity source, int factor) {
        StockClosingLayerEntity row = copy(source);
        row.capacity = quantity(row.capacity, factor);
        row.quantity = quantity(row.quantity, factor);
        row.writeDownQuantity = quantity(row.writeDownQuantity, factor);
        row.unitValueEur = unitCost(row.unitValueEur, factor);
        row.unitGoodsEur = unitCost(row.unitGoodsEur, factor);
        row.unitTransportEur = unitCost(row.unitTransportEur, factor);
        row.unitLogisticsEur = unitCost(row.unitLogisticsEur, factor);
        row.unitSeparateEur = unitCost(row.unitSeparateEur, factor);
        row.unitEstimatedEur = unitCost(row.unitEstimatedEur, factor);
        return row;
    }

    /** These inventory entities contain scalar snapshot fields only, with no associations or mutable collections. */
    static <T> T copy(T source) {
        try {
            @SuppressWarnings("unchecked") T copy = (T) source.getClass().getConstructor().newInstance();
            for (Field field : source.getClass().getDeclaredFields()) {
                if (field.getAnnotation(jakarta.persistence.Column.class) != null && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    field.set(copy, field.get(source));
                }
            }
            return copy;
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("Inventory snapshot could not be copied", impossible);
        }
    }
}
