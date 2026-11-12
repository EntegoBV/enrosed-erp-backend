package be.enrosed.sales.application;

import be.enrosed.sales.domain.PalletSpec;
import be.enrosed.sales.domain.PalletProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;

/**
 * Settings of the sales side.
 *
 * The standard pallet (enrosed.pallet.*) is what a new document is made with, what the
 * catalogue prints per pallet and what the website estimates with: a europallet loaded at
 * most 260 cm high, the pallet itself included (Emre, 2026-11-10). A document keeps the
 * height it was made with: a new one stores the standard, an order may deliberately pick
 * another footprint or height, a row without a stored height was made before 2026-11-07
 * on 260 cm and keeps counting 260, and a document made from 2026-11-07 to 2026-11-10,
 * while the standard was 220 cm, stores that 220.
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

    /**
     * Total loading height, including the pallet base: the standard a new document stores and
     * the catalogue and website estimates count with. 260 cm since 2026-11-10 (Emre: "260 cm
     * standaard"); 220 from 2026-11-07 to 2026-11-10; 260 before.
     */
    @ConfigProperty(name = "enrosed.pallet.max-height-cm", defaultValue = "260")
    BigDecimal palletMaxHeightCm;

    /**
     * The height of a stored document that has none: every such row was made while the standard
     * was 260 cm and was sent, invoiced and paid on 260, so it keeps reading 260.
     */
    public static final BigDecimal LEGACY_MAX_HEIGHT_CM = BigDecimal.valueOf(260);

    /**
     * The standard of 2026-11-07 to 2026-11-10: a document made then stores it without anyone
     * having picked it (PalletHeightPicks tells the two apart).
     */
    public static final BigDecimal FORMER_STANDARD_HEIGHT_CM = BigDecimal.valueOf(220);

    /**
     * Emre, 2026-11-10: a europallet is loaded at most 260 cm high, the pallet itself included.
     * A new pick above it is refused; a higher value a document already stores stays as it is.
     */
    public static final BigDecimal EURO_HEIGHT_LIMIT_CM = BigDecimal.valueOf(260);

    @ConfigProperty(name = "enrosed.pallet.max-weight-kg", defaultValue = "700")
    BigDecimal palletMaxWeightKg;

    public BigDecimal defaultMarkupPct() {
        return defaultMarkupPct;
    }

    /** The height a new document is made with. */
    public BigDecimal standardMaxHeightCm() {
        return palletMaxHeightCm;
    }

    /** The standard europallet: the catalogue's per-pallet figure and every website estimate of a new request or order. */
    public PalletSpec pallet() {
        return pallet(PalletProfile.EURO_120X80, palletMaxHeightCm);
    }

    /**
     * Pallet specification that applies to a document: its own stored height, or 260 cm for a
     * document made before heights were stored (LEGACY_MAX_HEIGHT_CM), never today's standard.
     */
    public PalletSpec pallet(PalletProfile requestedProfile, BigDecimal maxHeightCm) {
        PalletProfile profile = requestedProfile == null
                ? PalletProfile.EURO_120X80 : requestedProfile;
        BigDecimal length = profile == PalletProfile.EURO_120X80
                ? palletLengthCm : profile.lengthCm();
        BigDecimal width = profile == PalletProfile.EURO_120X80
                ? palletWidthCm : profile.widthCm();
        BigDecimal maxHeight = maxHeightCm == null
                ? LEGACY_MAX_HEIGHT_CM : maxHeightCm;
        return new PalletSpec(profile.label(), length, width,
                palletBaseHeightCm, maxHeight, palletMaxWeightKg);
    }
}
