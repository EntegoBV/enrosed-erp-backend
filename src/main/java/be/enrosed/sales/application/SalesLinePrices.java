package be.enrosed.sales.application;

import be.enrosed.sales.domain.SalesOrderLine;
import be.enrosed.shared.BusinessRuleException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Where each line's price comes from after a staff save or preview (Emre, 2026-11-06: "Klantprijzen
 * moeten ook op offertes en facturen kunnen alsook DEFAULT kunnen nemen").
 *
 * A customer's agreed price is the default of a document that takes it: it is pinned on a line when
 * the line is added, as its unit price with source CUSTOMER, and never looked up live afterwards, so
 * a sent quote, the invoice made from it and every document past concept keep their prices whatever
 * the customer price does later. Only a concept that changes customer is pinned again. A line set to
 * the standard price (STANDARD, no unit price) follows the catalogue and is never pinned; a typed
 * price stays typed (MANUAL, or no source as before). Pure: the caller says whether the document pins
 * and hands over the customer's active prices, asked only when a line needs them.
 */
final class SalesLinePrices {

    private SalesLinePrices() {}

    /**
     * @param lines           the lines as they will be saved (or previewed)
     * @param stored          the lines as stored, by id; empty for a new document
     * @param pins            whether this document takes the customer's agreed prices
     * @param customerChanged whether this save gives the concept another customer
     * @param keepsNone       a document that never carries a source (a credit note)
     * @param active          the customer's active prices of today by product id
     */
    static List<SalesOrderLine> settle(List<SalesOrderLine> lines, List<SalesOrderLine> stored, boolean pins,
                                       boolean customerChanged, boolean keepsNone,
                                       Supplier<Map<Long, BigDecimal>> active) {
        if (lines == null || lines.isEmpty()) return lines;
        Map<Long, SalesOrderLine> storedById = stored == null ? Map.of() : stored.stream()
                .filter(line -> line != null && line.id() != null)
                .collect(Collectors.toMap(SalesOrderLine::id, Function.identity(), (left, right) -> left));
        Map<Long, BigDecimal> agreed = null;
        List<SalesOrderLine> settled = new ArrayList<>(lines.size());
        for (SalesOrderLine line : lines) {
            if (line == null) {
                settled.add(null);
                continue;
            }
            String source = sourceOf(line.priceSource());
            if (keepsNone) {
                settled.add(source == null ? line : line.withPrice(line.unitPriceEur(), null));
                continue;
            }
            SalesOrderLine known = line.id() == null ? null : storedById.get(line.id());
            /* A screen from before price sources sends none: the stored one stands while the price does. */
            if (source == null && known != null && known.priceSource() != null) {
                source = samePrice(line, known) ? known.priceSource() : line.hasUnitPrice() ? SalesOrderLine.PRICE_MANUAL : null;
            }
            BigDecimal price = line.unitPriceEur();
            if (SalesOrderLine.PRICE_STANDARD.equals(source) && line.hasUnitPrice()) {
                source = SalesOrderLine.PRICE_MANUAL;
            } else if (SalesOrderLine.PRICE_MANUAL.equals(source) && !line.hasUnitPrice()) {
                source = null;
            } else if (SalesOrderLine.PRICE_CUSTOMER.equals(source) && !(pins && customerChanged)) {
                /* Also where nothing is pinned automatically: a website line keeps the agreed price it was given. */
                if (agreed == null) agreed = active.get();
                BigDecimal today = agreed.get(line.productId());
                if (!line.hasUnitPrice()) {
                    /* "Klantprijs gebruiken" without an amount: today's agreed price, else the catalogue. */
                    price = today;
                    if (today == null) source = null;
                } else if (!sameAmount(price, today)
                        && !(known != null && SalesOrderLine.PRICE_CUSTOMER.equals(known.priceSource())
                        && samePrice(line, known))) {
                    /* Neither today's agreed price nor the one pinned on this line: typed over it. */
                    source = SalesOrderLine.PRICE_MANUAL;
                }
            }
            if (pins) {
                boolean byDefault = source == null && !line.hasUnitPrice();
                boolean added = known == null && byDefault;
                boolean repinned = customerChanged && (byDefault || SalesOrderLine.PRICE_CUSTOMER.equals(source));
                if (added || repinned) {
                    if (agreed == null) agreed = active.get();
                    BigDecimal today = agreed.get(line.productId());
                    if (today != null) {
                        price = today;
                        source = SalesOrderLine.PRICE_CUSTOMER;
                    } else if (SalesOrderLine.PRICE_CUSTOMER.equals(source)) {
                        /* The new customer has no agreed price: back to the catalogue. */
                        price = null;
                        source = null;
                    }
                }
            }
            settled.add(Objects.equals(source, line.priceSource()) && sameAmount(price, line.unitPriceEur())
                    ? line : line.withPrice(price, source));
        }
        return settled;
    }

    /** "klantprijs", "standaardprijs" or "eigen prijs", for the Logboek; null without a source. */
    static String dutch(String source) {
        if (source == null) return null;
        return switch (source) {
            case SalesOrderLine.PRICE_CUSTOMER -> "klantprijs";
            case SalesOrderLine.PRICE_STANDARD -> "standaardprijs";
            case SalesOrderLine.PRICE_MANUAL -> "eigen prijs";
            default -> source;
        };
    }

    /** One of the three sources, whatever its case; blank is none, anything else is refused. */
    static String sourceOf(String source) {
        if (source == null || source.isBlank()) return null;
        String value = source.strip().toUpperCase(Locale.ROOT);
        if (!SalesOrderLine.PRICE_CUSTOMER.equals(value) && !SalesOrderLine.PRICE_STANDARD.equals(value)
                && !SalesOrderLine.PRICE_MANUAL.equals(value)) {
            throw new BusinessRuleException("Onbekende prijsbron op een productregel: " + source.strip());
        }
        return value;
    }

    private static boolean samePrice(SalesOrderLine line, SalesOrderLine stored) {
        /* Zero and empty both follow the catalogue. */
        return line.hasUnitPrice() == stored.hasUnitPrice()
                && (!line.hasUnitPrice() || line.unitPriceEur().compareTo(stored.unitPriceEur()) == 0);
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
