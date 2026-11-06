package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Carton;

import java.math.BigDecimal;

/**
 * How many omdozen go on one pallet, for the catalogue's per-pallet figure. The stacking rule and
 * the pallet belong to the sales side (Palletberekening); the catalogue only prints the result,
 * next to its 20ft and 40ft figures.
 */
public interface CataloguePallet {

    /** Breedte × Diepte of the pallet, the total loaded height including the wood, the weight limit. */
    record Basis(BigDecimal widthCm, BigDecimal depthCm, BigDecimal maxHeightCm, BigDecimal maxWeightKg) {}

    Basis basis();

    /** Whole omdozen per pallet; 0 without a complete carton size or when the carton does not fit. */
    int cartonsPerPallet(Carton carton);
}
