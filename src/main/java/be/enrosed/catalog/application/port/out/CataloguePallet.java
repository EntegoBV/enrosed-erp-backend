package be.enrosed.catalog.application.port.out;

import be.enrosed.catalog.domain.Carton;

import java.math.BigDecimal;

/**
 * How many omdozen go on one pallet and on one Deense kar, for the catalogue's per-pallet and
 * per-kar figures. The stacking rule, the pallet and the kar belong to the sales side
 * (Palletberekening); the catalogue only prints the result, next to its 20ft and 40ft figures.
 *
 * A catalogue PDF can be made for a customer's own loading heights and spill (Emre, 2026-11-26:
 * "sommige klanten hebben andere standaard hoogtes en die willen dan een andere overzicht van
 * ons"): {@link #loads} resolves one export's {@link Choice} once into the {@link Loads} every
 * figure and both basis sentences of that PDF are read from. Readers without a choice (the partner
 * portal) keep the standard through {@link #basis()} and {@link #cartonsPerPallet}.
 */
public interface CataloguePallet {

    /**
     * Breedte × Diepte of the floor as it is printed (the nominal 120 × 80 or 127 × 54,5, never
     * the floor widened by the spill), the total loaded height including the wood or the kar, the
     * weight limit, and the spill the figures were counted with: the centimetres an omdoos may
     * stick out past an edge, 0 for none (on the Deense kar across its long sides only).
     */
    record Basis(BigDecimal widthCm, BigDecimal depthCm, BigDecimal maxHeightCm, BigDecimal maxWeightKg,
                 BigDecimal spillCm) {
        public Basis {
            spillCm = spillCm == null ? BigDecimal.ZERO : spillCm;
        }

        /** The basis sentence names the spill only when there is one. */
        public boolean spills() {
            return spillCm.signum() > 0;
        }
    }

    /**
     * What one export asks for, in centimetres: the loaded height of the europallet and of the
     * Deense kar, and the spill per edge. Null takes the standard (260 / 230 / 2).
     */
    record Choice(BigDecimal palletHeightCm, BigDecimal karHeightCm, BigDecimal spillCm) {
        public static Choice standard() {
            return new Choice(null, null, null);
        }
    }

    /** The pallet and the kar of one export: what its figures count on and its basis sentences print. */
    interface Loads {
        Basis pallet();

        /** The Deense kar's floor (B × D), its loaded height from the floor, its weight limit, the spill. */
        Basis kar();

        /** Whole omdozen per pallet; 0 without a complete carton size or when the carton does not fit. */
        int cartonsPerPallet(Carton carton);

        /** Whole omdozen on one Deense kar, upright on its base plate; 0 as for {@link #cartonsPerPallet}. */
        int cartonsPerKar(Carton carton);
    }

    /**
     * The loads for this choice (null: the standard). A height or spill outside what a document
     * may have is refused with a BusinessRuleException (409) that says the allowed range.
     */
    Loads loads(Choice choice);

    /** The standard pallet, for a reader that has no choice to make. */
    default Basis basis() {
        return loads(Choice.standard()).pallet();
    }

    /** Whole omdozen on the standard pallet; 0 without a complete carton size or when the carton does not fit. */
    default int cartonsPerPallet(Carton carton) {
        return loads(Choice.standard()).cartonsPerPallet(carton);
    }
}
