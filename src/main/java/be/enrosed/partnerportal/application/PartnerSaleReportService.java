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
import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerLinkEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalSettingsEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportCostEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerCountUnits;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes meldingen, edits them (money-only or rebooked, round 2) and withdraws them (contract sections 1 and 3).
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
    static final String OLD_VERSION = "Dit is een oude versie";
    static final String CONFIRMED_FOR_PARTNER = "Enrosed heeft deze melding al bevestigd; vraag Enrosed om een wijziging.";
    static final String CHANGED = "Deze melding is intussen gewijzigd; laad ze opnieuw";
    static final String WITHDRAWN = "Deze melding is ingetrokken";
    static final String TOKEN_MISSING = "Laad de melding opnieuw voor je ze wijzigt";
    static final String EDIT_REFUSED = "Wijzigen kan niet: ";
    static final String UNKNOWN_BUYER = "Onbekende klant";
    /** The void reason of an archive row (the old version of an edited melding). */
    public static final String ARCHIVE_REASON = "Gewijzigd";

    static final int MAX_ENTRIES = 60;
    static final int MAX_DAYS_BACK = 90;
    static final int MAX_NOTE = 500;
    static final int MAX_CLIENT_REF = 64;
    static final int MAX_REASON = 200;
    static final int MAX_QUANTITY = 1_000_000;
    static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("1000000");
    static final BigDecimal MAX_TOTAL = new BigDecimal("100000000");
    static final BigDecimal MAX_EXTRA_PCT = new BigDecimal("100");
    static final int MAX_COST_LINES = 5;
    static final int MAX_COST_DESCRIPTION = 80;

    /** What a create did: the melding, and false when a retry found it already there. */
    public record Outcome(long reportId, boolean created) {}

    private final PartnerPortalRepositories.Settings settingsRows;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.Lines lines;
    private final PartnerPortalRepositories.Costs costRows;
    private final PartnerPortalRepositories.CustomerLinks customerLinks;
    private final PartnerCustomerQueries customerQueries;
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
                                    PartnerPortalRepositories.Costs costRows,
                                    PartnerPortalRepositories.CustomerLinks customerLinks,
                                    PartnerCustomerQueries customerQueries,
                                    PartnerPortalSettings settings, PartnerContainerCapacity capacity,
                                    PartnerOpenParts openParts, StockService stock, StockBatchService batches,
                                    ProductRepository products, ProductCostHistoryService costHistory,
                                    SalesRepositories.Customers customers, StaffContactService contacts,
                                    CurrentActor actor, ActivityLogService activity,
                                    Event<PartnerSalePushNotifier.Ready> pushReady) {
        this.settingsRows = settingsRows;
        this.reports = reports;
        this.lines = lines;
        this.costRows = costRows;
        this.customerLinks = customerLinks;
        this.customerQueries = customerQueries;
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
        Customer customer = partnerCustomer(customerId);
        /* Lock 1: the partner. A retry of the same melding waits here and then finds it. */
        PartnerPortalSettingsEntity row = settingsRows.lockOrCreate(customerId);
        if (input.clientRef() != null) {
            Optional<PartnerSaleReportEntity> existing = reports.findByClientRef(customerId, input.clientRef());
            if (existing.isPresent()) return new Outcome(existing.get().id, false);
        }
        Long buyerId = buyer(customerId, input, staff, null);
        PartnerPortalSettings.Effective applied = settings.of(customerId, row);
        StockLocation location = applied.location();
        /* Lock 2: the partner's containers, ascending. Lock 3: the products, ascending. */
        List<PurchaseOrder> containers = capacity.lockContainers(customerId);
        Map<Long, Product> catalog = lockProducts(input.productIds());
        List<EntryInput> resolved = resolve(input, catalog);
        boolean auction = PartnerPortalRules.CHANNEL_AUCTION.equals(input.channel());
        BigDecimal commissionPct = auction ? applied.auctionCommissionPct() : BigDecimal.ZERO;
        BigDecimal transportPct = auction ? applied.auctionTransportPct() : BigDecimal.ZERO;

        /* ---- the plan: every part, checked, before anything is written */
        Plan plan = plan(customerId, resolved, input.moneyCosts(), commissionPct, transportPct, location, containers,
                catalog, staff, false);

        /* ---- the writes */
        ActorRef who = actor.current();
        /* Micro precision, as the database keeps it: the editToken read back equals the one answered now. */
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PartnerSaleReportEntity report = new PartnerSaleReportEntity();
        report.customerId = customerId;
        report.loginId = loginId;
        report.channel = input.channel();
        report.soldOn = input.soldOn();
        report.commissionPct = commissionPct.setScale(4, RoundingMode.HALF_UP);
        report.transportPct = transportPct.setScale(4, RoundingMode.HALF_UP);
        /* Round 2 keeps the extra cost in partner_sale_report_cost; extra_cost_mode/value stay round-1 only. */
        report.note = input.note();
        report.buyerCustomerId = buyerId;
        report.locationId = location.id();
        report.clientRef = input.clientRef();
        report.createdAt = now;
        report.createdBy = who.username();
        report.createdByName = cut(who.displayName(), 100);
        reports.persist(report);
        reports.flush();
        writeCosts(report.id, input.costs());
        writeParts(report, plan, location, catalog);

        Tally tally = Tally.of(plan.written(), catalog);
        String number = PartnerPortalRules.reportNumber(report.id);
        activity.record(ActivityLogService.ACTION_CREATED, ENTITY, report.id.toString(),
                number + " · " + customer.company(), tally.summary(report.channel));
        pushReady.fire(new PartnerSalePushNotifier.Ready(report.id, tally.pushQuantity(),
                PartnerSaleTexts.kind(report.channel, tally.buybackOnly()), staff ? who.username() : null));
        return new Outcome(report.id, true);
    }

    /** The partner customer behind a melding; refused when it is gone or no longer a partner. */
    private Customer partnerCustomer(long customerId) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
        if (!customer.partner()) throw new BusinessRuleException("Deze klant is geen partner");
        return customer;
    }

    /** Lock 3: the products, ascending (each with its row lock), as known now. */
    private Map<Long, Product> lockProducts(Collection<Long> productIds) {
        Map<Long, Product> catalog = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(productIds)) {
            stock.lockProduct(productId);
            catalog.put(productId, products.findById(productId)
                    .orElseThrow(() -> new BusinessRuleException("Product #" + productId + " bestaat niet meer")));
        }
        return catalog;
    }

    /** Round-2 parts are typed in a count unit: converted to sales units once the products are locked. */
    private static List<EntryInput> resolve(Input input, Map<Long, Product> catalog) {
        List<EntryInput> resolved = new ArrayList<>();
        for (EntryInput entry : input.entries()) resolved.add(entry.resolve(catalog.get(entry.productId())));
        return resolved;
    }

    /** Every new part of a melding, planned and checked; nothing is written yet. */
    private record Plan(List<Planned> sales, List<Planned> buybacks, boolean batchesOn) {
        List<PartnerSaleReportLineEntity> written() {
            List<PartnerSaleReportLineEntity> result = new ArrayList<>();
            for (Planned part : sales) if (part.written != null) result.add(part.written);
            for (Planned part : buybacks) if (part.written != null) result.add(part.written);
            return result;
        }
    }

    /**
     * The parts of these entries at this location: each sale over the partner's containers (oldest first, up
     * to their capacity) and then own stock, each buyback over the sale parts it mirrors. Reads stock, capacity
     * and open parts as they are now (an edit first takes its old parts out). edit only changes the wording.
     */
    private Plan plan(long customerId, List<EntryInput> resolved, List<PartnerSaleMoney.CostLine> costs,
                      BigDecimal commissionPct, BigDecimal transportPct, StockLocation location,
                      List<PurchaseOrder> containers, Map<Long, Product> catalog, boolean staff, boolean edit) {
        boolean batchesOn = batches.active();
        List<EntryInput> saleEntries = resolved.stream().filter(entry -> entry.sale() != null).toList();
        List<PartnerSaleMoney.Sale> saleMoney = PartnerSaleMoney.sales(saleEntries.stream()
                        .map(entry -> PartnerSaleMoney.price(entry.sale().quantity(), entry.sale().unitPrice(),
                                entry.sale().total())).toList(),
                commissionPct, transportPct, costs);
        List<PurchaseOrder> candidates = PartnerContainerCapacity.candidates(containers, location);
        Map<Long, List<PartnerContainerCapacity.Slot>> slots = capacity.slots(customerId, candidates,
                saleEntries.stream().map(EntryInput::productId).toList(), batchesOn);
        List<Planned> sales = new ArrayList<>();
        Map<Integer, List<Planned>> salesByEntry = new HashMap<>();
        for (int index = 0; index < saleEntries.size(); index++) {
            EntryInput entry = saleEntries.get(index);
            List<Planned> parts = planSale(entry, saleMoney.get(index), catalog.get(entry.productId()), location,
                    slots.getOrDefault(entry.productId(), List.of()), batchesOn, staff, edit);
            sales.addAll(parts);
            salesByEntry.put(entry.entryNo(), parts);
        }
        List<Planned> buybacks = new ArrayList<>();
        for (EntryInput entry : resolved) {
            if (entry.buyback() == null) continue;
            buybacks.addAll(planBuyback(customerId, entry, catalog.get(entry.productId()),
                    salesByEntry.getOrDefault(entry.entryNo(), List.of()), commissionPct, transportPct, edit));
        }
        return new Plan(sales, buybacks, batchesOn);
    }

    /** Writes the planned parts of this melding: one stock movement each, sales first, then the buybacks. */
    private void writeParts(PartnerSaleReportEntity report, Plan plan, StockLocation location,
                            Map<Long, Product> catalog) {
        String number = PartnerPortalRules.reportNumber(report.id);
        for (Planned part : plan.sales()) {
            Long movementId = stock.partnerMove(part.productId, location.id(), -part.quantity,
                    StockMovement.Kind.PARTNER_SALE, "Veilingverkoop " + number, part.purchaseOrderId, report.soldOn,
                    StockBatchContext.pinned(part.purchaseOrderId));
            OwnCost cost = part.purchaseOrderId == null
                    ? ownUnitCost(catalog.get(part.productId), movementId, part.quantity, plan.batchesOn()) : null;
            part.written = write(report, part, movementId, cost == null ? null : cost.unitCostEur(), null);
            if (cost != null && cost.estimated()) part.written.unitCostEstimated = true;
        }
        Map<Long, List<Long>> earlierReversals = lines.reversedBuybackMovements(plan.buybacks().stream()
                .filter(part -> part.mirrorsExisting != null).map(part -> part.mirrorsExisting.id).toList());
        for (Planned part : plan.buybacks()) {
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
    }

    /**
     * What a melding's parts come to, for the activity line and the push: quantities in sales units and
     * pieces, and the net (a buyback part's net is -(gross + commission + transport), subtracted whole).
     */
    private record Tally(int saleQuantity, int buybackQuantity, int salePieces, int buybackPieces, BigDecimal net) {
        static Tally of(Collection<PartnerSaleReportLineEntity> parts, Map<Long, Product> catalog) {
            int saleQuantity = 0;
            int buybackQuantity = 0;
            int salePieces = 0;
            int buybackPieces = 0;
            BigDecimal net = BigDecimal.ZERO;
            for (PartnerSaleReportLineEntity part : parts) {
                if (!part.isSale() && !part.isBuyback()) continue;
                int pieces = part.quantityOrZero() * piecesPerSalesUnit(catalog.get(part.productId));
                if (part.isSale()) {
                    saleQuantity += part.quantityOrZero();
                    salePieces += pieces;
                } else {
                    buybackQuantity += part.quantityOrZero();
                    buybackPieces += pieces;
                }
                if (part.netEur != null) net = net.add(part.netEur);
            }
            return new Tally(saleQuantity, buybackQuantity, salePieces, buybackPieces, net);
        }

        boolean buybackOnly() {
            return saleQuantity == 0;
        }

        int pushQuantity() {
            return buybackOnly() ? buybackPieces : salePieces;
        }

        /** "120 verkocht · 12 teruggekocht" or "12 teruggekocht" (pieces). */
        String counts() {
            return buybackOnly() ? buybackPieces + " teruggekocht"
                    : salePieces + " verkocht" + (buybackPieces > 0 ? " · " + buybackPieces + " teruggekocht" : "");
        }

        /** "Veiling verkocht: 120 verkocht · netto € 98,40", "Teruggekocht op de klok: 12 teruggekocht · terugkoop € 13,20". */
        String summary(String channel) {
            return PartnerSaleTexts.title(channel, buybackOnly()) + ": " + counts() + " · "
                    + (buybackOnly() ? "terugkoop " + PartnerSaleTexts.euro(net.negate()) : "netto " + PartnerSaleTexts.euro(net));
        }
    }

    /** "Wijzigen kan niet: er liggen ..." for an edit, the create's own sentence otherwise. */
    private static String refusal(boolean edit, String sentence) {
        if (!edit || sentence.isEmpty()) return sentence;
        return EDIT_REFUSED + Character.toLowerCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /** The parts of one sale entry: the containers in order up to their capacity, the rest own stock. */
    private List<Planned> planSale(EntryInput entry, PartnerSaleMoney.Sale money, Product product,
                                   StockLocation location, List<PartnerContainerCapacity.Slot> slots,
                                   boolean batchesOn, boolean staff, boolean edit) {
        PartnerProductNames.Names names = PartnerProductNames.of(product);
        int wanted = money.quantity();
        int atLocation = stock.quantityAt(product.id(), location.id());
        if (wanted > atLocation) {
            /* Typed in a count unit: told in pieces ("360 rozen"), else in sales units as round 1. */
            PartnerCountUnits.Counting counting = PartnerCountUnits.of(product);
            String left = entry.sale().typed()
                    ? counting.pieces((long) Math.max(0, atLocation) * counting.piecesPerSalesUnit())
                    : PartnerSaleTexts.quantity(Math.max(0, atLocation), names.unitOne(), names.unitOther());
            String head = "Er liggen nog maar " + left + " van " + label(names);
            throw new BusinessRuleException(refusal(edit, staff ? head + " op " + location.name() + ". Werk eerst de voorraad bij."
                    : head + ". " + callEnrosed() + " om de voorraad bij te werken."));
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
                    throw new BusinessRuleException(refusal(edit, PartnerSaleTexts.quantity(blocked, names.unitOne(),
                            names.unitOther()) + " van " + label(names) + (blocked == 1 ? " is" : " zijn")
                            + " al afgerekend maar nog niet afgeboekt; "
                            + (staff ? "boek die eerst af." : "Enrosed boekt die eerst af.")));
                }
            }
            sources.add(null);
            quantities.add(rest);
        }
        List<PartnerSaleMoney.Sale> split = PartnerSaleMoney.split(money, quantities);
        List<Planned> parts = new ArrayList<>();
        for (int index = 0; index < split.size(); index++) {
            PartnerSaleMoney.Sale part = split.get(index);
            parts.add(Planned.sale(entry.entryNo(), product.id(), sources.get(index), part, entry.sale().entered()));
        }
        return parts;
    }

    /**
     * The parts of one buyback: mirrored sale parts of this product, first this melding's (the
     * last-filled part first, so a sale of 100 with 1 back reads as a sale of 99), then the
     * partner's other open sale parts newest first, then invoiced ones newest first; each only up
     * to its quantity minus what live buybacks already mirror.
     */
    private List<Planned> planBuyback(long customerId, EntryInput entry, Product product, List<Planned> sameMelding,
                                      BigDecimal commissionPct, BigDecimal transportPct, boolean edit) {
        PartnerProductNames.Names names = PartnerProductNames.of(product);
        PartnerSaleMoney.Priced priced = PartnerSaleMoney.price(entry.buyback().quantity(), entry.buyback().unitPrice(),
                entry.buyback().total());
        /* The pieces come back to Enrosed: the same commission and transport as a sale (auction only). */
        PartnerSaleMoney.Buyback money = PartnerSaleMoney.buyback(priced, commissionPct, transportPct);
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
            throw new BusinessRuleException(refusal(edit, found == 0
                    ? "Meld eerst de verkoop op de veiling; een terugkoop hoort bij een gemelde verkoop."
                    : "Van " + label(names) + " " + (found == 1 ? "staat" : "staan") + " maar "
                    + PartnerSaleTexts.quantity(found, names.unitOne(), names.unitOther())
                    + " als verkocht gemeld; een terugkoop hoort bij een gemelde verkoop."));
        }
        List<PartnerSaleMoney.Buyback> split = PartnerSaleMoney.splitBuyback(money, quantities);
        Entered entered = entry.buyback().entered();
        List<Planned> parts = new ArrayList<>();
        Map<Long, PartnerContainerCapacity.Settled> settled = new HashMap<>();
        for (int index = 0; index < mirrors.size(); index++) {
            Object mirror = mirrors.get(index);
            if (mirror instanceof Planned planned) {
                parts.add(Planned.buyback(entry.entryNo(), product.id(), planned.purchaseOrderId, split.get(index),
                        null, planned, entered));
                continue;
            }
            PartnerSaleReportLineEntity line = (PartnerSaleReportLineEntity) mirror;
            /* A container with nothing of this product left to settle can never invoice a buyback: Enrosed takes
               the pieces over as own stock at what the container's settlements charged for them. */
            PartnerContainerCapacity.Settled closed = line.purchaseOrderId == null ? null
                    : settled.computeIfAbsent(line.purchaseOrderId, id -> capacity.settled(id, product.id()));
            boolean takeOver = closed != null && closed.closed();
            Planned part = Planned.buyback(entry.entryNo(), product.id(), takeOver ? null : line.purchaseOrderId,
                    split.get(index), line, null, entered);
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
        if (part.entered != null) {
            line.enteredUnit = part.entered.unit();
            line.enteredCount = part.entered.count();
            line.piecePriceEur = part.entered.piecePriceEur();
            line.enteredPiecesPerUnit = part.entered.piecesPerUnit();
        }
        lines.persist(line);
        lines.flush();
        return line;
    }

    /** The extra cost lines of a new melding, numbered 1..n in the order typed. */
    private void writeCosts(long reportId, List<CostInput> lines) {
        int number = 0;
        for (CostInput line : lines) {
            PartnerSaleReportCostEntity row = new PartnerSaleReportCostEntity();
            row.reportId = reportId;
            row.lineNo = ++number;
            row.description = line.description();
            row.costMode = line.mode();
            row.costValue = line.value();
            costRows.persist(row);
        }
        if (number > 0) costRows.flush();
    }

    /**
     * The buyer of a direct sale, checked after the partner lock for partner and staff alike: an
     * existing customer that is no partner and not this partner, else one uniform "Onbekende klant"
     * (so an id never tells whether a customer exists). A partner may also only name a customer its
     * search would show (not one known only from website requests still waiting for review, unless it
     * added it), except the buyer the melding already has ({@code kept}). Required only on a round-2
     * partner request of a direct sale; older requests without a buyer are accepted.
     */
    private Long buyer(long partnerId, Input input, boolean staff, Long kept) {
        Long buyerId = input.buyerCustomerId();
        if (buyerId == null) {
            if (!staff && input.roundTwo() && PartnerPortalRules.CHANNEL_DIRECT.equals(input.channel())) {
                throw new BusinessRuleException("Kies de klant of voeg er een toe");
            }
            return null;
        }
        Customer buyer = buyerId == partnerId ? null : customers.findById(buyerId).orElse(null);
        if (buyer == null || buyer.partner()) throw new BusinessRuleException(UNKNOWN_BUYER);
        if (!staff && !buyerId.equals(kept)) {
            boolean added = customerLinks.find(partnerId, buyerId).map(PartnerCustomerLinkEntity::isAddedByPartner).orElse(false);
            if (!customerQueries.visible(partnerId, buyerId, added)) throw new BusinessRuleException(UNKNOWN_BUYER);
        }
        return buyerId;
    }

    private static int piecesPerSalesUnit(Product product) {
        return PartnerCountUnits.of(product).piecesPerSalesUnit();
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

    /* ================================================================== edit */

    /** What an edit did: rebooked = its stock was reversed and booked again (else only its money changed). */
    public record Edited(long reportId, boolean rebooked) {}

    /**
     * Bewerken (partner {@code PUT /api/partner-portal/reports/{id}}, staff {@code PUT /api/partner-sales/reports/{id}}):
     * the create body plus {@code expectedToken}, the editToken the client loaded. One transaction with the create's
     * lock order (the partner, its containers ascending, the old and new products together ascending); every check
     * runs after the partner lock, and any refusal leaves the melding as it was. Two paths, the server decides:
     * <ul>
     * <li><b>money-only</b>: per product the sale and buyback quantities (sales units) are unchanged, the sold-on day
     *     too, and the channel changes only without a buyback. The money is computed again over the same parts and
     *     written on the same line ids (prices, costs, what was typed); no stock movement, no archive of lines, no
     *     mirror or stock guards (an open part is held by nothing);</li>
     * <li><b>rebook</b>: every part is reversed as a withdrawal does (reference "Gewijzigd: VV-n"), the old lines
     *     move to an archive row (revision_of = this melding, voided "Gewijzigd"), and the new parts are planned and
     *     booked on this same melding at its own location. Refused while a buyback of another live melding mirrors
     *     one of its sales; the shelf and the containers are checked on the net change old -> new.</li>
     * </ul>
     * The commission and transport snapshot stays unless the channel changes; the 90-day rule applies only to a
     * changed sold-on day; a partner cannot edit a confirmed melding, staff can (and it stays confirmed). The cost
     * lines replace the old ones, which move to the archive row when they changed.
     */
    @Transactional
    public Edited update(Long partnerCustomerId, long reportId, PartnerSaleRequests.Report request, boolean staff) {
        PartnerSaleReportEntity report = reports.findById(reportId);
        if (report == null || (partnerCustomerId != null && !partnerCustomerId.equals(report.customerId))) {
            throw new NotFoundException("Melding", reportId);
        }
        if (request == null) throw new BusinessRuleException("Geen gegevens ontvangen");
        if (staff && request.customerId() != null && !request.customerId().equals(report.customerId)) {
            throw PartnerRefusals.badRequest("Deze melding hoort bij een andere partner");
        }
        String token = request.expectedToken() == null ? "" : request.expectedToken().strip();
        if (token.isEmpty()) throw PartnerRefusals.badRequest(TOKEN_MISSING);
        Input input = validate(request, report.soldOn, false);
        long customerId = report.customerId;
        Customer customer = partnerCustomer(customerId);
        /* Lock 1, then the melding as it is now: everything below is checked against that. */
        PartnerPortalSettingsEntity row = settingsRows.lockOrCreate(customerId);
        reports.getEntityManager().refresh(report);
        if (report.isArchive()) throw new BusinessRuleException(OLD_VERSION);
        if (report.isVoided()) throw new BusinessRuleException(WITHDRAWN);
        if (!token.equals(report.editToken())) throw new BusinessRuleException(CHANGED);
        if (!staff && report.confirmedAt != null) throw new BusinessRuleException(CONFIRMED_FOR_PARTNER);
        LocalDate soldOn = request.soldOn() == null ? report.soldOn : input.soldOn();
        boolean dateChanged = !soldOn.equals(report.soldOn);
        if (dateChanged && soldOn.isBefore(PartnerPortalRules.today().minusDays(MAX_DAYS_BACK))) {
            throw new BusinessRuleException("Een melding gaat hoogstens " + MAX_DAYS_BACK + " dagen terug");
        }
        Long buyerId = buyer(customerId, input, staff, report.buyerCustomerId);
        /* Lock 2 and 3: the containers, then the old and the new products together, ascending. */
        List<PurchaseOrder> containers = capacity.lockContainers(customerId);
        List<PartnerSaleReportLineEntity> old = parts(lines.byReport(reportId));
        Set<Long> productIds = new TreeSet<>(input.productIds());
        for (PartnerSaleReportLineEntity part : old) if (part.productId != null) productIds.add(part.productId);
        Map<Long, Product> catalog = lockProducts(productIds);
        PartnerOpenParts.Rule rule = openParts.forLines(old);
        if (old.stream().anyMatch(part -> !rule.isOpen(part, false))) {
            throw new BusinessRuleException("Deze melding is al (deels) gefactureerd; wijzigen kan niet meer");
        }
        List<EntryInput> resolved = resolve(input, catalog);
        boolean channelChanged = !input.channel().equals(report.channel);
        BigDecimal commissionPct;
        BigDecimal transportPct;
        if (channelChanged) {
            /* Another channel: the percents that apply to it now (the settings on AUCTION, 0 on DIRECT). */
            PartnerPortalSettings.Effective applied = settings.of(customerId, row);
            boolean auction = PartnerPortalRules.CHANNEL_AUCTION.equals(input.channel());
            commissionPct = auction ? applied.auctionCommissionPct() : BigDecimal.ZERO;
            transportPct = auction ? applied.auctionTransportPct() : BigDecimal.ZERO;
        } else {
            commissionPct = report.commissionPct == null ? BigDecimal.ZERO : report.commissionPct;
            transportPct = report.transportPct == null ? BigDecimal.ZERO : report.transportPct;
        }
        boolean anyBuyback = old.stream().anyMatch(PartnerSaleReportLineEntity::isBuyback)
                || resolved.stream().anyMatch(entry -> entry.buyback() != null);
        boolean moneyOnly = !dateChanged && (!channelChanged || !anyBuyback) && sameQuantities(old, resolved);
        long locationId = locationOf(report);
        StockLocation location = location(locationId);
        String number = PartnerPortalRules.reportNumber(reportId);
        Guarded guarded = moneyOnly ? null : rebookGuards(report, old, resolved, containers, catalog, locationId, staff);

        BigDecimal netBefore = Tally.of(old, catalog).net();
        List<CostInput> oldCosts = costInputs(report, costRows.byReport(reportId));
        boolean costsChanged = !oldCosts.equals(input.costs());
        if (moneyOnly) {
            /* The money again over the same parts (no stock moves); an edit that changes nothing writes nothing. */
            String before = fingerprint(old);
            rePrice(old, resolved, input.moneyCosts(), commissionPct, transportPct);
            if (!costsChanged && report.extraCostMode == null && before.equals(fingerprint(old))
                    && !channelChanged && Objects.equals(report.note, input.note())
                    && Objects.equals(report.buyerCustomerId, buyerId)) {
                return new Edited(reportId, false);
            }
        }
        ActorRef who = actor.current();
        Instant now = nextEditInstant(report);
        PartnerSaleReportEntity archive = !moneyOnly || costsChanged ? archive(report, now, who) : null;
        if (!moneyOnly) {
            /* The old parts go back exactly as a withdrawal puts them (the shelf may dip: the net was checked). */
            reverse(report, old, locationId, "Gewijzigd: " + number, true);
            lines.flush();
            for (PartnerSaleReportLineEntity line : lines.byReport(reportId)) line.reportId = archive.id;
            lines.flush();
        }
        /* The cost lines: the old ones to the archive row when they changed (or the melding rebooked), else as they are. */
        List<PartnerSaleReportCostEntity> currentCosts = costRows.byReport(reportId);
        boolean writeCosts = archive != null || currentCosts.isEmpty();
        if (archive != null) {
            for (PartnerSaleReportCostEntity cost : currentCosts) cost.reportId = archive.id;
            costRows.flush();
        }

        report.channel = input.channel();
        report.soldOn = soldOn;
        report.commissionPct = commissionPct.setScale(4, RoundingMode.HALF_UP);
        report.transportPct = transportPct.setScale(4, RoundingMode.HALF_UP);
        report.note = input.note();
        report.buyerCustomerId = buyerId;
        /* The cost lines live in partner_sale_report_cost from now on, also for a round-1 melding. */
        report.extraCostMode = null;
        report.extraCostValue = null;
        report.updatedAt = now;
        report.updatedBy = who.username();
        report.updatedByName = cut(who.displayName(), 100);
        reports.flush();

        if (moneyOnly) {
            lines.flush();
        } else {
            Plan plan = plan(customerId, resolved, input.moneyCosts(), commissionPct, transportPct, location,
                    containers, catalog, staff, true);
            requireRoom(old, plan, guarded.room(), catalog);
            writeParts(report, plan, location, catalog);
            /* The net was checked beforehand; this only makes sure no step on the way left the shelf lower. */
            for (Long productId : productIds) {
                int after = stock.quantityAt(productId, locationId);
                if (after < 0 && after < guarded.shelf().getOrDefault(productId, 0)) {
                    throw new BusinessRuleException(EDIT_REFUSED + "de teruggekochte stuks van "
                            + productLabel(catalog, productId) + " liggen niet meer op voorraad");
                }
            }
        }
        if (writeCosts) writeCosts(reportId, input.costs());

        Tally tally = Tally.of(parts(lines.byReport(reportId)), catalog);
        activity.record(ActivityLogService.ACTION_UPDATED, ENTITY, Long.toString(reportId),
                number + " · " + customer.company(),
                cut((moneyOnly ? "Bedragen gewijzigd: " : "Gewijzigd: ") + tally.counts() + " · netto "
                        + PartnerSaleTexts.euro(netBefore) + " → " + PartnerSaleTexts.euro(tally.net()), 500));
        pushReady.fire(new PartnerSalePushNotifier.Ready(reportId, tally.pushQuantity(),
                PartnerSaleTexts.kind(report.channel, tally.buybackOnly()), staff ? who.username() : null,
                PartnerSalePushNotifier.TITLE_CHANGED));
        return new Edited(reportId, !moneyOnly);
    }

    /** What a money-only edit may change on the parts, to tell an edit that changes nothing. */
    private static String fingerprint(List<PartnerSaleReportLineEntity> parts) {
        StringBuilder text = new StringBuilder();
        for (PartnerSaleReportLineEntity part : parts) {
            text.append(part.id).append('|').append(part.entryNo).append('|').append(plain(part.unitPriceEur))
                    .append('|').append(plain(part.grossEur)).append('|').append(plain(part.commissionEur))
                    .append('|').append(plain(part.transportEur)).append('|').append(plain(part.extraCostEur))
                    .append('|').append(plain(part.netEur)).append('|').append(part.enteredUnit).append('|')
                    .append(part.enteredCount).append('|').append(plain(part.piecePriceEur)).append('|')
                    .append(part.enteredPiecesPerUnit).append(';');
        }
        return text.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** SALE and BUYBACK rows only (a REVERSAL row is never a part). */
    private static List<PartnerSaleReportLineEntity> parts(List<PartnerSaleReportLineEntity> rows) {
        return rows.stream().filter(line -> line.isSale() || line.isBuyback()).toList();
    }

    /**
     * The edit's token instant: now at micro precision (as the database keeps it), and always after the
     * token it replaces, so two edits never answer the same editToken.
     */
    private static Instant nextEditInstant(PartnerSaleReportEntity report) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant last = report.updatedAt != null ? report.updatedAt : report.createdAt;
        if (last != null && !now.isAfter(last)) now = last.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
        return now;
    }

    /** Per product: the same sale and buyback quantity (sales units), and no product added or dropped. */
    private static boolean sameQuantities(List<PartnerSaleReportLineEntity> old, List<EntryInput> resolved) {
        Map<Long, List<Integer>> before = new HashMap<>();
        for (PartnerSaleReportLineEntity part : old) {
            List<Integer> sums = before.computeIfAbsent(part.productId, key -> new ArrayList<>(List.of(0, 0)));
            sums.set(part.isSale() ? 0 : 1, sums.get(part.isSale() ? 0 : 1) + part.quantityOrZero());
        }
        Map<Long, List<Integer>> after = new HashMap<>();
        for (EntryInput entry : resolved) {
            after.put(entry.productId(), new ArrayList<>(List.of(entry.sale() == null ? 0 : entry.sale().quantity(),
                    entry.buyback() == null ? 0 : entry.buyback().quantity())));
        }
        return before.equals(after);
    }

    /**
     * Money-only: the sale and buyback money of every entry again over the very same parts (their quantities, in
     * the order they were written), the entry numbers and what was typed as sent now.
     */
    private static void rePrice(List<PartnerSaleReportLineEntity> old, List<EntryInput> resolved,
                                List<PartnerSaleMoney.CostLine> costs, BigDecimal commissionPct, BigDecimal transportPct) {
        Map<Long, List<PartnerSaleReportLineEntity>> salesOf = new HashMap<>();
        Map<Long, List<PartnerSaleReportLineEntity>> buybacksOf = new HashMap<>();
        for (PartnerSaleReportLineEntity part : old) {
            (part.isSale() ? salesOf : buybacksOf).computeIfAbsent(part.productId, key -> new ArrayList<>()).add(part);
        }
        Comparator<PartnerSaleReportLineEntity> written = Comparator.comparing(line -> line.id);
        salesOf.values().forEach(list -> list.sort(written));
        buybacksOf.values().forEach(list -> list.sort(written));
        List<EntryInput> saleEntries = resolved.stream().filter(entry -> entry.sale() != null).toList();
        List<PartnerSaleMoney.Sale> money = PartnerSaleMoney.sales(saleEntries.stream()
                        .map(entry -> PartnerSaleMoney.price(entry.sale().quantity(), entry.sale().unitPrice(),
                                entry.sale().total())).toList(),
                commissionPct, transportPct, costs);
        for (int index = 0; index < saleEntries.size(); index++) {
            EntryInput entry = saleEntries.get(index);
            List<PartnerSaleReportLineEntity> parts = salesOf.get(entry.productId());
            List<PartnerSaleMoney.Sale> split = PartnerSaleMoney.split(money.get(index),
                    parts.stream().map(PartnerSaleReportLineEntity::quantityOrZero).toList());
            for (int part = 0; part < parts.size(); part++) {
                PartnerSaleMoney.Sale share = split.get(part);
                apply(parts.get(part), entry.entryNo(), share.unitPriceEur(), share.grossEur(), share.commissionEur(),
                        share.transportEur(), share.extraCostEur(), share.netEur(), entry.sale().entered());
            }
        }
        for (EntryInput entry : resolved) {
            if (entry.buyback() == null) continue;
            List<PartnerSaleReportLineEntity> parts = buybacksOf.get(entry.productId());
            PartnerSaleMoney.Buyback buyback = PartnerSaleMoney.buyback(PartnerSaleMoney.price(entry.buyback().quantity(),
                    entry.buyback().unitPrice(), entry.buyback().total()), commissionPct, transportPct);
            List<PartnerSaleMoney.Buyback> split = PartnerSaleMoney.splitBuyback(buyback,
                    parts.stream().map(PartnerSaleReportLineEntity::quantityOrZero).toList());
            for (int part = 0; part < parts.size(); part++) {
                PartnerSaleMoney.Buyback share = split.get(part);
                apply(parts.get(part), entry.entryNo(), share.unitPriceEur(), share.grossEur(), share.commissionEur(),
                        share.transportEur(), PartnerSaleMoney.ZERO, share.netEur(), entry.buyback().entered());
            }
        }
    }

    private static void apply(PartnerSaleReportLineEntity line, int entryNo, BigDecimal unitPrice, BigDecimal gross,
                              BigDecimal commission, BigDecimal transport, BigDecimal extra, BigDecimal net,
                              Entered entered) {
        line.entryNo = entryNo;
        line.unitPriceEur = unitPrice;
        line.grossEur = gross;
        line.commissionEur = commission;
        line.transportEur = transport;
        line.extraCostEur = extra;
        line.netEur = net;
        line.enteredUnit = entered == null ? null : entered.unit();
        line.enteredCount = entered == null ? null : entered.count();
        line.piecePriceEur = entered == null ? null : entered.piecePriceEur();
        line.enteredPiecesPerUnit = entered == null ? null : entered.piecesPerUnit();
    }

    /**
     * The rebook's checks before anything is written: no buyback of another live melding mirrors one of its sales,
     * and per product the shelf holds the net change (what the new melding takes beyond the old one). Answers the
     * room every container of the old parts has now (unclamped), for {@link #requireRoom}, and the shelf now.
     */
    private Guarded rebookGuards(PartnerSaleReportEntity report, List<PartnerSaleReportLineEntity> old,
                                 List<EntryInput> resolved, List<PurchaseOrder> containers,
                                 Map<Long, Product> catalog, long locationId, boolean staff) {
        Map<Long, Integer> shelf = new HashMap<>();
        Set<Long> saleIds = new HashSet<>();
        for (PartnerSaleReportLineEntity part : old) if (part.isSale()) saleIds.add(part.id);
        Set<Long> mirroring = new TreeSet<>();
        for (Object[] row : lines.liveBuybacksMirroring(saleIds)) {
            long other = ((Number) row[1]).longValue();
            if (other != report.id) mirroring.add(other);
        }
        if (!mirroring.isEmpty()) {
            throw new BusinessRuleException(EDIT_REFUSED + "trek eerst de terugkoop in ("
                    + String.join(", ", mirroring.stream().map(PartnerPortalRules::reportNumber).toList())
                    + "); een prijs of kost wijzigen kan wel");
        }
        Map<Long, int[]> oldSides = new HashMap<>();
        for (PartnerSaleReportLineEntity part : old) {
            oldSides.computeIfAbsent(part.productId, key -> new int[2])[part.isSale() ? 0 : 1] += part.quantityOrZero();
        }
        Map<Long, int[]> newSides = new HashMap<>();
        for (EntryInput entry : resolved) {
            int[] sides = newSides.computeIfAbsent(entry.productId(), key -> new int[2]);
            if (entry.sale() != null) sides[0] += entry.sale().quantity();
            if (entry.buyback() != null) sides[1] += entry.buyback().quantity();
        }
        Set<Long> productIds = new TreeSet<>(oldSides.keySet());
        productIds.addAll(newSides.keySet());
        for (Long productId : productIds) {
            int[] before = oldSides.getOrDefault(productId, new int[2]);
            int[] after = newSides.getOrDefault(productId, new int[2]);
            int at = stock.quantityAt(productId, locationId);
            shelf.put(productId, at);
            /* Stock now + what the old melding took out net - what the new one takes out net; refused only when
               the edit itself takes the shelf below zero. */
            int change = (before[0] - before[1]) - (after[0] - after[1]);
            if (change >= 0 || at + change >= 0) continue;
            Product product = catalog.get(productId);
            if (after[0] > before[0]) {
                int left = Math.max(0, at + before[0] - before[1]);
                PartnerProductNames.Names names = PartnerProductNames.of(product);
                PartnerCountUnits.Counting counting = PartnerCountUnits.of(product);
                String head = EDIT_REFUSED + "er liggen nog maar "
                        + counting.pieces((long) left * counting.piecesPerSalesUnit()) + " van " + label(names);
                throw new BusinessRuleException(staff ? head + " op " + location(locationId).name() + ". Werk eerst de voorraad bij."
                        : head + ". " + callEnrosed() + " om de voorraad bij te werken.");
            }
            throw new BusinessRuleException(EDIT_REFUSED + "de teruggekochte stuks van " + productLabel(catalog, productId)
                    + " liggen niet meer op voorraad");
        }
        /* The room the old parts' containers have now, before anything goes back. */
        Set<Long> containerIds = new HashSet<>();
        Set<Long> containerProducts = new HashSet<>();
        for (PartnerSaleReportLineEntity part : old) {
            if (part.purchaseOrderId == null) continue;
            containerIds.add(part.purchaseOrderId);
            containerProducts.add(part.productId);
        }
        Map<String, Integer> room = new HashMap<>();
        if (containerIds.isEmpty()) return new Guarded(room, shelf);
        List<PurchaseOrder> involved = containers.stream().filter(order -> containerIds.contains(order.id())).toList();
        capacity.slots(report.customerId, involved, containerProducts, batches.active()).forEach((productId, slots) -> {
            for (PartnerContainerCapacity.Slot slot : slots) {
                int left = slot.ledgerRemaining() - slot.historicShipped() - slot.openNet();
                if (slot.batchUnitsLeft() != null) left = Math.min(left, slot.batchUnitsLeft());
                room.put(PartnerContainerCapacity.key(slot.purchaseOrderId(), productId), left);
            }
        });
        return new Guarded(room, shelf);
    }

    /** What a rebook checked before writing: the room per container:product (unclamped), the shelf per product. */
    private record Guarded(Map<String, Integer> room, Map<Long, Integer> shelf) {}

    /**
     * A rebook that leaves a container claimed more than before (its old buybacks there now go elsewhere, or
     * nowhere) needs that room in the container as it was before the edit: pieces a buyback put back may have
     * been reported sold from it again since. A new sale only takes the room the plan found.
     */
    private static void requireRoom(List<PartnerSaleReportLineEntity> old, Plan plan, Map<String, Integer> roomBefore,
                                    Map<Long, Product> catalog) {
        Map<String, Integer> delta = new LinkedHashMap<>();
        Set<String> hadOld = new HashSet<>();
        for (PartnerSaleReportLineEntity part : old) {
            if (part.purchaseOrderId == null) continue;
            String key = PartnerContainerCapacity.key(part.purchaseOrderId, part.productId);
            hadOld.add(key);
            delta.merge(key, part.isSale() ? -part.quantityOrZero() : part.quantityOrZero(), Integer::sum);
        }
        for (Planned part : plan.sales()) {
            if (part.purchaseOrderId != null) {
                delta.merge(PartnerContainerCapacity.key(part.purchaseOrderId, part.productId), part.quantity, Integer::sum);
            }
        }
        for (Planned part : plan.buybacks()) {
            if (part.purchaseOrderId != null) {
                delta.merge(PartnerContainerCapacity.key(part.purchaseOrderId, part.productId), -part.quantity, Integer::sum);
            }
        }
        delta.forEach((key, more) -> {
            if (more <= 0 || !hadOld.contains(key)) return;
            if (roomBefore.getOrDefault(key, 0) < more) {
                long productId = Long.parseLong(key.split(":")[1]);
                throw new BusinessRuleException(EDIT_REFUSED + "de teruggekochte stuks van " + productLabel(catalog, productId)
                        + " zijn al opnieuw als verkocht gemeld; trek eerst die melding in");
            }
        });
    }

    /**
     * The archive row of an edit: the melding as it was (channel, day, percents, note, location, buyer, login,
     * who made it and when, confirmation, last edit), voided "Gewijzigd" now, revision_of = the melding,
     * client_ref null. Never listed; staff read it by id for the audit trail.
     */
    private PartnerSaleReportEntity archive(PartnerSaleReportEntity report, Instant now, ActorRef who) {
        PartnerSaleReportEntity copy = new PartnerSaleReportEntity();
        copy.customerId = report.customerId;
        copy.loginId = report.loginId;
        copy.channel = report.channel;
        copy.soldOn = report.soldOn;
        copy.commissionPct = report.commissionPct;
        copy.transportPct = report.transportPct;
        copy.extraCostMode = report.extraCostMode;
        copy.extraCostValue = report.extraCostValue;
        copy.note = report.note;
        copy.locationId = report.locationId;
        copy.clientRef = null;
        copy.createdAt = report.createdAt;
        copy.createdBy = report.createdBy;
        copy.createdByName = report.createdByName;
        copy.confirmedAt = report.confirmedAt;
        copy.confirmedBy = report.confirmedBy;
        copy.confirmedByName = report.confirmedByName;
        copy.updatedAt = report.updatedAt;
        copy.updatedBy = report.updatedBy;
        copy.updatedByName = report.updatedByName;
        copy.buyerCustomerId = report.buyerCustomerId;
        copy.revisionOf = report.id;
        copy.voidedAt = now;
        copy.voidedBy = who.username();
        copy.voidReason = ARCHIVE_REASON;
        reports.persist(copy);
        reports.flush();
        return copy;
    }

    /** The cost lines as they stand: the stored rows, or a round-1 melding's single extra cost. */
    private static List<CostInput> costInputs(PartnerSaleReportEntity report, List<PartnerSaleReportCostEntity> rows) {
        List<CostInput> result = new ArrayList<>();
        if (!rows.isEmpty()) {
            for (PartnerSaleReportCostEntity row : rows) result.add(storedCost(row.description, row.costMode, row.costValue));
            return result;
        }
        if (report.extraCostMode != null) result.add(storedCost(null, report.extraCostMode, report.extraCostValue));
        return result;
    }

    private static CostInput storedCost(String description, String mode, BigDecimal value) {
        BigDecimal scaled = value == null ? null
                : value.setScale(PartnerSaleMoney.AMOUNT.equals(mode) ? 2 : 4, RoundingMode.HALF_UP);
        return new CostInput(description, mode, scaled);
    }

    private StockLocation location(long locationId) {
        for (StockLocation location : stock.locations()) if (location.id() == locationId) return location;
        return stock.mainLocation();
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
        if (report.isArchive()) throw new BusinessRuleException(OLD_VERSION);
        if (report.isVoided()) return;
        /* Re-checked after the partner lock: staff may have confirmed it a moment ago. */
        if (!staff && report.confirmedAt != null) throw new BusinessRuleException(CONFIRMED_FOR_PARTNER);
        List<PurchaseOrder> containers = capacity.lockContainers(customerId);
        List<PartnerSaleReportLineEntity> parts = parts(lines.byReport(reportId));
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
        Map<Long, Integer> netBackByProduct = new HashMap<>();
        Map<String, Integer> containerNeed = new LinkedHashMap<>();
        for (PartnerSaleReportLineEntity part : parts) {
            int signed = part.isSale() ? part.quantityOrZero() : -part.quantityOrZero();
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
        reverse(report, parts, locationId, "Ingetrokken: " + PartnerPortalRules.reportNumber(reportId), false);
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
     * Gives every part of a melding back, batch-correct, as a withdrawal and a rebooking edit both do: buybacks
     * first (a take pinned to their container), then sales (back into their own takes, those of this melding's
     * buyback reversals that mirrored them, and those of earlier withdrawn buybacks of them). Each reversal
     * writes a REVERSAL row on the melding. A buyback may take the shelf below zero for a moment only when the
     * caller checked the net beforehand: a withdrawal when the melding's own sale of that product follows, an
     * edit always (its new parts come right after, the net change was checked).
     */
    private void reverse(PartnerSaleReportEntity report, List<PartnerSaleReportLineEntity> parts, long locationId,
                         String reference, boolean dipAlways) {
        Map<Long, Integer> saleByProduct = new HashMap<>();
        Set<Long> saleIds = new HashSet<>();
        for (PartnerSaleReportLineEntity part : parts) {
            if (!part.isSale()) continue;
            saleByProduct.merge(part.productId, part.quantityOrZero(), Integer::sum);
            saleIds.add(part.id);
        }
        /* Pieces of a sale part may sit in the takes of buyback withdrawals: those of other meldingen
           withdrawn earlier (read now, before this melding's own are written) and this melding's own
           (collected below); the sale's pieces go back into all of them. */
        Map<Long, List<Long>> earlierReversals = lines.reversedBuybackMovements(saleIds);
        Map<Long, List<Long>> reversalsOfMirroredSale = new HashMap<>();
        for (PartnerSaleReportLineEntity part : parts) {
            if (!part.isBuyback() || part.quantityOrZero() <= 0) continue;
            boolean dip = dipAlways || saleByProduct.getOrDefault(part.productId, 0) > 0;
            Long movementId = stock.partnerMove(part.productId, locationId, -part.quantityOrZero(),
                    StockMovement.Kind.PARTNER_SALE, reference, part.purchaseOrderId, report.soldOn,
                    StockBatchContext.pinned(part.purchaseOrderId), dip);
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

    /**
     * The checked body: channel upper case, soldOn filled, entry numbers 1..n in the order given,
     * the extra cost lines (a round-1 extraCost as one line without description), the buyer id
     * (checked against the customers after the partner lock) and whether a round-2 client sent it.
     */
    record Input(String channel, LocalDate soldOn, String clientRef, String note, List<CostInput> costs,
                 List<EntryInput> entries, Long buyerCustomerId, boolean roundTwo) {
        List<Long> productIds() {
            return entries.stream().map(EntryInput::productId).toList();
        }

        List<PartnerSaleMoney.CostLine> moneyCosts() {
            return costs.stream().map(line -> new PartnerSaleMoney.CostLine(line.mode(), line.value())).toList();
        }
    }

    /** One extra cost line as stored: description null or 1..80 characters, AMOUNT at 2 dp or PCT at 4 dp. */
    record CostInput(String description, String mode, BigDecimal value) {}

    record EntryInput(int entryNo, long productId, PartInput sale, PartInput buyback) {
        /** Both sides converted with this product's count units (round-1 sides stay as they are). */
        EntryInput resolve(Product product) {
            if ((sale == null || !sale.typed()) && (buyback == null || !buyback.typed())) return this;
            PartnerCountUnits.Counting counting = PartnerCountUnits.of(product);
            String label = label(PartnerProductNames.of(product));
            return new EntryInput(entryNo, productId, sale == null ? null : sale.resolve(counting, label),
                    buyback == null ? null : buyback.resolve(counting, label));
        }
    }

    /** What was typed on one side of a round-2 entry: unit, count, piece price (4 dp) and the unit's pieces. */
    record Entered(String unit, int count, BigDecimal piecePriceEur, int piecesPerUnit) {}

    /**
     * One side of an entry. Round 1: quantity (sales units) with unitPrice or total. Round 2 before
     * {@link #resolve}: unit, count and piecePrice; after it also quantity, unitPrice per sales unit
     * and the snapshot in {@link #entered}.
     */
    record PartInput(int quantity, BigDecimal unitPrice, BigDecimal total, String unit, Integer count,
                     BigDecimal piecePrice, Entered entered) {
        boolean typed() {
            return unit != null;
        }

        PartInput resolve(PartnerCountUnits.Counting counting, String productLabel) {
            if (!typed() || entered != null) return this;
            PartnerCountUnits.CountUnit chosen = counting.unit(unit).orElseThrow(() -> new BusinessRuleException(
                    productLabel + " telt niet per " + unitWord(unit) + "; kies een andere eenheid"));
            int salesUnits = counting.salesUnits(chosen, count);
            if (salesUnits <= 0 || salesUnits > MAX_QUANTITY) throw new BusinessRuleException("Dit aantal is te groot");
            BigDecimal piece = PartnerSaleMoney.piecePrice(piecePrice);
            return new PartInput(salesUnits, PartnerSaleMoney.unitPriceOfPiece(piece, counting.piecesPerSalesUnit()),
                    null, unit, count, piece, new Entered(chosen.unit(), count, piece, chosen.pieces()));
        }

        private static String unitWord(String unit) {
            return switch (unit) {
                case PartnerCountUnits.VERPAKKING -> "display";
                case PartnerCountUnits.OMDOOS -> "omdoos";
                default -> "stuk";
            };
        }
    }

    static Input validate(PartnerSaleRequests.Report request) {
        return validate(request, null, true);
    }

    /**
     * fallbackSoldOn: the day when none is sent (null = today); daysBack: refuse a day more than 90 days back
     * (an edit checks that itself, and only when the day changes).
     */
    static Input validate(PartnerSaleRequests.Report request, LocalDate fallbackSoldOn, boolean daysBack) {
        if (request == null) throw new BusinessRuleException("Geen gegevens ontvangen");
        String channel = request.channel() == null ? "" : request.channel().strip().toUpperCase(Locale.ROOT);
        if (!PartnerPortalRules.CHANNEL_AUCTION.equals(channel) && !PartnerPortalRules.CHANNEL_DIRECT.equals(channel)) {
            throw new BusinessRuleException("Kies veiling of direct");
        }
        LocalDate today = PartnerPortalRules.today();
        LocalDate soldOn = request.soldOn() != null ? request.soldOn() : fallbackSoldOn != null ? fallbackSoldOn : today;
        if (soldOn.isAfter(today)) throw new BusinessRuleException("De verkoopdatum ligt in de toekomst");
        if (daysBack && soldOn.isBefore(today.minusDays(MAX_DAYS_BACK))) {
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
        List<CostInput> costs = costs(request);
        if (!costs.isEmpty() && entries.stream().noneMatch(entry -> entry.sale() != null)) {
            throw new BusinessRuleException("Extra kost hoort bij een verkoop");
        }
        Long buyer = request.buyerCustomerId();
        if (buyer != null && !PartnerPortalRules.CHANNEL_DIRECT.equals(channel)) {
            throw new BusinessRuleException("Een klant hoort alleen bij een directe verkoop");
        }
        return new Input(channel, soldOn, clientRef, note, List.copyOf(costs), List.copyOf(entries), buyer,
                request.roundTwo());
    }

    /** The extra cost lines: round 2's extraCosts (0..5), or round 1's single extraCost as one line. */
    private static List<CostInput> costs(PartnerSaleRequests.Report request) {
        PartnerSaleRequests.ExtraCost legacy = request.extraCost();
        boolean legacyGiven = legacy != null && (legacy.mode() != null || legacy.value() != null);
        if (request.extraCosts() != null) {
            if (legacyGiven) throw PartnerRefusals.badRequest("Stuur de extra kosten als extraCosts, niet ook als extraCost");
            if (request.extraCosts().size() > MAX_COST_LINES) {
                throw new BusinessRuleException("Hoogstens " + MAX_COST_LINES + " extra kosten per melding");
            }
            List<CostInput> lines = new ArrayList<>();
            for (PartnerSaleRequests.CostLine line : request.extraCosts()) {
                if (line == null) continue;
                lines.add(cost(description(line.description()), line.mode(), line.value()));
            }
            return lines;
        }
        if (!legacyGiven) return List.of();
        return List.of(cost(null, legacy.mode(), legacy.value()));
    }

    private static CostInput cost(String description, String typedMode, BigDecimal typedValue) {
        String mode = typedMode == null ? "" : typedMode.strip().toUpperCase(Locale.ROOT);
        if (!PartnerSaleMoney.AMOUNT.equals(mode) && !PartnerSaleMoney.PCT.equals(mode)) {
            throw new BusinessRuleException("Kies voor de extra kost een bedrag of een percentage");
        }
        if (typedValue == null || typedValue.signum() < 0) throw new BusinessRuleException("Vul de extra kost in (0 of meer)");
        if (PartnerSaleMoney.PCT.equals(mode) && typedValue.compareTo(MAX_EXTRA_PCT) > 0) {
            throw new BusinessRuleException("Een percentage ligt tussen 0 en 100");
        }
        if (PartnerSaleMoney.AMOUNT.equals(mode) && typedValue.compareTo(MAX_TOTAL) > 0) {
            throw new BusinessRuleException("Dit bedrag is te groot");
        }
        /* Computed from what is stored (and what the frontend sends): an amount in cents, a percent at 4 dp. */
        BigDecimal value = typedValue.setScale(PartnerSaleMoney.AMOUNT.equals(mode) ? 2 : 4, RoundingMode.HALF_UP);
        return new CostInput(description, mode, value);
    }

    /** A cost line's description: control characters out, trimmed, blank = none, at most 80 characters. */
    private static String description(String typed) {
        if (typed == null) return null;
        String clean = typed.replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.isEmpty()) return null;
        if (clean.length() > MAX_COST_DESCRIPTION) {
            throw new BusinessRuleException("Een omschrijving heeft hoogstens " + MAX_COST_DESCRIPTION + " tekens");
        }
        return clean;
    }

    private static PartInput part(PartnerSaleRequests.Part given) {
        if (given == null) return null;
        if (given.roundTwo()) {
            if (given.roundOne()) {
                throw PartnerRefusals.badRequest("Een regel heeft óf een aantal met een prijs per eenheid of totaal, "
                        + "óf een eenheid met een aantal en een prijs per stuk");
            }
            String unit = PartnerCountUnits.normalize(given.enteredUnit());
            if (unit == null) throw new BusinessRuleException("Kies per stuk, per display of per omdoos");
            BigDecimal count = given.enteredCount();
            if (count == null || count.signum() <= 0 || count.stripTrailingZeros().scale() > 0
                    || count.compareTo(BigDecimal.valueOf(MAX_QUANTITY)) > 0) {
                throw new BusinessRuleException("Vul een heel aantal groter dan nul in");
            }
            BigDecimal price = given.piecePriceEur();
            if (price == null) throw new BusinessRuleException("Vul een gemiddelde prijs per stuk in");
            if (price.signum() < 0) throw new BusinessRuleException("Een prijs kan niet negatief zijn");
            if (price.compareTo(MAX_UNIT_PRICE) > 0) throw new BusinessRuleException("Dit bedrag is te groot");
            return new PartInput(0, null, null, unit, count.intValueExact(), price, null);
        }
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
        return new PartInput(quantity.intValueExact(), given.unitPriceEur(), given.totalEur(), null, null, null, null);
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
        /** What was typed on this side of the entry (round 2), else null. */
        final Entered entered;
        /** A buyback of a part on a settled-through container: booked on own stock at this cost (else null). */
        BigDecimal takeoverCostEur;
        PartnerSaleReportLineEntity written;

        private Planned(int entryNo, long productId, String type, Long purchaseOrderId, int quantity,
                        BigDecimal unitPrice, BigDecimal gross, BigDecimal commission, BigDecimal transport,
                        BigDecimal extra, BigDecimal net, PartnerSaleReportLineEntity mirrorsExisting,
                        Planned mirrorsPlanned, Entered entered) {
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
            this.entered = entered;
        }

        static Planned sale(int entryNo, long productId, Long purchaseOrderId, PartnerSaleMoney.Sale money,
                            Entered entered) {
            return new Planned(entryNo, productId, PartnerSaleReportLineEntity.SALE, purchaseOrderId, money.quantity(),
                    money.unitPriceEur(), money.grossEur(), money.commissionEur(), money.transportEur(),
                    money.extraCostEur(), money.netEur(), null, null, entered);
        }

        /** A buyback part: gross, commission and transport its share, net = -(gross + commission + transport). */
        static Planned buyback(int entryNo, long productId, Long purchaseOrderId, PartnerSaleMoney.Buyback money,
                               PartnerSaleReportLineEntity mirrorsExisting, Planned mirrorsPlanned, Entered entered) {
            return new Planned(entryNo, productId, PartnerSaleReportLineEntity.BUYBACK, purchaseOrderId, money.quantity(),
                    money.unitPriceEur(), money.grossEur(), money.commissionEur(), money.transportEur(),
                    PartnerSaleMoney.ZERO, money.netEur(), mirrorsExisting, mirrorsPlanned, entered);
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
