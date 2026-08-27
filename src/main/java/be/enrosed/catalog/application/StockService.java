package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockDaos;
import be.enrosed.catalog.adapter.out.persistence.StockLevelEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.application.port.out.StockLedger;
import be.enrosed.catalog.domain.StockLevel;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stock per location: where the pieces lie, and every change to that.
 *
 * The product keeps one cached figure, {@code stockQuantity}: the sum over
 * the locations that count for the website - what a customer can order.
 * What lies at a sales point is there to be sold on the spot and is only
 * seen here, per location.
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

    public StockService(StockDaos.Locations locations, StockDaos.Levels levels,
                        CatalogDaos.Products products, StockLedger ledger, CurrentActor actor,
                        WebsiteRebuildService websiteRebuild) {
        this.locations = locations;
        this.levels = levels;
        this.products = products;
        this.ledger = ledger;
        this.actor = actor;
        this.websiteRebuild = websiteRebuild;
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
        boolean holdsStock = levels.list("locationId = ?1 and quantity <> 0", id).size() > 0;
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
        Map<Long, Integer> held = levels.list("productId", productId).stream()
                .collect(Collectors.toMap(level -> level.locationId, level -> level.quantity));
        List<StockLevel> result = new ArrayList<>();
        for (StockLocation location : locations()) {
            int quantity = held.getOrDefault(location.id(), 0);
            if (!location.active() && quantity == 0) continue;
            result.add(new StockLevel(productId, location, quantity));
        }
        return result;
    }

    /** Every product's pieces per location, for the overview and the list totals. */
    public List<StockLevel> allLevels() {
        Map<Long, StockLocation> byId = locations().stream()
                .collect(Collectors.toMap(StockLocation::id, location -> location));
        return levels.listAll().stream()
                .filter(level -> byId.containsKey(level.locationId))
                .map(level -> new StockLevel(level.productId, byId.get(level.locationId), level.quantity))
                .toList();
    }

    /** Sets the count at one location, as after a recount. */
    @Transactional
    public void setLevel(long productId, long locationId, int quantity, StockMovement.Kind kind, String reference) {
        if (quantity < 0) throw new BusinessRuleException("Voorraad kan niet negatief zijn");
        requireProduct(productId);
        StockLocation location = location(locationId);
        int before = quantityAt(productId, locationId);
        if (before == quantity && kind != StockMovement.Kind.STOCKTAKE) return;
        write(productId, locationId, quantity);
        book(productId, location, quantity - before, quantity, kind, reference);
        recompute(productId);
    }

    /** Adds pieces at a location - a purchase receipt, or a correction by delta. */
    @Transactional
    public void add(long productId, long locationId, int delta, StockMovement.Kind kind, String reference) {
        requireProduct(productId);
        StockLocation location = location(locationId);
        int after = quantityAt(productId, locationId) + delta;
        write(productId, locationId, after);
        book(productId, location, delta, after, kind, reference);
        recompute(productId);
    }

    /**
     * Pieces leaving the shelf without a sale: broken, or given to a customer
     * as a demo. Booked as their own kind so both can be counted later.
     */
    @Transactional
    public void takeOut(long productId, long locationId, int quantity, StockMovement.Kind kind, String reference) {
        if (kind != StockMovement.Kind.DAMAGED && kind != StockMovement.Kind.DEMO) {
            throw new BusinessRuleException("Alleen beschadigd of demo kan zo uit de voorraad");
        }
        if (quantity <= 0) throw new BusinessRuleException("Geef een aantal groter dan nul op");
        requireProduct(productId);
        StockLocation location = location(locationId);
        int at = quantityAt(productId, locationId);
        if (at < quantity) {
            throw new BusinessRuleException("Op " + location.name() + " liggen maar " + at + " stuks");
        }
        write(productId, locationId, at - quantity);
        book(productId, location, -quantity, at - quantity, kind, reference);
        recompute(productId);
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
        if (quantity <= 0) throw new BusinessRuleException("Geef een aantal groter dan nul op");
        requireProduct(productId);
        StockLocation location = location(locationId);
        int at = quantityAt(productId, locationId);
        write(productId, locationId, at - quantity);
        book(productId, location, -quantity, at - quantity, StockMovement.Kind.SALE, reference);
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
                StockMovement.Kind.DAMAGED, reference, actor.name()));
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

    public int quantityAt(long productId, long locationId) {
        StockLevelEntity level = levels.find("productId = ?1 and locationId = ?2", productId, locationId)
                .firstResult();
        return level == null ? 0 : level.quantity;
    }

    /* ------------------------------------------------------------ internals */

    private void write(long productId, long locationId, int quantity) {
        StockLevelEntity level = levels.find("productId = ?1 and locationId = ?2", productId, locationId)
                .firstResult();
        if (level == null) {
            level = new StockLevelEntity();
            level.productId = productId;
            level.locationId = locationId;
            levels.persist(level);
        }
        level.quantity = quantity;
        levels.flush();
    }

    private void book(long productId, StockLocation location, int delta, int after,
                      StockMovement.Kind kind, String reference) {
        ledger.record(new StockMovement(null, productId, location.id(), Instant.now(), delta, after,
                kind, reference, actor.name()));
    }

    /** The product's cached figure: the pieces a customer can order. */
    void recompute(long productId) {
        ProductEntity product = products.findById(productId);
        if (product == null) return;
        int website = 0;
        for (StockLevel level : levelsFor(productId)) {
            if (level.location().countsForWebsite()) website += level.quantity();
        }
        product.stockQuantity = website;
        product.inventoryKnown = true;
        products.flush();
        websiteRebuild.queue();
    }

    private void recomputeAll() {
        for (ProductEntity product : products.listAll()) recompute(product.id);
    }

    private void requireProduct(long productId) {
        if (products.findById(productId) == null) throw new NotFoundException("Product", productId);
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
