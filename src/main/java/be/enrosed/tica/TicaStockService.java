package be.enrosed.tica;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.StockLocationEntity;
import be.enrosed.catalog.adapter.out.persistence.StockMovementEntity;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.application.ProductChannelPriceService;
import be.enrosed.catalog.domain.StockLocation;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.tica.TicaEntities.Sale;
import be.enrosed.tica.TicaEntities.Settings;
import be.enrosed.tica.TicaStockEntities.Location;
import be.enrosed.tica.TicaStockEntities.Revision;
import be.enrosed.tica.TicaStockEntities.ProductUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class TicaStockService {
    private final EntityManager entities;
    private final StockService stock;
    private final ObjectMapper json;
    private final ProductChannelPriceService channelPrices;

    public TicaStockService(EntityManager entities, StockService stock, ObjectMapper json, ProductChannelPriceService channelPrices) {
        this.entities = entities; this.stock = stock; this.json = json; this.channelPrices = channelPrices;
    }

    public record LocationDto(String sourceLocation, Long stockLocationId, String stockLocationName, boolean locked) {}
    public record ProductUnitDto(Long productId, String sourceUnit, String productSalesUnit, int piecesPerUnit, boolean locked) {}
    public record SettingsDto(boolean enabled, LocalDate fromDate, String timezone, boolean configurationLocked,
                              List<LocationDto> locations, long postedLineCount, long pendingLineCount,
                              List<ProductUnitDto> productUnits) {}
    public record Snapshot(String location, String exhibitor, LocalDateTime soldAt, String transactionNumber,
                           String lineNumber, String description, String beeyondArticleNumber, String sku, String barcode,
                           BigDecimal quantity, BigDecimal discountPercent, BigDecimal grossTotal, BigDecimal netTotal,
                           BigDecimal vatRate, String transactionType, Long productId, boolean active) {}
    public record MovementDto(Long id, Instant at, int delta, int quantityAfter, String kind, String reference,
                              long productId, Long locationId, int looseDelta, int loosePiecesAfter, Integer unitPieces,
                              BigDecimal sourceQuantity, String sourceUnit) {}
    public record RevisionDto(Long id, Instant recordedAt, LocalDateTime sourceSoldAt, boolean active, BigDecimal quantity,
                              BigDecimal grossTotal, BigDecimal netTotal, Long productId, String sku, String description,
                              String stockStatus, int stockQuantity, Long stockProductId, Long stockLocationId,
                              List<MovementDto> movements, int stockPieces, Integer stockUnitPieces, String sourceUnit) {}
    public record HistoryDto(long saleId, List<RevisionDto> revisions) {}
    private record Plan(String status, Long productId, Long locationId, int quantity, int pieces,
                        Integer unitPieces, String sourceUnit) {}

    @Transactional
    public SettingsDto settings() {
        Settings settings = Settings.findById(1L);
        Map<String, Location> mapped = mappings();
        Set<String> sourceLocations = new TreeSet<>(mapped.keySet());
        sourceLocations.addAll(entities.createQuery("select distinct s.location from TicaSale s", String.class).getResultList());
        List<LocationDto> locations = sourceLocations.stream().map(source -> {
            Location mapping = mapped.get(source);
            Long id = mapping == null ? null : mapping.stockLocationId;
            StockLocationEntity location = id == null ? null : entities.find(StockLocationEntity.class, id);
            return new LocationDto(source, id, location == null ? null : location.name, locationLocked(source));
        }).toList();
        long posted = Sale.count("stockStatus = 'POSTED'");
        long pending = Sale.count("active = true and (stockStatus is null or stockStatus not in ('POSTED','BEFORE_START'))");
        List<ProductUnitDto> units = entities.createQuery(
                "from ProductEntity p where p.id in (select s.productId from TicaSale s where s.productId is not null) order by p.id",
                ProductEntity.class).getResultList().stream().map(product -> new ProductUnitDto(product.id,
                        sourceUnit(product), product.packaging().salesUnit().name(),
                        product.packaging().soldAsDisplay() ? product.packaging().unitPieces() : 1,
                        productLocked(product.id))).toList();
        return new SettingsDto(settings != null && Boolean.TRUE.equals(settings.stockEnabled),
                settings == null ? null : settings.stockFromDate, TicaService.ZONE.getId(),
                anyBookings(), locations, posted, pending, units);
    }

    @Transactional
    public SettingsDto update(SettingsDto request) {
        if (request == null || request.locations() == null) throw new BusinessRuleException("Kies de TICA-voorraadinstellingen.");
        Settings settings = TicaService.lockedSettings();
        lockRelevantProducts();
        if (request.enabled() && request.fromDate() == null)
            throw new BusinessRuleException("Kies vanaf welke verkoopdatum de TICA-voorraad moet worden bijgewerkt.");
        if (anyBookings() && !Objects.equals(settings.stockFromDate, request.fromDate()))
            throw new BusinessRuleException("De startdatum is vastgelegd door de eerste TICA-voorraadboeking.");
        Map<String, Location> current = mappings();
        Set<String> known = new HashSet<>(current.keySet());
        known.addAll(entities.createQuery("select distinct s.location from TicaSale s", String.class).getResultList());
        Set<String> seen = new HashSet<>();
        for (LocationDto item : request.locations()) {
            if (item == null || item.sourceLocation() == null || !known.contains(item.sourceLocation())
                    || !seen.add(item.sourceLocation())) throw new BusinessRuleException("Kies elke geïmporteerde TICA-vestiging eenmaal.");
            Location row = current.get(item.sourceLocation());
            Long oldId = row == null ? null : row.stockLocationId;
            if (locationLocked(item.sourceLocation()) && !Objects.equals(oldId, item.stockLocationId()))
                throw new BusinessRuleException("Deze vestiging heeft voorraadboekingen; haar voorraadlocatie blijft vastgelegd.");
            if (item.stockLocationId() != null) {
                StockLocationEntity location = entities.find(StockLocationEntity.class, item.stockLocationId());
                if (location == null || !location.active || location.kind != StockLocation.Kind.SALES_POINT)
                    throw new BusinessRuleException("Kies een actieve voorraadlocatie van het type verkooppunt.");
            }
            if (row == null) { row = new Location(); row.sourceLocation = item.sourceLocation(); row.persist(); }
            row.stockLocationId = item.stockLocationId();
        }
        Set<Long> unitIds = new HashSet<>();
        if (request.productUnits() != null) request.productUnits().stream().filter(Objects::nonNull)
                .map(ProductUnitDto::productId).filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> entities.find(ProductEntity.class, id, LockModeType.PESSIMISTIC_WRITE));
        if (request.productUnits() != null) for (ProductUnitDto item : request.productUnits()) {
            if (item == null || item.productId() == null || !unitIds.add(item.productId())
                    || (item.sourceUnit() != null && !Set.of("PIECE", "DISPLAY").contains(item.sourceUnit())))
                throw new BusinessRuleException("Kies per TICA-product een geldige verkoopeenheid.");
            ProductEntity product = entities.find(ProductEntity.class, item.productId());
            if (product == null || Sale.count("productId", item.productId()) == 0)
                throw new BusinessRuleException("Kies een product uit de geïmporteerde TICA-verkopen.");
            if (!product.packaging().soldAsDisplay() && !"PIECE".equals(item.sourceUnit()))
                throw new BusinessRuleException("Dit ERP-product houdt voorraad per stuk bij.");
            ProductUnit unit = ProductUnit.<ProductUnit>find("productId", item.productId()).firstResult();
            if (productLocked(item.productId()) && !Objects.equals(sourceUnit(product), item.sourceUnit()))
                throw new BusinessRuleException("De TICA-verkoopeenheid is vastgelegd door de voorraadboekingen.");
            if (item.sourceUnit() == null) { if (unit != null) unit.delete(); }
            else {
                if (unit == null) {
                    unit = new ProductUnit(); unit.productId = item.productId(); unit.sourceUnit = item.sourceUnit(); unit.persist();
                }
                unit.sourceUnit = item.sourceUnit();
            }
        }
        settings.stockEnabled = request.enabled(); settings.stockFromDate = request.fromDate();
        settings.syncRequested = true;
        entities.flush();
        channelPrices.refreshTicaPrices();
        return settings();
    }

    /** Called inside the validated export transaction under the singleton TICA lock. */
    public void lockRelevantProducts() {
        SortedSet<Long> ids = new TreeSet<>();
        ids.addAll(entities.createQuery("select distinct s.productId from TicaSale s where s.productId is not null", Long.class).getResultList());
        ids.addAll(entities.createQuery("select distinct s.stockProductId from TicaSale s where s.stockProductId is not null", Long.class).getResultList());
        ids.addAll(entities.createQuery("select distinct p.productId from ProductChannelPriceEntity p where p.channel = 'TICA'", Long.class).getResultList());
        for (Long id : ids) entities.find(ProductEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    /** Called inside the validated export transaction under the singleton TICA lock. */
    public void refreshProductMappings(Instant now) {
        Map<String, Long> found = new HashMap<>();
        for (Sale sale : Sale.<Sale>list("active = true order by id")) {
            if (sale.sku == null || sale.sku.isBlank()) continue;
            if (!found.containsKey(sale.sku)) {
                List<Long> matches = entities.createQuery("select p.id from ProductEntity p where p.sku = :sku", Long.class)
                        .setParameter("sku", sale.sku).setMaxResults(2).getResultList();
                found.put(sale.sku, matches.size() == 1 ? matches.getFirst() : null);
            }
            Long id = found.get(sale.sku);
            if (!Objects.equals(sale.productId, id)) { sale.productId = id; sale.updatedAt = now; }
        }
    }

    /** Called inside the validated export transaction under the singleton TICA lock. */
    public void reconcileAll(Instant now) {
        Settings settings = Settings.findById(1L);
        Map<String, Location> locations = mappings();
        List<Sale> sales = Sale.list("order by id");
        // Match the product lock used by every stock writer, in deterministic order across a batch.
        SortedSet<Long> productIds = new TreeSet<>();
        for (Sale sale : sales) {
            if (sale.productId != null) productIds.add(sale.productId);
            if (sale.stockProductId != null) productIds.add(sale.stockProductId);
        }
        Map<Long, ProductEntity> products = new HashMap<>();
        for (Long id : productIds) {
            ProductEntity product = entities.find(ProductEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (product != null) products.put(id, product);
        }
        for (Sale sale : sales) reconcile(sale, settings, locations, products, now);
    }

    private void reconcile(Sale sale, Settings settings, Map<String, Location> locations,
                           Map<Long, ProductEntity> products, Instant now) {
        Snapshot source = snapshot(sale);
        Plan desired = plan(sale, settings, locations, products);
        String sourceJson = write(source);
        String fingerprint = digest(sourceJson + "\n" + write(desired));
        if (fingerprint.equals(sale.stockFingerprint)) return;
        Revision revision = new Revision();
        revision.saleId = sale.id; revision.recordedAt = now; revision.sourceSnapshot = sourceJson;
        revision.stockStatus = desired.status(); revision.stockProductId = desired.productId();
        revision.stockLocationId = desired.locationId(); revision.stockQuantity = desired.quantity();
        revision.stockPieces = desired.pieces(); revision.stockUnitPieces = desired.unitPieces();
        revision.sourceUnit = desired.sourceUnit();
        revision.persistAndFlush();
        int previous = pieces(sale);
        boolean sameTarget = Objects.equals(sale.stockProductId, desired.productId())
                && Objects.equals(sale.stockLocationId, desired.locationId());
        String ref = "TICA " + sale.transactionNumber + "/" + sale.lineNumber;
        if (sameTarget) {
            int delta = Math.subtractExact(previous, desired.pieces());
            if (delta != 0) stock.applyTica(desired.productId(), desired.locationId(), delta,
                    sale.id, revision.id, sale.soldAt, ref + (previous == 0 ? " · verkoop/retour" : " · correctie"), now,
                    sale.quantity, desired.sourceUnit());
        } else {
            if (previous != 0) stock.applyTica(sale.stockProductId, sale.stockLocationId, previous,
                    sale.id, revision.id, sale.soldAt, ref + " · eerdere boeking tegengeboekt", now,
                    sale.quantity, sale.stockSourceUnit);
            if (desired.pieces() != 0) stock.applyTica(desired.productId(), desired.locationId(), -desired.pieces(),
                    sale.id, revision.id, sale.soldAt, ref + " · verkoop/retour", now, sale.quantity, desired.sourceUnit());
        }
        sale.stockProductId = desired.productId(); sale.stockLocationId = desired.locationId();
        sale.stockQuantity = desired.quantity(); sale.stockStatus = desired.status();
        sale.stockPieces = desired.pieces(); sale.stockUnitPieces = desired.unitPieces();
        sale.stockSourceUnit = desired.sourceUnit();
        sale.stockUpdatedAt = now; sale.stockFingerprint = fingerprint;
    }

    private Plan plan(Sale sale, Settings settings, Map<String, Location> locations, Map<Long, ProductEntity> products) {
        if (!Boolean.TRUE.equals(settings.stockEnabled))
            return blocked("DISABLED", sale);
        if (!sale.active) return empty("REVERSED");
        if (settings.stockFromDate == null || sale.soldAt.toLocalDate().isBefore(settings.stockFromDate))
            return empty("BEFORE_START");
        ProductEntity product = sale.productId == null ? null : products.get(sale.productId);
        if (product == null || !Objects.equals(product.sku, sale.sku)) return blocked("PRODUCT_UNMATCHED", sale);
        String unit = sourceUnit(product);
        if (unit == null) return blocked("UNIT_MISMATCH", sale);
        int factor = product.packaging().soldAsDisplay() ? product.packaging().unitPieces() : 1;
        if (sale.stockUnitPieces != null && sale.stockUnitPieces != factor && pieces(sale) != 0)
            return blocked("UNIT_MISMATCH", sale);
        int quantity;
        try { quantity = sale.quantity.intValueExact(); }
        catch (ArithmeticException invalid) { return blocked("INVALID_QUANTITY", sale); }
        int physical;
        try { physical = Math.multiplyExact(quantity, "DISPLAY".equals(unit) ? factor : 1); }
        catch (ArithmeticException invalid) { return blocked("INVALID_QUANTITY", sale); }
        Location mapping = locations.get(sale.location);
        StockLocationEntity location = mapping == null || mapping.stockLocationId == null ? null
                : entities.find(StockLocationEntity.class, mapping.stockLocationId);
        if (location == null || !location.active || location.kind != StockLocation.Kind.SALES_POINT)
            return blocked("LOCATION_UNMAPPED", sale);
        return new Plan("POSTED", sale.productId, location.id, quantity, physical, factor, unit);
    }

    /** An unresolved correction retains the last verified posting until its new target is clear. */
    private static Plan blocked(String status, Sale sale) {
        return new Plan(status, sale.stockProductId, sale.stockLocationId, quantity(sale), pieces(sale),
                sale.stockUnitPieces, sale.stockSourceUnit);
    }
    private static Plan empty(String status) { return new Plan(status, null, null, 0, 0, null, null); }

    @Transactional
    public HistoryDto history(long saleId) {
        if (Sale.findById(saleId) == null) throw new NotFoundException("TICA-verkoop", saleId);
        List<RevisionDto> history = Revision.<Revision>list("saleId = ?1 order by id desc", saleId).stream().map(revision -> {
            Snapshot source = read(revision.sourceSnapshot);
            List<MovementDto> movements = entities.createQuery(
                    "from StockMovementEntity where ticaRevisionId = :revision order by id", StockMovementEntity.class)
                    .setParameter("revision", revision.id).getResultList().stream()
                    .map(m -> new MovementDto(m.id, m.at, m.delta, m.quantityAfter, m.kind, m.reference, m.productId, m.locationId,
                            m.looseDelta == null ? 0 : m.looseDelta, m.loosePiecesAfter == null ? 0 : m.loosePiecesAfter,
                            m.unitPieces, m.sourceQuantity, m.sourceUnit)).toList();
            return new RevisionDto(revision.id, revision.recordedAt, source.soldAt(), source.active(), source.quantity(),
                    source.grossTotal(), source.netTotal(), source.productId(), source.sku(), source.description(),
                    revision.stockStatus, revision.stockQuantity, revision.stockProductId, revision.stockLocationId, movements,
                    revision.stockPieces == null ? 0 : revision.stockPieces, revision.stockUnitPieces, revision.sourceUnit);
        }).toList();
        return new HistoryDto(saleId, history);
    }

    private Map<String, Location> mappings() {
        Map<String, Location> result = new LinkedHashMap<>();
        for (Location row : Location.<Location>listAll()) result.put(row.sourceLocation, row);
        return result;
    }
    private boolean anyBookings() {
        return entities.createQuery("select count(m) from StockMovementEntity m where m.ticaSaleId is not null", Long.class).getSingleResult() > 0;
    }
    private boolean locationLocked(String source) {
        return entities.createQuery("select count(m) from StockMovementEntity m where m.ticaSaleId in (select s.id from TicaSale s where s.location = :source)", Long.class)
                .setParameter("source", source).getSingleResult() > 0;
    }
    private boolean productLocked(long id) {
        return entities.createQuery("select count(m) from StockMovementEntity m where m.productId = :id and m.ticaSaleId is not null", Long.class)
                .setParameter("id", id).getSingleResult() > 0;
    }
    public String sourceUnit(ProductEntity product) {
        if (!product.packaging().soldAsDisplay()) return "PIECE";
        ProductUnit unit = ProductUnit.<ProductUnit>find("productId", product.id).firstResult();
        return unit == null ? null : unit.sourceUnit;
    }
    static int pieces(Sale sale) { return sale.stockPieces == null ? 0 : sale.stockPieces; }
    static int quantity(Sale sale) { return sale.stockQuantity == null ? 0 : sale.stockQuantity; }
    static String status(Sale sale) { return sale.stockStatus == null ? "DISABLED" : sale.stockStatus; }
    static String message(Sale sale) {
        return switch (status(sale)) {
            case "POSTED" -> "Verwerkt in de voorraad van het TICA-verkooppunt.";
            case "REVERSED" -> "De bronregel is ingetrokken; eerdere voorraadboekingen zijn tegengeboekt.";
            case "BEFORE_START" -> "Verkoop valt vóór de ingestelde startdatum.";
            case "PRODUCT_UNMATCHED" -> "Geen eenduidige productkoppeling op exact artikelnummer.";
            case "UNIT_MISMATCH" -> "Kies of TICA losse stuks of volledige displays verkoopt; de voorraadbasis moet eenduidig zijn.";
            case "INVALID_QUANTITY" -> "Het TICA-aantal kan niet exact in gehele ERP-voorraadeenheden worden verwerkt.";
            case "LOCATION_UNMAPPED" -> "Koppel deze TICA-vestiging aan een actieve voorraadlocatie.";
            default -> "Automatische TICA-voorraadverwerking staat uit.";
        };
    }
    private static Snapshot snapshot(Sale s) {
        return new Snapshot(s.location, s.exhibitor, s.soldAt, s.transactionNumber, s.lineNumber, s.description,
                s.beeyondArticleNumber, s.sku, s.barcode, s.quantity, s.discountPercent, s.grossTotal, s.netTotal,
                s.vatRate, s.transactionType, s.productId, s.active);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("TICA-bronhistorie kon niet worden vastgelegd.", e); }
    }
    private Snapshot read(String value) {
        try { return json.readValue(value, Snapshot.class); } catch (Exception e) { throw new IllegalStateException("TICA-bronhistorie kon niet worden gelezen.", e); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
