package be.enrosed.inventory.application;

import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockCountEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountLineEntity;
import be.enrosed.inventory.domain.CountReason;
import be.enrosed.sales.application.SalesAdvanceBilling;
import be.enrosed.sales.application.SalesAdvanceBillingService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The yearly count of one stock location, done on a phone by two people.
 *
 * A count never overwrites a level. Each line stores what the book said at
 * the moment its shelf was counted, and booking applies only the counted
 * DIFFERENCE to the level of that moment: a sale that is booked while the
 * count is open survives. A difference that an invoice that is not afgepunt
 * or a container that is not bijgeboekt explains is not booked here at all,
 * because that document would book the same pieces a second time.
 *
 * No entity carries a version. Starting locks the location row, every other
 * write locks the session row, so the writes of one session run one after
 * another and nothing is saved while or after the other phone books.
 */
@ApplicationScoped
public class StockCountService {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_BOOKED = "GEBOEKT";
    public static final String STATUS_CANCELLED = "GEANNULEERD";

    public static final String ENTITY_STOCK_COUNT = "STOCK_COUNT";

    private static final int MAX_REFERENCE = 255;
    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(InventoryClock.BRUSSELS);
    private static final Set<QuoteStatus> ISSUED = Set.of(QuoteStatus.VERZONDEN, QuoteStatus.UITGEREIKT, QuoteStatus.BETAALD);

    private final InventoryStore.Counts counts;
    private final InventoryStore.CountLines lines;
    private final StockService stock;
    private final ProductService products;
    private final CategoryService categories;
    private final CurrentActor actor;
    private final ActivityLogService activity;
    private final EntityManager entities;

    @Inject Instance<SalesOrderService> salesOrders;
    @Inject Instance<SalesAdvanceBillingService> advanceBilling;
    @Inject Instance<PurchaseOrderService> purchaseOrders;

    public StockCountService(InventoryStore.Counts counts, InventoryStore.CountLines lines, StockService stock,
                             ProductService products, CategoryService categories, CurrentActor actor,
                             ActivityLogService activity, EntityManager entities) {
        this.counts = counts;
        this.lines = lines;
        this.stock = stock;
        this.products = products;
        this.categories = categories;
        this.actor = actor;
        this.activity = activity;
        this.entities = entities;
    }

    /* ---------------------------------------------------------------- shapes */

    /** A session with the four figures of its progress. */
    public record Summary(StockCountEntity count, int lineCount, int countedCount, int differenceCount,
                          int missingReasonCount) {}

    /** An invoice that is not afgepunt (FACTUUR) or a container that is not bijgeboekt (CONTAINER), for one product. */
    public record OpenDocument(String kind, long id, String number, int quantity) {}

    public record Line(StockCountLineEntity row, int liveQuantity, boolean moved, List<OpenDocument> openDocuments) {}

    public record UnbookedContainer(long purchaseOrderId, String number, String displayName, LocalDate receivedOn,
                                    Map<Long, Integer> quantities) {}

    public record UnshippedInvoice(long salesOrderId, String number, LocalDate orderDate, Map<Long, Integer> quantities) {}

    /** Pieces in the book for a product that no longer exists. */
    public record OrphanLevel(long productId, int quantity) {}

    public record Warnings(List<UnbookedContainer> unbookedContainers, List<UnshippedInvoice> unshippedInvoices,
                           int olderUnshippedInvoiceCount, List<OrphanLevel> orphanLevels) {}

    public record Session(Summary summary, Warnings warnings, List<Line> lines) {}

    public record LocationState(StockLocation location, int productsWithStock, Summary open, Summary booked,
                                int correctionCount) {}

    public record Overview(int year, List<Integer> years, List<LocationState> locations, List<Summary> counts) {}

    public record LineWrite(Integer countedQuantity, String reasonCode, String reasonNote, Integer revision,
                            Boolean rebase, Boolean documentsConfirmed) {}

    /** The line of a product on the list; {@code created} is false when it was listed already. */
    public record Added(Line line, boolean created) {}

    public record Check(List<Uncounted> uncounted, List<Long> missingReasons, List<Long> openDocuments,
                        List<Moved> moved, List<Negative> negative, CheckSummary summary, String checkToken) {
        public record Uncounted(long productId, Long lineId, String sku, String productName, int liveQuantity) {}

        public record Moved(long lineId, long productId, String sku, String productName, int expectedQuantity,
                            int countedQuantity, int difference, int liveQuantity, int resultQuantity,
                            List<StockMovement> movements) {}

        public record Negative(long lineId, String productName, int resultQuantity) {}

        public record CheckSummary(int lines, int equal, int shortLines, int shortUnits, int overLines, int overUnits) {}
    }

    /**
     * What a closing rolls to its date for one location: the latest booked full
     * count of the year, the booked corrections of that count, and every line
     * of those sessions that was booked.
     */
    public record Anchor(long locationId, StockCountEntity base, List<StockCountEntity> corrections,
                         List<StockCountLineEntity> lines) {}

    /* --------------------------------------------------------------- reading */

    @Transactional
    public Overview overview(Integer year) {
        int shown = year == null ? InventoryClock.today().getYear() : year;
        stock.mainLocation();
        Set<Long> existing = products.list().stream().map(Product::id).collect(Collectors.toSet());
        Map<Long, Map<Long, Integer>> levels = new HashMap<>();
        for (StockLevel level : stock.allLevels()) {
            levels.computeIfAbsent(level.location().id(), key -> new HashMap<>()).put(level.productId(), level.quantity());
        }
        List<StockCountEntity> ofYear = counts.list("countYear", shown);
        Map<Long, Summary> summaries = new HashMap<>();
        Function<StockCountEntity, Summary> summarised = count -> summaries.computeIfAbsent(count.id,
                key -> summary(count, lines.list("countId", count.id), levels.getOrDefault(count.locationId, Map.of())));

        List<LocationState> states = new ArrayList<>();
        for (StockLocation location : stock.locations()) {
            Map<Long, Integer> held = levels.getOrDefault(location.id(), Map.of());
            if (!location.active() && held.values().stream().allMatch(quantity -> quantity == 0)) continue;
            int withStock = (int) held.entrySet().stream()
                    .filter(level -> level.getValue() != 0 && existing.contains(level.getKey())).count();
            StockCountEntity open = openAt(location.id());
            StockCountEntity booked = latestBookedFull(location.id(), shown);
            int corrections = booked == null ? 0 : bookedCorrections(booked.id).size();
            states.add(new LocationState(location, withStock, open == null ? null : summarised.apply(open),
                    booked == null ? null : summarised.apply(booked), corrections));
        }
        List<Summary> sessions = ofYear.stream()
                .sorted(Comparator.comparing((StockCountEntity count) -> count.startedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(count -> count.id).reversed())
                .map(summarised).toList();
        List<Integer> years = counts.listAll().stream().map(count -> count.countYear).filter(Objects::nonNull)
                .distinct().sorted(Comparator.reverseOrder()).toList();
        return new Overview(shown, years, states, sessions);
    }

    @Transactional
    public Session view(long countId) {
        StockCountEntity count = require(countId);
        return session(count);
    }

    /** Per location with a booked full count of the year: that count, its booked corrections and their booked lines. */
    @Transactional
    public List<Anchor> anchors(int countYear) {
        Map<Long, StockCountEntity> bases = new LinkedHashMap<>();
        for (StockCountEntity count : counts.list("countYear = ?1 and status = ?2 and correctsCountId is null order by locationId, id",
                countYear, STATUS_BOOKED)) {
            bases.merge(count.locationId, count, (left, right) -> later(left, right) ? left : right);
        }
        List<Anchor> anchors = new ArrayList<>();
        for (StockCountEntity base : bases.values()) {
            List<StockCountEntity> corrections = bookedCorrections(base.id);
            List<StockCountLineEntity> booked = new ArrayList<>(bookedLines(base.id));
            for (StockCountEntity correction : corrections) booked.addAll(bookedLines(correction.id));
            anchors.add(new Anchor(base.locationId, base, corrections, booked));
        }
        return anchors;
    }

    /** The sessions of the year that are still being counted. */
    @Transactional
    public List<StockCountEntity> openSessions(int countYear) {
        return counts.list("countYear = ?1 and status = ?2 order by id", countYear, STATUS_OPEN);
    }

    /* ----------------------------------------------------------------- start */

    @Transactional
    public Session start(Integer countYear, Long locationId, String note, Long correctsCountId) {
        if (countYear == null || countYear < 2000 || countYear > 2999) {
            throw new UnprocessableBusinessRuleException("Kies een boekjaar");
        }
        if (locationId == null) throw new UnprocessableBusinessRuleException("Kies een locatie voor de telling");
        /* The location row always exists, so it is the lock two starts for one location meet on. */
        StockLocationEntity location = entities.find(StockLocationEntity.class, locationId, LockModeType.PESSIMISTIC_WRITE);
        if (location == null) throw new NotFoundException("Locatie", locationId);
        StockCountEntity running = openAt(locationId);
        if (running != null) {
            throw new InventoryRefusal("TELLING_LOOPT", "Voor " + location.name + " loopt al een telling",
                    Map.of("countId", running.id));
        }
        if (correctsCountId != null) {
            StockCountEntity latest = latestBookedFull(locationId, countYear);
            if (latest == null || !latest.id.equals(correctsCountId)) {
                throw new InventoryRefusal("GEEN_TELLING_OM_TE_CORRIGEREN",
                        "Corrigeren kan alleen op de laatste geboekte telling van " + location.name + " voor " + countYear);
            }
        }
        ActorRef who = actor.current();
        StockCountEntity count = new StockCountEntity();
        count.countYear = countYear;
        count.locationId = locationId;
        count.locationName = location.name;
        count.correctsCountId = correctsCountId;
        count.status = STATUS_OPEN;
        count.note = cut(blankToNull(note), 1000);
        count.startedBy = who.username();
        count.startedByName = cut(who.displayName(), 120);
        count.startedAt = Instant.now();
        counts.persist(count);
        counts.flush();
        count.ledgerRef = "Jaartelling " + countYear + " #" + count.id + " ·";

        if (correctsCountId == null) {
            /* Everything that lies there according to the book, hidden products included, and everything that could. */
            Map<Long, Integer> held = levelsAt(locationId);
            Map<Long, String> categoryNames = categoryNames();
            products.list().stream()
                    .filter(product -> product.active() || held.getOrDefault(product.id(), 0) != 0)
                    .sorted(Comparator.comparing(Product::id))
                    .forEach(product -> lines.persist(newLine(count.id, product, categoryNames, false)));
        }
        lines.flush();
        return session(count);
    }

    /* ---------------------------------------------------------------- counting */

    @Transactional
    public Line saveLine(long countId, long lineId, LineWrite write) {
        if (write == null) throw new UnprocessableBusinessRuleException("Geteld aantal kan niet negatief zijn");
        if (write.countedQuantity() != null && write.countedQuantity() < 0) {
            throw new UnprocessableBusinessRuleException("Geteld aantal kan niet negatief zijn");
        }
        CountReason reason = CountReason.of(write.reasonCode());
        if (reason == null && write.reasonCode() != null && !write.reasonCode().isBlank()) {
            throw new UnprocessableBusinessRuleException("Onbekende reden");
        }
        StockCountEntity count = lockOpen(countId);
        StockCountLineEntity row = lines.findById(lineId);
        if (row == null || !Objects.equals(row.countId, countId)) throw new NotFoundException("Regel", lineId);
        if (!Objects.equals(row.revision, write.revision())) {
            throw new InventoryRefusal("REGEL_GEWIJZIGD", changedMessage(row), Map.of("line", line(count, row)));
        }

        String note = cut(blankToNull(write.reasonNote()), 500);
        if (write.countedQuantity() == null) {
            row.expectedQuantity = null;
            row.expectedAt = null;
            row.countedQuantity = null;
            row.difference = null;
            row.countedBy = null;
            row.countedByName = null;
            row.countedAt = null;
            row.reasonCode = null;
            row.reasonNote = null;
            row.documentsConfirmed = false;
        } else if (!write.countedQuantity().equals(row.countedQuantity) || Boolean.TRUE.equals(write.rebase())) {
            /* A new count: the book of this very moment is what the shelf is compared with. */
            int live = stock.quantityAt(row.productId, count.locationId);
            int difference = write.countedQuantity() - live;
            requireNote(reason, note, difference);
            ActorRef who = actor.current();
            Instant now = Instant.now();
            row.expectedQuantity = live;
            row.expectedAt = now;
            row.countedQuantity = write.countedQuantity();
            row.difference = difference;
            row.countedBy = who.username();
            row.countedByName = cut(who.displayName(), 120);
            row.countedAt = now;
            row.reasonCode = difference == 0 || reason == null ? null : reason.code();
            row.reasonNote = difference == 0 || reason == null ? null : note;
            row.documentsConfirmed = false;
        } else {
            /* The same count: choosing the reason later never turns the difference into something else. */
            int difference = row.difference == null ? 0 : row.difference;
            requireNote(reason, note, difference);
            row.reasonCode = difference == 0 || reason == null ? null : reason.code();
            row.reasonNote = difference == 0 || reason == null ? null : note;
            if (write.documentsConfirmed() != null) row.documentsConfirmed = write.documentsConfirmed();
        }
        row.revision = (row.revision == null ? 0 : row.revision) + 1;
        lines.flush();
        return line(count, row);
    }

    @Transactional
    public Added addLine(long countId, Long productId) {
        if (productId == null) throw new UnprocessableBusinessRuleException("Kies een product");
        StockCountEntity count = lockOpen(countId);
        StockCountLineEntity listed = lines.find("countId = ?1 and productId = ?2", countId, productId).firstResult();
        if (listed != null) return new Added(line(count, listed), false);
        Product product = products.get(productId);
        StockCountLineEntity row = newLine(countId, product, categoryNames(), true);
        lines.persist(row);
        lines.flush();
        return new Added(line(count, row), true);
    }

    /* ----------------------------------------------------------------- booking */

    @Transactional
    public Check bookingCheck(long countId) {
        StockCountEntity count = require(countId);
        requireOpen(count);
        return check(count, existingProducts()).check();
    }

    @Transactional
    public Session book(long countId, String checkToken) {
        StockCountEntity count = lockOpen(countId);
        Map<Long, Product> existing = existingProducts();
        Checked checked = check(count, existing);
        Check check = checked.check();
        if (!check.uncounted().isEmpty()) {
            throw new InventoryRefusal("NIET_GETELD",
                    "Nog " + check.uncounted().size() + " producten met voorraad zijn niet geteld");
        }
        if (!check.missingReasons().isEmpty()) {
            throw new InventoryRefusal("REDEN_ONTBREEKT",
                    "Bij " + check.missingReasons().size() + " verschillen ontbreekt een reden");
        }
        if (!check.negative().isEmpty()) {
            throw new InventoryRefusal("ONDER_NUL", check.negative().getFirst().productName()
                    + ": de telling zou onder nul uitkomen. Tel dit product opnieuw");
        }
        if (!check.openDocuments().isEmpty()) throw openDocumentRefusal(checked);
        if (!check.checkToken().equals(checkToken)) {
            throw new InventoryRefusal("TELLING_GEWIJZIGD",
                    "De voorraad of de telling is intussen gewijzigd; bekijk de controle opnieuw");
        }

        /* Only the difference goes to the level of now; a line whose product is gone is left alone. */
        for (Line line : checked.counted()) {
            StockCountLineEntity row = line.row();
            int live = stock.quantityAt(row.productId, count.locationId);
            int target = live + row.difference;
            String label = row.difference == 0 ? "geen verschil" : CountReason.labelOf(row.reasonCode);
            stock.setLevel(row.productId, count.locationId, target, StockMovement.Kind.STOCKTAKE,
                    reference(count.ledgerRef, label));
            row.liveAtBooking = live;
            row.bookedDelta = row.difference;
            row.bookedQuantity = target;
            row.bookedAt = Instant.now();
        }
        ActorRef who = actor.current();
        count.status = STATUS_BOOKED;
        count.bookedBy = who.username();
        count.bookedByName = cut(who.displayName(), 120);
        count.bookedAt = Instant.now();
        counts.flush();

        Check.CheckSummary summary = check.summary();
        activity.record(ActivityLogService.ACTION_STOCK_BOOKED, ENTITY_STOCK_COUNT, String.valueOf(count.id), label(count),
                cut((count.correctsCountId == null ? "Jaartelling " : "Correctie jaartelling ") + count.countYear + " "
                        + count.locationName + " geboekt: " + summary.lines() + " regels, "
                        + (summary.shortLines() + summary.overLines()) + " verschillen (-" + summary.shortUnits()
                        + " / +" + summary.overUnits() + ")", 500));
        return session(count);
    }

    @Transactional
    public Session cancel(long countId) {
        StockCountEntity count = lock(countId);
        if (STATUS_BOOKED.equals(count.status)) {
            throw new InventoryRefusal("TELLING_GESLOTEN", "Een geboekte telling kan niet geannuleerd worden");
        }
        requireOpen(count);
        ActorRef who = actor.current();
        count.status = STATUS_CANCELLED;
        count.cancelledBy = who.username();
        count.cancelledByName = cut(who.displayName(), 120);
        count.cancelledAt = Instant.now();
        counts.flush();
        activity.record(ActivityLogService.ACTION_STATUS_CHANGED, ENTITY_STOCK_COUNT, String.valueOf(count.id), label(count),
                cut((count.correctsCountId == null ? "Jaartelling " : "Correctie jaartelling ") + count.countYear + " "
                        + count.locationName + " geannuleerd", 500));
        return session(count);
    }

    /* --------------------------------------------------------------- internals */

    private StockCountEntity require(long countId) {
        StockCountEntity count = counts.findById(countId);
        if (count == null) throw new NotFoundException("Telling", countId);
        return count;
    }

    private StockCountEntity lock(long countId) {
        StockCountEntity count = entities.find(StockCountEntity.class, countId, LockModeType.PESSIMISTIC_WRITE);
        if (count == null) throw new NotFoundException("Telling", countId);
        return count;
    }

    private StockCountEntity lockOpen(long countId) {
        StockCountEntity count = lock(countId);
        requireOpen(count);
        return count;
    }

    private static void requireOpen(StockCountEntity count) {
        if (STATUS_OPEN.equals(count.status)) return;
        throw new InventoryRefusal("TELLING_GESLOTEN", STATUS_BOOKED.equals(count.status)
                ? "Deze telling is al geboekt" : "Deze telling is geannuleerd");
    }

    private StockCountEntity openAt(long locationId) {
        return counts.find("locationId = ?1 and status = ?2 order by id", locationId, STATUS_OPEN).firstResult();
    }

    private StockCountEntity latestBookedFull(long locationId, int countYear) {
        StockCountEntity latest = null;
        for (StockCountEntity count : counts.list(
                "locationId = ?1 and countYear = ?2 and status = ?3 and correctsCountId is null",
                locationId, countYear, STATUS_BOOKED)) {
            if (latest == null || later(count, latest)) latest = count;
        }
        return latest;
    }

    /** Whether the first session was booked after the second; the id decides between equal moments. */
    private static boolean later(StockCountEntity first, StockCountEntity second) {
        int byMoment = Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).compare(first.bookedAt, second.bookedAt);
        return byMoment != 0 ? byMoment > 0 : first.id > second.id;
    }

    private List<StockCountEntity> bookedCorrections(long baseId) {
        return counts.list("correctsCountId = ?1 and status = ?2 order by id", baseId, STATUS_BOOKED);
    }

    private List<StockCountLineEntity> bookedLines(long countId) {
        return lines.list("countId = ?1 and bookedAt is not null order by id", countId);
    }

    private Map<Long, Integer> levelsAt(long locationId) {
        Map<Long, Integer> held = new HashMap<>();
        for (StockLevel level : stock.allLevels()) {
            if (level.location().id() == locationId) held.put(level.productId(), level.quantity());
        }
        return held;
    }

    private Map<Long, Product> existingProducts() {
        return products.list().stream().collect(Collectors.toMap(Product::id, Function.identity()));
    }

    private Map<Long, String> categoryNames() {
        return categories.list().stream().collect(Collectors.toMap(Category::id, Category::name));
    }

    private static StockCountLineEntity newLine(long countId, Product product, Map<Long, String> categoryNames,
                                                boolean addedByHand) {
        StockCountLineEntity row = new StockCountLineEntity();
        row.countId = countId;
        row.productId = product.id();
        row.sku = cut(product.sku(), 120);
        row.productName = cut(product.nameWithColour(), 255);
        row.categoryName = product.categoryId() == null ? null : cut(categoryNames.get(product.categoryId()), 255);
        row.familyId = product.familyId();
        row.unitKey = cut(product.packaging().unitKey(), 32);
        row.salesUnit = product.packaging().salesUnit().name();
        row.piecesPerUnit = product.packaging().isPresent() ? product.packaging().unitPieces() : null;
        row.addedByHand = addedByHand;
        row.revision = 0;
        row.documentsConfirmed = false;
        return row;
    }

    private static void requireNote(CountReason reason, String note, int difference) {
        if (difference != 0 && reason != null && reason.noteRequired() && note == null) {
            throw new UnprocessableBusinessRuleException("Vul bij 'Andere reden' een notitie in");
        }
    }

    private static String changedMessage(StockCountLineEntity row) {
        if (row.countedQuantity == null || row.countedAt == null) return "Deze regel is intussen gewijzigd";
        return row.countedByName + " telde hier al " + row.countedQuantity + " (" + CLOCK_TIME.format(row.countedAt) + ")";
    }

    private static boolean counted(StockCountLineEntity row) {
        return row.countedQuantity != null;
    }

    private static boolean differs(StockCountLineEntity row) {
        return counted(row) && row.difference != null && row.difference != 0;
    }

    private static Summary summary(StockCountEntity count, List<StockCountLineEntity> rows, Map<Long, Integer> live) {
        int listed = 0, counted = 0, different = 0, withoutReason = 0;
        for (StockCountLineEntity row : rows) {
            boolean expected = row.expectedQuantity != null && row.expectedQuantity != 0;
            if (!counted(row) && live.getOrDefault(row.productId, 0) == 0 && !expected
                    && !Boolean.TRUE.equals(row.addedByHand)) continue;
            listed++;
            if (!counted(row)) continue;
            counted++;
            if (!differs(row)) continue;
            different++;
            if (row.reasonCode == null) withoutReason++;
        }
        return new Summary(count, listed, counted, different, withoutReason);
    }

    private Session session(StockCountEntity count) {
        Map<Long, Integer> live = levelsAt(count.locationId);
        Set<Long> existing = products.list().stream().map(Product::id).collect(Collectors.toSet());
        List<StockCountLineEntity> rows = lines.list("countId", count.id);
        Documents documents = documents(count, true, true);
        boolean open = STATUS_OPEN.equals(count.status);

        List<Line> shown = rows.stream()
                .sorted(Comparator.comparing((StockCountLineEntity row) -> row.categoryName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(row -> row.productName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(row -> row.id))
                .map(row -> {
                    int quantity = live.getOrDefault(row.productId, 0);
                    boolean known = existing.contains(row.productId);
                    return new Line(row, quantity, open && known && moved(row, quantity),
                            open && known ? documents.of(row) : List.of());
                })
                .toList();

        /* What the book holds for products that are gone: a level without a product, or a line whose product was deleted. */
        Map<Long, Integer> orphans = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(live.keySet())) {
            if (!existing.contains(productId) && live.get(productId) != 0) orphans.put(productId, live.get(productId));
        }
        for (StockCountLineEntity row : rows) {
            if (!existing.contains(row.productId)) orphans.putIfAbsent(row.productId, live.getOrDefault(row.productId, 0));
        }
        List<OrphanLevel> orphanLevels = orphans.entrySet().stream()
                .map(level -> new OrphanLevel(level.getKey(), level.getValue()))
                .sorted(Comparator.comparingLong(OrphanLevel::productId)).toList();
        return new Session(summary(count, rows, live),
                new Warnings(documents.containers, documents.invoices, documents.olderInvoices, orphanLevels), shown);
    }

    /** One line as a save answers it; the documents are read only when the line has a difference they could explain. */
    private Line line(StockCountEntity count, StockCountLineEntity row) {
        int live = stock.quantityAt(row.productId, count.locationId);
        boolean negative = differs(row) && row.difference < 0;
        boolean positive = differs(row) && row.difference > 0;
        return new Line(row, live, moved(row, live), documents(count, negative, positive).of(row));
    }

    private static boolean moved(StockCountLineEntity row, int live) {
        return counted(row) && row.expectedQuantity != null && row.expectedQuantity != live;
    }

    private record Checked(Check check, List<Line> counted, Documents documents) {}

    /** The booking check of 4.5, on the lines whose product still exists. */
    private Checked check(StockCountEntity count, Map<Long, Product> existing) {
        Map<Long, Integer> live = levelsAt(count.locationId);
        Documents documents = documents(count, true, true);
        List<StockCountLineEntity> rows = lines.list("countId = ?1 order by productId, id", count.id).stream()
                .filter(row -> existing.containsKey(row.productId)).toList();
        Map<Long, StockCountLineEntity> byProduct = new HashMap<>();
        for (StockCountLineEntity row : rows) byProduct.putIfAbsent(row.productId, row);

        List<Line> counted = new ArrayList<>();
        List<Long> missingReasons = new ArrayList<>();
        List<Long> openDocuments = new ArrayList<>();
        List<Check.Moved> moved = new ArrayList<>();
        List<Check.Negative> negative = new ArrayList<>();
        List<String> token = new ArrayList<>();
        int equal = 0, shortLines = 0, shortUnits = 0, overLines = 0, overUnits = 0;
        for (StockCountLineEntity row : rows) {
            if (!counted(row)) continue;
            int quantity = live.getOrDefault(row.productId, 0);
            int difference = row.difference == null ? 0 : row.difference;
            int result = quantity + difference;
            Line line = new Line(row, quantity, moved(row, quantity), documents.of(row));
            counted.add(line);
            token.add(row.productId + ":" + quantity + ":" + row.revision);
            if (difference == 0) equal++;
            else if (difference < 0) { shortLines++; shortUnits -= difference; }
            else { overLines++; overUnits += difference; }
            if (difference != 0 && row.reasonCode == null) missingReasons.add(row.id);
            if (!line.openDocuments().isEmpty() && !Boolean.TRUE.equals(row.documentsConfirmed)) openDocuments.add(row.id);
            if (line.moved()) {
                moved.add(new Check.Moved(row.id, row.productId, row.sku, row.productName, row.expectedQuantity,
                        row.countedQuantity, difference, quantity, result, movementsSince(row, count.locationId)));
            }
            if (result < 0) negative.add(new Check.Negative(row.id, row.productName, result));
        }

        /* A full count leaves nothing with stock uncounted; a correction touches only what the user adds. */
        List<Check.Uncounted> uncounted = new ArrayList<>();
        if (count.correctsCountId == null) {
            for (Long productId : new TreeSet<>(live.keySet())) {
                int quantity = live.get(productId);
                Product product = existing.get(productId);
                StockCountLineEntity row = byProduct.get(productId);
                if (quantity == 0 || product == null || (row != null && counted(row))) continue;
                uncounted.add(new Check.Uncounted(productId, row == null ? null : row.id,
                        row == null ? product.sku() : row.sku,
                        row == null ? product.nameWithColour() : row.productName, quantity));
                token.add(productId + ":" + quantity);
            }
        }
        return new Checked(new Check(uncounted, missingReasons, openDocuments, moved, negative,
                new Check.CheckSummary(counted.size(), equal, shortLines, shortUnits, overLines, overUnits),
                sha256(String.join("\n", token))), counted, documents);
    }

    /** What was booked for this product at this location after its shelf was counted, oldest first. */
    private List<StockMovement> movementsSince(StockCountLineEntity row, long locationId) {
        return stock.movementsFor(row.productId).stream()
                .filter(movement -> Objects.equals(movement.locationId(), locationId))
                .filter(movement -> row.countedAt == null || movement.at().isAfter(row.countedAt))
                .sorted(Comparator.comparing(StockMovement::at)
                        .thenComparing(StockMovement::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private InventoryRefusal openDocumentRefusal(Checked checked) {
        Set<Long> blocked = Set.copyOf(checked.check().openDocuments());
        List<Line> open = checked.counted().stream().filter(line -> blocked.contains(line.row().id)).toList();
        Map<String, Object> details = Map.of("lineIds", checked.check().openDocuments());
        Line shortage = open.stream().filter(line -> line.row().difference < 0).findFirst().orElse(null);
        if (shortage != null) {
            return new InventoryRefusal("EERST_AFPUNTEN", shortage.row().productName + ": factuur "
                    + shortage.openDocuments().getFirst().number() + " is nog niet afgepunt. Punt ze eerst af, of bevestig"
                    + " bij het product dat het verschil niet door die factuur komt", details);
        }
        Line surplus = open.getFirst();
        long containerId = surplus.openDocuments().getFirst().id();
        String name = checked.documents().containers.stream()
                .filter(container -> container.purchaseOrderId() == containerId)
                .map(UnbookedContainer::displayName).findFirst().orElseThrow();
        return new InventoryRefusal("EERST_BIJBOEKEN", surplus.row().productName + ": container "
                + name + " is nog niet bijgeboekt. Boek hem eerst bij, of bevestig"
                + " bij het product dat het verschil niet door die container komt", details);
    }

    /* ------------------------------------------------------------- documents */

    /** The source documents that could explain a difference at this location, read once per answer. */
    private final class Documents {
        private final List<UnbookedContainer> containers = new ArrayList<>();
        private final List<UnshippedInvoice> invoices = new ArrayList<>();
        private int olderInvoices;

        List<OpenDocument> of(StockCountLineEntity row) {
            if (!differs(row)) return List.of();
            List<OpenDocument> open = new ArrayList<>();
            if (row.difference < 0) {
                for (UnshippedInvoice invoice : invoices) {
                    Integer quantity = invoice.quantities().get(row.productId);
                    if (quantity != null) open.add(new OpenDocument("FACTUUR", invoice.salesOrderId(), invoice.number(), quantity));
                }
            } else {
                for (UnbookedContainer container : containers) {
                    Integer quantity = container.quantities().get(row.productId);
                    if (quantity != null) {
                        open.add(new OpenDocument("CONTAINER", container.purchaseOrderId(),
                                container.number() == null ? container.displayName() : container.number(), quantity));
                    }
                }
            }
            return open;
        }
    }

    private Documents documents(StockCountEntity count, boolean withInvoices, boolean withContainers) {
        Documents documents = new Documents();
        if (withContainers && purchaseOrders != null && purchaseOrders.isResolvable()) {
            boolean main = stock.mainLocation().id().equals(count.locationId);
            for (PurchaseOrder order : purchaseOrders.get().list()) {
                if (order.status() != PurchaseOrderStatus.ONTVANGEN || order.isStockBooked()) continue;
                boolean here = order.receivingLocationId() == null ? main : order.receivingLocationId().equals(count.locationId);
                if (!here) continue;
                Map<Long, Integer> quantities = new LinkedHashMap<>();
                for (PurchaseOrderLine line : order.lines()) {
                    if (line.productId() != null && line.usable() > 0) quantities.merge(line.productId(), line.usable(), Integer::sum);
                }
                documents.containers.add(new UnbookedContainer(order.id(), order.number(), order.displayName(),
                        order.receivedOn(), quantities));
            }
            documents.containers.sort(Comparator.comparingLong(UnbookedContainer::purchaseOrderId));
        }
        if (withInvoices && salesOrders != null && salesOrders.isResolvable()) {
            List<SalesOrder> all = salesOrders.get().list();
            SalesAdvanceBillingService.Views billing = advanceBilling != null && advanceBilling.isResolvable()
                    ? advanceBilling.get().views(all) : null;
            LocalDate from = LocalDate.of(count.countYear, 1, 1);
            for (SalesOrder order : all) {
                if (!order.isInvoice() || order.purpose() != SalesPurpose.STANDARD || !ISSUED.contains(order.status())
                        || order.goodsShippedAt() != null) continue;
                if (billing != null) {
                    var role = billing.billing(order);
                    if (role != null && role.stage() == SalesAdvanceBilling.Stage.ADVANCE) continue;
                }
                Map<Long, Integer> quantities = new LinkedHashMap<>();
                for (SalesOrderLine line : order.lines()) {
                    if (line.productId() != null && !line.isUnavailable() && line.quantity() > 0) {
                        quantities.merge(line.productId(), line.quantity(), Integer::sum);
                    }
                }
                if (quantities.isEmpty()) continue;
                /* An older invoice was never afgepunt and its goods left long ago: counted, never held against a line. */
                if (order.orderDate() == null || order.orderDate().isBefore(from)) {
                    documents.olderInvoices++;
                    continue;
                }
                documents.invoices.add(new UnshippedInvoice(order.id(), order.number(), order.orderDate(), quantities));
            }
            documents.invoices.sort(Comparator.comparing(UnshippedInvoice::orderDate)
                    .thenComparingLong(UnshippedInvoice::salesOrderId));
        }
        return documents;
    }

    /* --------------------------------------------------------------- helpers */

    /** The reference of a booked line in the stock book: the session's prefix and why the figure moved. */
    static String reference(String ledgerRef, String label) {
        return cut(ledgerRef + " " + label, MAX_REFERENCE);
    }

    private static String label(StockCountEntity count) {
        return cut("Jaartelling " + count.countYear + " " + count.locationName, 255);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
