package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockDaos;
import be.enrosed.catalog.adapter.out.persistence.StockLevelEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.application.port.out.StockLedger;
import be.enrosed.catalog.domain.StockBatchContext;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Stock per location: where the pieces lie, and every change to that.
 *
 * The product keeps one cached figure, {@code stockQuantity}: the sum over
 * the locations that count for the website - what a customer can order.
 * What lies at a sales point is there to be sold on the spot and is only
 * seen here, per location.
 *
 * Every writer that changes a count tells {@link StockBatchService} what happened, after the line is in the book and
 * in the same transaction (a failing batch hook rolls the booking back; "Pauzeren" makes every hook a no-op).
 * Transfers and damage noted on arrival do not touch batches.
 */
@ApplicationScoped
public class StockService {

    private static final Logger LOG = Logger.getLogger(StockService.class);

    private final StockDaos.Locations locations;
    private final StockDaos.Levels levels;
    private final CatalogDaos.Products products;
    private final StockLedger ledger;
    private final CurrentActor actor;
    private final WebsiteRebuildService websiteRebuild;
    private final StockBatchService batches;

    public StockService(StockDaos.Locations locations, StockDaos.Levels levels,
                        CatalogDaos.Products products, StockLedger ledger, CurrentActor actor,
                        WebsiteRebuildService websiteRebuild, StockBatchService batches) {
        this.locations = locations;
        this.levels = levels;
        this.products = products;
        this.ledger = ledger;
        this.actor = actor;
        this.websiteRebuild = websiteRebuild;
        this.batches = batches;
    }

    /* ------------------------------------------------------------ locations */

    public List<StockLocation> locations() {
        return locations.listAll().stream()
                .map(StockLocationEntity::toDomain)
                .sorted(Comparator.comparing(StockLocation::active).reversed()
                        .thenComparingInt(StockLocation::position)
                        .thenComparing(StockLocation::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Active customer-facing collection choices, in their own public order. */
    public List<StockLocation> publicPickupLocations() {
        return locations.listAll().stream()
                .map(StockLocationEntity::toDomain)
                .filter(StockLocation::active)
                .filter(StockLocation::publicPickupPoint)
                .sorted(Comparator.comparingInt(StockLocation::publicPickupPosition)
                        .thenComparing(StockLocation::publicPickupLabel,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(StockLocation::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public StockLocation location(long id) {
        StockLocationEntity entity = locations.findById(id);
        if (entity == null) throw new NotFoundException("Voorraadlocatie", id);
        return entity.toDomain();
    }

    /** The location that every catalogue started with; created on first use. */
    @Transactional
    public StockLocation mainLocation() {
        StockLocationEntity main = locations.find("code", StockLocation.MAIN_CODE).firstResult();
        if (main == null) {
            main = new StockLocationEntity();
            main.code = StockLocation.MAIN_CODE;
            main.name = "Magazijn";
            main.kind = StockLocation.Kind.WAREHOUSE;
            main.countsForWebsite = true;
            main.receivesByDefault = true;
            main.position = 0;
            main.publicPickupPoint = false;
            main.publicPickupPosition = 0;
            locations.persist(main);
            locations.flush();
            LOG.info("Voorraadlocatie Magazijn aangemaakt");
        }
        return main.toDomain();
    }

    @Transactional
    public StockLocation saveLocation(StockLocation input) {
        String name = required(input.name(), "Naam");
        if (input.publicPickupPoint()) {
            required(input.publicPickupLabel(), "Publieke afhaalnaam");
            required(input.publicPickupAddress(), "Publiek afhaaladres");
        }
        if (input.publicPickupPosition() < 0) {
            throw new BusinessRuleException("Volgorde voor afhalen kan niet negatief zijn");
        }
        String code = input.code() == null || input.code().isBlank()
                ? codeFor(name) : input.code().trim().toUpperCase();
        StockLocationEntity entity = input.id() == null ? new StockLocationEntity() : locations.findById(input.id());
        if (entity == null) throw new NotFoundException("Voorraadlocatie", input.id());
        StockLocationEntity clash = locations.find("code", code).firstResult();
        if (clash != null && !Objects.equals(clash.id, entity.id)) {
            throw new BusinessRuleException("Er bestaat al een locatie met code " + code);
        }
        if (entity.id != null && StockLocation.MAIN_CODE.equals(entity.code) && !input.active()) {
            throw new BusinessRuleException("Het magazijn kan niet op inactief");
        }
        entity.code = code;
        entity.name = name;
        entity.kind = input.kind() == null ? StockLocation.Kind.SALES_POINT : input.kind();
        entity.address = blankToNull(input.address());
        entity.active = input.active();
        entity.countsForWebsite = input.countsForWebsite();
        entity.receivesByDefault = input.receivesByDefault();
        entity.position = input.position();
        entity.publicPickupPoint = input.publicPickupPoint();
        entity.publicPickupLabel = blankToNull(input.publicPickupLabel());
        entity.publicPickupAddress = blankToNull(input.publicPickupAddress());
        entity.publicPickupInstructions = blankToNull(input.publicPickupInstructions());
        entity.publicPickupPosition = input.publicPickupPosition();
        if (entity.id == null) locations.persist(entity);
        locations.flush();
        /* A change in "counts for the website" moves the cached figure on every product. */
        recomputeAll();
        return entity.toDomain();
    }

    @Transactional
    public void deleteLocation(long id) {
        StockLocationEntity entity = locations.findById(id);
        if (entity == null) throw new NotFoundException("Voorraadlocatie", id);
        if (StockLocation.MAIN_CODE.equals(entity.code)) {
            throw new BusinessRuleException("Het magazijn kan niet verwijderd worden");
        }
        if (locations.getEntityManager().createQuery(
                "select count(l) from TicaStockLocation l where l.stockLocationId = :id", Long.class)
                .setParameter("id", id).getSingleResult() > 0) {
            throw new BusinessRuleException("Deze voorraadlocatie is gekoppeld aan TICA en blijft beschikbaar voor haar historie.");
        }
        boolean holdsStock = levels.list("locationId = ?1 and (quantity <> 0 or loosePieces <> 0)", id).size() > 0;
        if (holdsStock) {
            throw new BusinessRuleException(
                    "Er ligt nog voorraad op " + entity.name + "; verplaats die eerst");
        }
        levels.delete("locationId", id);
        locations.delete(entity);
        recomputeAll();
    }

    /* ------------------------------------------------------------- levels */

    /** One line per active location, zeros included, so the UI has a fixed shape. */
    public List<StockLevel> levelsFor(long productId) {
        Map<Long, StockLevelEntity> held = levels.list("productId", productId).stream()
                .collect(Collectors.toMap(level -> level.locationId, level -> level));
        int factor = unitPieces(productId);
        List<StockLevel> result = new ArrayList<>();
        for (StockLocation location : locations()) {
            StockLevelEntity level = held.get(location.id());
            int quantity = level == null ? 0 : level.quantity;
            int loose = loose(level);
            if (!location.active() && quantity == 0 && loose == 0) continue;
            result.add(new StockLevel(productId, location, quantity, loose, factor));
        }
        return result;
    }

    /** Every product's pieces per location, for the overview and the list totals. */
    public List<StockLevel> allLevels() {
        Map<Long, StockLocation> byId = locations().stream()
                .collect(Collectors.toMap(StockLocation::id, location -> location));
        return levels.listAll().stream()
                .filter(level -> byId.containsKey(level.locationId))
                .map(level -> new StockLevel(level.productId, byId.get(level.locationId), level.quantity,
                        loose(level), products.findById(level.productId) == null ? 1 : unitPieces(level.productId)))
                .toList();
    }

    /** Sets the count at one location, as after a recount. */
    @Transactional
    public void setLevel(long productId, long locationId, int quantity, StockMovement.Kind kind, String reference) {
        requireProduct(productId);
        setLevel(productId, locationId, quantity, loosePiecesAt(productId, locationId), kind, reference);
    }

    @Transactional
    public void setLevel(long productId, long locationId, int quantity, int loosePieces,
                         StockMovement.Kind kind, String reference) {
        if (quantity < 0) throw new BusinessRuleException("Voorraad kan niet negatief zijn");
        requireProduct(productId);
        int factor = unitPieces(productId);
        if (loosePieces < 0 || loosePieces >= factor) throw new BusinessRuleException("Losse stuks moeten tussen 0 en " + (factor - 1) + " liggen.");
        StockLocation location = location(locationId);
        int before = quantityAt(productId, locationId);
        int looseBefore = loosePiecesAt(productId, locationId);
        if (before == quantity && looseBefore == loosePieces && kind != StockMovement.Kind.STOCKTAKE) return;
        write(productId, locationId, quantity, loosePieces);
        Instant now = Instant.now();
        Long movementId = ledger.record(new StockMovement(null, productId, location.id(), now, quantity - before,
                quantity, kind, reference, actor.name(), null, null, null, null,
                loosePieces - looseBefore, loosePieces, factor, null, null));
        int physicalDelta = Math.subtractExact(
                Math.addExact(Math.multiplyExact(quantity, factor), loosePieces),
                Math.addExact(Math.multiplyExact(before, factor), looseBefore));
        batches.onCount(productId, movementId, kind, physicalDelta, reference, now);
        recompute(productId);
    }

    /** Adds pieces at a location - a purchase receipt, or a correction by delta. */
    @Transactional
    public void add(long productId, long locationId, int delta, StockMovement.Kind kind, String reference) {
        add(productId, locationId, delta, kind, reference, StockBatchContext.NONE);
    }

    /**
     * The same, saying where the pieces came from for the batches: the container line of a receipt, or the invoice
     * whose pieces come back on a credit note.
     */
    @Transactional
    public void add(long productId, long locationId, int delta, StockMovement.Kind kind, String reference,
                    StockBatchContext batch) {
        requireProduct(productId);
        StockLocation location = location(locationId);
        int after = quantityAt(productId, locationId) + delta;
        write(productId, locationId, after);
        Instant now = Instant.now();
        Long movementId = book(productId, location, delta, after, kind, reference, now);
        batches.onAdd(productId, movementId, kind, Math.multiplyExact(delta, unitPieces(productId)), batch,
                reference, now);
        recompute(productId);
    }

    /**
     * Pieces leaving the shelf without a sale: broken, or given to a customer
     * as a demo. Booked as their own kind so both can be counted later.
     */
    @Transactional
    public void takeOut(long productId, long locationId, int quantity, StockMovement.Kind kind, String reference) {
        takeOut(productId, locationId, quantity, kind, reference, null);
    }

    /**
     * The same, naming the container the pieces came on: a broken dome
     * found while unpacking, or a carton that turned out to hold less than
     * the packing list said. The order's dossier and the next supplier order
     * read the report back through {@link #movementsForPurchaseOrder}.
     */
    @Transactional
    public void takeOut(long productId, long locationId, int quantity, StockMovement.Kind kind, String reference,
                        Long purchaseOrderId) {
        takeOut(productId, locationId, quantity, kind, reference, purchaseOrderId,
                StockBatchContext.pinned(purchaseOrderId));
    }

    /**
     * The same with the batch context; the movement keeps {@code purchaseOrderId} as given, the batches are taken
     * from the context's container first, else from {@code purchaseOrderId}'s.
     */
    @Transactional
    public void takeOut(long productId, long locationId, int quantity, StockMovement.Kind kind, String reference,
                        Long purchaseOrderId, StockBatchContext batch) {
        if (kind != StockMovement.Kind.DAMAGED && kind != StockMovement.Kind.DEMO
                && kind != StockMovement.Kind.SHORTAGE) {
            throw new BusinessRuleException("Alleen beschadigd, te weinig geleverd of demo kan zo uit de voorraad");
        }
        if (quantity <= 0) throw new BusinessRuleException("Geef een aantal groter dan nul op");
        requireProduct(productId);
        StockLocation location = location(locationId);
        int at = quantityAt(productId, locationId);
        if (at < quantity) {
            throw new BusinessRuleException("Op " + location.name() + " liggen maar " + at + " stuks");
        }
        write(productId, locationId, at - quantity);
        Instant now = Instant.now();
        Long movementId = ledger.record(new StockMovement(null, productId, location.id(), now, -quantity,
                at - quantity, kind, reference, actor.name(), purchaseOrderId, null, null, null,
                0, loosePiecesAt(productId, locationId), unitPieces(productId), null, null));
        StockBatchContext context = batch == null ? StockBatchContext.NONE : batch;
        Long pin = context.pinPurchaseOrderId() != null ? context.pinPurchaseOrderId() : purchaseOrderId;
        batches.onTake(productId, movementId, kind, Math.multiplyExact(quantity, unitPieces(productId)),
                new StockBatchContext(pin, context.salesOrderId(), context.returnOfSalesOrderId(), context.receipt()),
                reference, now);
        recompute(productId);
    }

    /** The stock book of one product, newest first. */
    public List<StockMovement> movementsFor(long productId) {
        return ledger.forProduct(productId);
    }

    /** What was reported against one container after it was received, newest first. */
    public List<StockMovement> movementsForPurchaseOrder(long purchaseOrderId) {
        return ledger.forPurchaseOrder(purchaseOrderId);
    }

    /** Every line booked from one moment up to another, oldest first. */
    public List<StockMovement> movementsBetween(Instant fromInclusive, Instant toExclusive) {
        return ledger.between(fromInclusive, toExclusive);
    }

    /** The last line of a product at a location before a moment. */
    public Optional<StockMovement> lastMovementBefore(long productId, long locationId, Instant before) {
        return ledger.lastBefore(productId, locationId, before);
    }

    /**
     * A sold order leaves the warehouse.
     *
     * Unlike {@link #takeOut} the count may sink below zero: a sale is a
     * fact, and a negative book signals the counting was off - hiding that
     * behind an error would block the shipment over bookkeeping.
     */
    @Transactional
    public void sell(long productId, long locationId, int quantity, String reference) {
        sell(productId, locationId, quantity, reference, StockBatchContext.NONE);
    }

    /** The same, naming the sales document (and the container a container sale is pinned to) for the batches. */
    @Transactional
    public void sell(long productId, long locationId, int quantity, String reference, StockBatchContext batch) {
        if (quantity <= 0) throw new BusinessRuleException("Geef een aantal groter dan nul op");
        requireProduct(productId);
        StockLocation location = location(locationId);
        int at = quantityAt(productId, locationId);
        write(productId, locationId, at - quantity);
        Instant now = Instant.now();
        Long movementId = book(productId, location, -quantity, at - quantity, StockMovement.Kind.SALE, reference, now);
        batches.onTake(productId, movementId, StockMovement.Kind.SALE, Math.multiplyExact(quantity, unitPieces(productId)),
                batch, reference, now);
        recompute(productId);
    }

    /** A source-owned delta; its original sales date is separate from the immutable booking time. */
    @Transactional
    public void applyTica(long productId, long locationId, int physicalDelta, long saleId, long revisionId,
                          LocalDateTime soldAt, String reference, Instant bookedAt,
                          BigDecimal sourceQuantity, String sourceUnit) {
        if (physicalDelta == 0) return;
        requireProduct(productId);
        StockLocation location = location(locationId);
        int factor = unitPieces(productId);
        int before = quantityAt(productId, locationId), looseBefore = loosePiecesAt(productId, locationId);
        int afterPieces = Math.addExact(Math.addExact(Math.multiplyExact(before, factor), looseBefore), physicalDelta);
        int after = Math.floorDiv(afterPieces, factor), looseAfter = Math.floorMod(afterPieces, factor);
        write(productId, locationId, after, looseAfter);
        Long movementId = ledger.record(new StockMovement(null, productId, location.id(), bookedAt, after - before,
                after, physicalDelta < 0 ? StockMovement.Kind.SALE : StockMovement.Kind.SALE_RETURN,
                reference, "TICA-synchronisatie", null, saleId, revisionId, soldAt,
                looseAfter - looseBefore, looseAfter, factor, sourceQuantity, sourceUnit));
        batches.onTica(productId, movementId, physicalDelta, saleId, reference, bookedAt);
        recompute(productId);
    }

    /** Books pieces that never reached the shelf - broken on arrival - so the damage is counted. */
    @Transactional
    public void noteDamagedOnArrival(long productId, long locationId, int quantity, String reference) {
        if (quantity <= 0) return;
        requireProduct(productId);
        StockLocation location = location(locationId);
        int at = quantityAt(productId, locationId);
        ledger.record(new StockMovement(null, productId, location.id(), Instant.now(), -quantity, at,
                StockMovement.Kind.DAMAGED, reference, actor.name(), null, null, null, null,
                0, loosePiecesAt(productId, locationId), unitPieces(productId), null, null));
    }

    /** Moves pieces from one location to another: two lines, one story. */
    @Transactional
    public void transfer(long productId, long fromId, long toId, int quantity, String note) {
        if (quantity <= 0) throw new BusinessRuleException("Geef een aantal groter dan nul op");
        if (fromId == toId) throw new BusinessRuleException("Kies twee verschillende locaties");
        requireProduct(productId);
        StockLocation from = location(fromId);
        StockLocation to = location(toId);
        int atFrom = quantityAt(productId, fromId);
        if (atFrom < quantity) {
            throw new BusinessRuleException("Op " + from.name() + " liggen maar " + atFrom
                    + " stuks; " + quantity + " verplaatsen kan niet");
        }
        String suffix = note == null || note.isBlank() ? "" : " · " + note.trim();
        write(productId, fromId, atFrom - quantity);
        book(productId, from, -quantity, atFrom - quantity, StockMovement.Kind.TRANSFER_OUT, to.name() + suffix);
        int atTo = quantityAt(productId, toId) + quantity;
        write(productId, toId, atTo);
        book(productId, to, quantity, atTo, StockMovement.Kind.TRANSFER_IN, from.name() + suffix);
        recompute(productId);
    }

    @Transactional
    public int quantityAt(long productId, long locationId) {
        StockLevelEntity level = levels.find("productId = ?1 and locationId = ?2", productId, locationId)
                .firstResult();
        return level == null ? 0 : level.quantity;
    }

    @Transactional
    public int loosePiecesAt(long productId, long locationId) {
        return loose(levels.find("productId = ?1 and locationId = ?2", productId, locationId).firstResult());
    }

    public int unitPieces(long productId) {
        ProductEntity product = products.findById(productId);
        if (product == null) throw new NotFoundException("Product", productId);
        return product.packaging().soldAsDisplay() ? product.packaging().unitPieces() : 1;
    }

    @Transactional
    public void lockProduct(long productId) { requireProduct(productId); }

    @Transactional
    public int physicalQuantityAt(long productId, long locationId) {
        requireProduct(productId);
        return Math.addExact(Math.multiplyExact(quantityAt(productId, locationId), unitPieces(productId)),
                loosePiecesAt(productId, locationId));
    }

    private static int loose(StockLevelEntity level) { return level == null || level.loosePieces == null ? 0 : level.loosePieces; }

    /* ------------------------------------------------------------ internals */

    private void write(long productId, long locationId, int quantity) {
        write(productId, locationId, quantity, loosePiecesAt(productId, locationId));
    }

    private void write(long productId, long locationId, int quantity, int loosePieces) {
        StockLevelEntity level = levels.find("productId = ?1 and locationId = ?2", productId, locationId)
                .firstResult();
        if (level == null) {
            level = new StockLevelEntity();
            level.productId = productId;
            level.locationId = locationId;
            levels.persist(level);
        }
        level.quantity = quantity;
        level.loosePieces = loosePieces;
        levels.flush();
    }

    private Long book(long productId, StockLocation location, int delta, int after,
                      StockMovement.Kind kind, String reference) {
        return book(productId, location, delta, after, kind, reference, Instant.now());
    }

    /** Writes the book line; returns its id for the batch moves it causes. */
    private Long book(long productId, StockLocation location, int delta, int after,
                      StockMovement.Kind kind, String reference, Instant at) {
        return ledger.record(new StockMovement(null, productId, location.id(), at, delta, after,
                kind, reference, actor.name(), null, null, null, null,
                0, loosePiecesAt(productId, location.id()), unitPieces(productId), null, null));
    }

    /** The product's cached figure: the pieces a customer can order. */
    void recompute(long productId) {
        ProductEntity product = products.findById(productId);
        if (product == null) return;
        int website = 0;
        for (StockLevel level : levelsFor(productId)) {
            if (level.location().countsForWebsite()) website = Math.addExact(website, level.physicalQuantity());
        }
        product.stockQuantity = Math.floorDiv(website, unitPieces(productId));
        product.inventoryKnown = true;
        products.flush();
        websiteRebuild.queue();
    }

    private void recomputeAll() {
        for (ProductEntity product : products.list("order by id")) {
            requireProduct(product.id);
            recompute(product.id);
        }
    }

    private void requireProduct(long productId) {
        if (products.findById(productId, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new NotFoundException("Product", productId);
        // A caller may have inspected a level before it acquired the common product lock.
        // Flush our own earlier writes, then refresh levels under that lock before doing arithmetic.
        levels.flush();
        for (StockLevelEntity level : levels.list("productId", productId)) levels.getEntityManager().refresh(level);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new BusinessRuleException(label + " is verplicht");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String codeFor(String name) {
        String code = name.toUpperCase().replaceAll("[^A-Z0-9]+", "-").replaceAll("^-|-$", "");
        return code.isEmpty() ? "LOC" : code.substring(0, Math.min(code.length(), 24));
    }
}
