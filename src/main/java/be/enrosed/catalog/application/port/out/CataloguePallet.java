package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Carton;

import java.math.BigDecimal;

/**
 * How many omdozen go on one pallet and on one Deense kar, for the catalogue's per-pallet and
 * per-kar figures. The stacking rule, the pallet and the kar belong to the sales side
 * (Palletberekening); the catalogue only prints the result, next to its 20ft and 40ft figures.
 */
public interface CataloguePallet {

    /** Breedte × Diepte of the pallet, the total loaded height including the wood, the weight limit. */
    record Basis(BigDecimal widthCm, BigDecimal depthCm, BigDecimal maxHeightCm, BigDecimal maxWeightKg) {}

    Basis basis();

    /** Whole omdozen per pallet; 0 without a complete carton size or when the carton does not fit. */
    int cartonsPerPallet(Carton carton);

    /** The Deense kar's floor (B × D), its loaded height from the floor, its weight limit. */
    Basis karBasis();

    /** Whole omdozen on one Deense kar, upright on its base plate; 0 as for {@link #cartonsPerPallet}. */
    int cartonsPerKar(Carton carton);
}
