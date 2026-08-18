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
 * Calculates how many cartons fit on a pallet.
 *
 * Deliberately a real stacking, not a volume division: it checks how many
 * cartons fit per layer on the pallet face - both orientations, best wins -
 * and how many layers stay under the maximum height and weight. A volume
 * division always gives too many, because cartons do not spread over a
 * pallet like a liquid.
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

    /** Number of pallets for a number of cartons. */
    public int palletsFor(int cartons, int cartonsPerPallet) {
        if (cartons <= 0 || cartonsPerPallet <= 0) return 0;
        return (cartons + cartonsPerPallet - 1) / cartonsPerPallet;
    }

    public record OrderPallets(int strict, int optimised) {}

    /**
     * Pallets for a whole order.
     *
     * {@code strict} counts each product separately; that is what you quote,
     * because the warehouse promises no mixed pallets. {@code optimised}
     * merges the remainders and shows what that would save.
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
