package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnitNames;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The prices one customer was actually charged: what every quote and invoice of theirs put on a
 * line, per product, next to the klantprijs agreed today and the catalogue price now. Read only,
 * straight from the stored lines: a line pinned to a klantprijs or a typed price keeps that price,
 * but a line that follows the catalogue stores none (the catalogue price of that day is not kept),
 * so such a line reports no price rather than a figure that was never on the document. Credit
 * notes reverse an invoice and name no price of their own, a cancelled document was withdrawn,
 * a quote that became an invoice is counted once (as the invoice), and a deleted document never
 * counts.
 */
@ApplicationScoped
public class CustomerAppliedPriceService {

    /**
     * One product over all the customer's documents. sku and name are null once the product is
     * gone; the screen words that. {@code unit} is the Dutch singular the price is per ("stolp",
     * "display"), from the product as it is now, "stuk" when it is gone. The "last" fields speak
     * for the newest line that names a price on a document past concept; {@code lastPriceExclVat}
     * is null when every line of the product followed the catalogue, and min/max skip such lines.
     */
    public record AppliedPriceRow(Long productId, String sku, String name, String unit,
                                  BigDecimal lastPriceExclVat, String lastPriceSource, LocalDate lastOrderDate,
                                  Long lastDocumentId, String lastDocumentNumber, String lastDocType,
                                  String lastStatus, int documentCount, long totalQuantity,
                                  BigDecimal minPriceExclVat, BigDecimal maxPriceExclVat,
                                  BigDecimal agreedPriceExclVat, BigDecimal cataloguePriceNow) {}

    /**
     * One line of one document for one product. {@code lineId} tells two lines of the same product
     * on one document apart; {@code followsCatalogue} says the line stored no price of its own, so
     * {@code unitPriceEur} is null and the screen words it instead of printing a figure.
     */
    public record AppliedPriceEntry(Long lineId, Long documentId, String number, String docType, String status,
                                    LocalDate orderDate, int quantity, BigDecimal unitPriceEur,
                                    boolean followsCatalogue, String priceSource, BigDecimal manualDiscountPct) {}

    @Inject EntityManager entities;
    @Inject ProductRepository products;
    @Inject CustomerPriceService prices;

    /** Every product the customer was ever charged for, the most recently charged first; {@code productId} narrows to one. */
    public List<AppliedPriceRow> rows(long customerId, Long productId) {
        customer(customerId);
        Map<Long, Builder> builders = new LinkedHashMap<>();
        for (Line line : lines(customerId, productId)) {
            builders.computeIfAbsent(line.productId(), Builder::new).add(line);
        }
        if (builders.isEmpty()) return List.of();
        Map<Long, Product> byId = productsById(builders.keySet());
        Map<Long, BigDecimal> agreed = prices.activeFor(customerId, byId.values());
        return builders.values().stream()
                .map(builder -> builder.row(byId.get(builder.productId), agreed.get(builder.productId)))
                .sorted(Comparator.comparing((AppliedPriceRow row) -> row.lastOrderDate() == null)
                        .thenComparing(AppliedPriceRow::lastOrderDate,
                                Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
                        .thenComparing(row -> row.name() == null)
                        .thenComparing(row -> row.name() == null ? "" : row.name().toLowerCase(Locale.ROOT))
                        .thenComparing(AppliedPriceRow::productId))
                .toList();
    }

    /** Every line of this product on the customer's documents, newest document first. */
    public List<AppliedPriceEntry> entries(long customerId, long productId) {
        customer(customerId);
        return lines(customerId, productId).stream()
                .map(line -> new AppliedPriceEntry(line.lineId(), line.documentId(), line.number(),
                        docType(line.docType()), status(line.status()), line.orderDate(), line.quantity(),
                        line.unitPriceEur(), line.followsCatalogue(), line.priceSource(), line.manualDiscountPct()))
                .toList();
    }

    /** One line with the few columns of its document the screen needs; what {@link #lines} reads per row. */
    private record Line(Long lineId, Long productId, int quantity, BigDecimal unitPriceEur, String priceSource,
                        BigDecimal manualDiscountPct, Long documentId, String number, DocumentType docType,
                        QuoteStatus status, LocalDate orderDate) {

        static Line of(Object[] row) {
            return new Line((Long) row[0], (Long) row[1], row[2] == null ? 0 : ((Number) row[2]).intValue(),
                    (BigDecimal) row[3], (String) row[4], (BigDecimal) row[5], (Long) row[6], (String) row[7],
                    (DocumentType) row[8], (QuoteStatus) row[9], (LocalDate) row[10]);
        }

        /** No price of its own: it was priced from the catalogue of its day, which is not stored. */
        boolean followsCatalogue() {
            return unitPriceEur == null;
        }

        boolean concept() {
            return status == QuoteStatus.CONCEPT;
        }
    }

    /**
     * The product lines of the customer's quotes and invoices, newest document first, in one query
     * that reads only the columns used: the order entity would drag its lines, pallets and pallet
     * items along (all eager) for every document. Cancelled documents and quotes that became an
     * invoice (the invoice carries the same lines) are left out; {@code o.deletedAt is null} is
     * said here because the entity's restriction is only certain on a query root.
     */
    private List<Line> lines(long customerId, Long productId) {
        /* Both entities are nested in SalesEntities, so their JPQL names are not the simple class names (PartnerAdvanceQuotes does the same). */
        String lines = entities.getMetamodel().entity(SalesEntities.SalesOrderLineEntity.class).getName();
        String orders = entities.getMetamodel().entity(SalesEntities.SalesOrderEntity.class).getName();
        TypedQuery<Object[]> query = entities.createQuery(
                "select l.id, l.productId, l.quantity, l.unitPriceEur, l.priceSource, l.manualDiscountPct,"
                        + " o.id, o.number, o.docType, o.status, o.orderDate"
                        + " from " + lines + " l join l.order o"
                        + " where o.customerId = :customer and o.deletedAt is null and l.productId is not null"
                        + " and (o.docType is null or o.docType <> :credit)"
                        + " and (o.status is null or o.status <> :cancelled)"
                        + " and not exists (select 1 from " + orders + " i where i.sourceQuoteId = o.id"
                        + " and i.docType = :invoice and i.deletedAt is null and (i.status is null or i.status <> :cancelled))"
                        + (productId == null ? "" : " and l.productId = :product")
                        + " order by o.orderDate desc, o.id desc, l.id",
                        Object[].class)
                .setParameter("customer", customerId)
                .setParameter("credit", DocumentType.CREDITNOTA)
                .setParameter("invoice", DocumentType.FACTUUR)
                .setParameter("cancelled", QuoteStatus.GEANNULEERD);
        if (productId != null) query.setParameter("product", productId);
        return query.getResultList().stream().map(Line::of).toList();
    }

    private void customer(long id) {
        if (entities.find(SalesEntities.CustomerEntity.class, id) == null) throw new NotFoundException("Klant", id);
    }

    private Map<Long, Product> productsById(Set<Long> ids) {
        return products.findAllById(List.copyOf(ids)).stream()
                .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
    }

    /** A document from before the type existed is a quote. */
    private static String docType(DocumentType docType) {
        return (docType == null ? DocumentType.OFFERTE : docType).name();
    }

    private static String status(QuoteStatus status) {
        return status == null ? null : status.name();
    }

    /** "stolp", "display": what the line's price is per, as the product is sold now. */
    private static String unit(Product product) {
        if (product == null || product.packaging() == null) return UnitNames.DEFAULT;
        if (product.packaging().soldAsDisplay()) return "display";
        return UnitNames.one(product.packaging().unitKey(), Language.NL);
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    /** The running figures of one product; lines are handed in newest document first. */
    private static final class Builder {
        final Long productId;
        /** The line the row speaks for: the newest of the best rank seen, see {@link #rank}. */
        Line last;
        int lastRank = Integer.MAX_VALUE;
        final Set<Long> documents = new HashSet<>();
        long quantity;
        BigDecimal min;
        BigDecimal max;

        Builder(Long productId) {
            this.productId = productId;
        }

        void add(Line line) {
            documents.add(line.documentId());
            quantity += line.quantity();
            /* Lines come newest first, so the first line of a rank is the newest one of that rank. */
            int rank = rank(line);
            if (rank < lastRank) {
                last = line;
                lastRank = rank;
            }
            BigDecimal price = positive(line.unitPriceEur());
            if (price == null) return;
            if (min == null || price.compareTo(min) < 0) min = price;
            if (max == null || price.compareTo(max) > 0) max = price;
        }

        /**
         * A line that names a price on a document past concept says the most about what was
         * charged; a concept is still being typed, and a catalogue line names no price at all.
         */
        private static int rank(Line line) {
            return (line.followsCatalogue() ? 2 : 0) + (line.concept() ? 1 : 0);
        }

        AppliedPriceRow row(Product product, BigDecimal agreed) {
            return new AppliedPriceRow(productId, product == null ? null : product.sku(),
                    product == null ? null : product.name(), unit(product), last.unitPriceEur(),
                    last.priceSource(), last.orderDate(), last.documentId(), last.number(), docType(last.docType()),
                    status(last.status()), documents.size(), quantity, min, max, agreed,
                    product == null ? null : positive(product.computedSalesPriceEur()));
        }
    }
}
