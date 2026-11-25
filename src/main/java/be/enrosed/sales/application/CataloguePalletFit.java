package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.CataloguePallet;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.sales.domain.PalletSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The catalogue's per-pallet figure: the same stacking as Palletberekening, on the standard
 * pallet of the sales settings (a europallet 120 x 80 cm, 260 cm high with the wood, 700 kg,
 * unless configured otherwise), the one a new sales document is made with. The per-kar figure
 * stacks the same way on the Deense kar at its standard 230 cm ({@link PalletSpec#deenseKar()}).
 * Both are a pallet of one product in whole upright layers, an omdoos laid down only when it fits
 * no pallet upright: what MIXED counts. A PACKED quote (new documents since 2026-11-25) may load
 * more of one product on a pallet, side by side or on its side, so this figure is the safe one.
 */
@ApplicationScoped
public class CataloguePalletFit implements CataloguePallet {

    private final PalletCalculator calculator;
    private final SalesSettings settings;

    public CataloguePalletFit(PalletCalculator calculator, SalesSettings settings) {
        this.calculator = calculator;
        this.settings = settings;
    }

    @Override
    public Basis basis() {
        PalletSpec pallet = settings.pallet();
        return new Basis(pallet.lengthCm(), pallet.widthCm(), pallet.maxHeightCm(), pallet.maxWeightKg());
    }

    @Override
    public int cartonsPerPallet(Carton carton) {
        return calculator.fit(carton, settings.pallet()).cartonsPerPallet();
    }

    @Override
    public Basis karBasis() {
        PalletSpec kar = PalletSpec.deenseKar();
        return new Basis(kar.lengthCm(), kar.widthCm(), kar.maxHeightCm(), kar.maxWeightKg());
    }

    @Override
    public int cartonsPerKar(Carton carton) {
        return calculator.fit(carton, PalletSpec.deenseKar()).cartonsPerPallet();
    }
}
