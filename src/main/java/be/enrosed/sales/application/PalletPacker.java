package be.enrosed.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zo vol mogelijk laden (Emre, 2026-11-25: "bij een order van 3 pallets eerst de eerste proberen
 * helemaal te vullen en je mag kantelen"): the omdozen go pallet by pallet, each pallet filled as
 * full as it gets from everything still to load before the next one starts, so the last pallet
 * takes what is left.
 *
 * This is the ERP's pallet-pack.ts step for step, keep the two identical: the 3D view lays out
 * the pallets this class counts, and only identical arithmetic gives the same pallets. Every size
 * is in hundredths of a cm and every weight in hundredths of a kg, whole numbers only.
 *
 * How a pallet fills: its floor is a set of free rectangles, each carried in full by the deck or by
 * the flat top of omdozen under it. The lowest one is filled first with a block: omdozen of one
 * kind on one side, in rows along the length, rows across, and layers up, as many as fit and are
 * left. Its top becomes a new floor, the rest of the rectangle two smaller ones; tops at one height
 * that meet edge to edge join. Of the blocks that fit, the one that leaves the fullest pallet in
 * the end wins: every candidate (and its lower twin, only as high as the floor's best pattern of
 * rows needs) is tried by filling the rest of the pallet the quick way (the largest block each
 * time); a try that takes everything still to load beats any that leaves cartons for another pallet;
 * where they load the same, the one that ends lowest, so a part pallet stands low and wide. An omdoos
 * lies down only where that fills clearly more (TIP_COST). Nothing hangs over an edge and nothing
 * floats.
 *
 * FROZEN since 2026-11-25: PACKED documents are priced live with these exact steps, so any change
 * re-counts every sent and paid PACKED document (pallets, per-pallet freight, PDFs). A change in
 * behaviour needs a new PalletCountRule value (PACKED_2) for new documents only, with its own copy
 * of this class and of pallet-pack.ts.
 */
public final class PalletPacker {

    private PalletPacker() {
    }

    /** Candidates tried in full per decision. */
    private static final int PILOT = 8;

    /** An omdoos on its side counts this many hundredths of its volume less when the tries are compared. */
    private static final long TIP_COST = 5;

    /** The sides in the order they are tried: upright, on its long side, on its end. */
    public static final List<String> ALL_SIDES = List.of("h", "w", "l");

    /** One kind of omdoos: the same size and weight; sizes and weight in hundredths. */
    public record Kind(int key, int length, int width, int height, int weight, int count, List<String> sides) {
    }

    /** The floor and the room above it, in hundredths; maxWeight 0: no weight limit. */
    public record Deck(int length, int width, int height, int maxWeight) {
    }

    public record Box(int key, int x, int y, int z, String up, boolean turned) {
    }

    /** One pallet: its boxes in the order they were laid, its weight and the top of its load. */
    public record Pallet(List<Box> boxes, Map<Integer, Integer> counts, long weight, int top) {
    }

    /** The pallets, and per kind the omdozen that went on none. */
    public record Result(List<Pallet> pallets, Map<Integer, Integer> left) {
    }

    private record Shape(String up, boolean turned, int sx, int sy, int sz, int rank) {
    }

    private record Prepared(int key, long weight, long volume, List<Shape> shapes) {
    }

    private record Floor(int x, int z, int dx, int dz, int y, boolean parked) {
    }

    private record Block(int kind, Shape shape, int nx, int ny, int nz, int count, long volume, Block lower) {
        Block withLower(Block twin) {
            return new Block(kind, shape, nx, ny, nz, count, volume, twin);
        }
    }

    private static final class State {
        List<Floor> floors;
        long weight;
        int[] left;
        long volume;
        long tipped;
        int top;

        State copy() {
            State copy = new State();
            copy.floors = new ArrayList<>(floors);
            copy.weight = weight;
            copy.left = left.clone();
            copy.volume = volume;
            copy.tipped = tipped;
            copy.top = top;
            return copy;
        }
    }

    /** A size or weight in hundredths, rounded half up as the ERP's Math.round does for positive values. */
    public static int units(BigDecimal value) {
        if (value == null || value.signum() <= 0) return 0;
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** The deck of a pallet spec: its floor, the height above the wood, the weight limit. */
    public static Deck deck(be.enrosed.sales.domain.PalletSpec spec) {
        return new Deck(units(spec.lengthCm()), units(spec.widthCm()),
                Math.max(0, units(spec.maxHeightCm()) - units(spec.baseHeightCm())), Math.max(0, units(spec.maxWeightKg())));
    }

    private static long worth(State state) {
        return state.volume * 100 - state.tipped * TIP_COST;
    }

    /** The sides an omdoos stands on, each also a quarter turned on the floor, without repeats: upright first. */
    private static List<Shape> shapesOf(Kind kind, Deck deck) {
        List<Shape> shapes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rank = 0;
        for (String up : ALL_SIDES) {
            if (!kind.sides().contains(up)) continue;
            /* The footprint and the standing size: upright, on its long side (the width stands), on its end. */
            int length = up.equals("l") ? kind.height() : kind.length();
            int width = up.equals("w") ? kind.height() : kind.width();
            int height = up.equals("w") ? kind.width() : up.equals("l") ? kind.length() : kind.height();
            for (boolean turned : new boolean[] {false, true}) {
                int sx = turned ? width : length;
                int sz = turned ? length : width;
                String id = sx + "|" + height + "|" + sz;
                if (!seen.add(id)) continue;
                if (sx <= deck.length() && sz <= deck.width() && height <= deck.height()) {
                    shapes.add(new Shape(up, turned, sx, height, sz, rank));
                }
                rank += 1;
            }
        }
        return shapes;
    }

    private static boolean placeable(Kind kind) {
        return kind.count() > 0 && kind.length() > 0 && kind.width() > 0 && kind.height() > 0;
    }

    /** The largest block of one kind on one side for a floor: full layers of rows, or one short layer. */
    private static Block blockFor(Floor floor, int index, Prepared kind, Shape shape, int most, Deck deck) {
        if (shape.sx() > floor.dx() || shape.sz() > floor.dz() || floor.y() + shape.sy() > deck.height()) return null;
        int ax = floor.dx() / shape.sx();
        int az = floor.dz() / shape.sz();
        int ay = (deck.height() - floor.y()) / shape.sy();
        int layer = ax * az;
        if (most >= layer) {
            int ny = Math.min(ay, most / layer);
            int count = layer * ny;
            return new Block(index, shape, ax, ny, az, count, count * kind.volume(), null);
        }
        /* Less than a layer: the fullest rectangle, the longest rows first. */
        int nx = 0;
        int nz = 0;
        for (int rows = 1; rows <= az; rows++) {
            int across = Math.min(ax, most / rows);
            if (across <= 0) break;
            if (across * rows > nx * nz) {
                nx = across;
                nz = rows;
            }
        }
        if (nx == 0) return null;
        int count = nx * nz;
        return new Block(index, shape, nx, 1, nz, count, count * kind.volume(), null);
    }

    /** Rows of one footprint across a rectangle, either turn per row (PalletCalculator.bestRows): primary within a row. */
    private static int bestRows(int primary, int secondary, int length, int width) {
        int best = 0;
        int straight = primary / length;
        int turned = primary / width;
        for (int rows = 0, most = secondary / width; rows <= most; rows++) {
            int across = (secondary - rows * width) / length;
            best = Math.max(best, rows * straight + across * turned);
        }
        return best;
    }

    /** How many of this footprint the floor takes in its best pattern of rows: what one layer of it holds. */
    private static int floorPattern(Floor floor, Shape shape) {
        return Math.max(bestRows(floor.dx(), floor.dz(), shape.sx(), shape.sz()),
                bestRows(floor.dz(), floor.dx(), shape.sx(), shape.sz()));
    }

    /** The largest block first, upright before a side, then the kind's order. */
    private static int quick(Block left, Block right) {
        int byVolume = Long.compare(right.volume(), left.volume());
        if (byVolume != 0) return byVolume;
        int byRank = Integer.compare(left.shape().rank(), right.shape().rank());
        if (byRank != 0) return byRank;
        return Integer.compare(left.kind(), right.kind());
    }

    /** How many of a kind still go on: what is left of it, within the weight still allowed. */
    private static int most(State state, List<Prepared> kinds, int index, Deck deck) {
        int left = state.left[index];
        if (left <= 0) return 0;
        Prepared kind = kinds.get(index);
        long room = kind.weight() > 0 && deck.maxWeight() > 0
                ? Math.max(0, (deck.maxWeight() - state.weight) / kind.weight()) : left;
        return (int) Math.max(0, Math.min(left, room));
    }

    /** Every block that fits the floor, the quick way's order; each full block with its lower twin where one helps. */
    private static List<Block> candidates(Floor floor, State state, List<Prepared> kinds, Deck deck) {
        List<Block> blocks = new ArrayList<>();
        for (int index = 0; index < kinds.size(); index++) {
            int count = most(state, kinds, index, deck);
            if (count <= 0) continue;
            for (Shape shape : kinds.get(index).shapes()) {
                Block block = blockFor(floor, index, kinds.get(index), shape, count, deck);
                if (block == null) continue;
                if (block.ny() > 1) {
                    int pattern = floorPattern(floor, shape);
                    int needed = (count + pattern - 1) / pattern;
                    if (needed < block.ny()) {
                        int fewer = block.nx() * block.nz() * needed;
                        block = block.withLower(new Block(block.kind(), shape, block.nx(), needed, block.nz(), fewer,
                                fewer * kinds.get(index).volume(), null));
                    }
                }
                blocks.add(block);
            }
        }
        blocks.sort(PalletPacker::quick);
        return blocks;
    }

    /** The quick way's one block for a floor: the first of candidates without sorting them all. */
    private static Block quickest(Floor floor, State state, List<Prepared> kinds, Deck deck) {
        Block best = null;
        for (int index = 0; index < kinds.size(); index++) {
            int count = most(state, kinds, index, deck);
            if (count <= 0) continue;
            for (Shape shape : kinds.get(index).shapes()) {
                Block block = blockFor(floor, index, kinds.get(index), shape, count, deck);
                if (block != null && (best == null || quick(block, best) < 0)) best = block;
            }
        }
        return best;
    }

    /** The lowest free rectangle, then the one furthest back, then the one furthest left. */
    private static int lowest(List<Floor> floors) {
        int best = -1;
        for (int index = 0; index < floors.size(); index++) {
            Floor floor = floors.get(index);
            if (floor.parked()) continue;
            if (best < 0) {
                best = index;
                continue;
            }
            Floor other = floors.get(best);
            if (floor.y() < other.y() || (floor.y() == other.y()
                    && (floor.z() < other.z() || (floor.z() == other.z() && floor.x() < other.x())))) best = index;
        }
        return best;
    }

    /** Rectangles at one height that meet along a whole edge become one. */
    private static List<Floor> join(List<Floor> floors) {
        boolean changed = true;
        while (changed) {
            changed = false;
            search:
            for (int i = 0; i < floors.size(); i++) {
                for (int j = i + 1; j < floors.size(); j++) {
                    Floor a = floors.get(i);
                    Floor b = floors.get(j);
                    if (a.y() != b.y()) continue;
                    Floor joined = null;
                    if (a.x() == b.x() && a.dx() == b.dx() && (a.z() + a.dz() == b.z() || b.z() + b.dz() == a.z())) {
                        joined = new Floor(a.x(), Math.min(a.z(), b.z()), a.dx(), a.dz() + b.dz(), a.y(), false);
                    } else if (a.z() == b.z() && a.dz() == b.dz() && (a.x() + a.dx() == b.x() || b.x() + b.dx() == a.x())) {
                        joined = new Floor(Math.min(a.x(), b.x()), a.z(), a.dx() + b.dx(), a.dz(), a.y(), false);
                    }
                    if (joined != null) {
                        floors.remove(j);
                        floors.set(i, joined);
                        changed = true;
                        break search;
                    }
                }
            }
        }
        return floors;
    }

    /** The block in the floor's back left corner: its top is a new floor, the rest of the floor two rectangles. */
    private static void apply(State state, int at, Block block, List<Prepared> kinds, Deck deck, List<Box> boxes) {
        Floor floor = state.floors.get(at);
        Shape shape = block.shape();
        int width = block.nx() * shape.sx();
        int depth = block.nz() * shape.sz();
        int height = block.ny() * shape.sy();
        if (boxes != null) {
            int key = kinds.get(block.kind()).key();
            for (int iy = 0; iy < block.ny(); iy++) {
                for (int iz = 0; iz < block.nz(); iz++) {
                    for (int ix = 0; ix < block.nx(); ix++) {
                        boxes.add(new Box(key, floor.x() + ix * shape.sx(), floor.y() + iy * shape.sy(), floor.z() + iz * shape.sz(),
                                shape.up(), shape.turned()));
                    }
                }
            }
        }
        state.left[block.kind()] -= block.count();
        state.weight += (long) block.count() * kinds.get(block.kind()).weight();
        state.volume += block.volume();
        if (!shape.up().equals("h")) state.tipped += block.volume();
        state.top = Math.max(state.top, floor.y() + height);
        List<Floor> next = new ArrayList<>(state.floors.size() + 3);
        for (int index = 0; index < state.floors.size(); index++) if (index != at) next.add(state.floors.get(index));
        if (floor.y() + height < deck.height()) next.add(new Floor(floor.x(), floor.z(), width, depth, floor.y() + height, false));
        /* The rest of the floor as two rectangles, cut so the larger one is as large as it gets. */
        int restX = floor.dx() - width;
        int restZ = floor.dz() - depth;
        long along = Math.max((long) restX * floor.dz(), (long) width * restZ);
        long across = Math.max((long) restX * depth, (long) floor.dx() * restZ);
        Floor[] pieces = along >= across
                ? new Floor[] {new Floor(floor.x() + width, floor.z(), restX, floor.dz(), floor.y(), false),
                        new Floor(floor.x(), floor.z() + depth, width, restZ, floor.y(), false)}
                : new Floor[] {new Floor(floor.x() + width, floor.z(), restX, depth, floor.y(), false),
                        new Floor(floor.x(), floor.z() + depth, floor.dx(), restZ, floor.y(), false)};
        for (Floor piece : pieces) if (piece.dx() > 0 && piece.dz() > 0) next.add(piece);
        state.floors = join(next);
    }

    /** A rectangle nothing fits on is set aside; it only comes back joined to a neighbour. */
    private static void park(State state, int at) {
        Floor floor = state.floors.get(at);
        state.floors.set(at, new Floor(floor.x(), floor.z(), floor.dx(), floor.dz(), floor.y(), true));
    }

    /** The rest of the pallet the quick way: how full it ends. */
    private static long finish(State state, List<Prepared> kinds, Deck deck) {
        while (true) {
            int at = lowest(state.floors);
            if (at < 0) return worth(state);
            Block block = quickest(state.floors.get(at), state, kinds, deck);
            if (block != null) apply(state, at, block, kinds, deck, null);
            else park(state, at);
        }
    }

    private static State fillOne(List<Prepared> kinds, int[] pool, Deck deck, List<Box> boxes) {
        State state = new State();
        state.floors = new ArrayList<>(List.of(new Floor(0, 0, deck.length(), deck.width(), 0, false)));
        state.left = pool.clone();
        while (true) {
            int at = lowest(state.floors);
            if (at < 0) break;
            List<Block> blocks = candidates(state.floors.get(at), state, kinds, deck);
            if (blocks.isEmpty()) {
                park(state, at);
                continue;
            }
            Block chosen = blocks.get(0);
            List<Block> tries = new ArrayList<>();
            for (Block block : blocks.subList(0, Math.min(PILOT, blocks.size()))) {
                tries.add(block);
                if (block.lower() != null) tries.add(block.lower());
            }
            if (tries.size() > 1) {
                /* A try that takes everything still to load wins (no omdoos on a pallet of its own because an upright
                   block was preferred); then the fullest pallet; where several load the same (the last pallet), the lowest. */
                long best = Long.MIN_VALUE;
                int lowestTop = Integer.MAX_VALUE;
                boolean bestCleared = false;
                for (Block block : tries) {
                    State trial = state.copy();
                    apply(trial, at, block, kinds, deck, null);
                    long full = finish(trial, kinds, deck);
                    boolean cleared = true;
                    for (int count : trial.left) if (count > 0) { cleared = false; break; }
                    if ((cleared && !bestCleared)
                            || (cleared == bestCleared && (full > best || (full == best && trial.top < lowestTop)))) {
                        best = full;
                        lowestTop = trial.top;
                        bestCleared = cleared;
                        chosen = block;
                    }
                }
            }
            apply(state, at, chosen, kinds, deck, boxes);
        }
        return state;
    }

    /** The load in the middle of the floor. */
    private static List<Box> centred(List<Box> boxes, Map<Integer, Prepared> kinds, Deck deck) {
        int usedX = 0;
        int usedZ = 0;
        for (Box box : boxes) {
            Shape shape = shapeOf(kinds.get(box.key()), box);
            if (shape == null) continue;
            usedX = Math.max(usedX, box.x() + shape.sx());
            usedZ = Math.max(usedZ, box.z() + shape.sz());
        }
        int dx = Math.max(0, (deck.length() - usedX) / 2);
        int dz = Math.max(0, (deck.width() - usedZ) / 2);
        if (dx == 0 && dz == 0) return boxes;
        List<Box> moved = new ArrayList<>(boxes.size());
        for (Box box : boxes) moved.add(new Box(box.key(), box.x() + dx, box.y(), box.z() + dz, box.up(), box.turned()));
        return moved;
    }

    private static Shape shapeOf(Prepared kind, Box box) {
        if (kind == null) return null;
        for (Shape shape : kind.shapes()) if (shape.up().equals(box.up()) && shape.turned() == box.turned()) return shape;
        return null;
    }

    /**
     * The omdozen pallet by pallet, each as full as it gets; at most maxPallets. Kinds that fit no
     * pallet on any allowed side (too large, too heavy, no sizes) stay in left, as do omdozen past
     * the limit.
     */
    public static Result pack(List<Kind> input, Deck deck, int maxPallets) {
        List<Prepared> kinds = new ArrayList<>(input.size());
        Map<Integer, Prepared> byKey = new LinkedHashMap<>();
        for (Kind kind : input) {
            boolean usable = placeable(kind) && !(deck.maxWeight() > 0 && kind.weight() > deck.maxWeight());
            Prepared prepared = new Prepared(kind.key(), Math.max(0, kind.weight()),
                    (long) kind.length() * kind.width() * kind.height(), usable ? shapesOf(kind, deck) : List.of());
            kinds.add(prepared);
            byKey.put(kind.key(), prepared);
        }
        int[] pool = new int[input.size()];
        for (int index = 0; index < input.size(); index++) {
            pool[index] = kinds.get(index).shapes().isEmpty() ? 0 : Math.max(0, input.get(index).count());
        }
        List<Pallet> pallets = new ArrayList<>();
        while (pallets.size() < maxPallets && anyLeft(pool)) {
            List<Box> boxes = new ArrayList<>();
            State state = fillOne(kinds, pool, deck, boxes);
            if (boxes.isEmpty()) break;
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            int top = 0;
            for (Box box : boxes) {
                counts.merge(box.key(), 1, Integer::sum);
                Shape shape = shapeOf(byKey.get(box.key()), box);
                if (shape != null) top = Math.max(top, box.y() + shape.sy());
            }
            System.arraycopy(state.left, 0, pool, 0, pool.length);
            pallets.add(new Pallet(List.copyOf(centred(boxes, byKey, deck)), counts, state.weight, top));
        }
        Map<Integer, Integer> left = new LinkedHashMap<>();
        for (int index = 0; index < input.size(); index++) {
            int rest = kinds.get(index).shapes().isEmpty() ? Math.max(0, input.get(index).count()) : pool[index];
            if (rest > 0) left.merge(input.get(index).key(), rest, Integer::sum);
        }
        return new Result(List.copyOf(pallets), left);
    }

    private static boolean anyLeft(int[] pool) {
        for (int count : pool) if (count > 0) return true;
        return false;
    }
}
