package be.enrosed.inventory.application;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rolls a booked count to the closing date.
 *
 * The stock book cannot rebuild a past date by itself: it knows when a line
 * was booked, not when the pieces moved. So the roll is a list the user
 * reviews: every line booked between the closing date and the count, and the
 * lines of the 31 days after the later of the two, each with one question,
 * whether it counts towards the closing quantity. The anchor is per product
 * and location: the booked line with the latest moment in the base count and
 * its corrections. There is no hand-typed closing quantity.
 */
public final class StockRoll {

    public static final String ANCHOR_COUNT = "TELLING";
    public static final String ANCHOR_BOOK = "BOEKSTAND";
    public static final String ANCHOR_NONE = "GEEN";

    public static final int LATER_DAYS = 31;

    public static final String NOTE_CORRECTION = "Correctie van de stand, geen fysieke beweging";
    public static final String NOTE_REPLACED_COUNT = "Eerdere telling van dit product, vervangen door de correctie";
    public static final String SOURCE_RECEIPT = "Ontvangstdatum van de container";
    public static final String SOURCE_INVOICE = "Factuurdatum";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Comparator<StockMovement> BOOK_ORDER = Comparator.comparing(StockMovement::at)
            .thenComparing(StockMovement::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private StockRoll() {}

    /* ---------------------------------------------------------------- shapes */

    public record Key(long productId, long locationId) {}

    /** The frozen name of a product on the rows. */
    public record Named(String sku, String name) {}

    /** One line of the base count or of a correction, as it was booked. */
    public record BookedLine(long countId, long lineId, long productId, int quantity, Instant bookedAt) {}

    /**
     * A location and what it is anchored on.
     *
     * @param anchor       TELLING with a base count; BOEKSTAND on the live levels of {@code readAt}
     * @param ledgerRefs   the stock book prefix of the base count and of each correction, by session id
     * @param lines        the booked lines of those sessions
     * @param liveLevels   BOEKSTAND only: the level per product at {@code readAt}
     * @param readAt       TELLING: when the base count was booked; BOEKSTAND: when the levels were read
     */
    public record Location(long locationId, String name, String anchor, Long baseCountId,
                           Map<Long, String> ledgerRefs, List<BookedLine> lines,
                           Map<Long, Integer> liveLevels, Instant readAt) {
        public Location {
            ledgerRefs = ledgerRefs == null ? Map.of() : ledgerRefs;
            lines = lines == null ? List.of() : lines;
            liveLevels = liveLevels == null ? Map.of() : liveLevels;
        }
    }

    /** A container as a receipt line of the stock book names it. */
    public record Receipt(long purchaseOrderId, LocalDate receivedOn) {}

    /** The user's own answer for one line, with the reason. */
    public record Flip(boolean applied, String reason) {}

    /** Reads the figure a line started from when the loaded lines do not hold its predecessor. */
    @FunctionalInterface
    public interface Preceding {
        /** The {@code quantityAfter} of the last line of that product and location before the moment; null when none. */
        Integer quantityBefore(long productId, long locationId, Instant before);
    }

    /**
     * @param movements          the stock book of the stretch that covers every window, in any order
     * @param receiptsByReference container number, and "{number} correctie", to its container
     * @param invoiceDates       invoice number to its invoice date
     * @param decisions          the MOVEMENT decisions by ledger row id
     * @param previousRows       the rows the previous compute of this closing stored
     * @param ledgerIds          which of those rows still exist in the stock book
     */
    public record Input(LocalDate closingDate, Instant cutoffAt, List<Location> locations, Map<Long, Named> products,
                        List<StockMovement> movements, Preceding preceding,
                        Map<String, Receipt> receiptsByReference, Map<String, LocalDate> invoiceDates,
                        Map<Long, Flip> decisions, List<StockClosingMovementEntity> previousRows, Set<Long> ledgerIds) {
        public Input {
            locations = locations == null ? List.of() : locations;
            products = products == null ? Map.of() : products;
            movements = movements == null ? List.of() : movements;
            preceding = preceding == null ? (product, location, before) -> null : preceding;
            receiptsByReference = receiptsByReference == null ? Map.of() : receiptsByReference;
            invoiceDates = invoiceDates == null ? Map.of() : invoiceDates;
            decisions = decisions == null ? Map.of() : decisions;
            previousRows = previousRows == null ? List.of() : previousRows;
            ledgerIds = ledgerIds == null ? Set.of() : ledgerIds;
        }
    }

    /** The closing quantity of one product at one location and what it was rolled from. */
    public record Position(long productId, long locationId, String locationName, String anchor, Long countId,
                           Long countLineId, Instant anchoredAt, boolean countAfterClosingDate,
                           int anchorQuantity, int rollDelta, int closingQuantity) {}

    /**
     * One listed line.
     *
     * @param between             booked between the closing date and the count, not in the 31 days after
     * @param purchaseOrderId     the container a receipt line names; null otherwise
     * @param receivedBeforeCount a receipt booked after the count of pieces that had arrived by then
     */
    public record Row(StockClosingMovementEntity row, boolean between, boolean countAfterClosingDate,
                      Long purchaseOrderId, boolean receivedBeforeCount) {}

    public record Result(List<Position> positions, List<Row> rows) {

        public Position position(long productId, long locationId) {
            return positions.stream().filter(position -> position.productId() == productId
                    && position.locationId() == locationId).findFirst().orElse(null);
        }

        public Row row(long movementId) {
            return rows.stream().filter(row -> Objects.equals(row.row().movementId, movementId)).findFirst().orElse(null);
        }
    }

    /* ------------------------------------------------------------------ roll */

    public static Result roll(Input input) {
        Map<Key, List<StockMovement>> book = new HashMap<>();
        for (StockMovement movement : input.movements()) {
            if (movement.locationId() == null || !input.products().containsKey(movement.productId())) continue;
            book.computeIfAbsent(new Key(movement.productId(), movement.locationId()), key -> new ArrayList<>()).add(movement);
        }
        book.values().forEach(lines -> lines.sort(BOOK_ORDER));

        List<Position> positions = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (Location location : input.locations()) {
            if (ANCHOR_NONE.equals(location.anchor())) continue;
            /* Per product the booked line with the latest moment; the line id decides between equal moments. */
            Map<Long, BookedLine> anchors = new TreeMap<>();
            for (BookedLine line : location.lines()) {
                if (!input.products().containsKey(line.productId())) continue;
                anchors.merge(line.productId(), line, (left, right) -> later(right, left) ? right : left);
            }
            Map<Long, Boolean> listed = new TreeMap<>();
            anchors.keySet().forEach(productId -> listed.put(productId, true));
            location.liveLevels().forEach((productId, quantity) -> {
                if (quantity != 0 && input.products().containsKey(productId)) listed.put(productId, true);
            });
            book.keySet().stream().filter(key -> key.locationId() == location.locationId())
                    .forEach(key -> listed.putIfAbsent(key.productId(), false));

            for (Map.Entry<Long, Boolean> entry : listed.entrySet()) {
                long productId = entry.getKey();
                BookedLine anchor = anchors.get(productId);
                Instant anchoredAt = anchor == null ? location.readAt() : anchor.bookedAt();
                int quantity = anchor != null ? anchor.quantity() : location.liveLevels().getOrDefault(productId, 0);
                boolean countAfter = !anchoredAt.isBefore(input.cutoffAt());
                Long ownSession = anchor == null ? location.baseCountId() : Long.valueOf(anchor.countId());
                String ownRef = ownSession == null ? null : location.ledgerRefs().get(ownSession);

                List<Row> own = listedRows(input, location, productId, anchoredAt, countAfter, ownRef,
                        book.getOrDefault(new Key(productId, location.locationId()), List.of()));
                int moved = 0;
                for (Row row : own) {
                    if (Boolean.TRUE.equals(row.row().applied)) moved += row.row().effectiveDelta;
                }
                int delta = countAfter ? -moved : moved;
                if (!entry.getValue() && own.isEmpty()) continue;
                rows.addAll(own);
                positions.add(new Position(productId, location.locationId(), location.name(), location.anchor(),
                        location.baseCountId(), anchor == null ? null : anchor.lineId(), anchoredAt, countAfter,
                        quantity, delta, quantity + delta));
            }
        }

        /* A line that was listed and is no longer in the stock book stays in the list and counts for nothing. */
        Set<Long> now = new java.util.HashSet<>();
        rows.forEach(row -> now.add(row.row().movementId));
        for (StockClosingMovementEntity before : input.previousRows()) {
            if (before.movementId == null || now.contains(before.movementId)) continue;
            if (!Boolean.TRUE.equals(before.removed) && input.ledgerIds().contains(before.movementId)) continue;
            StockClosingMovementEntity kept = copy(before);
            kept.removed = true;
            kept.applied = false;
            rows.add(new Row(kept, false, false, null, false));
            now.add(before.movementId);
        }
        rows.sort(Comparator.comparing((Row row) -> row.row().bookedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.row().movementId));
        return new Result(positions, rows);
    }

    private static List<Row> listedRows(Input input, Location location, long productId, Instant anchoredAt,
                                        boolean countAfter, String ownRef, List<StockMovement> book) {
        Instant from = countAfter ? input.cutoffAt() : anchoredAt;
        Instant until = (countAfter ? anchoredAt : input.cutoffAt()).plus(LATER_DAYS, ChronoUnit.DAYS);
        LocalDate countDay = LocalDate.ofInstant(anchoredAt, InventoryClock.BRUSSELS);
        Named named = input.products().get(productId);
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < book.size(); index++) {
            StockMovement movement = book.get(index);
            Instant at = movement.at();
            /* A line of the very moment of a count before the closing date is part of that count. */
            boolean inWindow = countAfter ? !at.isBefore(from) && !at.isAfter(until) : at.isAfter(from) && !at.isAfter(until);
            if (!inWindow || movement.id() == null) continue;
            boolean stocktake = movement.kind() == StockMovement.Kind.STOCKTAKE;
            if (stocktake && ownRef != null && startsWith(movement.reference(), ownRef)) continue;

            Integer startedFrom = index > 0 ? Integer.valueOf(book.get(index - 1).quantityAfter())
                    : input.preceding().quantityBefore(productId, location.locationId(), at);
            boolean between = countAfter ? !at.isAfter(anchoredAt) : at.isBefore(input.cutoffAt());

            StockClosingMovementEntity row = new StockClosingMovementEntity();
            row.movementId = movement.id();
            row.productId = productId;
            row.sku = named == null ? null : named.sku();
            row.productName = named == null ? null : named.name();
            row.locationId = location.locationId();
            row.locationName = location.name();
            row.countId = location.baseCountId();
            row.bookedAt = at;
            row.kind = movement.kind().name();
            row.kindLabel = movement.kind().dutchLabel();
            row.refText = cut(movement.reference(), 255);
            row.actor = cut(movement.actor(), 64);
            row.delta = movement.delta();
            row.quantityAfter = movement.quantityAfter();
            row.effectiveDelta = startedFrom == null ? movement.delta() : movement.quantityAfter() - startedFrom;
            row.noAnchor = startedFrom == null;
            row.defaultApplied = between;
            row.review = false;
            row.removed = false;

            Long purchaseOrderId = null;
            boolean receivedBeforeCount = false;
            boolean ofThisCount = stocktake && location.ledgerRefs().values().stream()
                    .anyMatch(ref -> startsWith(movement.reference(), ref));
            Receipt receipt = movement.kind() == StockMovement.Kind.PURCHASE_RECEIPT && movement.reference() != null
                    ? input.receiptsByReference().get(movement.reference()) : null;
            LocalDate invoiceDate = movement.kind() == StockMovement.Kind.SALE && movement.reference() != null
                    ? input.invoiceDates().get(movement.reference()) : null;
            if (ofThisCount) {
                row.defaultApplied = false;
                row.defaultNote = NOTE_REPLACED_COUNT;
            } else if (countAfter && between && (stocktake || movement.kind() == StockMovement.Kind.MANUAL_CORRECTION)) {
                row.defaultApplied = false;
                row.defaultNote = NOTE_CORRECTION;
                row.review = true;
            } else if (receipt != null && receipt.receivedOn() != null) {
                purchaseOrderId = receipt.purchaseOrderId();
                row.businessDate = receipt.receivedOn();
                row.businessDateSource = SOURCE_RECEIPT;
                LocalDate bookedOn = LocalDate.ofInstant(at, InventoryClock.BRUSSELS);
                if (countAfter && between && !receipt.receivedOn().isAfter(input.closingDate())) {
                    row.defaultApplied = false;
                    row.defaultNote = "Ontvangen op " + DAY.format(receipt.receivedOn()) + ", bijgeboekt op "
                            + DAY.format(bookedOn) + ": lag er al op de afsluitdatum";
                } else if (!countAfter && !at.isBefore(input.cutoffAt()) && receipt.receivedOn().isAfter(countDay)
                        && !receipt.receivedOn().isAfter(input.closingDate())) {
                    row.defaultApplied = true;
                    row.defaultNote = "Ontvangen op " + DAY.format(receipt.receivedOn()) + ", bijgeboekt na de afsluitdatum";
                }
                receivedBeforeCount = at.isAfter(anchoredAt) && !receipt.receivedOn().isAfter(countDay);
            } else if (invoiceDate != null) {
                row.businessDate = invoiceDate;
                row.businessDateSource = SOURCE_INVOICE;
                /* Invoiced on one side of the closing date and booked on the other: only the user knows when the goods left. */
                row.review = !invoiceDate.isAfter(input.closingDate()) != at.isBefore(input.cutoffAt());
            }

            Flip flip = input.decisions().get(movement.id());
            row.applied = flip == null ? row.defaultApplied : flip.applied();
            row.appliedReason = flip == null ? null : cut(flip.reason(), 1000);
            rows.add(new Row(row, between, countAfter, purchaseOrderId, receivedBeforeCount));
        }
        return rows;
    }

    /** The closing quantities per location of one product, in the order of the positions. */
    public static Map<Long, Integer> closingQuantities(List<Position> positions, long productId) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (Position position : positions) {
            if (position.productId() == productId) quantities.put(position.locationId(), position.closingQuantity());
        }
        return quantities;
    }

    private static boolean later(BookedLine first, BookedLine second) {
        int byMoment = first.bookedAt().compareTo(second.bookedAt());
        return byMoment != 0 ? byMoment > 0 : first.lineId() > second.lineId();
    }

    private static boolean startsWith(String reference, String prefix) {
        return reference != null && prefix != null && reference.startsWith(prefix);
    }

    static StockClosingMovementEntity copy(StockClosingMovementEntity source) {
        StockClosingMovementEntity row = new StockClosingMovementEntity();
        row.closingId = source.closingId;
        row.movementId = source.movementId;
        row.productId = source.productId;
        row.sku = source.sku;
        row.productName = source.productName;
        row.locationId = source.locationId;
        row.locationName = source.locationName;
        row.countId = source.countId;
        row.bookedAt = source.bookedAt;
        row.kind = source.kind;
        row.kindLabel = source.kindLabel;
        row.refText = source.refText;
        row.actor = source.actor;
        row.delta = source.delta;
        row.quantityAfter = source.quantityAfter;
        row.effectiveDelta = source.effectiveDelta;
        row.noAnchor = source.noAnchor;
        row.businessDate = source.businessDate;
        row.businessDateSource = source.businessDateSource;
        row.defaultApplied = source.defaultApplied;
        row.defaultNote = source.defaultNote;
        row.applied = source.applied;
        row.appliedReason = source.appliedReason;
        row.review = source.review;
        row.removed = source.removed;
        return row;
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
