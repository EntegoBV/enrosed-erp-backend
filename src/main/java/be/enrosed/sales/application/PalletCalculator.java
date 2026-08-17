package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.sales.domain.PalletSpec;
import be.enrosed.shared.Money;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Rekent uit hoeveel dozen er op een pallet gaan.
 *
 * Bewust een echte stapeling en geen volumedeling: er wordt gekeken hoeveel
 * dozen er per laag op het palletvlak passen - in beide orientaties, de beste
 * wint - en hoeveel lagen er onder de maximale hoogte en het maximale gewicht
 * blijven. Een volumedeling geeft altijd te veel, want dozen laten zich niet
 * vloeibaar over een pallet verdelen.
 */
@ApplicationScoped
public class PalletCalculator {

    public record Fit(int perLayer, int layers, int cartonsPerPallet, String limitedBy) {
        public static Fit none(String reason) {
            return new Fit(0, 0, 0, reason);
        }
    }

    public Fit fit(Carton carton, PalletSpec pallet) {
        if (carton == null || carton.dimensions() == null) return Fit.none("afmetingen ontbreken");
        Dimensions box = carton.dimensions();
        if (isBlank(box.lengthCm()) || isBlank(box.widthCm()) || isBlank(box.heightCm())) {
            return Fit.none("afmetingen ontbreken");
        }

        int straight = floorDiv(pallet.lengthCm(), box.lengthCm()) * floorDiv(pallet.widthCm(), box.widthCm());
        int rotated = floorDiv(pallet.lengthCm(), box.widthCm()) * floorDiv(pallet.widthCm(), box.lengthCm());
        int perLayer = Math.max(straight, rotated);
        if (perLayer <= 0) return Fit.none("doos past niet op de pallet");

        BigDecimal usableHeight = pallet.maxHeightCm().subtract(pallet.baseHeightCm());
        int layersByHeight = Math.max(0, floorDiv(usableHeight, box.heightCm()));

        BigDecimal weight = Money.nz(carton.weightKg());
        int layersByWeight = Integer.MAX_VALUE;
        if (weight.signum() > 0) {
            BigDecimal layerWeight = weight.multiply(BigDecimal.valueOf(perLayer));
            layersByWeight = floorDiv(pallet.maxWeightKg(), layerWeight);
        }

        int layers = Math.min(layersByHeight, layersByWeight);
        String limitedBy = layers == 0
                ? "te hoog of te zwaar"
                : (layersByWeight < layersByHeight ? "gewicht" : "hoogte");

        return new Fit(perLayer, layers, perLayer * layers, limitedBy);
    }

    /** Aantal pallets voor een aantal dozen. */
    public int palletsFor(int cartons, int cartonsPerPallet) {
        if (cartons <= 0 || cartonsPerPallet <= 0) return 0;
        return (cartons + cartonsPerPallet - 1) / cartonsPerPallet;
    }

    public record OrderPallets(int strict, int optimised) {}

    /**
     * Pallets voor een hele order.
     *
     * {@code strict} telt elk product apart; dat is wat je offreert, want het
     * magazijn belooft geen gemengde pallets. {@code optimised} legt de
     * restanten samen en laat zien wat dat zou schelen.
     */
    public OrderPallets forOrder(List<int[]> cartonsAndPerPallet) {
        int strict = 0;
        int fullPallets = 0;
        BigDecimal leftover = BigDecimal.ZERO;

        for (int[] entry : cartonsAndPerPallet) {
            int cartons = entry[0];
            int perPallet = entry[1];
            if (cartons <= 0 || perPallet <= 0) continue;

            strict += palletsFor(cartons, perPallet);
            fullPallets += cartons / perPallet;

            int remainder = cartons % perPallet;
            if (remainder > 0) {
                leftover = leftover.add(Money.divide(BigDecimal.valueOf(remainder), BigDecimal.valueOf(perPallet)));
            }
        }

        int optimised = fullPallets + leftover.setScale(0, RoundingMode.CEILING).intValue();
        return new OrderPallets(strict, Math.min(strict, optimised));
    }

    private static boolean isBlank(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static int floorDiv(BigDecimal space, BigDecimal item) {
        if (item == null || item.signum() <= 0) return 0;
        return space.divide(item, 0, RoundingMode.FLOOR).intValue();
    }
}
