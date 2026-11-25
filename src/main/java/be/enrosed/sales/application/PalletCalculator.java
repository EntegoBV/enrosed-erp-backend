package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.sales.domain.PalletCountRule;
import be.enrosed.sales.domain.PalletSpec;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.shared.Money;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calculates how many cartons fit on a pallet.
 *
 * Deliberately a real stacking, not a volume division: it checks how many
 * cartons fit per layer on the pallet face - both orientations, best wins -
 * and how many layers stay under the maximum height and weight. A volume
 * division always gives too many, because cartons do not spread over a
 * pallet like a liquid.
 *
 * A carton stands upright. Only a carton that fits no pallet upright (too
 * wide or too high) is laid down: on its long side or on its end, whichever
 * takes the most cartons, the lower load on a tie (Emre, 2026-11-20, about
 * 81 x 81 cm half hearts on a Euro pallet: tip "alleen als het moet").
 * Palletberekening's palletFit counts the same way.
 */
@ApplicationScoped
public class PalletCalculator {

    /**
     * {@code layerHeightCm}: the carton's size that stands up, one layer's height; null when nothing fits.
     * {@code up}: which size stands up, as Palletberekening writes it: "h" upright, "w" on its long side
     * (the width stands), "l" on its end (the length stands).
     */
    public record Fit(int perLayer, int layers, int cartonsPerPallet,
                      BigDecimal fullPalletHeightCm, String limitedBy, BigDecimal layerHeightCm, String up) {
        /** An upright fit as it was counted before cartons could be laid down. */
        public Fit(int perLayer, int layers, int cartonsPerPallet, BigDecimal fullPalletHeightCm, String limitedBy) {
            this(perLayer, layers, cartonsPerPallet, fullPalletHeightCm, limitedBy, null, "h");
        }

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
        Fit upright = standing(box.lengthCm(), box.widthCm(), box.heightCm(), "h", carton, pallet);
        if (upright.cartonsPerPallet() > 0) return upright;
        /* Laid down only because upright fits no pallet; the weight is the same on every side. */
        Fit best = upright;
        for (Fit laid : List.of(standing(box.lengthCm(), box.heightCm(), box.widthCm(), "w", carton, pallet),
                standing(box.heightCm(), box.widthCm(), box.lengthCm(), "l", carton, pallet))) {
            boolean more = laid.cartonsPerPallet() > best.cartonsPerPallet()
                    || (laid.cartonsPerPallet() == best.cartonsPerPallet() && laid.cartonsPerPallet() > 0
                        && laid.fullPalletHeightCm().compareTo(best.fullPalletHeightCm()) < 0);
            if (more) best = laid;
        }
        return best;
    }

    /** The fit of the carton standing on one side: {@code length} x {@code width} on the pallet, {@code height} up. */
    private static Fit standing(BigDecimal length, BigDecimal width, BigDecimal height, String up,
                                Carton carton, PalletSpec pallet) {
        /* A layer may mix both orientations. For example, 58.5 x 40 cm
           cartons on a 120 x 100 pallet fit as one 40 cm deep row of two
           plus one 58.5 cm deep rotated row of three: five, not four.
           Enumerating guillotine rows in both pallet directions covers that
           common warehouse pattern without pretending this is a generic
           free-form rectangle-packing solver. */
        int perLayer = Math.max(
                bestRows(pallet.lengthCm(), pallet.widthCm(), length, width),
                bestRows(pallet.widthCm(), pallet.lengthCm(), length, width));
        if (perLayer <= 0) return Fit.none("doos past niet op de pallet");

        BigDecimal usableHeight = pallet.maxHeightCm().subtract(pallet.baseHeightCm());
        int layersByHeight = Math.max(0, floorDiv(usableHeight, height));

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
                : pallet.baseHeightCm().add(height.multiply(BigDecimal.valueOf(layers)));

        return new Fit(perLayer, layers, cartonsPerPallet, fullHeight, limitedBy,
                cartonsPerPallet > 0 ? height : null, up);
    }

    /** Number of pallets for a number of cartons. */
    public int palletsFor(int cartons, int cartonsPerPallet) {
        if (cartons <= 0 || cartonsPerPallet <= 0) return 0;
        return (cartons + cartonsPerPallet - 1) / cartonsPerPallet;
    }

    /**
     * One palletised order line as the stacking sees it: its cartons, their fit, one carton's height and weight, and
     * the carton's own length, width and height (PACKED lays it on any side); null box sizes leave it to MIXED.
     */
    public record Load(long productId, int cartons, Fit fit, BigDecimal cartonHeightCm, BigDecimal cartonWeightKg, Dimensions box) {
        /** A load without the carton's own sizes, as it was before PACKED. */
        public Load(long productId, int cartons, Fit fit, BigDecimal cartonHeightCm, BigDecimal cartonWeightKg) {
            this(productId, cartons, fit, cartonHeightCm, cartonWeightKg, null);
        }

        /** Cartons that fit a pallet; a line without a fit drops out of every count. */
        boolean placeable() {
            return cartons > 0 && fit != null && fit.cartonsPerPallet() > 0 && fit.perLayer() > 0
                    && cartonHeightCm != null && cartonHeightCm.signum() > 0;
        }

        /** Zero when the carton has no weight: the height alone decides. */
        BigDecimal weightKg() {
            return Money.nz(cartonWeightKg).max(BigDecimal.ZERO);
        }
    }

    /**
     * Pallets for a whole order: {@code strict} with every product on pallets of its own,
     * {@code optimised} with the remainders merged as fractions (an estimate only, never billed),
     * and the automatic layout under the document's rule, whose size is the count freight bills.
     */
    public record OrderPallets(int strict, int optimised, List<PricedOrder.AutoPallet> layout) {
        public static final OrderPallets NONE = new OrderPallets(0, 0, List.of());

        public int automatic() {
            return layout.size();
        }
    }

    public OrderPallets forOrder(List<Load> loads, PalletSpec pallet, PalletCountRule rule) {
        int strict = 0;
        int fullPallets = 0;
        BigDecimal leftover = BigDecimal.ZERO;

        for (Load load : loads) {
            int cartons = load.cartons();
            int perPallet = load.fit() == null ? 0 : load.fit().cartonsPerPallet();
            if (cartons <= 0 || perPallet <= 0) continue;

            strict += palletsFor(cartons, perPallet);
            fullPallets += cartons / perPallet;

            int remainder = cartons % perPallet;
            if (remainder > 0) {
                leftover = leftover.add(Money.divide(BigDecimal.valueOf(remainder), BigDecimal.valueOf(perPallet)));
            }
        }

        int optimised = fullPallets + leftover.setScale(0, RoundingMode.CEILING).intValue();
        List<PricedOrder.AutoPallet> layout = switch (rule) {
            case PACKED -> packedPallets(loads, pallet);
            case MIXED -> mixedPallets(loads, pallet);
            default -> separatePallets(loads, pallet);
        };
        return new OrderPallets(strict, Math.min(strict, optimised), layout);
    }

    /**
     * Every product on pallets of its own, in line order: a product's full pallets, then its rest.
     * The layout "Zelf indelen" started from until 2026-11-04; its size is the strict count. Also
     * what the mixed plan gives way to when mixing would need more pallets.
     */
    List<PricedOrder.AutoPallet> separatePallets(List<Load> loads, PalletSpec pallet) {
        List<PricedOrder.AutoPallet> layout = new ArrayList<>();
        for (Load load : loads) {
            if (!load.placeable()) continue;
            int perPallet = load.fit().cartonsPerPallet();
            for (int left = load.cartons(); left > 0; left -= perPallet) {
                int cartons = Math.min(perPallet, left);
                int layers = (cartons + load.fit().perLayer() - 1) / load.fit().perLayer();
                layout.add(autoPallet(List.of(new PricedOrder.AutoPallet.Item(load.productId(), cartons, layers)),
                        pallet.baseHeightCm().add(load.cartonHeightCm().multiply(BigDecimal.valueOf(layers))),
                        load.weightKg().multiply(BigDecimal.valueOf(cartons))));
            }
        }
        return List.copyOf(layout);
    }

    /**
     * Products sharing pallets, as Palletberekening stacks them (planPallets in the frontend's
     * pallet-calculator-model.ts, step for step): every product in whole layers of its own, a
     * short one as high as a full one. The products go tallest carton first, ties in line order.
     * Each lays a layer of what it still has to place, at most a full one, on the first pallet
     * where one more layer stays under the maximum height and the weight still takes a carton;
     * there the weight may cut the layer short, and the cartons it leaves go into the product's
     * next layer instead of making a short one of their own. One product alone thus gets exactly
     * its cartonsPerPallet per pallet (238 cartons of 40 x 40 x 50 cm at 30.7 kg on a Euro pallet
     * under 260 cm: 11 pallets, as strict; the weight decides). An empty pallet always takes the layer, or the part
     * the weight allows: a fit means one layer goes under the height and one carton under the
     * weight. A plan that needs more pallets than every product on pallets of its own gives way
     * to that separate layout, so the mixed count never exceeds the strict one.
     */
    List<PricedOrder.AutoPallet> mixedPallets(List<Load> loads, PalletSpec pallet) {
        BigDecimal usable = pallet.maxHeightCm().subtract(pallet.baseHeightCm());
        record Line(Load load, int order) {}
        List<Line> lines = new ArrayList<>();
        int separate = 0;
        for (Load load : loads) {
            if (!load.placeable()) continue;
            lines.add(new Line(load, lines.size()));
            separate += palletsFor(load.cartons(), load.fit().cartonsPerPallet());
        }
        lines.sort(Comparator.comparing((Line line) -> line.load().cartonHeightCm(), Comparator.reverseOrder())
                .thenComparingInt(Line::order));

        List<Stack> stacks = new ArrayList<>();
        for (Line line : lines) {
            Load load = line.load();
            int rest = load.cartons();
            while (rest > 0) {
                int layer = Math.min(load.fit().perLayer(), rest);
                Stack target = null;
                int fits = 0;
                for (Stack stack : stacks) {
                    if (stack.heightCm.add(load.cartonHeightCm()).compareTo(usable) > 0) continue;
                    fits = Math.min(layer, weightRoom(stack.weightKg, load, pallet));
                    if (fits > 0) {
                        target = stack;
                        break;
                    }
                }
                if (target == null) {
                    target = new Stack();
                    stacks.add(target);
                    fits = Math.min(layer, weightRoom(BigDecimal.ZERO, load, pallet));
                }
                target.place(load, fits);
                rest -= fits;
            }
        }
        if (stacks.size() > separate) return separatePallets(loads, pallet);
        return stacks.stream()
                .map(stack -> autoPallet(stack.parts.stream().map(Part::item).toList(),
                        pallet.baseHeightCm().add(stack.heightCm), stack.weightKg))
                .toList();
    }

    /**
     * The pallets loaded one after the other, each as full as it goes (PalletPacker; the ERP's
     * orderLoad3d lays out the same pallets in 3D, keep the two alike): the order's products in line
     * order, a product on several lines once, the colours of one carton size one kind (the heaviest
     * colour's weight), every side allowed. Where that would take more pallets than MIXED, or leave
     * cartons over, the mixed layout stands: PACKED never counts more than MIXED. A pallet's items
     * come in the order their first carton was laid; an item's layers are the heights its cartons
     * start at; a pallet's height is the top of its load on the wood, its weight its cartons' own.
     */
    List<PricedOrder.AutoPallet> packedPallets(List<Load> loads, PalletSpec pallet) {
        List<PricedOrder.AutoPallet> mixed = mixedPallets(loads, pallet);
        java.util.LinkedHashMap<Long, Load> byProduct = new java.util.LinkedHashMap<>();
        for (Load load : loads) {
            if (!load.placeable()) continue;
            if (load.box() == null || isBlank(load.box().lengthCm()) || isBlank(load.box().widthCm()) || isBlank(load.box().heightCm())) {
                return mixed;
            }
            byProduct.merge(load.productId(), load, (first, more) -> new Load(first.productId(), first.cartons() + more.cartons(),
                    first.fit(), first.cartonHeightCm(), first.cartonWeightKg(), first.box()));
        }
        if (byProduct.isEmpty()) return mixed;
        /* One carton size is one kind; its products keep the line order and hand out the cartons in turn. */
        java.util.LinkedHashMap<String, int[]> kindOf = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, List<long[]>> members = new java.util.HashMap<>();
        List<PalletPacker.Kind> kinds = new ArrayList<>();
        for (Load load : byProduct.values()) {
            int length = PalletPacker.units(load.box().lengthCm());
            int width = PalletPacker.units(load.box().widthCm());
            int height = PalletPacker.units(load.box().heightCm());
            int weight = PalletPacker.units(load.weightKg());
            String size = length + "|" + width + "|" + height;
            int[] kind = kindOf.get(size);
            if (kind == null) {
                kind = new int[] {kindOf.size() + 1, length, width, height, weight, 0};
                kindOf.put(size, kind);
                members.put(kind[0], new ArrayList<>());
            }
            kind[4] = Math.max(kind[4], weight);
            kind[5] += load.cartons();
            members.get(kind[0]).add(new long[] {load.productId(), load.cartons()});
        }
        for (int[] kind : kindOf.values()) {
            kinds.add(new PalletPacker.Kind(kind[0], kind[1], kind[2], kind[3], kind[4], kind[5], PalletPacker.ALL_SIDES));
        }
        PalletPacker.Result plan = PalletPacker.pack(kinds, PalletPacker.deck(pallet), mixed.size());
        if (!plan.left().isEmpty() || plan.pallets().isEmpty() || plan.pallets().size() > mixed.size()) return mixed;
        List<PricedOrder.AutoPallet> layout = new ArrayList<>();
        for (PalletPacker.Pallet packed : plan.pallets()) {
            java.util.LinkedHashMap<Long, Integer> cartons = new java.util.LinkedHashMap<>();
            java.util.Map<Long, java.util.Set<Integer>> starts = new java.util.HashMap<>();
            for (PalletPacker.Box box : packed.boxes()) {
                long[] member = null;
                for (long[] candidate : members.get(box.key())) {
                    if (candidate[1] > 0) {
                        member = candidate;
                        break;
                    }
                }
                if (member == null) continue;
                member[1] -= 1;
                cartons.merge(member[0], 1, Integer::sum);
                starts.computeIfAbsent(member[0], id -> new java.util.HashSet<>()).add(box.y());
            }
            BigDecimal weight = BigDecimal.ZERO;
            List<PricedOrder.AutoPallet.Item> items = new ArrayList<>();
            for (var entry : cartons.entrySet()) {
                items.add(new PricedOrder.AutoPallet.Item(entry.getKey(), entry.getValue(), starts.get(entry.getKey()).size()));
                weight = weight.add(byProduct.get(entry.getKey()).weightKg().multiply(BigDecimal.valueOf(entry.getValue())));
            }
            layout.add(autoPallet(items, pallet.baseHeightCm().add(BigDecimal.valueOf(packed.top(), 2)), weight));
        }
        return List.copyOf(layout);
    }

    /** Cartons of a line the weight still allows on a pallet; without a carton weight, any number. */
    private static int weightRoom(BigDecimal loadedKg, Load load, PalletSpec pallet) {
        return load.weightKg().signum() > 0
                ? Math.max(0, floorDiv(pallet.maxWeightKg().subtract(loadedKg), load.weightKg()))
                : Integer.MAX_VALUE;
    }

    /** One pallet while the layout is made: its height above the base and its weight, unrounded. */
    private static final class Stack {
        private final List<Part> parts = new ArrayList<>();
        private BigDecimal heightCm = BigDecimal.ZERO;
        private BigDecimal weightKg = BigDecimal.ZERO;

        /** A layer, or the part of one the weight allows: always as high as a full layer. */
        void place(Load load, int cartons) {
            Part part = parts.stream().filter(candidate -> candidate.productId == load.productId())
                    .findFirst().orElse(null);
            if (part == null) {
                part = new Part(load.productId());
                parts.add(part);
            }
            part.cartons += cartons;
            part.layers += 1;
            heightCm = heightCm.add(load.cartonHeightCm());
            weightKg = weightKg.add(load.weightKg().multiply(BigDecimal.valueOf(cartons)));
        }
    }

    private static final class Part {
        private final long productId;
        private int cartons;
        private int layers;

        Part(long productId) {
            this.productId = productId;
        }

        PricedOrder.AutoPallet.Item item() {
            return new PricedOrder.AutoPallet.Item(productId, cartons, layers);
        }
    }

    /** Heights and weights as the screens write them, to one decimal. */
    private static PricedOrder.AutoPallet autoPallet(List<PricedOrder.AutoPallet.Item> items,
                                                     BigDecimal heightCm, BigDecimal weightKg) {
        return new PricedOrder.AutoPallet(items, heightCm.setScale(1, RoundingMode.HALF_UP),
                weightKg.setScale(1, RoundingMode.HALF_UP));
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
