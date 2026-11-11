package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.ProductCostHistoryService;
import be.enrosed.catalog.application.StockBatchService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockBatchMove;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalSettingsEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.partnerportal.domain.PartnerProductNames;
import be.enrosed.partnerportal.domain.PartnerSaleTexts;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.shared.security.StaffContactService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes meldingen and withdraws them (contract sections 1 and 3).
 *
 * <p>Every write runs in one transaction that first takes, in this order, the partner lock
 * ({@code partner_portal_settings} FOR UPDATE), every container of the partner (ascending id) and
 * the products of the melding (ascending id); only then are open parts, the ledger and the batches
 * read. A melding books one stock movement per part at the partner's location: a sale fills the
 * partner's containers oldest first up to their capacity ({@link PartnerContainerCapacity}) and
 * the rest comes from own stock; a buyback mirrors sale parts (this melding's, then the partner's
 * other open ones newest first, then invoiced ones) and puts the pieces back where those came
 * from. {@code client_ref} makes a retry safe: the partner lock serialises, the unique constraint
 * is the backstop, and either way the retry answers the existing melding.
 */
@ApplicationScoped
public class PartnerSaleReportService {

    private static final Logger LOG = Logger.getLogger(PartnerSaleReportService.class);

    public static final String ENTITY = "PARTNER_SALE_REPORT";
    public static final String ACTION_VOIDED = "VOIDED";

    static final int MAX_ENTRIES = 60;
    static final int MAX_DAYS_BACK = 90;
    static final int MAX_NOTE = 500;
    static final int MAX_CLIENT_REF = 64;
    static final int MAX_REASON = 200;
    static final int MAX_QUANTITY = 1_000_000;
    static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("1000000");
    static final BigDecimal MAX_TOTAL = new BigDecimal("100000000");
    static final BigDecimal MAX_EXTRA_PCT = new BigDecimal("100");

    /** What a create did: the melding, and false when a retry found it already there. */
    public record Outcome(long reportId, boolean created) {}

    private final PartnerPortalRepositories.Settings settingsRows;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerPortalSettings settings;
    private final PartnerContainerCapacity capacity;
    private final PartnerOpenParts openParts;
    private final StockService stock;
    private final StockBatchService batches;
    private final ProductRepository products;
    private final ProductCostHistoryService costHistory;
    private final SalesRepositories.Customers customers;
    private final StaffContactService contacts;
    private final CurrentActor actor;
    private final ActivityLogService activity;
    private final Event<PartnerSalePushNotifier.Ready> pushReady;

    public PartnerSaleReportService(PartnerPortalRepositories.Settings settingsRows,
                                    PartnerPortalRepositories.Reports reports, PartnerPortalRepositories.Lines lines,
                                    PartnerPortalSettings settings, PartnerContainerCapacity capacity,
                                    PartnerOpenParts openParts, StockService stock, StockBatchService batches,
                                    ProductRepository products, ProductCostHistoryService costHistory,
                                    SalesRepositories.Customers customers, StaffContactService contacts,
                                    CurrentActor actor, ActivityLogService activity,
                                    Event<PartnerSalePushNotifier.Ready> pushReady) {
        this.settingsRows = settingsRows;
        this.reports = reports;
        this.lines = lines;
        this.settings = settings;
        this.capacity = capacity;
        this.openParts = openParts;
        this.stock = stock;
        this.batches = batches;
        this.products = products;
        this.costHistory = costHistory;
        this.customers = customers;
        this.contacts = contacts;
        this.actor = actor;
        this.activity = activity;
        this.pushReady = pushReady;
    }

    /* ================================================================ create */

    /**
     * A new melding of this partner. loginId is null when staff enter it for the partner
     * ({@code staff} true: other refusal texts, the push skips the staff member's own phones).
     * Not transactional itself: the melding gets a transaction of its own so a unique-constraint
     * violation on client_ref can be answered with the existing melding from a fresh one.
     */
    public Outcome create(long customerId, Long loginId, PartnerSaleRequests.Report request, boolean staff) {
        Input input = validate(request);
        try {
            return QuarkusTransaction.requiringNew().call(() -> createLocked(customerId, loginId, input, staff));
        } catch (RuntimeException failure) {
            if (input.clientRef() == null || !uniqueViolation(failure)) throw failure;
            Optional<Long> existing = QuarkusTransaction.requiringNew().call(() ->
                    reports.findByClientRef(customerId, input.clientRef()).map(report -> report.id));
            if (existing.isEmpty()) throw failure;
            LOG.infof("Melding met clientRef van klant %d bestond al: VV-%d", customerId, existing.get());
            return new Outcome(existing.get(), false);
        }
    }

    private Outcome createLocked(long customerId, Long loginId, Input input, boolean staff) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
        if (!customer.partner()) throw new BusinessRuleException("Deze klant is geen partner");
        /* Lock 1: the partner. A retry of the same melding waits here and then finds it. */
        PartnerPortalSettingsEntity row = settingsRows.lockOrCreate(customerId);
        if (input.clientRef() != null) {
            Optional<PartnerSaleReportEntity> existing = reports.findByClientRef(customerId, input.clientRef());
            if (existing.isPresent()) return new Outcome(existing.get().id, false);
        }
        PartnerPortalSettings.Effective applied = settings.of(customerId, row);
        StockLocation location = applied.location();
        /* Lock 2: the partner's containers, ascending. Lock 3: the products, ascending. */
        List<PurchaseOrder> containers = capacity.lockContainers(customerId);
        Map<Long, Product> catalog = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(input.productIds())) {
            stock.lockProduct(productId);
            catalog.put(productId, products.findById(productId)
                    .orElseThrow(() -> new BusinessRuleException("Product #" + productId + " bestaat niet meer")));
        }
        boolean batchesOn = batches.active();
        boolean auction = PartnerPortalRules.CHANNEL_AUCTION.equals(input.channel());
        BigDecimal commissionPct = auction ? applied.auctionCommissionPct() : BigDecimal.ZERO;
        BigDecimal transportPct = auction ? applied.auctionTransportPct() : BigDecimal.ZERO;

        /* ---- the plan: every part, checked, before anything is written */
        List<EntryInput> saleEntries = input.entries().stream().filter(entry -> entry.sale() != null).toList();
        List<PartnerSaleMoney.Sale> saleMoney = PartnerSaleMoney.sales(saleEntries.stream()
                        .map(entry -> PartnerSaleMoney.price(entry.sale().quantity(), entry.sale().unitPrice(),
                                entry.sale().total())).toList(),
                commissionPct, transportPct, input.extraMode(), input.extraValue());
        List<PurchaseOrder> candidates = PartnerContainerCapacity.candidates(containers, location);
        Map<Long, List<PartnerContainerCapacity.Slot>> slots = capacity.slots(customerId, candidates,
                saleEntries.stream().map(EntryInput::productId).toList(), batchesOn);
        List<Planned> sales = new ArrayList<>();
        Map<Integer, List<Planned>> salesByEntry = new HashMap<>();
        for (int index = 0; index < saleEntries.size(); index++) {
            EntryInput entry = saleEntries.get(index);
            List<Planned> parts = planSale(entry, saleMoney.get(index), catalog.get(entry.productId()), location,
                    slots.getOrDefault(entry.productId(), List.of()), batchesOn, staff);
            sales.addAll(parts);
            salesByEntry.put(entry.entryNo(), parts);
        }
        List<Planned> buybacks = new ArrayList<>();
        for (EntryInput entry : input.entries()) {
            if (entry.buyback() == null) continue;
            buybacks.addAll(planBuyback(customerId, entry, catalog.get(entry.productId()),
                    salesByEntry.getOrDefault(entry.entryNo(), List.of())));
        }

        /* ---- the writes */
        ActorRef who = actor.current();
        Instant now = Instant.now();
        PartnerSaleReportEntity report = new PartnerSaleReportEntity();
        report.customerId = customerId;
        report.loginId = loginId;
        report.channel = input.channel();
        report.soldOn = input.soldOn();
        report.commissionPct = commissionPct.setScale(4, RoundingMode.HALF_UP);
        report.transportPct = transportPct.setScale(4, RoundingMode.HALF_UP);
        report.extraCostMode = input.extraMode();
        report.extraCostValue = input.extraValue();
        report.note = input.note();
        report.locationId = location.id();
        report.clientRef = input.clientRef();
        report.createdAt = now;
        report.createdBy = who.username();
        report.createdByName = cut(who.displayName(), 100);
        reports.persist(report);
        reports.flush();
        String number = PartnerPortalRules.reportNumber(report.id);
        for (Planned part : sales) {
            Long movementId = stock.partnerMove(part.productId, location.id(), -part.quantity,
                    StockMovement.Kind.PARTNER_SALE, "Veilingverkoop " + number, part.purchaseOrderId, report.soldOn,
                    StockBatchContext.pinned(part.purchaseOrderId));
            OwnCost cost = part.purchaseOrderId == null
                    ? ownUnitCost(catalog.get(part.productId), movementId, part.quantity, batchesOn) : null;
            part.written = write(report, part, movementId, cost == null ? null : cost.unitCostEur(), null);
            if (cost != null && cost.estimated()) part.written.unitCostEstimated = true;
        }
        Map<Long, List<Long>> earlierReversals = lines.reversedBuybackMovements(buybacks.stream()
                .filter(part -> part.mirrorsExisting != null).map(part -> part.mirrorsExisting.id).toList());
        for (Planned part : buybacks) {
            PartnerSaleReportLineEntity mirrored = part.mirrorsExisting != null ? part.mirrorsExisting
                    : part.mirrorsPlanned.written;
            /* Back into the takes that hold the mirrored sale's pieces: its own, and those of withdrawn
               buybacks of it (they took the pieces out again), so a container's pieces stay the container's.
               Taken over from a settled-through container: own stock at that container's cost instead. */
            List<Long> sources = new ArrayList<>();
            sources.add(mirrored.stockMovementId);
            sources.addAll(earlierReversals.getOrDefault(mirrored.id, List.of()));
            Long movementId = stock.partnerMove(part.productId, location.id(), part.quantity,
                    StockMovement.Kind.PARTNER_BUYBACK, "Terugkoop " + number, part.purchaseOrderId, report.soldOn,
                    part.takeoverCostEur != null ? StockBatchContext.partnerTakeOver(mirrored.purchaseOrderId)
                            : StockBatchContext.partnerReturn(sources));
            BigDecimal unitCost = part.purchaseOrderId != null ? null
                    : part.takeoverCostEur != null ? part.takeoverCostEur : mirrored.unitCostEur;
            part.written = write(report, part, movementId, unitCost, mirrored.id);
            if (part.purchaseOrderId == null && part.takeoverCostEur == null
                    && Boolean.TRUE.equals(mirrored.unitCostEstimated)) part.written.unitCostEstimated = true;
        }
        lines.flush();

        int saleQuantity = sales.stream().mapToInt(part -> part.quantity).sum();
        int buybackQuantity = buybacks.stream().mapToInt(part -> part.quantity).sum();
        boolean buybackOnly = saleQuantity == 0;
        BigDecimal net = sales.stream().map(part -> part.net).reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(buybacks.stream().map(part -> part.gross).reduce(BigDecimal.ZERO, BigDecimal::add));
        String summary = PartnerSaleTexts.title(report.channel, buybackOnly) + ": "
                + (buybackOnly ? buybackQuantity + " teruggekocht · terugkoop " + PartnerSaleTexts.euro(net.negate())
                : saleQuantity + " verkocht" + (buybackQuantity > 0 ? " · " + buybackQuantity + " teruggekocht" : "")
                + " · netto " + PartnerSaleTexts.euro(net));
        activity.record(ActivityLogService.ACTION_CREATED, ENTITY, report.id.toString(),
                number + " · " + customer.company(), summary);
        pushReady.fire(new PartnerSalePushNotifier.Ready(report.id, buybackOnly ? buybackQuantity : saleQuantity,
                PartnerSaleTexts.kind(report.channel, buybackOnly), staff ? who.username() : null));
        return new Outcome(report.id, true);
    }

    /** The parts of one sale entry: the containers in order up to their capacity, the rest own stock. */
    private List<Planned> planSale(EntryInput entry, PartnerSaleMoney.Sale money, Product product,
                                   StockLocation location, List<PartnerContainerCapacity.Slot> slots,
                                   boolean batchesOn, boolean staff) {
        PartnerProductNames.Names names = PartnerProductNames.of(product);
        int wanted = money.quantity();
        int atLocation = stock.quantityAt(product.id(), location.id());
        if (wanted > atLocation) {
            String head = "Er liggen nog maar " + PartnerSaleTexts.quantity(Math.max(0, atLocation), names.unitOne(),
                    names.unitOther()) + " van " + label(names);
            throw new BusinessRuleException(staff ? head + " op " + location.name() + ". Werk eerst de voorraad bij."
                    : head + ". " + callEnrosed() + " om de voorraad bij te werken.");
        }
        List<Long> sources = new ArrayList<>();
        List<Integer> quantities = new ArrayList<>();
        int rest = wanted;
        int available = 0;
        for (PartnerContainerCapacity.Slot slot : slots) {
            int free = slot.capacity();
            available += free;
            int taken = Math.min(rest, free);
            if (taken <= 0) continue;
            sources.add(slot.purchaseOrderId());
            quantities.add(taken);
            rest -= taken;
        }
        if (rest > 0) {
            if (batchesOn) {
                StockBatchService.Holdings held = batches.holdings(product.id());
                int ownUnits = (held.ownPieces() + held.uncoveredPieces()) / Math.max(1, held.unitPieces());
                available += ownUnits;
                if (rest > ownUnits) {
                    int blocked = Math.max(1, atLocation - available);
                    throw new BusinessRuleException(PartnerSaleTexts.quantity(blocked, names.unitOne(), names.unitOther())
                            + " van " + label(names) + (blocked == 1 ? " is" : " zijn")
                            + " al afgerekend maar nog niet afgeboekt; "
                            + (staff ? "boek die eerst af." : "Enrosed boekt die eerst af."));
                }
            }
            sources.add(null);
            quantities.add(rest);
        }
        List<PartnerSaleMoney.Sale> split = PartnerSaleMoney.split(money, quantities);
        List<Planned> parts = new ArrayList<>();
        for (int index = 0; index < split.size(); index++) {
            PartnerSaleMoney.Sale part = split.get(index);
            parts.add(Planned.sale(entry.entryNo(), product.id(), sources.get(index), part));
        }
        return parts;
    }

    /**
     * The parts of one buyback: mirrored sale parts of this product, first this melding's (the
     * last-filled part first, so a sale of 100 with 1 back reads as a sale of 99), then the
     * partner's other open sale parts newest first, then invoiced ones newest first; each only up
     * to its quantity minus what live buybacks already mirror.
     */
    private List<Planned> planBuyback(long customerId, EntryInput entry, Product product, List<Planned> sameMelding) {
        PartnerProductNames.Names names = PartnerProductNames.of(product);
        PartnerSaleMoney.Priced priced = PartnerSaleMoney.price(entry.buyback().quantity(), entry.buyback().unitPrice(),
                entry.buyback().total());
        int rest = priced.quantity();
        List<Object> mirrors = new ArrayList<>();
        List<Integer> quantities = new ArrayList<>();
        for (int index = sameMelding.size() - 1; index >= 0 && rest > 0; index--) {
            Planned sale = sameMelding.get(index);
            int taken = Math.min(rest, sale.quantity);
            mirrors.add(sale);
            quantities.add(taken);
            rest -= taken;
        }
        if (rest > 0) {
            List<PartnerSaleReportLineEntity> earlier = new ArrayList<>();
            for (Object[] row : lines.salePartsOfProduct(customerId, product.id())) {
                earlier.add((PartnerSaleReportLineEntity) row[0]);
            }
            PartnerOpenParts.Rule rule = openParts.forLines(earlier);
            Map<Long, Integer> mirrored = lines.mirroredQuantities(earlier.stream().map(line -> line.id).toList());
            List<PartnerSaleReportLineEntity> ordered = new ArrayList<>();
            for (PartnerSaleReportLineEntity line : earlier) if (rule.isOpen(line, false)) ordered.add(line);
            for (PartnerSaleReportLineEntity line : earlier) if (!rule.isOpen(line, false)) ordered.add(line);
            for (PartnerSaleReportLineEntity line : ordered) {
                if (rest == 0) break;
                int free = line.quantityOrZero() - mirrored.getOrDefault(line.id, 0);
                int taken = Math.min(rest, free);
                if (taken <= 0) continue;
                mirrors.add(line);
                quantities.add(taken);
                rest -= taken;
            }
        }
        if (rest > 0) {
            int found = priced.quantity() - rest;
            throw new BusinessRuleException(found == 0
                    ? "Meld eerst de verkoop op de veiling; een terugkoop hoort bij een gemelde verkoop."
                    : "Van " + label(names) + " " + (found == 1 ? "staat" : "staan") + " maar "
                    + PartnerSaleTexts.quantity(found, names.unitOne(), names.unitOther())
                    + " als verkocht gemeld; een terugkoop hoort bij een gemelde verkoop.");
        }
        List<BigDecimal> gross = PartnerSaleMoney.splitGross(priced.grossEur(), quantities);
        List<Planned> parts = new ArrayList<>();
        Map<Long, PartnerContainerCapacity.Settled> settled = new HashMap<>();
        for (int index = 0; index < mirrors.size(); index++) {
            Object mirror = mirrors.get(index);
            if (mirror instanceof Planned planned) {
                parts.add(Planned.buyback(entry.entryNo(), product.id(), planned.purchaseOrderId, quantities.get(index),
                        priced.unitPriceEur(), gross.get(index), null, planned));
                continue;
            }
            PartnerSaleReportLineEntity line = (PartnerSaleReportLineEntity) mirror;
            /* A container with nothing of this product left to settle can never invoice a buyback: Enrosed takes
               the pieces over as own stock at what the container's settlements charged for them. */
            PartnerContainerCapacity.Settled closed = line.purchaseOrderId == null ? null
                    : settled.computeIfAbsent(line.purchaseOrderId, id -> capacity.settled(id, product.id()));
            boolean takeOver = closed != null && closed.closed();
            Planned part = Planned.buyback(entry.entryNo(), product.id(), takeOver ? null : line.purchaseOrderId,
                    quantities.get(index), priced.unitPriceEur(), gross.get(index), line, null);
            if (takeOver) part.takeoverCostEur = closed.unitCostEur() == null
                    ? BigDecimal.ZERO.setScale(4) : closed.unitCostEur();
            parts.add(part);
        }
        return parts;
    }

    private PartnerSaleReportLineEntity write(PartnerSaleReportEntity report, Planned part, Long movementId,
                                              BigDecimal unitCost, Long mirrorsLineId) {
        PartnerSaleReportLineEntity line = new PartnerSaleReportLineEntity();
        line.reportId = report.id;
        line.customerId = report.customerId;
        line.productId = part.productId;
        line.entryNo = part.entryNo;
        line.lineType = part.type;
        line.purchaseOrderId = part.purchaseOrderId;
        line.quantity = part.quantity;
        line.unitPriceEur = part.unitPrice;
        line.grossEur = part.gross;
        line.commissionEur = part.commission;
        line.transportEur = part.transport;
        line.extraCostEur = part.extra;
        line.netEur = part.net;
        line.unitCostEur = unitCost;
        line.mirrorsLineId = mirrorsLineId;
        line.stockMovementId = movementId;
        lines.persist(line);
        lines.flush();
        return line;
    }

    /** An own part's cost per sales unit; estimated = some pieces had no known cost and count at the known average. */
    private record OwnCost(BigDecimal unitCostEur, boolean estimated) {}

    /**
     * External cost per sales unit of an own part (without the Enrosed kost, the basis the
     * settlement ledger uses for containers). With batches: what its takes were worth excl. the
     * Enrosed kost; a take from a batch that only knows its full cost (a Telling or opening batch
     * at the card cost) loses the Enrosed kost the cost history explains for that cost, and pieces
     * without a batch or a cost count at the card. Pieces with no known cost at all (no batch cost
     * and no card) count at the average of the known pieces, and the part is marked estimated so
     * Factureren warns; never as 0 inside a plausible-looking average. Without batches: the card.
     * 0 when nothing is known (Factureren then warns "Geen kostprijs bekend").
     */
    private OwnCost ownUnitCost(Product product, Long movementId, int quantity, boolean batchesOn) {
        ProductCostHistoryService.EnrosedParts enrosed = costHistory.enrosedParts(List.of(product.id()));
        BigDecimal card = externalCost(product.id(), product.landedCostEur(), enrosed);
        if (batchesOn && movementId != null) {
            List<StockBatchMove> takes = batches.takesForMovement(product.id(), movementId);
            if (!takes.isEmpty()) {
                int factor = Math.max(1, PartnerProductNames.of(product).piecesPerUnit());
                BigDecimal value = BigDecimal.ZERO;
                BigDecimal knownUnits = BigDecimal.ZERO;
                boolean unknown = false;
                for (StockBatchMove take : takes) {
                    int pieces = Math.abs(take.pieces());
                    BigDecimal perUnit;
                    int unitPieces;
                    if (take.batchId() == null || take.unitCostEur() == null) {
                        perUnit = card;
                        unitPieces = factor;
                    } else {
                        perUnit = take.unitCostExclEnrosedEur() != null ? take.unitCostExclEnrosedEur()
                                : externalCost(product.id(), take.unitCostEur(), enrosed);
                        unitPieces = Math.max(1, take.unitPieces());
                    }
                    if (perUnit == null) {
                        unknown = true;
                        continue;
                    }
                    BigDecimal units = BigDecimal.valueOf(pieces).divide(BigDecimal.valueOf(unitPieces), 10, RoundingMode.HALF_UP);
                    value = value.add(perUnit.multiply(units));
                    knownUnits = knownUnits.add(units);
                }
                if (!unknown) return new OwnCost(value.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP), false);
                if (knownUnits.signum() <= 0) return new OwnCost(BigDecimal.ZERO.setScale(4), false);
                return new OwnCost(value.divide(knownUnits, 4, RoundingMode.HALF_UP), true);
            }
        }
        return new OwnCost(card == null ? BigDecimal.ZERO.setScale(4) : card.setScale(4, RoundingMode.HALF_UP), false);
    }

    /** A full cost per sales unit minus the Enrosed kost the cost history explains in it; null when there is none. */
    private static BigDecimal externalCost(long productId, BigDecimal fullCost,
                                           ProductCostHistoryService.EnrosedParts enrosed) {
        if (fullCost == null || fullCost.signum() <= 0) return null;
        return fullCost.subtract(enrosed.enrosedIn(productId, fullCost)).max(BigDecimal.ZERO);
    }

    /* ================================================================== void */

    /**
     * Withdraws a melding (Intrekken / Ongedaan maken): only while every part is open, never while
     * a buyback of another live melding mirrors one of its sales, and a buyback only while its
     * pieces still lie at the location. Every part gets a reversing movement of the opposite
     * kind, batch-correct: buybacks first (a take pinned to their container), then sales (back
     * into their own takes and those of the buyback reversals that mirrored them). A melding that
     * is already withdrawn stays as it is. partnerCustomerId limits a partner to its own.
     */
    @Transactional
    public void withdraw(Long partnerCustomerId, long reportId, String reason, boolean staff) {
        PartnerSaleReportEntity report = reports.findById(reportId);
        if (report == null || (partnerCustomerId != null && !partnerCustomerId.equals(report.customerId))) {
            throw new NotFoundException("Melding", reportId);
        }
        long customerId = report.customerId;
        settingsRows.lockOrCreate(customerId);
        reports.getEntityManager().refresh(report);
        if (report.isVoided()) return;
        List<PurchaseOrder> containers = capacity.lockContainers(customerId);
        List<PartnerSaleReportLineEntity> all = lines.byReport(reportId);
        List<PartnerSaleReportLineEntity> parts = all.stream().filter(line -> line.isSale() || line.isBuyback()).toList();
        Map<Long, Product> catalog = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(parts.stream().map(line -> line.productId).toList())) {
            stock.lockProduct(productId);
            products.findById(productId).ifPresent(product -> catalog.put(productId, product));
        }
        PartnerOpenParts.Rule rule = openParts.forLines(parts);
        if (parts.stream().anyMatch(part -> !rule.isOpen(part, false))) {
            throw new BusinessRuleException("Deze melding is al (deels) gefactureerd; intrekken kan niet meer");
        }
        Set<Long> saleIds = new HashSet<>();
        for (PartnerSaleReportLineEntity part : parts) if (part.isSale()) saleIds.add(part.id);
        Set<Long> mirroring = new TreeSet<>();
        for (Object[] row : lines.liveBuybacksMirroring(saleIds)) {
            long other = ((Number) row[1]).longValue();
            if (other != reportId) mirroring.add(other);
        }
        if (!mirroring.isEmpty()) {
            throw new BusinessRuleException("Trek eerst de terugkoop in ("
                    + String.join(", ", mirroring.stream().map(PartnerPortalRules::reportNumber).toList()) + ")");
        }
        long locationId = locationOf(report);
        /* The withdrawal's net effect: per product sale - buyback back on the shelf, per container
           buyback - sale more claimed by meldingen. Checked as a whole, so a melding that sold 100
           and bought 1 back can be withdrawn even when that 1 piece was sold again since. */
        Map<Long, Integer> saleByProduct = new HashMap<>();
        Map<Long, Integer> netBackByProduct = new HashMap<>();
        Map<String, Integer> containerNeed = new LinkedHashMap<>();
        for (PartnerSaleReportLineEntity part : parts) {
            int signed = part.isSale() ? part.quantityOrZero() : -part.quantityOrZero();
            if (part.isSale()) saleByProduct.merge(part.productId, part.quantityOrZero(), Integer::sum);
            netBackByProduct.merge(part.productId, signed, Integer::sum);
            if (part.purchaseOrderId != null) {
                containerNeed.merge(PartnerContainerCapacity.key(part.purchaseOrderId, part.productId), -signed, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> entry : netBackByProduct.entrySet()) {
            if (entry.getValue() < 0 && stock.quantityAt(entry.getKey(), locationId) + entry.getValue() < 0) {
                throw new BusinessRuleException("De teruggekochte stuks van " + productLabel(catalog, entry.getKey())
                        + " liggen niet meer op voorraad; intrekken kan niet");
            }
        }
        requireContainerRoom(customerId, containers, containerNeed, catalog);
        String reference = "Ingetrokken: " + PartnerPortalRules.reportNumber(reportId);
        /* Pieces of a sale part may sit in the takes of buyback withdrawals: those of other meldingen
           withdrawn earlier (read now, before this melding's own are written) and this melding's own
           (collected below); the sale's pieces go back into all of them. */
        Map<Long, List<Long>> earlierReversals = lines.reversedBuybackMovements(saleIds);
        Map<Long, List<Long>> reversalsOfMirroredSale = new HashMap<>();
        for (PartnerSaleReportLineEntity part : parts) {
            if (!part.isBuyback() || part.quantityOrZero() <= 0) continue;
            /* Only when this melding's own sale of the product goes back right after may the shelf dip for a moment. */
            boolean saleFollows = saleByProduct.getOrDefault(part.productId, 0) > 0;
            Long movementId = stock.partnerMove(part.productId, locationId, -part.quantityOrZero(),
                    StockMovement.Kind.PARTNER_SALE, reference, part.purchaseOrderId, report.soldOn,
                    StockBatchContext.pinned(part.purchaseOrderId), saleFollows);
            if (part.mirrorsLineId != null) {
                reversalsOfMirroredSale.computeIfAbsent(part.mirrorsLineId, key -> new ArrayList<>()).add(movementId);
            }
            writeReversal(part, movementId);
        }
        for (PartnerSaleReportLineEntity part : parts) {
            if (!part.isSale() || part.quantityOrZero() <= 0) continue;
            List<Long> sources = new ArrayList<>();
            sources.add(part.stockMovementId);
            sources.addAll(reversalsOfMirroredSale.getOrDefault(part.id, List.of()));
            sources.addAll(earlierReversals.getOrDefault(part.id, List.of()));
            Long movementId = stock.partnerMove(part.productId, locationId, part.quantityOrZero(),
                    StockMovement.Kind.PARTNER_BUYBACK, reference, part.purchaseOrderId, report.soldOn,
                    StockBatchContext.partnerReturn(sources));
            writeReversal(part, movementId);
        }
        ActorRef who = actor.current();
        report.voidedAt = Instant.now();
        report.voidedBy = who.username();
        report.voidReason = reason == null || reason.isBlank() ? null : cut(reason.strip(), MAX_REASON);
        reports.flush();
        Customer customer = customers.findById(customerId).orElse(null);
        activity.record(ACTION_VOIDED, ENTITY, Long.toString(reportId),
                PartnerPortalRules.reportNumber(reportId) + (customer == null ? "" : " · " + customer.company()),
                (staff ? "Ingetrokken" : "Ongedaan gemaakt")
                        + (report.voidReason == null ? "" : ": " + report.voidReason));
    }

    /**
     * A withdrawal that leaves more of a container claimed (its buybacks outweigh its sales there)
     * needs that room in the container: pieces a buyback put back may have been reported sold
     * from the container again since.
     */
    private void requireContainerRoom(long customerId, List<PurchaseOrder> containers, Map<String, Integer> need,
                                      Map<Long, Product> catalog) {
        Set<Long> containerIds = new HashSet<>();
        Set<Long> productIds = new HashSet<>();
        need.forEach((key, quantity) -> {
            if (quantity <= 0) return;
            String[] ids = key.split(":");
            containerIds.add(Long.parseLong(ids[0]));
            productIds.add(Long.parseLong(ids[1]));
        });
        if (containerIds.isEmpty()) return;
        List<PurchaseOrder> involved = containers.stream().filter(order -> containerIds.contains(order.id())).toList();
        Map<Long, List<PartnerContainerCapacity.Slot>> slots = capacity.slots(customerId, involved, productIds,
                batches.active());
        need.forEach((key, quantity) -> {
            if (quantity <= 0) return;
            String[] ids = key.split(":");
            long containerId = Long.parseLong(ids[0]);
            long productId = Long.parseLong(ids[1]);
            int room = slots.getOrDefault(productId, List.of()).stream()
                    .filter(slot -> slot.purchaseOrderId() == containerId)
                    .mapToInt(PartnerContainerCapacity.Slot::capacity).sum();
            if (room < quantity) {
                throw new BusinessRuleException("De teruggekochte stuks van " + productLabel(catalog, productId)
                        + " zijn al opnieuw als verkocht gemeld; trek eerst die melding in");
            }
        });
    }

    private static String productLabel(Map<Long, Product> catalog, long productId) {
        Product product = catalog.get(productId);
        return product == null ? "product #" + productId : label(PartnerProductNames.of(product));
    }

    private void writeReversal(PartnerSaleReportLineEntity part, Long movementId) {
        PartnerSaleReportLineEntity line = new PartnerSaleReportLineEntity();
        line.reportId = part.reportId;
        line.customerId = part.customerId;
        line.productId = part.productId;
        line.entryNo = part.entryNo;
        line.lineType = PartnerSaleReportLineEntity.REVERSAL;
        line.purchaseOrderId = part.purchaseOrderId;
        line.quantity = part.quantity;
        line.mirrorsLineId = part.id;
        line.stockMovementId = movementId;
        lines.persist(line);
    }

    /** Where the melding booked its stock; MAIN when that location no longer exists. */
    private long locationOf(PartnerSaleReportEntity report) {
        if (report.locationId != null) {
            for (StockLocation location : stock.locations()) {
                if (report.locationId.equals(location.id())) return location.id();
            }
        }
        return stock.mainLocation().id();
    }

    /* ============================================================ validation */

    /** The checked body: channel upper case, soldOn filled, entry numbers 1..n in the order given. */
    record Input(String channel, LocalDate soldOn, String clientRef, String note, String extraMode,
                 BigDecimal extraValue, List<EntryInput> entries) {
        List<Long> productIds() {
            return entries.stream().map(EntryInput::productId).toList();
        }
    }

    record EntryInput(int entryNo, long productId, PartInput sale, PartInput buyback) {}

    record PartInput(int quantity, BigDecimal unitPrice, BigDecimal total) {}

    static Input validate(PartnerSaleRequests.Report request) {
        if (request == null) throw new BusinessRuleException("Geen gegevens ontvangen");
        String channel = request.channel() == null ? "" : request.channel().strip().toUpperCase(Locale.ROOT);
        if (!PartnerPortalRules.CHANNEL_AUCTION.equals(channel) && !PartnerPortalRules.CHANNEL_DIRECT.equals(channel)) {
            throw new BusinessRuleException("Kies veiling of direct");
        }
        LocalDate today = PartnerPortalRules.today();
        LocalDate soldOn = request.soldOn() == null ? today : request.soldOn();
        if (soldOn.isAfter(today)) throw new BusinessRuleException("De verkoopdatum ligt in de toekomst");
        if (soldOn.isBefore(today.minusDays(MAX_DAYS_BACK))) {
            throw new BusinessRuleException("Een melding gaat hoogstens " + MAX_DAYS_BACK + " dagen terug");
        }
        String clientRef = request.clientRef() == null || request.clientRef().isBlank() ? null : request.clientRef().strip();
        if (clientRef != null && clientRef.length() > MAX_CLIENT_REF) {
            throw new BusinessRuleException("De meldingscode is te lang");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note != null && note.length() > MAX_NOTE) {
            throw new BusinessRuleException("Een opmerking heeft hoogstens " + MAX_NOTE + " tekens");
        }
        List<PartnerSaleRequests.Entry> given = request.entries() == null ? List.of() : request.entries();
        if (given.isEmpty()) throw new BusinessRuleException("Kies minstens één product");
        if (given.size() > MAX_ENTRIES) throw new BusinessRuleException("Hoogstens " + MAX_ENTRIES + " producten per melding");
        boolean auction = PartnerPortalRules.CHANNEL_AUCTION.equals(channel);
        Set<Long> seen = new HashSet<>();
        List<EntryInput> entries = new ArrayList<>();
        for (PartnerSaleRequests.Entry entry : given) {
            if (entry == null || entry.productId() == null) throw new BusinessRuleException("Kies bij elke regel een product");
            if (!seen.add(entry.productId())) throw new BusinessRuleException("Elk product staat maar één keer in een melding");
            if (entry.sale() == null && entry.buyback() == null) {
                throw new BusinessRuleException("Vul bij elk product een verkoop of een terugkoop in");
            }
            if (entry.buyback() != null && !auction) {
                throw new BusinessRuleException("Terugkopen kan alleen bij een verkoop op de veiling");
            }
            entries.add(new EntryInput(entries.size() + 1, entry.productId(), part(entry.sale()), part(entry.buyback())));
        }
        String extraMode = null;
        BigDecimal extraValue = null;
        PartnerSaleRequests.ExtraCost extra = request.extraCost();
        if (extra != null && (extra.mode() != null || extra.value() != null)) {
            extraMode = extra.mode() == null ? "" : extra.mode().strip().toUpperCase(Locale.ROOT);
            if (!"AMOUNT".equals(extraMode) && !"PCT".equals(extraMode)) {
                throw new BusinessRuleException("Kies voor de extra kost een bedrag of een percentage");
            }
            if (entries.stream().noneMatch(entry -> entry.sale() != null)) {
                throw new BusinessRuleException("Extra kost hoort bij een verkoop");
            }
            extraValue = extra.value();
            if (extraValue == null || extraValue.signum() < 0) {
                throw new BusinessRuleException("Vul de extra kost in (0 of meer)");
            }
            if ("PCT".equals(extraMode) && extraValue.compareTo(MAX_EXTRA_PCT) > 0) {
                throw new BusinessRuleException("Een percentage ligt tussen 0 en 100");
            }
            if ("AMOUNT".equals(extraMode) && extraValue.compareTo(MAX_TOTAL) > 0) {
                throw new BusinessRuleException("Dit bedrag is te groot");
            }
            /* Computed from what is stored (and what the frontend sends): an amount in cents, a percent at 4 dp. */
            extraValue = extraValue.setScale("AMOUNT".equals(extraMode) ? 2 : 4, RoundingMode.HALF_UP);
        }
        return new Input(channel, soldOn, clientRef, note, extraMode, extraValue, List.copyOf(entries));
    }

    private static PartInput part(PartnerSaleRequests.Part given) {
        if (given == null) return null;
        BigDecimal quantity = given.quantity();
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0
                || quantity.compareTo(BigDecimal.valueOf(MAX_QUANTITY)) > 0) {
            throw new BusinessRuleException("Vul een heel aantal groter dan nul in");
        }
        if ((given.unitPriceEur() == null) == (given.totalEur() == null)) {
            throw new BusinessRuleException("Vul een gemiddelde prijs of een totaal in");
        }
        BigDecimal price = given.unitPriceEur() != null ? given.unitPriceEur() : given.totalEur();
        if (price.signum() < 0) throw new BusinessRuleException("Een prijs kan niet negatief zijn");
        if (price.compareTo(given.unitPriceEur() != null ? MAX_UNIT_PRICE : MAX_TOTAL) > 0) {
            throw new BusinessRuleException("Dit bedrag is te groot");
        }
        return new PartInput(quantity.intValueExact(), given.unitPriceEur(), given.totalEur());
    }

    /* =============================================================== helpers */

    /** One part in the plan; written is set once its row exists. */
    private static final class Planned {
        final int entryNo;
        final long productId;
        final String type;
        final Long purchaseOrderId;
        final int quantity;
        final BigDecimal unitPrice;
        final BigDecimal gross;
        final BigDecimal commission;
        final BigDecimal transport;
        final BigDecimal extra;
        final BigDecimal net;
        final PartnerSaleReportLineEntity mirrorsExisting;
        final Planned mirrorsPlanned;
        /** A buyback of a part on a settled-through container: booked on own stock at this cost (else null). */
        BigDecimal takeoverCostEur;
        PartnerSaleReportLineEntity written;

        private Planned(int entryNo, long productId, String type, Long purchaseOrderId, int quantity,
                        BigDecimal unitPrice, BigDecimal gross, BigDecimal commission, BigDecimal transport,
                        BigDecimal extra, BigDecimal net, PartnerSaleReportLineEntity mirrorsExisting,
                        Planned mirrorsPlanned) {
            this.entryNo = entryNo;
            this.productId = productId;
            this.type = type;
            this.purchaseOrderId = purchaseOrderId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.gross = gross;
            this.commission = commission;
            this.transport = transport;
            this.extra = extra;
            this.net = net;
            this.mirrorsExisting = mirrorsExisting;
            this.mirrorsPlanned = mirrorsPlanned;
        }

        static Planned sale(int entryNo, long productId, Long purchaseOrderId, PartnerSaleMoney.Sale money) {
            return new Planned(entryNo, productId, PartnerSaleReportLineEntity.SALE, purchaseOrderId, money.quantity(),
                    money.unitPriceEur(), money.grossEur(), money.commissionEur(), money.transportEur(),
                    money.extraCostEur(), money.netEur(), null, null);
        }

        static Planned buyback(int entryNo, long productId, Long purchaseOrderId, int quantity, BigDecimal unitPrice,
                               BigDecimal gross, PartnerSaleReportLineEntity mirrorsExisting, Planned mirrorsPlanned) {
            return new Planned(entryNo, productId, PartnerSaleReportLineEntity.BUYBACK, purchaseOrderId, quantity,
                    unitPrice, gross, PartnerSaleMoney.ZERO, PartnerSaleMoney.ZERO, PartnerSaleMoney.ZERO,
                    gross.negate(), mirrorsExisting, mirrorsPlanned);
        }
    }

    /** "Glass dome Gemengd": the tile's name with its variant. */
    static String label(PartnerProductNames.Names names) {
        return names.variant() == null ? names.name() : names.name() + " " + names.variant();
    }

    /** "Bel Enrosed (+32 ...)", or without a number when no staff account has one. */
    private String callEnrosed() {
        for (StaffContactService.Contact contact : contacts.list()) {
            if (contact.phone() != null && !contact.phone().isBlank()) return "Bel Enrosed (" + contact.phone().strip() + ")";
        }
        return "Bel Enrosed";
    }

    /** A unique-constraint violation anywhere in the cause chain (Hibernate's or the driver's, SQLState 23xxx). */
    static boolean uniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
