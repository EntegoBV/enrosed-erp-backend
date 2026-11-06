package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.CataloguePallet;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.sales.domain.PalletSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The catalogue's per-pallet figure: the same stacking as Palletberekening, on the default
 * pallet of the sales settings (a europallet unless configured otherwise).
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
}
