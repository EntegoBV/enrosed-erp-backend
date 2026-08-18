package be.enrosed.sales.application;

import be.enrosed.sales.domain.PalletSpec;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;

/**
 * Settings of the sales side.
 *
 * Kept out of the database because this is configuration, not data: the
 * pallet size does not change per order.
 */
@ApplicationScoped
public class SalesSettings {

    @ConfigProperty(name = "enrosed.sales.default-markup-pct", defaultValue = "45")
    BigDecimal defaultMarkupPct;

    @ConfigProperty(name = "enrosed.pallet.length-cm", defaultValue = "120")
    BigDecimal palletLengthCm;

    @ConfigProperty(name = "enrosed.pallet.width-cm", defaultValue = "80")
    BigDecimal palletWidthCm;

    @ConfigProperty(name = "enrosed.pallet.base-height-cm", defaultValue = "14.4")
    BigDecimal palletBaseHeightCm;

    @ConfigProperty(name = "enrosed.pallet.max-height-cm", defaultValue = "180")
    BigDecimal palletMaxHeightCm;

    @ConfigProperty(name = "enrosed.pallet.max-weight-kg", defaultValue = "700")
    BigDecimal palletMaxWeightKg;

    public BigDecimal defaultMarkupPct() {
        return defaultMarkupPct;
    }

    public PalletSpec pallet() {
        return new PalletSpec("Euro-pallet", palletLengthCm, palletWidthCm,
                palletBaseHeightCm, palletMaxHeightCm, palletMaxWeightKg);
    }
}
