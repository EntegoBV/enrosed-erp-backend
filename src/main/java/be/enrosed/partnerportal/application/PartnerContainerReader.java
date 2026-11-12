package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.application.port.out.CataloguePallet;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerContainerQueries;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderName;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.tracking.ArrivalMoveStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The partner's container list (round 2, contract section 5 and v2-F): every purchase order that is not a concept,
 * archived and long received ones included (his answer: "alle containers"), trashed ones never (the entity's SQL
 * restriction). Price-free by construction: it reads the order's identity, dates, ports, location and line
 * quantities, and the product card's sizes; it never calls the landed-cost calculation, payables, reconciliation,
 * the price resolver or anything of the supplier, and it never copies a free-text field (alias, notes, issue note,
 * tracking reference, description, supplier note) into {@link PartnerContainerViews}.
 */
@ApplicationScoped
public class PartnerContainerReader {

    /**
     * The one predicate for what a partner may see of the containers: the list below and the photo guard
     * ({@link #productInVisibleContainer}) both use exactly these statuses. A concept is a calculation (duplicates
     * exist to compare variants), so it never shows.
     */
    public static final Set<PurchaseOrderStatus> VISIBLE = Collections.unmodifiableSet(
            EnumSet.of(PurchaseOrderStatus.BESTELD, PurchaseOrderStatus.ONDERWEG, PurchaseOrderStatus.ONTVANGEN));

    /** A departed container expected at most this many days ahead or overdue reads "Binnenkort" (frontend SOON_DAYS). */
    static final int SOON_DAYS = 14;
    /** A container received longer ago than this is "old": only "Ook oude containers" shows it. */
    static final int RECENT_DAYS = 60;
    /** The warehouse's name when no MAIN location row exists yet (as the receipt note says it). */
    private static final String MAIN_NAME = "Magazijn";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PurchaseOrderService purchaseOrders;
    private final PartnerProductTiles tiles;
    private final StockService stock;
    private final CataloguePallet pallet;
    private final ArrivalMoveStore moves;
    private final PartnerContainerQueries queries;

    public PartnerContainerReader(PurchaseOrderService purchaseOrders, PartnerProductTiles tiles,
                                  StockService stock, CataloguePallet pallet,
                                  ArrivalMoveStore moves, PartnerContainerQueries queries) {
        this.purchaseOrders = purchaseOrders;
        this.tiles = tiles;
        this.stock = stock;
        this.pallet = pallet;
        this.moves = moves;
        this.queries = queries;
    }

    /** Whether a partner may see this order (see {@link #VISIBLE}). */
    public static boolean visible(PurchaseOrder order) {
        return order != null && order.id() != null && VISIBLE.contains(order.status());
    }

    /** The photo guard's half of the shared predicate: the product is on a line of a visible container. */
    public boolean productInVisibleContainer(long productId) {
        return queries.productOnContainer(productId, VISIBLE);
    }

    /** GET /api/partner-portal/containers for this partner (own = the container's partner is this customer). */
    @Transactional
    public PartnerContainerViews.ContainerPage page(long customerId) {
        LocalDate today = PartnerPortalRules.today();
        PartnerProductTiles.Catalog catalog = tiles.load(customerId);
        Places places = places();
        Map<Long, ArrivalMoveStore.Move> moved = moves.all();
        Map<Long, Integer> perPallet = new HashMap<>();
        Set<Long> productIds = new LinkedHashSet<>();
        List<PartnerContainerViews.Container> containers = new ArrayList<>();
        for (PurchaseOrder order : purchaseOrders.list()) {
            if (!visible(order)) continue;
            PartnerContainerViews.Container container = container(order, customerId, today, catalog, places,
                    moved.get(order.id()), perPallet);
            container.lines().forEach(line -> productIds.add(line.productId()));
            containers.add(container);
        }
        containers.sort(ORDER);
        List<PartnerContainerViews.ProductSpec> products = new ArrayList<>();
        productIds.stream().map(catalog::tile).sorted(catalog.order())
                .forEach(tile -> products.add(spec(tile, catalog, perPallet)));
        CataloguePallet.Basis basis = pallet.basis();
        return new PartnerContainerViews.ContainerPage(today,
                new PartnerContainerViews.Pallet(basis.widthCm(), basis.depthCm(), basis.maxHeightCm()),
                List.copyOf(containers), List.copyOf(products));
    }

    /* ------------------------------------------------------------------ one container */

    private PartnerContainerViews.Container container(PurchaseOrder order, long customerId, LocalDate today,
                                                      PartnerProductTiles.Catalog catalog, Places places,
                                                      ArrivalMoveStore.Move move, Map<Long, Integer> perPallet) {
        boolean received = order.status() == PurchaseOrderStatus.ONTVANGEN;
        boolean own = Objects.equals(order.partnerCustomerId(), customerId);
        String state = state(order, today);
        LocalDate due = due(state, order.expectedArrival(), today);

        Map<Long, Load> loads = new LinkedHashMap<>();
        for (PurchaseOrderLine line : order.lines()) {
            if (line == null || line.productId() == null) continue;
            Product product = catalog.product(line.productId()).orElse(null);
            loads.computeIfAbsent(line.productId(), id -> new Load(product))
                    .add(received ? line.usable() : Math.max(0, line.quantity()), line.ordered());
        }

        List<PartnerContainerViews.ContainerLine> lines = new ArrayList<>();
        int units = 0;
        int pieces = 0;
        int cartons = 0;
        BigDecimal cbm = BigDecimal.ZERO;
        int palletSum = 0;
        int palletsKnown = 0;
        int palletsUnknown = 0;
        BigDecimal weight = BigDecimal.ZERO;
        boolean weightKnown = false;
        boolean weightUnknown = false;
        boolean cbmUnknown = false;
        for (Map.Entry<Long, Load> entry : loads.entrySet()) {
            long productId = entry.getKey();
            Load load = entry.getValue();
            if (load.quantity <= 0 && (!received || load.ordered <= 0)) continue;
            int linePieces = load.quantity * Math.max(1, catalog.tile(productId).piecesPerUnit());
            Integer linePallets = load.quantity <= 0 ? Integer.valueOf(0) : palletsOf(productId, load, perPallet);
            BigDecimal lineCbm = load.cbm.setScale(3, RoundingMode.HALF_UP);
            BigDecimal lineWeight = load.weightMissing ? null : load.weight.setScale(2, RoundingMode.HALF_UP);
            lines.add(new PartnerContainerViews.ContainerLine(productId, load.quantity,
                    received && load.ordered != load.quantity ? load.ordered : null, linePieces, load.cartons,
                    lineCbm, linePallets, lineWeight));
            units += load.quantity;
            pieces += linePieces;
            cartons += load.cartons;
            cbm = cbm.add(lineCbm);
            if (linePallets == null) {
                palletsUnknown++;
            } else {
                palletSum += linePallets;
                palletsKnown++;
            }
            if (lineWeight == null) {
                weightUnknown = true;
            } else {
                weight = weight.add(lineWeight);
                weightKnown = true;
            }
            cbmUnknown |= load.cbmMissing;
        }

        ContainerType type = order.containerType();
        BigDecimal capacity = type != null && type.hasCapacity() ? type.capacityCbm() : null;
        BigDecimal fill = capacity == null ? null
                : Money.divide(cbm.multiply(HUNDRED), capacity).setScale(1, RoundingMode.HALF_UP);
        PartnerContainerViews.ContainerTotals totals = new PartnerContainerViews.ContainerTotals(lines.size(), units,
                pieces, cartons, cbm.setScale(3, RoundingMode.HALF_UP), capacity, fill,
                palletsKnown == 0 ? null : palletSum, palletsUnknown, weightKnown ? weight : null, weightUnknown,
                cbmUnknown);

        LocalDate movedFrom = !received && move != null && move.from() != null
                && !move.from().equals(order.expectedArrival()) ? move.from() : null;
        boolean old = received
                ? order.receivedOn() == null || order.receivedOn().isBefore(today.minusDays(RECENT_DAYS))
                : order.isArchived();
        return new PartnerContainerViews.Container(order.id(), blankToNull(order.number()),
                PurchaseOrderName.display(order.id(), order.number(), null), own, order.status().name(), state,
                type == null ? null : type.code(), type == null ? null : type.label(), order.orderDate(),
                order.shippedOn(), order.expectedArrival(), due, order.receivedOn(), movedFrom,
                order.isStockBooked(), old, order.departurePort(), order.destinationPort(),
                places.nameFor(order.receivingLocationId(), own), totals, List.copyOf(lines));
    }

    /** Each product on pallets of its own (what the warehouse does after unloading); null without a fit. */
    private Integer palletsOf(long productId, Load load, Map<Long, Integer> perPallet) {
        int fit = cartonsPerPallet(productId, load.product, perPallet);
        if (fit <= 0 || load.cartons <= 0) return null;
        return (load.cartons + fit - 1) / fit;
    }

    /** Omdozen per pallet on the standard pallet, once per product per answer; 0 when unknown or not fitting. */
    private int cartonsPerPallet(long productId, Product product, Map<Long, Integer> perPallet) {
        return perPallet.computeIfAbsent(productId, id -> {
            if (product == null || product.carton() == null || product.carton().dimensions() == null
                    || product.carton().dimensions().isBlank()) return 0;
            return Math.max(0, pallet.cartonsPerPallet(product.carton()));
        });
    }

    /**
     * The shared word of the frontend's product-incoming: received = Aangekomen; not departed (status and no
     * departure day that has come) = Besteld, however near its date; departed with a date within SOON_DAYS either
     * side = Binnenkort; else Onderweg.
     */
    static String state(PurchaseOrder order, LocalDate today) {
        if (order.status() == PurchaseOrderStatus.ONTVANGEN) return "AANGEKOMEN";
        boolean departed = order.status() == PurchaseOrderStatus.ONDERWEG
                || (order.shippedOn() != null && !order.shippedOn().isAfter(today));
        if (!departed) return "BESTELD";
        LocalDate date = order.expectedArrival();
        return date != null && !date.isBefore(today.minusDays(SOON_DAYS)) && !date.isAfter(today.plusDays(SOON_DAYS))
                ? "BINNENKORT" : "ONDERWEG";
    }

    /** The expected date while it can still hold (product-incoming's dueOf). */
    static LocalDate due(String state, LocalDate expectedArrival, LocalDate today) {
        if (expectedArrival == null || "AANGEKOMEN".equals(state)) return null;
        return !expectedArrival.isBefore(today) || "BINNENKORT".equals(state) ? expectedArrival : null;
    }

    /** Tie-break among orders of one arrival, as product-incoming's RANK. */
    private static int rank(String state) {
        return switch (state) {
            case "AANGEKOMEN" -> 0;
            case "BINNENKORT" -> 1;
            case "ONDERWEG" -> 2;
            default -> 3;
        };
    }

    /**
     * Open containers first, in product-incoming's arrival order (a date that can still hold, earliest first; then
     * those without one, departed before not departed, the longest overdue first); then the received ones, the
     * newest receipt first (no receipt date last).
     */
    private static final Comparator<PartnerContainerViews.Container> ORDER = (a, b) -> {
        boolean aIn = "AANGEKOMEN".equals(a.state());
        boolean bIn = "AANGEKOMEN".equals(b.state());
        if (aIn != bIn) return aIn ? 1 : -1;
        if (aIn) {
            if (!Objects.equals(a.receivedOn(), b.receivedOn())) {
                if (a.receivedOn() == null) return 1;
                if (b.receivedOn() == null) return -1;
                return b.receivedOn().compareTo(a.receivedOn());
            }
            return Long.compare(b.id(), a.id());
        }
        int byKey = arrivalKey(a).compareTo(arrivalKey(b));
        return byKey != 0 ? byKey : Long.compare(a.id(), b.id());
    };

    private static String arrivalKey(PartnerContainerViews.Container container) {
        if (container.due() != null) return "1" + container.due() + rank(container.state());
        return "2" + rank(container.state()) + (container.expectedArrival() == null ? "9" : container.expectedArrival());
    }

    /* ------------------------------------------------------------------ products */

    private PartnerContainerViews.ProductSpec spec(PartnerPortalViews.ProductTile tile,
                                                   PartnerProductTiles.Catalog catalog, Map<Long, Integer> perPallet) {
        Product product = catalog.product(tile.productId()).orElse(null);
        if (product == null) {
            return new PartnerContainerViews.ProductSpec(tile, null, null, null, null, null, null, null, null,
                    null, null);
        }
        Dimensions own = product.dimensions();
        Packaging packaging = product.packaging();
        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        int fit = cartonsPerPallet(tile.productId(), product, perPallet);
        return new PartnerContainerViews.ProductSpec(tile,
                blankToNull(product.colour()), blankToNull(product.variantSize()), size(own),
                positive(own == null ? null : own.weightKg()),
                packaging == null ? "NONE" : packaging.kind().name(),
                packaging == null || !packaging.isPresent() ? null : size(packaging.dimensions()),
                Math.max(1, carton.piecesPerCarton()), size(carton.dimensions()), positive(carton.weightKg()),
                fit > 0 ? fit : null);
    }

    private static PartnerContainerViews.Size size(Dimensions dimensions) {
        if (dimensions == null || dimensions.isBlank()) return null;
        return new PartnerContainerViews.Size(dimensions.lengthCm(), dimensions.widthCm(), dimensions.heightCm());
    }

    private static BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /* ------------------------------------------------------------------ helpers */

    /** One product on one container, its lines added up the way the purchase screen counts them (per line). */
    private static final class Load {
        final Product product;
        final Carton carton;
        int quantity;
        int ordered;
        int cartons;
        BigDecimal cbm = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        boolean weightMissing;
        boolean cbmMissing;

        Load(Product product) {
            this.product = product;
            this.carton = product == null ? null : product.carton() == null ? Carton.empty() : product.carton();
        }

        void add(int quantity, int ordered) {
            this.quantity += quantity;
            this.ordered += ordered;
            if (quantity <= 0) return;
            if (carton == null) {
                /* A product deleted since: nothing to count its cargo by. */
                cbmMissing = true;
                weightMissing = true;
                return;
            }
            int boxes = carton.cartonsFor(quantity);
            cartons += boxes;
            BigDecimal volume = carton.cbm();
            if (volume == null || volume.signum() <= 0) cbmMissing = true;
            else cbm = cbm.add(volume.multiply(BigDecimal.valueOf(boxes)).setScale(3, RoundingMode.HALF_UP));
            BigDecimal kg = carton.weightKg();
            if (kg == null || kg.signum() <= 0) weightMissing = true;
            else weight = weight.add(kg.multiply(BigDecimal.valueOf(boxes)));
        }
    }

    /** The location names, and the rule which of them a partner may read. */
    private Places places() {
        Map<Long, StockLocation> byId = new HashMap<>();
        StockLocation main = null;
        for (StockLocation location : stock.locations()) {
            if (location.id() == null) continue;
            byId.put(location.id(), location);
            if (StockLocation.MAIN_CODE.equals(location.code())) main = location;
        }
        return new Places(byId, main);
    }

    private record Places(Map<Long, StockLocation> byId, StockLocation main) {
        /**
         * The name only for an own container or one unloaded at the main warehouse (no location on the order reads
         * as MAIN); another partner's place is never named.
         */
        String nameFor(Long locationId, boolean own) {
            boolean isMain = locationId == null || (main != null && Objects.equals(main.id(), locationId));
            if (!own && !isMain) return null;
            StockLocation location = locationId == null ? main : byId.get(locationId);
            if (location == null) return isMain ? MAIN_NAME : null;
            return blankToNull(location.name());
        }
    }
}
