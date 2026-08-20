package be.enrosed.sales.application;

import be.enrosed.sales.domain.PalletSpec;
import be.enrosed.sales.domain.PalletProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;

/**
 * Settings of the sales side.
 *
 * Provides operational defaults. An order may deliberately pick another
 * footprint or lower total height, but a legacy order keeps these defaults.
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

    /** Total loading height, including the pallet base. */
    @ConfigProperty(name = "enrosed.pallet.max-height-cm", defaultValue = "260")
    BigDecimal palletMaxHeightCm;

    @ConfigProperty(name = "enrosed.pallet.max-weight-kg", defaultValue = "700")
    BigDecimal palletMaxWeightKg;

    public BigDecimal defaultMarkupPct() {
        return defaultMarkupPct;
    }

    public PalletSpec pallet() {
        return pallet(PalletProfile.EURO_120X80, null);
    }

    /** Pallet specification that actually applies to this order. */
    public PalletSpec pallet(PalletProfile requestedProfile, BigDecimal maxHeightOverrideCm) {
        PalletProfile profile = requestedProfile == null
                ? PalletProfile.EURO_120X80 : requestedProfile;
        BigDecimal length = profile == PalletProfile.EURO_120X80
                ? palletLengthCm : profile.lengthCm();
        BigDecimal width = profile == PalletProfile.EURO_120X80
                ? palletWidthCm : profile.widthCm();
        BigDecimal maxHeight = maxHeightOverrideCm == null
                ? palletMaxHeightCm : maxHeightOverrideCm;
        return new PalletSpec(profile.label(), length, width,
                palletBaseHeightCm, maxHeight, palletMaxWeightKg);
    }
}
