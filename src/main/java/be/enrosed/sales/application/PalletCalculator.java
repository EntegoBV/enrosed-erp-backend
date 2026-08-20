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

    public record Fit(int perLayer, int layers, int cartonsPerPallet,
                      BigDecimal fullPalletHeightCm, String limitedBy) {
        public static Fit none(String reason) {
            return new Fit(0, 0, 0, BigDecimal.ZERO, reason);
        }
    }

    public Fit fit(Carton carton, PalletSpec pallet) {
        if (carton == null || carton.dimensions() == null) return Fit.none("afmetingen ontbreken");
        Dimensions box = carton.dimensions();
        if (isBlank(box.lengthCm()) || isBlank(box.widthCm()) || isBlank(box.heightCm())) {
            return Fit.none("afmetingen ontbreken");
        }

        /* A layer may mix both orientations. For example, 58.5 x 40 cm
           cartons on a 120 x 100 pallet fit as one 40 cm deep row of two
           plus one 58.5 cm deep rotated row of three: five, not four.
           Enumerating guillotine rows in both pallet directions covers that
           common warehouse pattern without pretending this is a generic
           free-form rectangle-packing solver. */
        int perLayer = Math.max(
                bestRows(pallet.lengthCm(), pallet.widthCm(), box.lengthCm(), box.widthCm()),
                bestRows(pallet.widthCm(), pallet.lengthCm(), box.lengthCm(), box.widthCm()));
        if (perLayer <= 0) return Fit.none("doos past niet op de pallet");

        BigDecimal usableHeight = pallet.maxHeightCm().subtract(pallet.baseHeightCm());
        int layersByHeight = Math.max(0, floorDiv(usableHeight, box.heightCm()));

        int heightCapacity = perLayer * layersByHeight;
        BigDecimal weight = Money.nz(carton.weightKg());
        int weightCapacity = weight.signum() > 0
                ? floorDiv(pallet.maxWeightKg(), weight)
                : Integer.MAX_VALUE;
        int cartonsPerPallet = Math.min(heightCapacity, weightCapacity);
        int layers = cartonsPerPallet <= 0 ? 0
                : (cartonsPerPallet + perLayer - 1) / perLayer;
        String limitedBy = cartonsPerPallet == 0
                ? "te hoog of te zwaar"
                : (weightCapacity < heightCapacity ? "gewicht" : "hoogte");
        BigDecimal fullHeight = layers == 0 ? BigDecimal.ZERO
                : pallet.baseHeightCm().add(box.heightCm().multiply(BigDecimal.valueOf(layers)));

        return new Fit(perLayer, layers, cartonsPerPallet, fullHeight, limitedBy);
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

    /**
     * Best layer made from rows that may use either carton orientation.
     * {@code primary} is the direction within one row; {@code secondary}
     * is the direction in which rows are placed next to each other.
     */
    private static int bestRows(BigDecimal primary, BigDecimal secondary,
                                BigDecimal boxLength, BigDecimal boxWidth) {
        int best = 0;
        int straightPerRow = floorDiv(primary, boxLength);
        int rotatedPerRow = floorDiv(primary, boxWidth);
        int maxStraightRows = floorDiv(secondary, boxWidth);

        for (int straightRows = 0; straightRows <= maxStraightRows; straightRows++) {
            BigDecimal used = boxWidth.multiply(BigDecimal.valueOf(straightRows));
            BigDecimal remaining = secondary.subtract(used);
            int rotatedRows = remaining.signum() < 0 ? 0 : floorDiv(remaining, boxLength);
            int count = straightRows * straightPerRow + rotatedRows * rotatedPerRow;
            best = Math.max(best, count);
        }
        return best;
    }

    private static int floorDiv(BigDecimal space, BigDecimal item) {
        if (item == null || item.signum() <= 0) return 0;
        return space.divide(item, 0, RoundingMode.FLOOR).intValue();
    }
}
