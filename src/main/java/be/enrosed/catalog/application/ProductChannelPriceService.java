package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductChannelPriceEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductChannelPriceHistoryEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.CurrentActor;
import be.enrosed.tica.TicaEntities.Sale;
import be.enrosed.tica.TicaStockEntities.ProductUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@ApplicationScoped
public class ProductChannelPriceService {
    public enum Unit { PIECE, DISPLAY, TICA_ARTICLE }
    public record Price(String channel, BigDecimal priceExclVat, String source, Unit unit,
                        BigDecimal observedPriceExclVat, Unit observedUnit, LocalDateTime observedAt,
                        Instant updatedAt, BigDecimal manualPriceExclVat, Unit manualUnit) {}
    public record Change(BigDecimal priceExclVat, Unit unit) {}
    public record History(Long id, BigDecimal priceExclVat, String unit, String source,
                          Instant changedAt, String changedBy) {}
    private record Effective(BigDecimal price, String unit, String source) {}

    @Inject EntityManager entities;
    @Inject CurrentActor actor;

    public List<Price> prices(long productId) {
        product(productId, false);
        return List.of(dto(find(productId)));
    }

    public List<History> history(long productId) {
        product(productId, false);
        return entities.createQuery("""
                from ProductChannelPriceHistoryEntity h where h.productId = :product and h.channel = 'TICA'
                order by h.changedAt desc, h.id desc
                """, ProductChannelPriceHistoryEntity.class).setParameter("product", productId).setMaxResults(100)
                .getResultList().stream().map(h -> new History(h.id, h.priceExclVat, h.unit, h.source,
                        h.changedAt, h.changedBy)).toList();
    }

    @Transactional
    public Price update(long productId, Change request) {
        if (request == null) throw new BusinessRuleException("Geef de TICA-prijs door");
        BigDecimal amount = request.priceExclVat();
        if (amount != null && (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000000")) >= 0
                || amount.stripTrailingZeros().scale() > 4))
            throw new BusinessRuleException("De TICA-prijs moet positief zijn, met maximaal vier decimalen");
        if (amount != null && request.unit() == null)
            throw new BusinessRuleException("Kies de eenheid van de TICA-prijs");
        product(productId, true); // Also serializes first-row creation with the synchronizer.
        ProductChannelPriceEntity row = find(productId);
        if (row == null) row = create(productId);
        Effective before = effective(row);
        row.manualPrice = amount == null ? null : amount.setScale(4);
        row.manualUnit = amount == null ? null : request.unit().name();
        if (!same(before, effective(row))) {
            row.updatedAt = Instant.now();
            record(row, actor.name());
        }
        return dto(row);
    }

    /**
     * Called within the successful, complete TICA import transaction. The latest sale
     * supplies an undiscounted price excluding VAT; a manual price always wins.
     * Returns and missing source rows never become a new positive selling price.
     */
    @Transactional
    public void refreshTicaPrices() {
        List<Sale> sales = Sale.list("active = true and productId is not null and quantity > 0 and grossTotal > 0 and discountPercent = 0 "
                + "order by soldAt desc, id desc");
        Map<Long, List<Sale>> candidates = new TreeMap<>();
        for (Sale sale : sales) candidates.computeIfAbsent(sale.productId, ignored -> new ArrayList<>()).add(sale);
        // Include removed/remapped source products, so a retracted price cannot remain silently current.
        // Fetch IDs only: loading price entities before their product lock could cache a
        // stale manual override while another editor holds the lock and commits a change.
        for (Long productId : entities.createQuery(
                "select p.productId from ProductChannelPriceEntity p where p.channel = 'TICA'", Long.class)
                .getResultList()) candidates.putIfAbsent(productId, List.of());
        for (Map.Entry<Long, List<Sale>> entry : candidates.entrySet()) {
            ProductEntity product = entities.find(ProductEntity.class, entry.getKey(), LockModeType.PESSIMISTIC_WRITE);
            if (product == null) continue;
            entities.flush();
            entities.refresh(product, LockModeType.PESSIMISTIC_WRITE);
            ProductChannelPriceEntity row = find(product.id);
            if (row == null) row = create(product.id);
            Effective before = effective(row);
            // A product editor can change the SKU while this import waits for its lock.
            // Recheck source identity against the locked product, including older candidates.
            Sale sale = entry.getValue().stream().filter(source -> Objects.equals(product.sku, source.sku))
                    .findFirst().orElse(null);
            row.observedPrice = sale == null ? null : sale.grossTotal.divide(sale.quantity, 4, RoundingMode.HALF_UP);
            row.observedUnit = sale == null ? null : observedUnit(product, sale).name();
            row.observedAt = sale == null ? null : sale.soldAt;
            row.observedSaleId = sale == null ? null : sale.id;
            if (!same(before, effective(row))) {
                row.updatedAt = Instant.now();
                record(row, "system");
            }
        }
    }

    private Unit observedUnit(ProductEntity product, Sale sale) {
        if (product.packaging().salesUnit() != SalesUnit.DISPLAY) return Unit.PIECE;
        ProductUnit configured = ProductUnit.find("productId", product.id).firstResult();
        if (configured != null) return Unit.valueOf(configured.sourceUnit);
        if (sale.barcode != null && !sale.barcode.isBlank() && sale.barcode.equals(product.packagingBarcode))
            return Unit.DISPLAY;
        // A SKU match does not prove whether a display or one of its pieces was sold.
        return Unit.TICA_ARTICLE;
    }

    private ProductEntity product(long id, boolean lock) {
        ProductEntity product = lock ? entities.find(ProductEntity.class, id, LockModeType.PESSIMISTIC_WRITE)
                : entities.find(ProductEntity.class, id);
        if (product == null) throw new NotFoundException("Product", id);
        return product;
    }

    private ProductChannelPriceEntity find(long productId) {
        return entities.createQuery("from ProductChannelPriceEntity p where p.productId = :product and p.channel = 'TICA'",
                        ProductChannelPriceEntity.class).setParameter("product", productId)
                .getResultStream().findFirst().orElse(null);
    }

    private ProductChannelPriceEntity create(long productId) {
        ProductChannelPriceEntity row = new ProductChannelPriceEntity();
        row.productId = productId; row.channel = "TICA"; row.updatedAt = Instant.now();
        entities.persist(row);
        return row;
    }

    private void record(ProductChannelPriceEntity price, String changedBy) {
        Effective effective = effective(price);
        ProductChannelPriceHistoryEntity row = new ProductChannelPriceHistoryEntity();
        row.productId = price.productId; row.channel = price.channel; row.priceExclVat = effective.price();
        row.unit = effective.unit(); row.source = effective.source();
        row.sourceSaleId = price.manualPrice == null ? price.observedSaleId : null;
        row.changedAt = price.updatedAt; row.changedBy = changedBy;
        entities.persist(row);
    }

    private static Effective effective(ProductChannelPriceEntity row) {
        if (row == null) return new Effective(null, null, null);
        if (row.manualPrice != null) return new Effective(row.manualPrice, row.manualUnit, "MANUAL");
        return new Effective(row.observedPrice, row.observedUnit, row.observedPrice == null ? null : "TICA");
    }

    private static boolean same(Effective a, Effective b) {
        return (a.price() == null ? b.price() == null : b.price() != null && a.price().compareTo(b.price()) == 0)
                && Objects.equals(a.unit(), b.unit()) && Objects.equals(a.source(), b.source());
    }

    private static Unit unit(String value) { return value == null ? null : Unit.valueOf(value); }
    private static Price dto(ProductChannelPriceEntity row) {
        Effective effective = effective(row);
        return new Price("TICA", effective.price(), effective.source(), unit(effective.unit()),
                row == null ? null : row.observedPrice, row == null ? null : unit(row.observedUnit),
                row == null ? null : row.observedAt, row == null ? null : row.updatedAt,
                row == null ? null : row.manualPrice, row == null ? null : unit(row.manualUnit));
    }
}
