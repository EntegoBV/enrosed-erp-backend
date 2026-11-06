package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.PriceBasis;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.sales.adapter.out.persistence.CustomerProductPriceEntity;
import be.enrosed.sales.adapter.out.persistence.CustomerProductPriceHistoryEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Klantprijzen: one agreed price per customer and product, excl. VAT on the product's own sales
 * unit (per display when sold per display), like the catalogue price. A price applies while it has
 * not expired and its basis still matches the product; it is kept, not applied, otherwise. Only the
 * catalogue PDF made for that customer uses them (not quotes, invoices, the portal or the website).
 * Every change leaves a history row; the Logboek gets one entry per save.
 */
@ApplicationScoped
public class CustomerPriceService {

    public static final String SOURCE_CUSTOMER_SCREEN = "CUSTOMER_SCREEN";
    public static final String SOURCE_CATALOG_EXPORT = "CATALOG_EXPORT";
    public static final String SOURCE_CUSTOMER_DELETED = "CUSTOMER_DELETED";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_BASIS_CHANGED = "BASIS_CHANGED";
    public static final String STATUS_PRODUCT_GONE = "PRODUCT_GONE";

    private static final String ACTIVITY_ENTITY = "CUSTOMER";
    private static final BigDecimal PRICE_LIMIT = new BigDecimal("1000000");
    private static final int NOTE_LIMIT = 300;
    private static final int CHANGE_LIMIT = 1000;
    private static final int HISTORY_LIMIT = 200;
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    /** One stored price as the customer sheet shows it; sku and name are null once the product is gone. */
    public record Row(Long productId, String sku, String name, BigDecimal priceExclVat, String salesUnit,
                      Integer piecesPerUnit, String unitKey, BigDecimal cataloguePriceAtSet,
                      BigDecimal cataloguePriceNow, LocalDate validUntil, String note, String status,
                      Instant updatedAt, String updatedBy) {}

    /** A null price removes the product's price; the other fields are written as given. */
    public record Change(Long productId, BigDecimal priceExclVat, LocalDate validUntil, String note) {}

    /** Patch semantics: only the listed products change. */
    public record ChangeRequest(String source, List<Change> changes) {}

    public record HistoryRow(Long productId, BigDecimal priceExclVat, BigDecimal previousPriceExclVat,
                             String salesUnit, Integer piecesPerUnit, LocalDate validUntil, String source,
                             Instant changedAt, String changedBy) {}

    @Inject EntityManager entities;
    @Inject ProductRepository products;
    @Inject CurrentActor actor;
    @Inject AdminIdentityProvider names;
    @Inject Instance<ActivityLogService> activity;

    public List<Row> list(long customerId) {
        customer(customerId, false);
        List<CustomerProductPriceEntity> rows = entities.createQuery(
                        "from CustomerProductPriceEntity p where p.customerId = :customer",
                        CustomerProductPriceEntity.class)
                .setParameter("customer", customerId).getResultList();
        Map<Long, Product> byId = productsById(rows.stream().map(row -> row.productId).toList());
        LocalDate today = today();
        return rows.stream().map(row -> row(row, byId.get(row.productId), today))
                .sorted(Comparator.comparing((Row row) -> row.sku() == null)
                        .thenComparing(row -> row.sku() == null ? "" : row.sku().toLowerCase(Locale.ROOT))
                        .thenComparing(Row::productId))
                .toList();
    }

    public List<HistoryRow> history(long customerId, Long productId) {
        customer(customerId, false);
        String filter = productId == null ? "" : " and h.productId = :product";
        var query = entities.createQuery("from CustomerProductPriceHistoryEntity h where h.customerId = :customer"
                        + filter + " order by h.changedAt desc, h.id desc", CustomerProductPriceHistoryEntity.class)
                .setParameter("customer", customerId).setMaxResults(HISTORY_LIMIT);
        if (productId != null) query.setParameter("product", productId);
        return query.getResultList().stream().map(row -> new HistoryRow(row.productId, row.priceExclVat,
                row.previousPriceExclVat, row.salesUnit, row.piecesPerUnit, row.validUntil, row.source,
                row.changedAt, names.displayNameFor(row.changedBy))).toList();
    }

    @Transactional
    public List<Row> change(long customerId, ChangeRequest request) {
        SalesEntities.CustomerEntity customer = customer(customerId, true);
        String source = source(request == null ? null : request.source());
        List<Change> changes = request == null || request.changes() == null ? List.of()
                : request.changes().stream().filter(Objects::nonNull).toList();
        if (changes.size() > CHANGE_LIMIT) {
            throw new BusinessRuleException("Bewaar hoogstens " + CHANGE_LIMIT + " klantprijzen tegelijk");
        }
        Set<Long> seen = new HashSet<>();
        for (Change change : changes) {
            if (change.productId() == null) throw new BusinessRuleException("Een klantprijs noemt geen product");
            if (!seen.add(change.productId())) {
                throw new BusinessRuleException("Product #" + change.productId() + " staat twee keer in de wijzigingen");
            }
        }
        Map<Long, Product> byId = productsById(seen);
        Map<Long, CustomerProductPriceEntity> current = currentRows(customerId, seen);
        LocalDate today = today();
        Instant now = Instant.now();
        String who = actor.name();
        ActivityChangeSet log = ActivityChangeSet.create();
        int changed = 0;
        for (Change change : changes) {
            Product product = byId.get(change.productId());
            CustomerProductPriceEntity row = current.get(change.productId());
            String label = product == null ? "Product #" + change.productId() : product.sku();
            if (change.priceExclVat() == null) {
                /* Removing works for a deleted product too: that is how a PRODUCT_GONE row is cleared. */
                if (row == null) continue;
                history(row, null, row.priceExclVat, source, now, who);
                log.add("customerPrice." + row.productId, "Klantprijs · " + label, row.priceExclVat, null);
                entities.remove(row);
                changed++;
                continue;
            }
            if (product == null) throw new BusinessRuleException(label + " bestaat niet meer");
            if (product.demo()) throw new BusinessRuleException(label + " is een demoproduct en krijgt geen klantprijs");
            BigDecimal price = price(change.priceExclVat(), label);
            String note = note(change.note(), label);
            PriceBasis basis = PriceBasis.of(product.packaging());
            boolean validityChanged = row == null || !Objects.equals(row.validUntil, change.validUntil());
            if (validityChanged && change.validUntil() != null && change.validUntil().isBefore(today)) {
                throw new BusinessRuleException(label + ": de einddatum ligt in het verleden");
            }
            if (row == null) {
                row = new CustomerProductPriceEntity();
                row.customerId = customerId;
                row.productId = product.id();
                row.createdAt = now;
                row.createdBy = who;
                write(row, product, basis, price, change.validUntil(), note, now, who);
                entities.persist(row);
                history(row, price, null, source, now, who);
                log.add("customerPrice." + row.productId, "Klantprijs · " + label, null, price);
                if (change.validUntil() != null) log.add("customerPriceValidUntil." + row.productId,
                        "Geldig tot · " + label, null, change.validUntil());
                changed++;
                continue;
            }
            boolean priceChanged = row.priceExclVat.compareTo(price) != 0;
            boolean basisChanged = !basis.matches(salesUnit(row.salesUnit), row.piecesPerUnit);
            boolean noteChanged = !Objects.equals(row.note, note);
            if (!priceChanged && !basisChanged && !validityChanged && !noteChanged) continue;
            BigDecimal previous = row.priceExclVat;
            LocalDate previousValidity = row.validUntil;
            String previousNote = row.note;
            BigDecimal catalogueAtSet = row.cataloguePriceEur;
            /* A price typed again on a changed basis is confirmed on the new basis, so it applies again;
               only a new price or basis takes a new catalogue snapshot. */
            write(row, product, basis, price, change.validUntil(), note, now, who);
            if (!priceChanged && !basisChanged) row.cataloguePriceEur = catalogueAtSet;
            if (priceChanged || basisChanged || validityChanged) history(row, price, previous, source, now, who);
            log.add("customerPrice." + row.productId, "Klantprijs · " + label, previous, price)
                    .add("customerPriceValidUntil." + row.productId, "Geldig tot · " + label,
                            previousValidity, row.validUntil)
                    .privateValue("customerPriceNote." + row.productId, "Notitie klantprijs · " + label,
                            previousNote, row.note);
            changed++;
        }
        if (changed > 0) recordActivity(customer, source, changed, log);
        entities.flush();
        return list(customerId);
    }

    /** Called inside the customer's deletion: the rows go, the history says so. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void removeForDeletedCustomer(long customerId) {
        List<CustomerProductPriceEntity> rows = entities.createQuery(
                        "from CustomerProductPriceEntity p where p.customerId = :customer",
                        CustomerProductPriceEntity.class)
                .setParameter("customer", customerId).getResultList();
        Instant now = Instant.now();
        String who = actor.name();
        for (CustomerProductPriceEntity row : rows) {
            history(row, null, row.priceExclVat, SOURCE_CUSTOMER_DELETED, now, who);
            entities.remove(row);
        }
    }

    private Row row(CustomerProductPriceEntity row, Product product, LocalDate today) {
        return new Row(row.productId, product == null ? null : product.sku(), product == null ? null : product.name(),
                row.priceExclVat, row.salesUnit, row.piecesPerUnit, row.unitKey, row.cataloguePriceEur,
                product == null ? null : positive(product.computedSalesPriceEur()), row.validUntil, row.note,
                status(row, product, today), row.updatedAt, names.displayNameFor(row.updatedBy));
    }

    /** PRODUCT_GONE, then BASIS_CHANGED (the amount means something else now), then EXPIRED. */
    static String status(CustomerProductPriceEntity row, Product product, LocalDate today) {
        if (product == null) return STATUS_PRODUCT_GONE;
        if (!PriceBasis.of(product.packaging()).matches(salesUnit(row.salesUnit), row.piecesPerUnit)) {
            return STATUS_BASIS_CHANGED;
        }
        if (row.validUntil != null && row.validUntil.isBefore(today)) return STATUS_EXPIRED;
        return STATUS_ACTIVE;
    }

    private static void write(CustomerProductPriceEntity row, Product product, PriceBasis basis, BigDecimal price,
                              LocalDate validUntil, String note, Instant now, String who) {
        row.priceExclVat = price;
        row.salesUnit = basis.salesUnit().name();
        row.piecesPerUnit = basis.piecesPerUnit();
        row.unitKey = product.packaging().unitKey();
        row.cataloguePriceEur = positive(product.computedSalesPriceEur());
        row.validUntil = validUntil;
        row.note = note;
        row.updatedAt = now;
        row.updatedBy = who;
    }

    private void history(CustomerProductPriceEntity row, BigDecimal price, BigDecimal previous, String source,
                         Instant now, String who) {
        CustomerProductPriceHistoryEntity entry = new CustomerProductPriceHistoryEntity();
        entry.customerId = row.customerId;
        entry.productId = row.productId;
        entry.priceExclVat = price;
        entry.previousPriceExclVat = previous;
        entry.salesUnit = row.salesUnit;
        entry.piecesPerUnit = row.piecesPerUnit;
        entry.unitKey = row.unitKey;
        entry.validUntil = price == null ? null : row.validUntil;
        entry.source = source;
        entry.changedAt = now;
        entry.changedBy = who;
        entities.persist(entry);
    }

    private void recordActivity(SalesEntities.CustomerEntity customer, String source, int count,
                                ActivityChangeSet changes) {
        if (activity == null || !activity.isResolvable()) return;
        String summary = (SOURCE_CATALOG_EXPORT.equals(source)
                ? "Klantprijzen bewaard vanuit de catalogus" : "Klantprijzen bijgewerkt") + " (" + count + ")";
        activity.get().record(ActivityLogService.ACTION_UPDATED, ACTIVITY_ENTITY, customer.id.toString(),
                customer.company, summary, changes.build());
    }

    private SalesEntities.CustomerEntity customer(long id, boolean lock) {
        /* The lock serializes two saves for one customer, so neither creates a row the other just made. */
        SalesEntities.CustomerEntity customer = lock
                ? entities.find(SalesEntities.CustomerEntity.class, id, LockModeType.PESSIMISTIC_WRITE)
                : entities.find(SalesEntities.CustomerEntity.class, id);
        if (customer == null) throw new NotFoundException("Klant", id);
        return customer;
    }

    private Map<Long, CustomerProductPriceEntity> currentRows(long customerId, Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return entities.createQuery("from CustomerProductPriceEntity p where p.customerId = :customer"
                        + " and p.productId in :products", CustomerProductPriceEntity.class)
                .setParameter("customer", customerId).setParameter("products", List.copyOf(productIds))
                .getResultList().stream()
                .collect(Collectors.toMap(row -> row.productId, Function.identity(), (left, right) -> left));
    }

    private Map<Long, Product> productsById(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return products.findAllById(List.copyOf(new HashSet<>(ids))).stream()
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
    }

    private static String source(String requested) {
        if (requested == null || requested.isBlank()) return SOURCE_CUSTOMER_SCREEN;
        String source = requested.strip().toUpperCase(Locale.ROOT);
        if (!SOURCE_CUSTOMER_SCREEN.equals(source) && !SOURCE_CATALOG_EXPORT.equals(source)) {
            throw new BusinessRuleException("Onbekende bron voor klantprijzen: " + requested.strip());
        }
        return source;
    }

    /** At most two decimals, refused rather than rounded: the PDF prints cents. */
    private static BigDecimal price(BigDecimal value, String label) {
        if (value.signum() <= 0 || value.compareTo(PRICE_LIMIT) >= 0) {
            throw new BusinessRuleException(label + ": een klantprijs ligt tussen 0 en 1 000 000 euro");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleException(label + ": een klantprijs heeft hoogstens 2 decimalen");
        }
        return value.setScale(2);
    }

    private static String note(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String note = value.strip();
        if (note.length() > NOTE_LIMIT) {
            throw new BusinessRuleException(label + ": de notitie mag hoogstens " + NOTE_LIMIT + " tekens lang zijn");
        }
        return note;
    }

    private static SalesUnit salesUnit(String value) {
        if (value == null) return null;
        try {
            return SalesUnit.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static LocalDate today() {
        return LocalDate.now(BRUSSELS);
    }
}
