package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.CataloguePallet;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.PalletSpec;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The catalogue's per-pallet figure: the same stacking as Palletberekening, on the standard
 * pallet of the sales settings (a europallet 120 x 80 cm, 260 cm high with the wood, 700 kg,
 * unless configured otherwise), the one a new sales document is made with. The per-kar figure
 * stacks the same way on the Deense kar at its standard 230 cm ({@link SalesSettings#standardKar()}).
 * Both count with the standard spill (2 cm per edge since 2026-11-26, on the kar across its width
 * only), as a new document does; the basis keeps the nominal floor (120 × 80, 127 × 54,5).
 * Both are a pallet of one product in whole upright layers, an omdoos laid down only when it fits
 * no pallet upright: what MIXED counts. A PACKED quote (new documents since 2026-11-25) may load
 * more of one product on a pallet, side by side or on its side, so this figure is the safe one.
 *
 * One catalogue PDF may ask its own heights and spill (Emre, 2026-11-26: "Catalogus pdf zowel
 * korte en lange daar moet ik ook eigenlijk kunnen aanpassen op pallet en deense car de hoogte
 * zodat dan de aantallen kunnen veranderen"; "De spill willen wij ook kunnen aanpassen"). What it
 * leaves out is the standard, and a chosen value stays within what a sales document may store, so
 * a catalogue never promises a load no quote can be made on: the europallet from just above its
 * wood to 260 cm, the kar from just above its base to 300 cm, the spill 0 to 5 cm, whole
 * centimetres each. The specs come from {@link SalesSettings#pallet(PalletProfile, BigDecimal,
 * BigDecimal)}, the builder every document uses, so the kar takes the spill across its width only.
 */
@ApplicationScoped
public class CataloguePalletFit implements CataloguePallet {

    /** The 300 cm no document's height passes (SalesOrderService.validatePalletHeight); the kar's only limit. */
    private static final BigDecimal KAR_HEIGHT_LIMIT_CM = BigDecimal.valueOf(300);

    private final PalletCalculator calculator;
    private final SalesSettings settings;

    public CataloguePalletFit(PalletCalculator calculator, SalesSettings settings) {
        this.calculator = calculator;
        this.settings = settings;
    }

    @Override
    public Loads loads(Choice choice) {
        Choice asked = choice == null ? Choice.standard() : choice;
        PalletSpec pallet = settings.pallet();
        PalletSpec kar = settings.standardKar();
        /* Without a choice these are settings.pallet() and settings.standardKar() themselves. */
        BigDecimal spill = asked.spillCm() == null ? settings.standardSpillCm()
                : wholeCm("Spill", asked.spillCm(), BigDecimal.ZERO, SalesSettings.SPILL_LIMIT_CM);
        BigDecimal palletHeight = asked.palletHeightCm() == null ? pallet.maxHeightCm()
                : wholeCm("Pallethoogte", asked.palletHeightCm(),
                        lowestHeightCm(pallet), SalesSettings.EURO_HEIGHT_LIMIT_CM);
        BigDecimal karHeight = asked.karHeightCm() == null ? kar.maxHeightCm()
                : wholeCm("Hoogte van de Deense kar", asked.karHeightCm(),
                        lowestHeightCm(kar), KAR_HEIGHT_LIMIT_CM);
        return new Fit(calculator,
                settings.pallet(PalletProfile.EURO_120X80, palletHeight, spill),
                settings.pallet(PalletProfile.DEENSE_KAR, karHeight, spill));
    }

    /**
     * A height or spill a client sends: a whole number of centimetres within the range, refused
     * otherwise in the words a document's spill is refused with ("Spill moet een heel aantal
     * centimeter zijn, van 0 tot 5"). Returned without decimals, so 220.0 prints and answers as 220.
     */
    private static BigDecimal wholeCm(String what, BigDecimal sent, BigDecimal lowest, BigDecimal highest) {
        if (sent.compareTo(lowest) < 0 || sent.compareTo(highest) > 0 || sent.stripTrailingZeros().scale() > 0)
            throw new BusinessRuleException(what + " moet een heel aantal centimeter zijn, van "
                    + lowest.toPlainString() + " tot " + highest.toPlainString());
        return sent.setScale(0, RoundingMode.UNNECESSARY);
    }

    /**
     * The lowest height that can be picked, just above the base: 16 cm on a pallet (14,4 cm of
     * wood), 18 on the kar (16,5 cm). The ERP's minHeightCm in pallet-calculator-model.ts.
     */
    private static BigDecimal lowestHeightCm(PalletSpec spec) {
        return spec.baseHeightCm().add(BigDecimal.ONE).setScale(0, RoundingMode.CEILING);
    }

    /** One export's pallet and kar. The basis reports the nominal floor; the fit reads the load floor. */
    private record Fit(PalletCalculator calculator, PalletSpec palletSpec, PalletSpec karSpec) implements Loads {

        @Override
        public Basis pallet() {
            return basis(palletSpec);
        }

        @Override
        public Basis kar() {
            return basis(karSpec);
        }

        @Override
        public int cartonsPerPallet(Carton carton) {
            return calculator.fit(carton, palletSpec).cartonsPerPallet();
        }

        @Override
        public int cartonsPerKar(Carton carton) {
            return calculator.fit(carton, karSpec).cartonsPerPallet();
        }

        private static Basis basis(PalletSpec spec) {
            return new Basis(spec.lengthCm(), spec.widthCm(), spec.maxHeightCm(), spec.maxWeightKg(), spec.spillCm());
        }
    }
}
