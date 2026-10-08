package be.enrosed.inventory.application;

import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.domain.Category;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingContainerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingDecisionEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLineEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLotEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingWriteDownEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountLineEntity;
import be.enrosed.inventory.adapter.out.persistence.StockOpeningLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockValuationRuleEntity;
import be.enrosed.inventory.application.ClosingNotices.Notice;
import be.enrosed.inventory.application.StockClosingDecisionService.Index;
import be.enrosed.inventory.domain.ClosingDecisionKind;
import be.enrosed.inventory.domain.PayeeLabels;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.SalesAdvanceBilling;
import be.enrosed.sales.application.SalesAdvanceBillingService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.sourcing.application.LotCostCalculator;
import be.enrosed.sourcing.application.LotCostService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The year-end closing of the stock: the closing quantity per product (the
 * booked count rolled to the closing date), its acquisition value per
 * receipt lot under FIFO, the blocks that are shown apart, and what still
 * stands between the concept and "Definitief maken".
 *
 * Inputs and outputs are kept apart. What the user gives lives in the
 * decisions and the opening values; every other closing table is output,
 * deleted and rebuilt by each compute from live data plus those inputs. A
 * compute therefore never runs twice at once on one closing and never on a
 * closing that is final: every write path first takes the closing row and
 * re-checks that it is a concept. A final closing is served from its stored
 * rows only; making one final is not done here.
 */
@ApplicationScoped
public class StockClosingService {

    public static final String STATUS_CONCEPT = "CONCEPT";
    public static final String STATUS_FINAL = "DEFINITIEF";

    public static final String ENTITY_STOCK_CLOSING = "STOCK_CLOSING";
    public static final String ACTION_CONCEPT_DELETED = "CLOSING_CONCEPT_DELETED";

    public static final String ROLE_OWN = "EIGEN";
    public static final String ROLE_PARTNER = "PARTNER";
    public static final String ROLE_TRANSIT = "ONDERWEG";
    public static final String ROLE_PREVIOUS = "VORIG";

    public static final String BORDER_DECISION = "BESLISSING";
    public static final String BORDER_TRANSIT = "ONDERWEG";
    public static final String BORDER_PREVIOUS = "VORIGE_AFSLUITING";
    public static final String BORDER_RECEIPT = "ONTVANGST";
    public static final String BORDER_CLOSING_DATE = "AFSLUITDATUM";

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private final InventoryStore.Closings closings;
    private final InventoryStore.Decisions decisionRows;
    private final InventoryStore.Movements movements;
    private final InventoryStore.Containers containers;
    private final InventoryStore.Lots lots;
    private final InventoryStore.Articles articles;
    private final InventoryStore.Layers layers;
    private final InventoryStore.Lines lines;
    private final InventoryStore.Separates separates;
    private final InventoryStore.WriteDowns writeDowns;
    private final InventoryStore.CountLines countLines;
    private final StockOpeningLayerService openingLayers;
    private final StockValuationRuleService rules;
    private final StockClosingDecisionService decisions;
    private final StockCountService counts;
    private final CategoryService categories;
    private final CurrentActor actor;
    private final ActivityLogService activity;
    private final EntityManager entities;

    @Inject Instance<PurchaseOrderService> purchaseOrders;
    @Inject Instance<SalesOrderService> salesOrders;
    @Inject Instance<SalesAdvanceBillingService> advanceBilling;
    @Inject Instance<ProductService> products;
    @Inject Instance<StockService> stock;
    @Inject Instance<LotCostService> lotCosts;
    @Inject Instance<SupplierService> suppliers;
    @Inject Instance<CustomerService> customers;

    public StockClosingService(InventoryStore.Closings closings, InventoryStore.Decisions decisionRows,
                               InventoryStore.Movements movements, InventoryStore.Containers containers,
                               InventoryStore.Lots lots, InventoryStore.Articles articles, InventoryStore.Layers layers,
                               InventoryStore.Lines lines, InventoryStore.Separates separates,
                               InventoryStore.WriteDowns writeDowns, InventoryStore.CountLines countLines,
                               StockOpeningLayerService openingLayers, StockValuationRuleService rules,
                               StockClosingDecisionService decisions, StockCountService counts, CategoryService categories,
                               CurrentActor actor, ActivityLogService activity, EntityManager entities) {
        this.closings = closings;
        this.decisionRows = decisionRows;
        this.movements = movements;
        this.containers = containers;
        this.lots = lots;
        this.articles = articles;
        this.layers = layers;
        this.lines = lines;
        this.separates = separates;
        this.writeDowns = writeDowns;
        this.countLines = countLines;
        this.openingLayers = openingLayers;
        this.rules = rules;
        this.decisions = decisions;
        this.counts = counts;
        this.categories = categories;
        this.actor = actor;
        this.activity = activity;
        this.entities = entities;
    }

    /* ---------------------------------------------------------------- shapes */

    /** The rule and every version of every year, newest year first, then newest version. */
    public record Overview(StockValuationRuleEntity rule, List<StockClosingEntity> closings) {}

    /** One location as the first step of the screen shows it, read from the line and movement rows of the closing. */
    public record LocationView(long locationId, String locationName, String anchor, Long countId, Instant anchoredAt,
                               String countedByName, boolean countAfterClosingDate, int correctionCount, int lineCount,
                               int differenceCount, int movementCount, int reviewCount) {}

    public record OpeningLayerView(long id, long productId, String sku, String productName, int quantity,
                                   BigDecimal unitValueEur, LocalDate asOfDate, String source, String note,
                                   String createdByName, Instant createdAt) {}

    /**
     * A closing as it is stored.
     *
     * @param previous           the closing its opening stock comes from; null in a first year
     * @param previousReplacedBy read time only: the version that replaced {@code previous} after this closing was frozen
     * @param versionChanges     only for a correction: what differs from the version it replaces
     * @param closingYears       the year of every closing a carried layer can name
     * @param versions           every version of this year, newest first
     */
    public record View(StockClosingEntity closing, StockClosingEntity previous, StockClosingEntity previousReplacedBy,
                       ClosingVersionDiff.Changes versionChanges, List<Notice> notices, List<LocationView> locations,
                       List<StockClosingArticleEntity> articles, List<StockClosingLayerEntity> layers,
                       List<StockClosingLineEntity> lines, List<StockClosingContainerEntity> containers,
                       List<StockClosingLotEntity> lots, List<StockClosingSeparateEntity> separates,
                       List<StockClosingWriteDownEntity> writeDowns, List<StockClosingMovementEntity> movements,
                       List<StockClosingDecisionEntity> decisions, List<OpeningLayerView> openingLayers,
                       Map<Long, Integer> closingYears, List<StockClosingEntity> versions, boolean canFinalize,
                       boolean canCorrect) {}

    /* --------------------------------------------------------------- reading */

    @Transactional
    public Overview overview() {
        List<StockClosingEntity> all = closings.listAll().stream()
                .sorted(Comparator.comparing((StockClosingEntity closing) -> closing.closingYear).reversed()
                        .thenComparing(Comparator.comparing((StockClosingEntity closing) -> closing.versionNo).reversed()))
                .toList();
        return new Overview(rules.current(), all);
    }

    @Transactional
    public View view(long closingId) {
        StockClosingEntity closing = closings.findById(closingId);
        if (closing == null) throw new NotFoundException("Afsluiting", closingId);
        boolean concept = STATUS_CONCEPT.equals(closing.status);
        Index index = Index.of(decisions.of(closingId));
        List<StockClosingLineEntity> lineRows = lines.list("closingId = ?1 order by productId, locationId", closingId);
        List<StockClosingMovementEntity> movementRows = movements.list("closingId = ?1 order by bookedAt, movementId", closingId);
        List<StockClosingArticleEntity> articleRows = articles.list("closingId = ?1 order by productName, productId", closingId);
        List<StockClosingLayerEntity> layerRows = layers.list("closingId = ?1 order by productId, id", closingId).stream()
                .sorted(Comparator.comparing((StockClosingLayerEntity layer) -> layer.productId)
                        .thenComparing(layer -> layer.position).thenComparing(layer -> layer.block)
                        .thenComparing(layer -> layer.salesOrderId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        List<StockClosingEntity> all = closings.listAll();
        Map<Long, StockClosingEntity> byId = all.stream().collect(Collectors.toMap(row -> row.id, Function.identity()));
        StockClosingEntity previous = closing.previousClosingId == null ? null : byId.get(closing.previousClosingId);

        /* Read time only: a final closing keeps the version it was frozen on, also when that one was replaced later. */
        StockClosingEntity previousReplacedBy = null;
        if (!concept && previous != null && previous.supersededById != null) {
            previousReplacedBy = validVersion(all, previous.closingYear);
        }
        ClosingVersionDiff.Changes changes = null;
        if (closing.supersedesId != null && byId.containsKey(closing.supersedesId)) {
            changes = ClosingVersionDiff.between(diffRows(closing, articleRows,
                            lots.list("closingId", closingId), containers.list("closingId", closingId), movementRows, layerRows),
                    storedDiffRows(byId.get(closing.supersedesId)));
        }
        List<StockClosingEntity> versions = all.stream().filter(row -> Objects.equals(row.closingYear, closing.closingYear))
                .sorted(Comparator.comparing((StockClosingEntity row) -> row.versionNo).reversed()).toList();
        boolean conceptOpen = versions.stream().anyMatch(row -> STATUS_CONCEPT.equals(row.status));
        Map<Long, Integer> years = new HashMap<>();
        all.forEach(row -> years.put(row.id, row.closingYear));

        return new View(closing, previous, previousReplacedBy, changes, ClosingNotices.fromJson(closing.noticesJson),
                locationViews(closing, lineRows, movementRows, index, concept), articleRows, layerRows, lineRows,
                containers.list("closingId = ?1 order by receivedOn desc, purchaseOrderId desc", closingId),
                lots.list("closingId = ?1 order by id", closingId),
                separates.list("closingId = ?1 order by kind, documentDate, documentNumber, id", closingId),
                writeDowns.list("closingId = ?1 order by decisionId, layerPosition", closingId), movementRows, index.all(),
                openingViews(closing, previous, concept, articleRows, layerRows), years, versions,
                concept && closing.blockerCount != null && closing.blockerCount == 0,
                !concept && closing.supersededById == null && !conceptOpen);
    }

    private List<LocationView> locationViews(StockClosingEntity closing, List<StockClosingLineEntity> lineRows,
                                             List<StockClosingMovementEntity> movementRows, Index index, boolean concept) {
        Map<Long, List<StockClosingLineEntity>> byLocation = new TreeMap<>();
        for (StockClosingLineEntity row : lineRows) byLocation.computeIfAbsent(row.locationId, key -> new ArrayList<>()).add(row);
        Set<Long> lineIds = lineRows.stream().map(row -> row.countLineId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Long> sessionOfLine = new HashMap<>();
        if (!lineIds.isEmpty()) {
            for (StockCountLineEntity line : countLines.list("id in ?1", lineIds)) sessionOfLine.put(line.id, line.countId);
        }
        Instant cutoff = closing.cutoffAt;
        List<LocationView> views = new ArrayList<>();
        for (Map.Entry<Long, List<StockClosingLineEntity>> entry : byLocation.entrySet()) {
            List<StockClosingLineEntity> rows = entry.getValue();
            StockClosingLineEntity first = rows.getFirst();
            Long countId = rows.stream().map(row -> row.countId).filter(Objects::nonNull).findFirst().orElse(null);
            StockClosingLineEntity latest = rows.stream().filter(row -> row.countLineId != null && row.anchoredAt != null)
                    .max(Comparator.comparing((StockClosingLineEntity row) -> row.anchoredAt).thenComparing(row -> row.productId))
                    .orElse(null);
            Instant anchoredAt = latest == null ? null : latest.anchoredAt;
            Set<Long> otherSessions = new HashSet<>();
            int counted = 0, different = 0;
            Map<Long, StockClosingLineEntity> byProduct = new HashMap<>();
            for (StockClosingLineEntity row : rows) {
                byProduct.put(row.productId, row);
                if (row.countLineId == null) continue;
                Long session = sessionOfLine.get(row.countLineId);
                if (session != null && !session.equals(countId)) otherSessions.add(session);
                if (!StockRoll.ANCHOR_COUNT.equals(row.anchor)) continue;
                counted++;
                if (row.countDifference != null && row.countDifference != 0) different++;
            }
            int listed = 0, review = 0;
            for (StockClosingMovementEntity row : movementRows) {
                if (!entry.getKey().equals(row.locationId) || Boolean.TRUE.equals(row.removed)) continue;
                if (Boolean.TRUE.equals(row.review) && index.movement(row.movementId) == null) review++;
                if (between(row, byProduct.get(row.productId), closing)) listed++;
            }
            views.add(new LocationView(entry.getKey(), first.locationName, first.anchor, countId, anchoredAt,
                    latest == null ? null : latest.countedByName, countAfter(anchoredAt, first.anchor, closing),
                    otherSessions.size(), counted, different, listed, review));
        }
        if (concept) {
            /* A location that holds nothing and saw no movement has no row; a concept still shows whether it was counted. */
            Map<Long, StockCountService.Anchor> anchors = counts.anchors(closing.closingYear).stream()
                    .collect(Collectors.toMap(StockCountService.Anchor::locationId, Function.identity()));
            for (StockLocation location : stock.get().locations()) {
                if (byLocation.containsKey(location.id())) continue;
                StockCountService.Anchor anchor = anchors.get(location.id());
                if (anchor == null && !location.active()) continue;
                views.add(new LocationView(location.id(), location.name(),
                        anchor == null ? StockRoll.ANCHOR_NONE : StockRoll.ANCHOR_COUNT,
                        anchor == null ? null : anchor.base().id, null, null, false, 0, 0, 0, 0, 0));
            }
        }
        views.sort(Comparator.comparing(LocationView::locationName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparingLong(LocationView::locationId));
        return views;
    }

    /** Whether the count of this figure was booked after the closing date; a level read from the book counts from its compute. */
    public static boolean countAfter(Instant anchoredAt, String anchor, StockClosingEntity closing) {
        if (closing.cutoffAt == null) return false;
        if (anchoredAt != null) return !anchoredAt.isBefore(closing.cutoffAt);
        return StockRoll.ANCHOR_BOOK.equals(anchor) && closing.computedAt != null && !closing.computedAt.isBefore(closing.cutoffAt);
    }

    /** Whether a listed line was booked between the closing date and the count of its product, not in the 31 days after. */
    public static boolean between(StockClosingMovementEntity row, StockClosingLineEntity line, StockClosingEntity closing) {
        if (row.bookedAt == null || closing.cutoffAt == null || line == null) return false;
        Instant anchoredAt = line.anchoredAt != null ? line.anchoredAt
                : StockRoll.ANCHOR_BOOK.equals(line.anchor) ? closing.computedAt : null;
        if (anchoredAt == null) return false;
        return !anchoredAt.isBefore(closing.cutoffAt)
                ? !row.bookedAt.isBefore(closing.cutoffAt) && !row.bookedAt.isAfter(anchoredAt)
                : row.bookedAt.isAfter(anchoredAt) && row.bookedAt.isBefore(closing.cutoffAt);
    }

    private List<OpeningLayerView> openingViews(StockClosingEntity closing, StockClosingEntity previous, boolean concept,
                                                List<StockClosingArticleEntity> articleRows, List<StockClosingLayerEntity> layerRows) {
        List<OpeningLayerView> views = new ArrayList<>();
        if (!concept) {
            /* A final closing shows its own copy: retiring or replacing an opening value afterwards changes nothing here. */
            Map<Long, StockClosingArticleEntity> named = articleRows.stream()
                    .collect(Collectors.toMap(article -> article.productId, Function.identity(), (left, right) -> left));
            for (StockClosingLayerEntity layer : layerRows) {
                if (!FifoValuer.SOURCE_OPENING.equals(layer.source) || layer.openingLayerId == null) continue;
                StockOpeningLayerEntity entered = entities.find(StockOpeningLayerEntity.class, layer.openingLayerId);
                StockClosingArticleEntity article = named.get(layer.productId);
                views.add(new OpeningLayerView(layer.openingLayerId, layer.productId, article == null ? null : article.sku,
                        article == null ? null : article.productName, layer.quantity, layer.unitValueEur, layer.receivedOn,
                        layer.openingSource, entered == null ? null : entered.note,
                        entered == null ? null : entered.createdByName, entered == null ? null : entered.createdAt));
            }
            return views;
        }
        Set<Long> consumed = previous == null ? Set.of() : consumedOpenings(chain(previous));
        Set<Long> valued = articleRows.stream().map(article -> article.productId).collect(Collectors.toSet());
        for (StockOpeningLayerEntity layer : openingLayers.active()) {
            /* What an earlier closing used is not shown, so nobody is invited to remove last year's opening values. */
            if (previous != null && !layer.asOfDate.isAfter(previous.closingDate)
                    && (consumed.contains(layer.id) || !valued.contains(layer.productId))) continue;
            views.add(new OpeningLayerView(layer.id, layer.productId, layer.sku, layer.productName, layer.quantity,
                    layer.unitValueEur, layer.asOfDate, layer.source, layer.note, layer.createdByName, layer.createdAt));
        }
        return views;
    }

    /* ----------------------------------------------------------- write paths */

    @Transactional
    public View create(Integer closingYear, LocalDate closingDate) {
        if (closingYear == null || closingYear < 2000 || closingYear > 2999) {
            throw new UnprocessableBusinessRuleException("Kies een boekjaar");
        }
        LocalDate date = closingDate == null ? LocalDate.of(closingYear, 12, 31) : closingDate;
        requireNearYear(closingYear, date);
        /* Two creates meet on the rule row; for the very first closing the unique index decides. */
        StockValuationRuleEntity rule = rules.lockForCreate();
        List<StockClosingEntity> all = closings.listAll();
        StockClosingEntity existing = all.stream().filter(closing -> closingYear.equals(closing.closingYear))
                .max(Comparator.comparing(closing -> closing.versionNo)).orElse(null);
        if (existing != null) {
            throw new InventoryRefusal("BESTAAT_AL", "Voor " + closingYear + " bestaat al een afsluiting."
                    + " Open ze, of maak een nieuwe versie", Map.of("closingId", existing.id));
        }
        boolean anyFinal = all.stream().anyMatch(closing -> STATUS_FINAL.equals(closing.status));
        if (anyFinal && rule != null && rule.effectiveFromYear != null && closingYear < rule.effectiveFromYear) {
            throw new InventoryRefusal("VOOR_REGEL", "De waarderingsregel geldt vanaf " + rule.effectiveFromYear
                    + "; een vroeger boekjaar kan niet");
        }
        requireDateOrder(all, closingYear, date);

        ActorRef who = actor.current();
        StockClosingEntity closing = new StockClosingEntity();
        closing.closingYear = closingYear;
        closing.versionNo = 1;
        closing.closingDate = date;
        closing.cutoffAt = InventoryClock.cutoffAt(date);
        closing.status = STATUS_CONCEPT;
        closing.createdBy = who.username();
        closing.createdByName = cut(who.displayName(), 120);
        closing.createdAt = Instant.now();
        closings.persist(closing);
        closings.flush();
        rules.alignWithClosings();
        compute(closing.id);
        return view(closing.id);
    }

    @Transactional
    public View setClosingDate(long closingId, LocalDate closingDate) {
        StockClosingEntity closing = requireConcept(closingId);
        LocalDate date = closingDate == null ? LocalDate.of(closing.closingYear, 12, 31) : closingDate;
        requireNearYear(closing.closingYear, date);
        requireDateOrder(closings.listAll(), closing.closingYear, date);
        closing.closingDate = date;
        closing.cutoffAt = InventoryClock.cutoffAt(date);
        compute(closingId);
        return view(closingId);
    }

    @Transactional
    public View recompute(long closingId) {
        requireConcept(closingId);
        compute(closingId);
        return view(closingId);
    }

    @Transactional
    public View saveDecision(long closingId, StockClosingDecisionService.Write write) {
        StockClosingEntity closing = requireConcept(closingId);
        decisions.write(closing, write, new DecisionFacts(closing));
        compute(closingId);
        return view(closingId);
    }

    @Transactional
    public View deleteDecision(long closingId, long decisionId) {
        StockClosingEntity closing = requireConcept(closingId);
        decisions.delete(closing, decisionId);
        compute(closingId);
        return view(closingId);
    }

    /** Removes a concept with its rows and decisions; a final closing has no delete path. */
    @Transactional
    public void delete(long closingId) {
        StockClosingEntity closing;
        try {
            closing = requireConcept(closingId);
        } catch (InventoryRefusal refusal) {
            throw new InventoryRefusal(refusal.code(), "Een definitieve afsluiting kan niet verwijderd worden");
        }
        deleteOutput(closingId);
        movements.delete("closingId", closingId);
        decisionRows.delete("closingId", closingId);
        int year = closing.closingYear;
        int version = closing.versionNo;
        closings.delete(closing);
        closings.flush();
        rules.alignWithClosings();
        activity.record(ACTION_CONCEPT_DELETED, ENTITY_STOCK_CLOSING, String.valueOf(closingId),
                "Jaarinventaris " + year + " versie " + version,
                "Concept jaarinventaris " + year + " versie " + version + " verwijderd");
    }

    /**
     * Takes the closing row for the length of the transaction and refuses unless it is a concept.
     * Every write on a closing starts here, so two computes of one closing never interleave and
     * none runs on a closing that became final a moment earlier.
     */
    @Transactional
    public StockClosingEntity requireConcept(long closingId) {
        StockClosingEntity closing = entities.find(StockClosingEntity.class, closingId, LockModeType.PESSIMISTIC_WRITE);
        if (closing == null) throw new NotFoundException("Afsluiting", closingId);
        /* The row may have been read before the lock was ours: look at it again as it is now. */
        entities.flush();
        entities.refresh(closing);
        if (!STATUS_CONCEPT.equals(closing.status)) {
            throw new InventoryRefusal("DEFINITIEF",
                    "Deze afsluiting is definitief. Maak een nieuwe versie om iets te corrigeren");
        }
        return closing;
    }

    private static void requireNearYear(int closingYear, LocalDate date) {
        if (date.isBefore(LocalDate.of(closingYear - 2, 1, 1)) || date.isAfter(LocalDate.of(closingYear + 2, 12, 31))) {
            throw new UnprocessableBusinessRuleException("De afsluitdatum ligt meer dan twee jaar van het boekjaar");
        }
    }

    /** The closing date of a year lies after that of the latest earlier year that has a closing. */
    private static void requireDateOrder(List<StockClosingEntity> all, int closingYear, LocalDate date) {
        StockClosingEntity earlier = all.stream().filter(closing -> closing.closingYear < closingYear)
                .max(Comparator.comparing((StockClosingEntity closing) -> closing.closingYear)
                        .thenComparing(closing -> closing.closingDate)).orElse(null);
        if (earlier != null && !date.isAfter(earlier.closingDate)) {
            throw new InventoryRefusal("DATUM_VOLGORDE", "De afsluitdatum moet na die van " + earlier.closingYear + " liggen");
        }
    }

    /* ------------------------------------------------------------- continuity */

    /** The valid version of a year: its final row with the highest version number; null when none is final. */
    private static StockClosingEntity validVersion(List<StockClosingEntity> all, int closingYear) {
        return all.stream().filter(row -> row.closingYear == closingYear && STATUS_FINAL.equals(row.status))
                .max(Comparator.comparing(row -> row.versionNo)).orElse(null);
    }

    /**
     * The closing the opening stock of this one comes from: the valid version of the year whose
     * closing date is the latest one before this closing date.
     */
    private StockClosingEntity previousOf(StockClosingEntity closing) {
        List<StockClosingEntity> all = closings.listAll();
        StockClosingEntity previous = null;
        for (Integer year : all.stream().map(row -> row.closingYear).distinct().toList()) {
            if (year.equals(closing.closingYear)) continue;
            StockClosingEntity valid = validVersion(all, year);
            if (valid == null || !valid.closingDate.isBefore(closing.closingDate)) continue;
            if (previous == null || valid.closingDate.isAfter(previous.closingDate)) previous = valid;
        }
        return previous;
    }

    /** The previous closing and every closing before it, following the stored links. */
    private List<StockClosingEntity> chain(StockClosingEntity previous) {
        List<StockClosingEntity> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        StockClosingEntity link = previous;
        while (link != null && seen.add(link.id)) {
            chain.add(link);
            link = link.previousClosingId == null ? null : closings.findById(link.previousClosingId);
        }
        return chain;
    }

    private Set<Long> consumedOpenings(List<StockClosingEntity> chain) {
        if (chain.isEmpty()) return Set.of();
        List<Long> ids = chain.stream().map(link -> link.id).toList();
        return layers.list("closingId in ?1 and openingLayerId is not null", ids).stream()
                .map(layer -> layer.openingLayerId).collect(Collectors.toSet());
    }

    /** Whether a previous closing stored the day the goods were bought in a way that no later year may move. */
    private static boolean fixesBorder(StockClosingContainerEntity before) {
        if (before == null || before.rateCutoffDate == null) return false;
        if (ROLE_TRANSIT.equals(before.role)) {
            return BORDER_TRANSIT.equals(before.rateCutoffSource) || BORDER_PREVIOUS.equals(before.rateCutoffSource);
        }
        return ROLE_OWN.equals(before.role) || ROLE_PARTNER.equals(before.role) || ROLE_PREVIOUS.equals(before.role);
    }

    private record Border(LocalDate date, String source) {}

    /** The day the goods of a container were bought, by the first rule that applies. */
    private static Border border(PurchaseOrder order, String role, StockClosingContainerEntity before, Index index,
                                 LocalDate closingDate) {
        if (fixesBorder(before)) return new Border(before.rateCutoffDate, BORDER_PREVIOUS);
        StockClosingDecisionEntity ownership = index.container(ClosingDecisionKind.OWNERSHIP_DATE, order.id());
        if (ownership != null && ownership.decisionDate != null) return new Border(ownership.decisionDate, BORDER_DECISION);
        if (ROLE_TRANSIT.equals(role)) {
            StockClosingDecisionEntity transit = index.container(ClosingDecisionKind.TRANSIT, order.id());
            if (transit != null && Boolean.TRUE.equals(transit.flag) && transit.decisionDate != null) {
                return new Border(transit.decisionDate, BORDER_TRANSIT);
            }
            return new Border(closingDate, BORDER_CLOSING_DATE);
        }
        return new Border(order.receivedOn(), BORDER_RECEIPT);
    }

    /* ---------------------------------------------------------------- compute */

    /**
     * Rebuilds every output row of the closing and its fingerprint from live data and the decisions.
     * The caller holds the closing row lock, or inserted the row in this transaction.
     */
    @Transactional
    public void compute(long closingId) {
        StockClosingEntity closing = closings.findById(closingId);
        if (closing == null) throw new NotFoundException("Afsluiting", closingId);
        Instant now = Instant.now();
        LocalDate closingDate = closing.closingDate;
        Instant cutoff = InventoryClock.cutoffAt(closingDate);
        closing.cutoffAt = cutoff;
        List<StockClosingDecisionEntity> decided = decisions.of(closingId);
        Index index = Index.of(decided);
        ClosingNotices notices = new ClosingNotices();

        StockClosingEntity previous = previousOf(closing);
        List<StockClosingEntity> chain = chain(previous);
        closing.previousClosingId = previous == null ? null : previous.id;
        StockValuationRuleEntity rule = rules.current();
        if (rule != null) {
            closing.ruleId = rule.id;
            closing.ruleMethod = rule.method;
            closing.ruleMethodLabel = rule.methodLabel;
            closing.ruleEffectiveFromYear = rule.effectiveFromYear;
            closing.ruleVersion = rule.ruleVersion;
            closing.ruleText = rule.ruleText;
        }

        Map<Long, Product> productsById = new TreeMap<>();
        products.get().list().forEach(product -> productsById.put(product.id(), product));
        Map<Long, String> categoryNames = categories.list().stream().collect(Collectors.toMap(Category::id, Category::name));
        List<PurchaseOrder> orders = purchaseOrders.get().list().stream()
                .sorted(Comparator.comparing(PurchaseOrder::id)).toList();
        List<SalesOrder> sales = salesOrders.get().list();
        Map<Long, String> customerNames = new HashMap<>();
        if (customers.isResolvable()) {
            for (Customer customer : customers.get().list()) customerNames.put(customer.id(), customer.company());
        }

        if (cutoff.isAfter(now)) {
            notices.block("DATUM_TOEKOMST", ClosingNotices.SEGMENT_FINALIZE, "De afsluitdatum is nog niet voorbij.");
        }

        /* ---- 1. the closing quantity per product and location: the booked count rolled to the closing date */
        StockRoll.Result roll = roll(closing, cutoff, now, productsById, orders, sales, index, notices);
        Map<Long, StockCountLineEntity> countLineById = new HashMap<>();
        List<StockCountService.Anchor> anchors = counts.anchors(closing.closingYear);
        anchors.forEach(anchor -> anchor.lines().forEach(line -> countLineById.put(line.id, line)));
        for (StockCountEntity open : counts.openSessions(closing.closingYear)) {
            notices.forLocation("TELLING_OPEN", open.locationId, "De telling van " + open.locationName + " is nog niet geboekt.");
        }

        /* ---- 2. the containers and their lots */
        Map<Long, StockClosingContainerEntity> before = new HashMap<>();
        Map<String, StockClosingLotEntity> lotBefore = new HashMap<>();
        List<StockClosingLayerEntity> carried = List.of();
        Set<Long> known = new HashSet<>();
        if (previous != null) {
            containers.list("closingId", previous.id).forEach(row -> before.put(row.purchaseOrderId, row));
            lots.list("closingId", previous.id).forEach(row -> lotBefore.put(row.purchaseOrderId + ":" + row.productId, row));
            carried = layers.list("closingId = ?1 and block = ?2 order by productId, position, id", previous.id, FifoValuer.BLOCK_OWN);
            List<Long> chainIds = chain.stream().map(link -> link.id).toList();
            lots.list("closingId in ?1 and role = ?2", chainIds, ROLE_OWN).forEach(row -> known.add(row.purchaseOrderId));
        }
        Map<String, BigDecimal> frozenUnit = new HashMap<>();
        Map<String, Integer> frozenQuantity = new HashMap<>();
        Set<Long> carriedContainers = new LinkedHashSet<>();
        for (StockClosingLayerEntity layer : carried) {
            if (layer.purchaseOrderId == null || !FifoValuer.SOURCE_LOT.equals(layer.originSource)) continue;
            carriedContainers.add(layer.purchaseOrderId);
            String key = layer.purchaseOrderId + ":" + layer.productId;
            frozenUnit.putIfAbsent(key, layer.unitValueEur);
            frozenQuantity.merge(key, layer.quantity == null ? 0 : layer.quantity, Integer::sum);
        }

        List<StockClosingContainerEntity> containerRows = new ArrayList<>();
        Map<StockClosingContainerEntity, List<StockClosingLotEntity>> lotRows = new LinkedHashMap<>();
        List<FifoValuer.Lot> periodLots = new ArrayList<>();
        List<FifoValuer.PartnerLot> partnerLots = new ArrayList<>();
        List<StockClosingSeparateEntity> separateRows = new ArrayList<>();
        Map<Long, String> containerNames = new HashMap<>();
        int changedLots = 0;
        BigDecimal changedValue = BigDecimal.ZERO;
        for (PurchaseOrder order : orders) {
            containerNames.put(order.id(), order.displayName());
            if (order.status() == PurchaseOrderStatus.CONCEPT) continue;
            boolean received = order.status() == PurchaseOrderStatus.ONTVANGEN;
            if (received && order.receivedOn() != null && !order.receivedOn().isAfter(closingDate) && !order.isStockBooked()) {
                notices.forContainer(true, "CONTAINER_NIET_BIJGEBOEKT", ClosingNotices.SEGMENT_COUNT, order.id(),
                        "Container " + order.displayName() + " is ontvangen op " + ClosingNotices.day(order.receivedOn())
                                + " maar nog niet bijgeboekt. Boek de voorraad bij en corrigeer de telling voor die producten.");
            }
            String role;
            List<PurchasePayment> payments = null;
            if (carriedContainers.contains(order.id())) {
                role = ROLE_PREVIOUS;
            } else if (received && order.receivedOn() == null) {
                notices.forContainer(true, "GEEN_ONTVANGSTDATUM", ClosingNotices.SEGMENT_VALUE, order.id(),
                        "Container " + order.displayName() + " heeft geen ontvangstdatum.");
                continue;
            } else if (received && !order.receivedOn().isAfter(closingDate)) {
                /* A container of an earlier closing is never a lot again, whatever its receipt date says today. */
                if (known.contains(order.id())) continue;
                role = order.isPartnerContainer() ? ROLE_PARTNER : ROLE_OWN;
            } else {
                payments = purchaseOrders.get().payments(order.id());
                if (!ClosingSeparations.inTransit(order, payments, closingDate)) continue;
                role = ROLE_TRANSIT;
            }
            if (payments == null) payments = purchaseOrders.get().payments(order.id());

            StockClosingContainerEntity earlier = before.get(order.id());
            Border border = border(order, role, earlier, index, closingDate);
            boolean transit = ROLE_TRANSIT.equals(role);
            boolean live = !ROLE_PREVIOUS.equals(role);
            StockClosingDecisionEntity billed = index.container(ClosingDecisionKind.SUPPLIER_BILLED, order.id());
            LotCost.BilledBasis billedBasis = live
                    ? billed != null && "GELEVERD".equals(billed.choice) ? LotCost.BilledBasis.GELEVERD : LotCost.BilledBasis.BESTELD
                    : earlier != null && "GELEVERD".equals(earlier.billedBasis) ? LotCost.BilledBasis.GELEVERD : LotCost.BilledBasis.BESTELD;
            /* A container of an earlier year is recomputed for comparison with what that year decided about it. */
            Map<Long, LotCost.CreditTreatment> treatments = live ? index.treatments(order.id()) : frozenTreatments(earlier);
            LotCost.Options options = new LotCost.Options(border.date(),
                    transit ? LotCost.QuantityBasis.BESTELD : LotCost.QuantityBasis.ONTVANGEN, transit ? closingDate : null,
                    cutoff, billedBasis, live ? index.accruals(order.id()) : Map.of(), treatments);
            LotCost.Container cost = lotCosts.get().forContainer(order, options, productsById);

            Supplier supplier = order.supplierId() == null || !suppliers.isResolvable() ? null : suppliers.get().find(order.supplierId());
            StockClosingContainerEntity row = containerRow(order, role, border, cost, supplier,
                    order.partnerCustomerId() == null ? null : customerNames.get(order.partnerCustomerId()), index);
            containerRows.add(row);
            List<StockClosingLotEntity> ownLots = new ArrayList<>();
            lotRows.put(row, ownLots);
            for (LotCost.Lot lot : cost.lots()) {
                StockClosingLotEntity lotRow = lotRow(order, role, lot);
                String key = order.id() + ":" + lot.productId();
                StockClosingLotEntity lastYear = lotBefore.get(key);
                if (ROLE_PREVIOUS.equals(role)) {
                    lotRow.previousUnitValueEur = frozenUnit.get(key);
                    lotRow.previousClosingId = previous.id;
                    if (lotRow.previousUnitValueEur != null && lotRow.previousUnitValueEur.compareTo(lot.unitValueEur()) != 0) {
                        changedLots++;
                        changedValue = changedValue.add(lot.unitValueEur().subtract(lotRow.previousUnitValueEur)
                                .multiply(BigDecimal.valueOf(frozenQuantity.getOrDefault(key, 0))));
                    }
                } else if (lastYear != null) {
                    lotRow.previousUnitValueEur = lastYear.unitValueEur;
                    lotRow.previousClosingId = previous.id;
                }
                ownLots.add(lotRow);
                FifoValuer.Lot valued = new FifoValuer.Lot(order.id(), order.number(), order.displayName(), order.receivedOn(),
                        lot.productId(), lot.capacity(), lot.unitValueEur(), lot.unitGoodsEur(), lot.unitTransportEur(),
                        lot.unitLogisticsEur(), lot.unitSeparateEur(), lot.unitEstimatedEur());
                if (ROLE_OWN.equals(role) && lot.capacity() > 0) periodLots.add(valued);
                if (ROLE_PARTNER.equals(role)) partnerLots.add(partnerLot(order, valued, row.partnerName, sales, cutoff, index));
                if (transit) separateRows.add(transitRow(order, row, lot, payments, closingDate, index));
            }
            if (!live) continue;

            Map<String, BigDecimal> normalisedPaid = new HashMap<>();
            for (PurchasePayment payment : LotCostCalculator.normalised(order, payments)) {
                if (payment.amountEur() != null) normalisedPaid.merge(payment.payee().name(), payment.amountEur(), BigDecimal::add);
            }
            notices.container(order.id(), order.displayName(), cost, billed != null, normalisedPaid);
            if (transit && index.container(ClosingDecisionKind.TRANSIT, order.id()) == null) {
                notices.forContainer(true, "BESLISSING_ONDERWEG", ClosingNotices.SEGMENT_SEPARATE, order.id(),
                        "Container " + order.displayName() + " was onderweg op de afsluitdatum: beslis of hij opgenomen wordt.");
            }
            if (ROLE_OWN.equals(role) && previous != null && !order.receivedOn().isAfter(previous.closingDate)) {
                notices.forContainer(false, "LAAT_ONTVANGEN_VORIG_JAAR", ClosingNotices.SEGMENT_VALUE, order.id(),
                        "Container " + order.displayName() + ": ontvangen op " + ClosingNotices.day(order.receivedOn())
                                + ", niet in de afsluiting van " + previous.closingYear + ": opgenomen als partij van dit jaar.");
            }
        }
        if (changedLots > 0) {
            notices.amount("PARTIJ_GEWIJZIGD", ClosingNotices.SEGMENT_VALUE, changedValue, changedLots
                    + " partijen hebben nu een andere waarde per stuk dan in de afsluiting van " + previous.closingYear
                    + ": verschil € " + ClosingNotices.euro(changedValue) + ", niet verwerkt.");
        }

        /* ---- 3. invoices that were sent and not afgepunt */
        SalesAdvanceBillingService.Views billing = advanceBilling.isResolvable() ? advanceBilling.get().views(sales) : null;
        ClosingSeparations.Invoices invoices = ClosingSeparations.invoices(sales, order -> advance(billing, order),
                closing.closingYear, closingDate, cutoff, previous == null ? null : previous.closingDate);
        List<FifoValuer.Invoice> invoiced = new ArrayList<>();
        for (SalesOrder invoice : invoices.candidates()) {
            invoiced.add(new FifoValuer.Invoice(invoice.id(), invoice.number(), invoice.orderDate(),
                    customerNames.get(invoice.customerId()), ClosingSeparations.productQuantities(invoice),
                    decidedOf(index.invoice(invoice.id())), ClosingSeparations.alreadyOut(invoice, roll.rows())));
        }
        for (SalesOrder invoice : invoices.older()) {
            StockClosingSeparateEntity row = new StockClosingSeparateEntity();
            row.kind = FifoValuer.KIND_OLDER;
            row.salesOrderId = invoice.id();
            row.documentNumber = cut(invoice.number(), 120);
            row.documentDate = invoice.orderDate();
            row.counterparty = cut(customerNames.get(invoice.customerId()), 255);
            row.quantity = ClosingSeparations.productQuantities(invoice).values().stream().mapToInt(Integer::intValue).sum();
            row.automatic = false;
            separateRows.add(row);
        }

        /* ---- 4. FIFO per product */
        Map<Long, FifoValuer.Article> valuedProducts = new TreeMap<>();
        for (Product product : productsById.values()) {
            valuedProducts.put(product.id(), new FifoValuer.Article(product.id(), cut(product.sku(), 120),
                    cut(product.nameWithColour(), 255),
                    product.categoryId() == null ? null : cut(categoryNames.get(product.categoryId()), 255),
                    cut(product.packaging().unitKey(), 32), product.packaging().salesUnit().name(),
                    product.packaging().isPresent() ? product.packaging().unitPieces() : null, product.demo(), product.active()));
        }
        Map<Long, List<FifoValuer.LocationQuantity>> quantities = new TreeMap<>();
        for (StockRoll.Position position : roll.positions()) {
            quantities.computeIfAbsent(position.productId(), key -> new ArrayList<>()).add(new FifoValuer.LocationQuantity(
                    position.locationId(), position.anchorQuantity(), position.rollDelta(), position.closingQuantity()));
        }
        List<FifoValuer.ThirdParty> thirdParties = index.kind(ClosingDecisionKind.THIRD_PARTY).stream()
                .filter(decision -> decision.productId != null && decision.quantity != null)
                .map(decision -> new FifoValuer.ThirdParty(decision.id, decision.productId, decision.quantity,
                        decision.counterparty, decision.reason, decision.decidedByName, decision.decidedAt)).toList();
        List<FifoValuer.WriteDown> lowered = index.kind(ClosingDecisionKind.WRITE_DOWN).stream()
                .filter(decision -> decision.productId != null)
                .map(decision -> new FifoValuer.WriteDown(decision.id, decision.productId, decision.quantity,
                        decision.unitValueEur, decision.reasonCode, decision.reason, decision.decidedByName, decision.decidedAt))
                .toList();
        List<FifoValuer.Opening> openings = openingLayers.active().stream()
                .map(layer -> new FifoValuer.Opening(layer.id, layer.productId, layer.quantity, layer.unitValueEur,
                        layer.asOfDate, layer.source)).toList();
        Map<Long, Integer> closingYears = new HashMap<>();
        closings.listAll().forEach(row -> closingYears.put(row.id, row.closingYear));
        Map<Long, BigDecimal> previousWriteDowns = new HashMap<>();
        if (previous != null) {
            articles.list("closingId", previous.id).forEach(article -> previousWriteDowns.put(article.productId, article.writeDownEur));
        }
        FifoValuer.Result valued = FifoValuer.value(new FifoValuer.Input(closingDate, previous == null ? null : previous.closingDate,
                previous == null ? null : previous.id, valuedProducts, quantities, thirdParties, partnerLots, periodLots,
                carried, openings, consumedOpenings(chain), invoiced, lowered, closingYears, previousWriteDowns));
        separateRows.addAll(valued.separates());

        /* ---- 5. the stored rows */
        List<StockClosingMovementEntity> keptMovements = roll.rows().stream().map(StockRoll.Row::row).toList();
        deleteOutput(closingId);
        movements.delete("closingId", closingId);
        entities.flush();
        for (StockClosingMovementEntity row : keptMovements) {
            row.id = null;
            row.closingId = closingId;
            movements.persist(row);
        }
        Map<String, StockClosingLotEntity> lotByKey = new HashMap<>();
        List<StockClosingLotEntity> allLots = new ArrayList<>();
        for (StockClosingContainerEntity row : containerRows) {
            row.closingId = closingId;
            containers.persist(row);
        }
        containers.flush();
        for (Map.Entry<StockClosingContainerEntity, List<StockClosingLotEntity>> entry : lotRows.entrySet()) {
            for (StockClosingLotEntity lot : entry.getValue()) {
                lot.closingId = closingId;
                lot.containerId = entry.getKey().id;
                lots.persist(lot);
                allLots.add(lot);
                lotByKey.put(lot.role + ":" + lot.purchaseOrderId + ":" + lot.productId, lot);
            }
        }
        lots.flush();
        for (StockClosingLayerEntity layer : valued.layers()) {
            layer.closingId = closingId;
            if (layer.purchaseOrderId != null) {
                StockClosingLotEntity lot = lotByKey.get((FifoValuer.SOURCE_PREVIOUS.equals(layer.source) ? ROLE_PREVIOUS : ROLE_OWN)
                        + ":" + layer.purchaseOrderId + ":" + layer.productId);
                layer.lotId = lot == null ? null : lot.id;
            }
            layers.persist(layer);
        }
        for (StockClosingArticleEntity article : valued.articles()) {
            article.closingId = closingId;
            articles.persist(article);
        }
        Map<String, FifoValuer.LocationValue> valueAt = new HashMap<>();
        valued.locations().forEach(value -> valueAt.put(value.productId() + ":" + value.locationId(), value));
        List<StockClosingLineEntity> lineRows = new ArrayList<>();
        for (StockRoll.Position position : roll.positions()) {
            StockClosingLineEntity row = lineRow(position, productsById.get(position.productId()),
                    position.countLineId() == null ? null : countLineById.get(position.countLineId()),
                    valueAt.get(position.productId() + ":" + position.locationId()));
            row.closingId = closingId;
            lines.persist(row);
            lineRows.add(row);
        }
        for (StockClosingSeparateEntity row : separateRows) {
            row.closingId = closingId;
            separates.persist(row);
        }
        for (StockClosingWriteDownEntity row : valued.writeDowns()) {
            row.closingId = closingId;
            writeDowns.persist(row);
        }

        /* ---- 6. totals and notices */
        closing.costValueEur = sum(valued.articles(), article -> article.costValueEur);
        closing.writeDownEur = sum(valued.articles(), article -> article.writeDownEur);
        closing.ownValueEur = closing.costValueEur.subtract(closing.writeDownEur);
        closing.demoValueEur = sum(valued.articles().stream().filter(article -> Boolean.TRUE.equals(article.demo)).toList(),
                article -> article.ownValueEur);
        closing.partnerIncludedEur = sum(separateRows, row -> FifoValuer.KIND_PARTNER.equals(row.kind) && Boolean.TRUE.equals(row.included) ? row.valueEur : null);
        closing.partnerExcludedEur = sum(separateRows, row -> FifoValuer.KIND_PARTNER.equals(row.kind) && !Boolean.TRUE.equals(row.included) ? row.valueEur : null);
        closing.transitIncludedEur = sum(separateRows, row -> FifoValuer.KIND_TRANSIT.equals(row.kind) && Boolean.TRUE.equals(row.included) ? row.valueEur : null);
        closing.transitExcludedEur = sum(separateRows, row -> FifoValuer.KIND_TRANSIT.equals(row.kind) && !Boolean.TRUE.equals(row.included) ? row.valueEur : null);
        closing.invoicedOutEur = sum(valued.layers(), layer -> FifoValuer.BLOCK_INVOICED.equals(layer.block) ? layer.valueEur : null);
        closing.totalValueEur = closing.ownValueEur.add(closing.partnerIncludedEur).add(closing.transitIncludedEur);
        closing.estimatedEur = sum(valued.articles(), article -> article.estimatedEur)
                .add(sum(separateRows, row -> Boolean.TRUE.equals(row.included)
                        && (FifoValuer.KIND_PARTNER.equals(row.kind) || FifoValuer.KIND_TRANSIT.equals(row.kind)) ? row.estimatedEur : null));
        closing.ownQuantity = valued.articles().stream().mapToInt(article -> article.ownQuantity).sum();
        closing.unvaluedQuantity = valued.articles().stream().mapToInt(article -> article.unvaluedQuantity).sum();

        valuationNotices(closing, notices, index, valued, valuedProducts, roll, containerRows, separateRows, containerNames, previous);
        if (!index.vatConfirmed()) {
            notices.block("BTW_BEVESTIGING", ClosingNotices.SEGMENT_FINALIZE, "Bevestig dat de betalingen onder Leverancier,"
                    + " Douane & transport en Inspectie & andere kosten zonder aftrekbare btw zijn ingevoerd.");
        }
        if (!containerRows.isEmpty()) {
            notices.warn("KOERS_INGEVOERD", ClosingNotices.SEGMENT_FINALIZE,
                    "De koersen zijn op de container ingevoerd en blijven wijzigbaar tot de afsluiting definitief is.");
        }
        if (closing.supersedesId != null) correctionNotices(closing, notices, valued, allLots, containerRows, keptMovements);

        List<Notice> raised = notices.list();
        closing.noticesJson = ClosingNotices.toJson(raised);
        closing.blockerCount = (int) raised.stream().filter(Notice::blocker).count();
        closing.warningCount = raised.size() - closing.blockerCount;
        closing.computedAt = now;
        closing.computedBy = actor.current().username();
        closing.dataSha256 = ClosingHash.sha256(new ClosingHash.Rows(closing, lineRows, valued.layers(), allLots, containerRows,
                valued.articles(), separateRows, valued.writeDowns(), keptMovements, decided, raised));
        entities.flush();
    }

    private void deleteOutput(long closingId) {
        lines.delete("closingId", closingId);
        layers.delete("closingId", closingId);
        lots.delete("closingId", closingId);
        containers.delete("closingId", closingId);
        articles.delete("closingId", closingId);
        separates.delete("closingId", closingId);
        writeDowns.delete("closingId", closingId);
    }

    /** Step 1 of a compute: the anchors, the stock book of the window and the reviewed list. */
    private StockRoll.Result roll(StockClosingEntity closing, Instant cutoff, Instant now, Map<Long, Product> productsById,
                                  List<PurchaseOrder> orders, List<SalesOrder> sales, Index index, ClosingNotices notices) {
        StockService stockBook = stock.get();
        Map<Long, StockCountService.Anchor> anchors = counts.anchors(closing.closingYear).stream()
                .collect(Collectors.toMap(StockCountService.Anchor::locationId, Function.identity()));
        Map<Long, Map<Long, Integer>> levels = new HashMap<>();
        for (StockLevel level : stockBook.allLevels()) {
            if (!productsById.containsKey(level.productId())) {
                if (level.quantity() != 0) {
                    notices.forProduct(false, "VERWIJDERD_PRODUCT", ClosingNotices.SEGMENT_COUNT, level.productId(),
                            level.location().id(), "Voorraadstand van een verwijderd product (id " + level.productId() + ", "
                                    + level.quantity() + " stuks) is niet opgenomen.");
                }
                continue;
            }
            levels.computeIfAbsent(level.location().id(), key -> new HashMap<>()).put(level.productId(), level.quantity());
        }

        /* One read of the stock book covers every window and tells which uncounted location needs a count. */
        Instant yearStart = LocalDate.of(closing.closingYear, 1, 1).atStartOfDay(InventoryClock.BRUSSELS).toInstant();
        Instant from = yearStart.isBefore(cutoff) ? yearStart : cutoff;
        for (StockCountService.Anchor anchor : anchors.values()) {
            for (StockCountLineEntity line : anchor.lines()) {
                if (line.bookedAt != null && line.bookedAt.isBefore(from)) from = line.bookedAt;
            }
            if (anchor.base().bookedAt != null && anchor.base().bookedAt.isBefore(from)) from = anchor.base().bookedAt;
        }
        List<StockMovement> book = stockBook.movementsBetween(from, now.plus(1, ChronoUnit.DAYS));
        Set<Long> movedSinceNewYear = book.stream().filter(movement -> movement.locationId() != null && !movement.at().isBefore(yearStart))
                .map(StockMovement::locationId).collect(Collectors.toSet());

        List<StockRoll.Location> locations = new ArrayList<>();
        for (StockLocation location : stockBook.locations()) {
            StockCountService.Anchor anchor = anchors.get(location.id());
            if (anchor != null) {
                Map<Long, String> refs = new HashMap<>();
                refs.put(anchor.base().id, anchor.base().ledgerRef);
                anchor.corrections().forEach(correction -> refs.put(correction.id, correction.ledgerRef));
                List<StockRoll.BookedLine> booked = anchor.lines().stream()
                        .filter(line -> line.bookedAt != null && line.bookedQuantity != null)
                        .map(line -> new StockRoll.BookedLine(line.countId, line.id, line.productId, line.bookedQuantity, line.bookedAt))
                        .toList();
                locations.add(new StockRoll.Location(location.id(), location.name(), StockRoll.ANCHOR_COUNT, anchor.base().id,
                        refs, booked, null, anchor.base().bookedAt));
                continue;
            }
            Map<Long, Integer> held = levels.getOrDefault(location.id(), Map.of());
            boolean needsCount = held.values().stream().anyMatch(quantity -> quantity != 0)
                    || movedSinceNewYear.contains(location.id());
            if (!needsCount) continue;
            /* Without a count the concept shows the level of the book, and cannot become final. */
            notices.forLocation("TELLING_ONTBREEKT", location.id(),
                    location.name() + " is voor " + closing.closingYear + " nog niet geteld.");
            locations.add(new StockRoll.Location(location.id(), location.name(), StockRoll.ANCHOR_BOOK, null, null, null, held, now));
        }

        Map<String, StockRoll.Receipt> receipts = new HashMap<>();
        for (PurchaseOrder order : orders) {
            if (order.number() == null || order.number().isBlank()) continue;
            StockRoll.Receipt receipt = new StockRoll.Receipt(order.id(), order.receivedOn());
            receipts.put(order.number(), receipt);
            receipts.put(order.number() + " correctie", receipt);
        }
        Map<String, LocalDate> invoiceDates = new HashMap<>();
        for (SalesOrder order : sales) {
            if (order.isInvoice() && order.number() != null && order.orderDate() != null) invoiceDates.put(order.number(), order.orderDate());
        }
        Map<Long, StockRoll.Flip> flips = new HashMap<>();
        for (StockClosingDecisionEntity decision : index.kind(ClosingDecisionKind.MOVEMENT)) {
            if (decision.movementId != null) flips.put(decision.movementId, new StockRoll.Flip(Boolean.TRUE.equals(decision.flag), decision.reason));
        }
        Map<Long, StockRoll.Named> names = new HashMap<>();
        productsById.values().forEach(product -> names.put(product.id(),
                new StockRoll.Named(cut(product.sku(), 120), cut(product.nameWithColour(), 255))));

        /* Which lines of the previous compute are still in the stock book: a vanished one stays listed. */
        List<StockClosingMovementEntity> previousRows = movements.list("closingId", closing.id);
        Set<Long> inBook = book.stream().map(StockMovement::id).collect(Collectors.toCollection(HashSet::new));
        List<StockClosingMovementEntity> outside = previousRows.stream()
                .filter(row -> row.bookedAt != null && !inBook.contains(row.movementId)).toList();
        if (!outside.isEmpty()) {
            Instant first = outside.stream().map(row -> row.bookedAt).min(Comparator.naturalOrder()).orElseThrow();
            Instant last = outside.stream().map(row -> row.bookedAt).max(Comparator.naturalOrder()).orElseThrow();
            stockBook.movementsBetween(first.minusSeconds(1), last.plusSeconds(1)).forEach(movement -> inBook.add(movement.id()));
        }

        StockRoll.Result roll = StockRoll.roll(new StockRoll.Input(closing.closingDate, cutoff, locations, names, book,
                (productId, locationId, before) -> stockBook.lastMovementBefore(productId, locationId, before)
                        .map(StockMovement::quantityAfter).orElse(null),
                receipts, invoiceDates, flips, previousRows, inBook));

        Map<Long, String> lateReceipts = new TreeMap<>();
        int toReview = 0;
        List<String> vanished = new ArrayList<>();
        for (StockRoll.Row row : roll.rows()) {
            if (Boolean.TRUE.equals(row.row().removed)) {
                vanished.add(row.row().refText == null || row.row().refText.isBlank() ? "#" + row.row().movementId : row.row().refText);
                continue;
            }
            if (Boolean.TRUE.equals(row.row().review) && !flips.containsKey(row.row().movementId)) toReview++;
            if (row.receivedBeforeCount() && row.purchaseOrderId() != null) {
                PurchaseOrder order = orders.stream().filter(candidate -> candidate.id().equals(row.purchaseOrderId())).findFirst().orElseThrow();
                lateReceipts.putIfAbsent(order.id(), "Container " + order.displayName() + " (ontvangen "
                        + ClosingNotices.day(order.receivedOn()) + ") is pas na de telling bijgeboekt. Kijk na of die stuks geteld zijn.");
            }
        }
        if (toReview > 0) {
            notices.warn("BEWEGING_NAKIJKEN", ClosingNotices.SEGMENT_DATE,
                    toReview + " bewegingen rond de afsluitdatum moet je nog nakijken.");
        }
        if (!vanished.isEmpty()) {
            notices.warn("BEWEGING_VERDWENEN", ClosingNotices.SEGMENT_DATE, cut(vanished.size()
                    + " bewegingen uit de vorige berekening staan niet meer in de voorraadgeschiedenis: "
                    + String.join(", ", vanished) + ".", 2000));
        }
        lateReceipts.forEach((orderId, message) ->
                notices.forContainer(false, "ONTVANGST_NA_TELLING", ClosingNotices.SEGMENT_DATE, orderId, message));
        return roll;
    }

    /** The notices that follow from the valued figures. */
    private void valuationNotices(StockClosingEntity closing, ClosingNotices notices, Index index, FifoValuer.Result valued,
                                  Map<Long, FifoValuer.Article> named, StockRoll.Result roll,
                                  List<StockClosingContainerEntity> containerRows, List<StockClosingSeparateEntity> separateRows,
                                  Map<Long, String> containerNames, StockClosingEntity previous) {
        for (StockRoll.Key key : valued.negativeLocations()) {
            StockRoll.Position position = roll.position(key.productId(), key.locationId());
            notices.forProduct(true, "NEGATIEF", ClosingNotices.SEGMENT_DATE, key.productId(), key.locationId(),
                    named.get(key.productId()).name() + " staat op " + position.locationName() + " op " + position.closingQuantity()
                            + " op de afsluitdatum. Verkopen worden altijd uit het magazijn afgeboekt, ook als de stuks op een"
                            + " verkooppunt lagen. Los dit op met een verplaatsing en corrigeer de telling voor dat product,"
                            + " of pas hieronder aan welke bewegingen meetellen.");
        }
        long unvaluedProducts = valued.articles().stream().filter(article -> article.unvaluedQuantity > 0).count();
        if (unvaluedProducts > 0) {
            notices.block("ZONDER_WAARDE", ClosingNotices.SEGMENT_VALUE, unvaluedProducts + " producten (" + closing.unvaluedQuantity
                    + " stuks) zonder gewaardeerde partij. Vul een beginwaarde met bron in.");
        }
        for (Long productId : valued.writeDownExcess()) {
            notices.forProduct(true, "AFWAARDERING_TE_VEEL", ClosingNotices.SEGMENT_VALUE, productId, null,
                    named.get(productId).name() + ": de aantallen met een waardevermindering zijn samen meer dan de eigen voorraad.");
        }
        for (Long productId : valued.thirdPartyExcess()) {
            notices.forProduct(true, "DERDEN_TE_VEEL", ClosingNotices.SEGMENT_SEPARATE, productId, null,
                    named.get(productId).name() + ": meer goederen van derden dan er geteld zijn.");
        }
        Set<Long> partnersToDecide = new LinkedHashSet<>();
        Set<Long> invoicesToDecide = new LinkedHashSet<>();
        Map<Long, String> invoiceNumbers = new HashMap<>();
        for (StockClosingSeparateEntity row : separateRows) {
            if (FifoValuer.KIND_PARTNER.equals(row.kind) && row.quantity != null && row.quantity > 0
                    && index.container(ClosingDecisionKind.PARTNER_CONTAINER, row.purchaseOrderId) == null) {
                partnersToDecide.add(row.purchaseOrderId);
            }
            if (FifoValuer.KIND_INVOICED.equals(row.kind) && !Boolean.TRUE.equals(row.automatic)
                    && index.invoice(row.salesOrderId) == null) {
                invoicesToDecide.add(row.salesOrderId);
                invoiceNumbers.put(row.salesOrderId, row.documentNumber);
            }
        }
        for (Long orderId : partnersToDecide) {
            notices.forContainer(true, "BESLISSING_PARTNER", ClosingNotices.SEGMENT_SEPARATE, orderId,
                    "Partnercontainer " + containerNames.get(orderId) + ": beslis of hij opgenomen wordt.");
        }
        for (Long invoiceId : invoicesToDecide) {
            notices.forInvoice("BESLISSING_GEFACTUREERD", invoiceId, "Factuur " + invoiceNumbers.get(invoiceId)
                    + " is gefactureerd maar nog niet afgepunt: geef aan of de stuks er op de afsluitdatum nog lagen.");
        }

        List<StockClosingContainerEntity> valuedContainers = containerRows.stream()
                .filter(row -> !ROLE_PREVIOUS.equals(row.role)).toList();
        if (closing.estimatedEur.signum() > 0) {
            long estimated = valuedContainers.stream().filter(row -> row.estimatedEur != null && row.estimatedEur.signum() > 0).count();
            notices.amount("GESCHAT", ClosingNotices.SEGMENT_VALUE, closing.estimatedEur, estimated
                    + " containers met geschatte kosten: € " + ClosingNotices.euro(closing.estimatedEur) + " in de voorraadwaarde.");
        }
        BigDecimal other = sum(valuedContainers, row -> row.otherExcludedEur);
        if (other.signum() > 0) {
            notices.amount("BIJKOMENDE_KOSTEN", ClosingNotices.SEGMENT_VALUE, other, "€ " + ClosingNotices.euro(other)
                    + " bank- en betalingskosten en andere bedragen onder 'Bijkomende kosten' zijn niet opgenomen."
                    + " Hoort een bedrag bij de zending, zet het dan op de container onder 'Inspectie & andere kosten'."
                    + " Btw die je terugkrijgt hoort hier wel.");
        }
        BigDecimal exchange = sum(valuedContainers, row -> row.exchangeDifferenceEur);
        if (exchange.signum() != 0) {
            notices.amount("KOERSVERSCHIL", ClosingNotices.SEGMENT_VALUE, exchange,
                    "€ " + ClosingNotices.euro(exchange) + " koersverschil staat buiten de voorraadwaarde.");
        }
        for (FifoValuer.Opening opening : valued.unusedOpenings()) {
            notices.forProduct(false, "BEGINWAARDE_BUITEN_PERIODE", ClosingNotices.SEGMENT_VALUE, opening.productId(), null,
                    "Beginwaarde van " + named.get(opening.productId()).name() + " per " + ClosingNotices.day(opening.asOfDate())
                            + " ligt niet na de vorige afsluitdatum en wordt niet gebruikt.");
        }
        Set<Long> loweredProducts = index.kind(ClosingDecisionKind.WRITE_DOWN).stream().map(decision -> decision.productId)
                .collect(Collectors.toSet());
        long fullDemo = valued.articles().stream().filter(article -> Boolean.TRUE.equals(article.demo)
                && article.ownQuantity > 0 && !loweredProducts.contains(article.productId)).count();
        if (fullDemo > 0) {
            notices.warn("DEMO_VOL", ClosingNotices.SEGMENT_VALUE, fullDemo + " demoproducten staan aan volle aanschafwaarde.");
        }
        List<StockClosingArticleEntity> notAgain = valued.articles().stream()
                .filter(article -> article.previousWriteDownEur != null && article.previousWriteDownEur.signum() > 0
                        && article.writeDownEur.signum() == 0).toList();
        if (!notAgain.isEmpty()) {
            BigDecimal lastYear = sum(notAgain, article -> article.previousWriteDownEur);
            notices.amount("VORIG_AFGEWAARDEERD", ClosingNotices.SEGMENT_VALUE, lastYear, "Vorig jaar een waardevermindering,"
                    + " dit jaar niet: " + notAgain.size() + " producten, € " + ClosingNotices.euro(lastYear) + ".");
        }
        for (FifoValuer.WriteDown decision : valued.withoutEffect()) {
            FifoValuer.Article article = named.get(decision.productId());
            notices.forProduct(false, "AFWAARDERING_ZONDER_EFFECT", ClosingNotices.SEGMENT_VALUE, decision.productId(), null,
                    (article == null ? "Product " + decision.productId() : article.name())
                            + ": de marktwaarde ligt niet onder de aanschafwaarde; er is geen waardevermindering.");
        }
        BigDecimal apart = closing.partnerIncludedEur.add(closing.transitIncludedEur);
        if (apart.signum() > 0) {
            notices.amount("APART_ZONDER_MARKTTOETS", ClosingNotices.SEGMENT_SEPARATE, apart, "Op opgenomen partnercontainers"
                    + " en goederen onderweg (€ " + ClosingNotices.euro(apart) + ") is geen lagere marktwaarde ingevoerd.");
        }
    }

    /** What a correction says about the version it replaces and about a later year that was built on it. */
    private void correctionNotices(StockClosingEntity closing, ClosingNotices notices, FifoValuer.Result valued,
                                   List<StockClosingLotEntity> lotRows, List<StockClosingContainerEntity> containerRows,
                                   List<StockClosingMovementEntity> movementRows) {
        List<StockClosingEntity> all = closings.listAll();
        StockClosingEntity replaced = all.stream().filter(row -> row.id.equals(closing.supersedesId)).findFirst().orElse(null);
        if (replaced == null) return;
        ClosingVersionDiff.Changes changes = ClosingVersionDiff.between(
                diffRows(closing, valued.articles(), lotRows, containerRows, movementRows, valued.layers()), storedDiffRows(replaced));
        if (!changes.isEmpty()) {
            notices.warn("VERSCHIL_MET_VORIGE_VERSIE", ClosingNotices.SEGMENT_FINALIZE, "Tegenover versie " + replaced.versionNo + ": "
                    + changes.articles().size() + " producten, " + changes.lots().size() + " partijen, "
                    + changes.movements().size() + " bewegingen en " + changes.openingLayers().size() + " beginwaarden anders."
                    + " Totaal € " + ClosingNotices.euro(replaced.totalValueEur) + " → € " + ClosingNotices.euro(closing.totalValueEur) + ".");
        }
        Set<Long> ownVersions = all.stream().filter(row -> Objects.equals(row.closingYear, closing.closingYear))
                .map(row -> row.id).collect(Collectors.toSet());
        for (StockClosingEntity later : all) {
            if (!STATUS_FINAL.equals(later.status) || later.supersededById != null || later.previousClosingId == null
                    || !ownVersions.contains(later.previousClosingId) || ownVersions.contains(later.id)) continue;
            StockClosingEntity built = all.stream().filter(row -> row.id.equals(later.previousClosingId)).findFirst().orElseThrow();
            notices.warn("LATER_JAAR_AFGESLOTEN", ClosingNotices.SEGMENT_FINALIZE, "Boekjaar " + later.closingYear
                    + " is afgesloten op versie " + built.versionNo + " van dit boekjaar. Maak daarna ook van "
                    + later.closingYear + " een nieuwe versie.");
        }
        if (closing.previousClosingId != null && replaced.previousClosingId != null
                && !closing.previousClosingId.equals(replaced.previousClosingId)) {
            StockClosingEntity now = all.stream().filter(row -> row.id.equals(closing.previousClosingId)).findFirst().orElseThrow();
            StockClosingEntity then = all.stream().filter(row -> row.id.equals(replaced.previousClosingId)).findFirst().orElse(null);
            notices.warn("VORIGE_VERSIE_VERVANGEN", ClosingNotices.SEGMENT_FINALIZE, "Deze versie steunt op versie " + now.versionNo
                    + " van " + now.closingYear + "; de vorige versie steunde op versie " + (then == null ? "?" : then.versionNo) + ".");
        }
    }

    private static ClosingVersionDiff.Rows diffRows(StockClosingEntity closing, List<StockClosingArticleEntity> articleRows,
                                                   List<StockClosingLotEntity> lotRows, List<StockClosingContainerEntity> containerRows,
                                                   List<StockClosingMovementEntity> movementRows, List<StockClosingLayerEntity> layerRows) {
        return new ClosingVersionDiff.Rows(closing, articleRows, lotRows, containerRows, movementRows, layerRows);
    }

    /** The stored rows of a closing in the shape the version comparison reads. */
    @Transactional
    public ClosingVersionDiff.Rows storedDiffRows(StockClosingEntity closing) {
        return new ClosingVersionDiff.Rows(closing, articles.list("closingId", closing.id), lots.list("closingId", closing.id),
                containers.list("closingId", closing.id), movements.list("closingId", closing.id), layers.list("closingId", closing.id));
    }

    /* ------------------------------------------------------------------- rows */

    private static StockClosingContainerEntity containerRow(PurchaseOrder order, String role, Border border, LotCost.Container cost,
                                                            Supplier supplier, String partnerName, Index index) {
        StockClosingContainerEntity row = new StockClosingContainerEntity();
        row.purchaseOrderId = order.id();
        row.orderNumber = cut(order.number(), 120);
        row.displayName = cut(order.displayName(), 255);
        row.supplierName = supplier == null ? null : cut(supplier.name(), 255);
        row.role = role;
        row.partnerName = cut(partnerName, 255);
        row.orderDate = order.orderDate();
        row.shippedOn = order.shippedOn();
        row.receivedOn = order.receivedOn();
        row.rateCutoffDate = border.date();
        row.rateCutoffSource = border.source();
        row.supplierIncoterm = supplier == null || supplier.incoterm() == null || supplier.incoterm().isBlank()
                ? null : cut(supplier.incoterm().strip(), 120);
        row.quantityBasis = cost.quantityBasis().name();
        row.billedBasis = cost.billedBasis().name();
        row.cnyToUsd = order.cnyToUsd();
        row.usdToEurGoods = order.usdToEurGoods();
        row.usdToEurTransport = order.usdToEurTransport();
        row.cif = cost.cif();
        row.groupVariants = cost.groupVariants();
        row.separateInPiecePrice = cost.separateInPiecePrice();
        row.allocOrigin = cost.allocOrigin();
        row.allocFreight = cost.allocFreight();
        row.allocDestination = cost.allocDestination();
        row.allocSeparate = cost.allocSeparate();
        LotCost.StreamCost supplierStream = cost.stream(PurchasePayment.Payee.SUPPLIER);
        row.supplierStatus = supplierStream.status();
        row.supplierPlannedEur = supplierStream.plannedEur();
        row.supplierPaidEur = supplierStream.paidEur();
        row.supplierOpenEur = supplierStream.openEur();
        row.supplierIncludedEur = supplierStream.includedEur();
        row.supplierEstimatedEur = supplierStream.estimatedEur();
        row.supplierGoodsEur = cost.supplierGoodsEur();
        row.supplierTransportEur = cost.supplierTransportEur();
        LotCost.StreamCost logistics = cost.stream(PurchasePayment.Payee.LOGISTICS);
        row.logisticsStatus = logistics.status();
        row.logisticsPlannedEur = logistics.plannedEur();
        row.logisticsPaidEur = logistics.paidEur();
        row.logisticsOpenEur = logistics.openEur();
        row.logisticsIncludedEur = logistics.includedEur();
        row.logisticsEstimatedEur = logistics.estimatedEur();
        LotCost.StreamCost separate = cost.stream(PurchasePayment.Payee.SEPARATE);
        row.separateStatus = separate.status();
        row.separatePlannedEur = separate.plannedEur();
        row.separatePaidEur = separate.paidEur();
        row.separateOpenEur = separate.openEur();
        row.separateIncludedEur = separate.includedEur();
        row.separateEstimatedEur = separate.estimatedEur();
        row.otherExcludedEur = cost.otherExcludedEur();
        row.priceCreditEur = cost.priceCreditEur();
        row.lossCreditEur = cost.lossCreditEur();
        row.exchangeDifferenceEur = cost.exchangeDifferenceEur();
        row.enrosedCostExcludedEur = cost.enrosedCostExcludedEur();
        row.acquisitionEur = cost.acquisitionEur();
        row.estimatedEur = cost.estimatedEur();
        row.paymentsJson = paymentsJson(cost.payments());
        row.creditsJson = creditsJson(order.id(), cost.credits(), ROLE_PREVIOUS.equals(role) ? Index.of(List.of()) : index);
        row.notes = cost.notes().isEmpty() ? null : cut(String.join("\n", cost.notes()), 2000);
        return row;
    }

    private static StockClosingLotEntity lotRow(PurchaseOrder order, String role, LotCost.Lot lot) {
        StockClosingLotEntity row = new StockClosingLotEntity();
        row.purchaseOrderId = order.id();
        row.role = role;
        row.productId = lot.productId();
        row.sku = cut(lot.sku(), 120);
        row.productName = cut(lot.productName(), 255);
        row.orderedQuantity = lot.orderedQuantity();
        row.receivedQuantity = lot.receivedQuantity();
        row.damagedQuantity = lot.damagedQuantity();
        row.laterLostQuantity = lot.laterLostQuantity();
        row.billedQuantity = lot.billedQuantity();
        row.goodsDivisor = lot.goodsDivisor();
        row.costDivisor = lot.costDivisor();
        row.capacity = lot.capacity();
        row.unitPriceEur = lot.unitPriceEur();
        row.goodsKeyEur = lot.goodsKey();
        row.transportKeyEur = lot.transportKey();
        row.logisticsKeyEur = lot.logisticsKey();
        row.separateKeyEur = lot.separateKey();
        row.goodsEur = lot.goodsEur();
        row.priceCreditEur = lot.priceCreditEur();
        row.transportEur = lot.transportEur();
        row.logisticsEur = lot.logisticsEur();
        row.separateEur = lot.separateEur();
        row.lotCostEur = lot.lotCostEur();
        row.estimatedEur = lot.estimatedEur();
        row.unitGoodsEur = lot.unitGoodsEur();
        row.unitTransportEur = lot.unitTransportEur();
        row.unitLogisticsEur = lot.unitLogisticsEur();
        row.unitSeparateEur = lot.unitSeparateEur();
        row.unitValueEur = lot.unitValueEur();
        row.unitEstimatedEur = lot.unitEstimatedEur();
        row.calcOriginEur = lot.calcOriginEur();
        row.calcFreightEur = lot.calcFreightEur();
        row.calcDutyEur = lot.calcDutyEur();
        row.calcDestinationEur = lot.calcDestinationEur();
        row.calcDutyRatePct = lot.calcDutyRatePct();
        row.status = lot.status().name();
        return row;
    }

    private static StockClosingLineEntity lineRow(StockRoll.Position position, Product product, StockCountLineEntity counted,
                                                  FifoValuer.LocationValue value) {
        StockClosingLineEntity row = new StockClosingLineEntity();
        row.productId = position.productId();
        row.sku = product == null ? null : cut(product.sku(), 120);
        row.productName = product == null ? null : cut(product.nameWithColour(), 255);
        row.locationId = position.locationId();
        row.locationName = cut(position.locationName(), 255);
        row.anchor = position.anchor();
        row.countId = position.countId();
        row.countLineId = position.countLineId();
        /* A level read from the book has no moment that stays the same from one compute to the next. */
        row.anchoredAt = StockRoll.ANCHOR_COUNT.equals(position.anchor()) ? position.anchoredAt() : null;
        if (counted != null) {
            row.expectedQuantity = counted.expectedQuantity;
            row.countedQuantity = counted.countedQuantity;
            row.countDifference = counted.difference;
            row.countReasonCode = counted.reasonCode;
            row.countReasonNote = counted.reasonNote;
            row.countedByName = counted.countedByName;
            row.countedAt = counted.countedAt;
        }
        row.anchorQuantity = position.anchorQuantity();
        row.rollDelta = position.rollDelta();
        row.closingQuantity = position.closingQuantity();
        row.costValueEur = value == null ? ZERO : value.costValueEur();
        row.goodsEur = value == null ? ZERO : value.goodsEur();
        row.transportEur = value == null ? ZERO : value.transportEur();
        row.logisticsEur = value == null ? ZERO : value.logisticsEur();
        row.separateEur = value == null ? ZERO : value.separateEur();
        row.openingEur = value == null ? ZERO : value.openingEur();
        row.estimatedEur = value == null ? ZERO : value.estimatedEur();
        row.writeDownEur = value == null ? ZERO : value.writeDownEur();
        row.ownValueEur = value == null ? ZERO : value.ownValueEur();
        return row;
    }

    private static FifoValuer.PartnerLot partnerLot(PurchaseOrder order, FifoValuer.Lot lot, String partnerName,
                                                    List<SalesOrder> sales, Instant cutoff, Index index) {
        int shipped = ClosingSeparations.partnerShipped(order.id(), sales, cutoff).getOrDefault(lot.productId(), 0);
        StockClosingDecisionEntity quantity = index.partnerQuantity(order.id(), lot.productId());
        return new FifoValuer.PartnerLot(lot, partnerName, shipped, quantity == null ? null : quantity.quantity,
                decidedOf(index.container(ClosingDecisionKind.PARTNER_CONTAINER, order.id())));
    }

    /** One product of a container that was on the water: its ordered pieces at the unit value of its lot. */
    private static StockClosingSeparateEntity transitRow(PurchaseOrder order, StockClosingContainerEntity container, LotCost.Lot lot,
                                                         List<PurchasePayment> payments, LocalDate closingDate, Index index) {
        StockClosingDecisionEntity decision = index.container(ClosingDecisionKind.TRANSIT, order.id());
        StockClosingSeparateEntity row = new StockClosingSeparateEntity();
        row.kind = FifoValuer.KIND_TRANSIT;
        row.purchaseOrderId = order.id();
        row.documentNumber = container.orderNumber;
        row.documentName = container.displayName;
        row.documentDate = order.orderDate();
        row.counterparty = container.supplierName;
        row.productId = lot.productId();
        row.sku = cut(lot.sku(), 120);
        row.productName = cut(lot.productName(), 255);
        row.quantity = lot.orderedQuantity();
        row.unitValueEur = lot.unitValueEur();
        row.valueEur = lot.unitValueEur().multiply(BigDecimal.valueOf(lot.orderedQuantity())).setScale(2, RoundingMode.HALF_UP);
        row.estimatedEur = lot.unitEstimatedEur().multiply(BigDecimal.valueOf(lot.orderedQuantity())).setScale(2, RoundingMode.HALF_UP);
        row.shippedOn = order.shippedOn();
        row.receivedOn = order.receivedOn();
        row.paidUntilClosingEur = payments.stream()
                .filter(payment -> payment.paidOn() != null && !payment.paidOn().isAfter(closingDate) && payment.amountEur() != null)
                .map(PurchasePayment::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        row.automatic = false;
        if (decision != null) {
            row.included = Boolean.TRUE.equals(decision.flag);
            row.ownershipDate = decision.decisionDate;
            row.reason = decision.reason;
            row.decisionId = decision.id;
            row.decidedByName = decision.decidedByName;
            row.decidedAt = decision.decidedAt;
        }
        return row;
    }

    private static FifoValuer.Decided decidedOf(StockClosingDecisionEntity decision) {
        return decision == null ? null : new FifoValuer.Decided(decision.id, decision.flag, decision.choice, decision.reason,
                decision.decidedByName, decision.decidedAt);
    }

    private static boolean advance(SalesAdvanceBillingService.Views billing, SalesOrder order) {
        if (billing == null) return false;
        var role = billing.billing(order);
        return role != null && role.stage() == SalesAdvanceBilling.Stage.ADVANCE;
    }

    /* ------------------------------------------------------------------- json */

    /** The payments of a container as its row keeps them: fixed key order, no whitespace. */
    static String paymentsJson(List<LotCost.PaymentUse> payments) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LotCost.PaymentUse payment : payments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", payment.paymentId());
            row.put("paidOn", payment.paidOn() == null ? null : payment.paidOn().toString());
            row.put("payee", payment.payee().name());
            row.put("payeeLabel", PayeeLabels.of(payment.payee().name()));
            row.put("label", payment.label());
            row.put("amount", payment.amount());
            row.put("currency", payment.currency() == null ? null : payment.currency().name());
            row.put("storedEur", payment.storedEur());
            row.put("countedEur", payment.countedEur());
            row.put("inValue", payment.inValue());
            row.put("rule", payment.rule());
            rows.add(row);
        }
        return write(rows);
    }

    /** The credits of a container with the treatment each one got and the decision behind it. */
    static String creditsJson(long purchaseOrderId, List<LotCost.CreditUse> credits, Index index) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LotCost.CreditUse credit : credits) {
            StockClosingDecisionEntity decision = index.credit(purchaseOrderId, credit.creditId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("creditId", credit.creditId());
            row.put("notedOn", credit.notedOn() == null ? null : credit.notedOn().toString());
            row.put("reasonLabel", (credit.reason() == null ? PurchaseSupplierCredit.Reason.OTHER : credit.reason()).dutchLabel());
            row.put("amount", credit.amount());
            row.put("currency", credit.currency() == null ? null : credit.currency().name());
            row.put("countedEur", credit.countedEur());
            row.put("treatment", credit.treatmentCode());
            row.put("treatmentLabel", treatmentLabel(credit.treatmentCode()));
            row.put("decisionId", decision == null ? null : decision.id);
            row.put("decisionRequired", credit.decisionRequired());
            row.put("reason", decision == null ? null : decision.reason);
            rows.add(row);
        }
        return write(rows);
    }

    /** How the day the goods were bought was found, as the screens and files name it. */
    public static String borderLabel(String source) {
        return switch (source == null ? "" : source) {
            case BORDER_DECISION -> "Eigendom of risico (ingevoerd)";
            case BORDER_TRANSIT -> "Eigendom of risico (goederen onderweg)";
            case BORDER_PREVIOUS -> "Overgenomen uit de vorige afsluiting";
            case BORDER_CLOSING_DATE -> "Afsluitdatum (niet opgenomen)";
            default -> "Ontvangstdatum";
        };
    }

    /**
     * How sure the amount of a payee stream is, from its stored figures: nothing unpaid is real, an
     * unpaid amount that an invoice confirms carries no estimate, anything else is estimated.
     */
    public static String streamState(BigDecimal paidEur, BigDecimal includedEur, BigDecimal estimatedEur) {
        BigDecimal unpaid = (includedEur == null ? ZERO : includedEur).subtract(paidEur == null ? ZERO : paidEur);
        if (unpaid.signum() <= 0) return LotCost.State.WERKELIJK.name();
        return estimatedEur == null || estimatedEur.signum() == 0 ? LotCost.State.BEVESTIGD.name() : LotCost.State.GESCHAT.name();
    }

    /** What the missing and the damaged pieces of a container cost, from its lot rows. */
    public static BigDecimal missingAndDamagedCost(List<StockClosingLotEntity> lotRows) {
        BigDecimal total = ZERO;
        for (StockClosingLotEntity lot : lotRows) {
            int missing = (lot.goodsDivisor == null ? 0 : lot.goodsDivisor) - (lot.receivedQuantity == null ? 0 : lot.receivedQuantity);
            int damaged = (lot.damagedQuantity == null ? 0 : lot.damagedQuantity) + (lot.laterLostQuantity == null ? 0 : lot.laterLostQuantity);
            if (lot.unitGoodsEur != null) {
                total = total.add(lot.unitGoodsEur.multiply(BigDecimal.valueOf(missing)).setScale(2, RoundingMode.HALF_UP));
            }
            if (lot.unitValueEur != null) {
                total = total.add(lot.unitValueEur.multiply(BigDecimal.valueOf(damaged)).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return total;
    }

    public static String treatmentLabel(String treatment) {
        return switch (treatment == null ? "" : treatment) {
            case "VERLAAGT" -> "verlaagt de aanschafwaarde";
            case "BUITEN" -> "buiten de voorraadwaarde";
            case "IN_BETALING" -> "reeds in de betaling";
            default -> "nog te beslissen";
        };
    }

    /** The treatment a previous closing stored per credit of a container. */
    private static Map<Long, LotCost.CreditTreatment> frozenTreatments(StockClosingContainerEntity earlier) {
        Map<Long, LotCost.CreditTreatment> treatments = new HashMap<>();
        if (earlier == null) return treatments;
        for (Map<String, Object> credit : readJson(earlier.creditsJson)) {
            Object id = credit.get("creditId");
            Object treatment = credit.get("treatment");
            if (id instanceof Number number && treatment != null
                    && StockClosingDecisionService.TREATMENTS.contains(treatment.toString())) {
                treatments.put(number.longValue(), LotCost.CreditTreatment.valueOf(treatment.toString()));
            }
        }
        return treatments;
    }

    private static String write(List<Map<String, Object>> rows) {
        try {
            return JSON.writeValueAsString(rows);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A stored payment or credit list as objects, amounts as decimals with their stored scale. */
    public static List<Map<String, Object>> readJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    /* -------------------------------------------------------- decision facts */

    /** What a decision write is checked against, read from the live data of this closing. */
    private final class DecisionFacts implements StockClosingDecisionService.Facts {
        private final StockClosingEntity closing;
        private StockClosingEntity previous;
        private boolean previousRead;

        DecisionFacts(StockClosingEntity closing) {
            this.closing = closing;
        }

        private StockClosingEntity previous() {
            if (!previousRead) {
                previous = previousOf(closing);
                previousRead = true;
            }
            return previous;
        }

        private PurchaseOrder order(long purchaseOrderId) {
            try {
                return purchaseOrders.get().get(purchaseOrderId);
            } catch (NotFoundException gone) {
                return null;
            }
        }

        @Override
        public StockClosingDecisionService.ContainerFacts container(long purchaseOrderId) {
            PurchaseOrder order = order(purchaseOrderId);
            if (order == null) return null;
            Set<Long> creditIds = purchaseOrders.get().supplierCredits(purchaseOrderId).stream()
                    .map(PurchaseSupplierCredit::id).collect(Collectors.toSet());
            return new StockClosingDecisionService.ContainerFacts(order.receivedOn(), creditIds);
        }

        @Override
        public StockClosingDecisionService.FixedBorder fixedBorder(long purchaseOrderId) {
            if (previous() == null) return null;
            StockClosingContainerEntity before = containers.find("closingId = ?1 and purchaseOrderId = ?2",
                    previous().id, purchaseOrderId).firstResult();
            return fixesBorder(before) ? new StockClosingDecisionService.FixedBorder(before.rateCutoffDate, previous().closingYear) : null;
        }

        @Override
        public BigDecimal openAmount(long purchaseOrderId, String payee) {
            PurchaseOrder order = order(purchaseOrderId);
            if (order == null) return ZERO;
            boolean received = order.status() == PurchaseOrderStatus.ONTVANGEN && order.receivedOn() != null
                    && !order.receivedOn().isAfter(closing.closingDate);
            Map<Long, Product> productsById = products.get().list().stream().collect(Collectors.toMap(Product::id, Function.identity()));
            LotCost.Options options = new LotCost.Options(null, received ? LotCost.QuantityBasis.ONTVANGEN : LotCost.QuantityBasis.BESTELD,
                    received ? null : closing.closingDate, closing.cutoffAt, null, null, null);
            LotCost.StreamCost stream = lotCosts.get().forContainer(order, options, productsById).stream(PurchasePayment.Payee.valueOf(payee));
            return stream == null || stream.openEur() == null ? ZERO : stream.openEur();
        }

        @Override
        public boolean productExists(long productId) {
            return products.get().list().stream().anyMatch(product -> product.id() == productId);
        }

        @Override
        public int ownQuantity(long productId) {
            StockClosingArticleEntity article = articles.find("closingId = ?1 and productId = ?2", closing.id, productId).firstResult();
            return article == null || article.ownQuantity == null ? 0 : article.ownQuantity;
        }

        @Override
        public boolean invoiceCandidate(long salesOrderId) {
            List<SalesOrder> sales = salesOrders.get().list();
            SalesAdvanceBillingService.Views billing = advanceBilling.isResolvable() ? advanceBilling.get().views(sales) : null;
            return ClosingSeparations.invoices(sales, order -> advance(billing, order), closing.closingYear, closing.closingDate,
                            InventoryClock.cutoffAt(closing.closingDate), previous() == null ? null : previous().closingDate)
                    .candidates().stream().anyMatch(invoice -> invoice.id() == salesOrderId);
        }

        @Override
        public String invoiceNumber(long salesOrderId) {
            return salesOrders.get().list().stream().filter(order -> order.id() == salesOrderId)
                    .map(SalesOrder::number).findFirst().orElse(null);
        }

        @Override
        public boolean movementListed(long movementId) {
            return movements.count("closingId = ?1 and movementId = ?2", closing.id, movementId) > 0;
        }
    }

    /* --------------------------------------------------------------- helpers */

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> amount) {
        BigDecimal total = ZERO;
        for (T row : rows) {
            BigDecimal value = amount.apply(row);
            if (value != null) total = total.add(value);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
