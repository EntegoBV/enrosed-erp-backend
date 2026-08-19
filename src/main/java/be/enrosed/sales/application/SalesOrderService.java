package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drafting and pricing sales orders.
 *
 * Sending, the customer view and the change proposals live in
 * {@link QuoteService}; only composing the order stays here.
 */
@ApplicationScoped
public class SalesOrderService {

    private final SalesRepositories.Orders orders;
    private final ProductService products;
    private final CountryService countries;
    private final DiscountTierService tiers;
    private final SalesPricingCalculator pricing;
    private final SalesSettings settings;
    private final SalesRepositories.Events events;
    private final SalesRepositories.Revisions revisions;
    private final CustomerService customers;
    private final VatCalculator vat;

    public SalesOrderService(SalesRepositories.Orders orders, ProductService products,
                             CountryService countries, DiscountTierService tiers,
                             SalesPricingCalculator pricing, SalesSettings settings,
                             CustomerService customers, VatCalculator vat,
                             SalesRepositories.Events events,
                             SalesRepositories.Revisions revisions) {
        this.orders = orders;
        this.products = products;
        this.countries = countries;
        this.tiers = tiers;
        this.pricing = pricing;
        this.settings = settings;
        this.customers = customers;
        this.events = events;
        this.revisions = revisions;
        this.vat = vat;
    }

    public List<SalesOrder> list() {
        return orders.findAll();
    }

    public SalesOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Verkooporder", id));
    }

    /** Prices an order with the current prices, tiers and rates. */
    public PricedOrder price(SalesOrder order) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Country country = countries.find(order.countryCode());
        Customer customer = order.customerId() == null ? null : customers.get(order.customerId());

        return pricing.price(order, byId, new SalesPricingCalculator.Context(
                country,
                customer,
                settings.pallet(),
                tiers.list(TierScope.LINE),
                tiers.list(TierScope.ORDER),
                vat.determine(country, customer)));
    }

    /** Price this order uses for this product; the portal uses it too. */
    public BigDecimal unitPriceFor(be.enrosed.catalog.domain.Product product, SalesOrder order) {
        return pricing.unitPriceFor(product, order, null);
    }

    @Transactional
    public SalesOrder create(long customerId, String countryCode, String incoterm) {
        LocalDate today = LocalDate.now();
        SalesOrder draft = new SalesOrder(
                null, nextNumber(), customerId, countryCode, today, today.plusDays(30),
                QuoteStatus.CONCEPT, incoterm == null ? "DAP" : incoterm, null, "",
                MarkupMode.PRODUCT, settings.defaultMarkupPct(),
                null, null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                List.of(), List.of());
        validateForSave(draft);
        SalesOrder created = orders.save(draft);

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), null, false, "Offerte opgemaakt", null));
        return created;
    }

    @Transactional
    public SalesOrder update(long id, SalesOrder changes) {
        SalesOrder current = get(id);
        SalesLifecycle.requireEditable(current);
        if (changes == null) {
            throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        }

        SalesOrder updated = new SalesOrder(
                current.id(), numberFor(current, changes),
                changes.customerId(), changes.countryCode(),
                changes.orderDate(), changes.validUntil(),
                /* The status is driven by the quote workflow, not by a plain update. */
                current.status(),
                changes.incoterm(), changes.paymentTerms(), changes.notes(),
                changes.markupMode() == null ? current.markupMode() : changes.markupMode(),
                changes.orderMarkupPct() == null ? current.orderMarkupPct() : changes.orderMarkupPct(),
                changes.extraDiscountPct(), changes.extraDiscountLabel(),
                current.portalToken(), current.sentAt(), current.viewedAt(), current.viewCount(),
                current.decidedAt(), current.signedByName(), current.customerMessage(),
                /* Internal notes DO come from the form. */
                changes.internalNotes(),
                /* The delivery-terms state is driven by the quote workflow: it
                   follows from what was sent, not from what is filled in. */
                current.deliveryTerms(),
                /* The freight state is ours to set on screen ("to be
                   determined later"), so it DOES come from the form. When not
                   sent along, what was there stays. */
                changes.freightOrNull() == null ? current.freight() : changes.freight(),
                changes.manualFreightEur(),
                changes.lines(), changes.pallets());
        validateForSave(updated);
        return orders.save(updated);
    }

    /** One line whose promised delivery week may be filled in separately. */
    public record DeliveryWeekChange(Long productId, String deliveryWeek) {}

    /**
     * Narrow update for a promise that was left open on a sent quotation.
     * Prices, quantities, customer data and every other field stay untouched.
     */
    @Transactional
    public SalesOrder updateDeliveryWeeks(long id, List<DeliveryWeekChange> requested) {
        SalesOrder current = get(id);
        SalesLifecycle.requireTermsEditable(current);
        if (requested == null || requested.isEmpty()) {
            throw new BusinessRuleException("Geef minstens een levertermijn mee");
        }

        Map<Long, String> weeks = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        Set<Long> productsOnOrder = current.lines().stream()
                .map(SalesOrderLine::productId)
                .collect(Collectors.toSet());
        for (DeliveryWeekChange change : requested) {
            if (change == null || change.productId() == null) {
                throw new BusinessRuleException("Elke levertermijn moet bij een product horen");
            }
            if (!productsOnOrder.contains(change.productId())) {
                throw new BusinessRuleException(
                        "Product " + change.productId() + " staat niet op deze offerte");
            }
            if (!seen.add(change.productId())) {
                throw new BusinessRuleException(
                        "Product " + change.productId() + " staat dubbel in de levertermijnen");
            }
            weeks.put(change.productId(), clean(change.deliveryWeek()));
        }

        List<SalesOrderLine> lines = current.lines().stream()
                .map(line -> weeks.containsKey(line.productId())
                        ? new SalesOrderLine(line.id(), line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), weeks.get(line.productId()))
                        : line)
                .toList();
        return orders.save(copyWithTerms(current, current.freight(), current.manualFreightEur(), lines));
    }

    /**
     * Narrow freight update for a sent quotation. Moving away from an open
     * freight item records AANGEVULD so the following mail can announce it.
     */
    @Transactional
    public SalesOrder updateFreight(long id, FreightState requestedState, BigDecimal manualFreightEur) {
        SalesOrder current = get(id);
        SalesLifecycle.requireTermsEditable(current);
        if (requestedState == null) {
            throw new BusinessRuleException("Kies of de vracht berekend of nog te bepalen is");
        }
        requireNonNegative(manualFreightEur, "Handmatige vracht");

        FreightState state = current.freight() == FreightState.TE_BEPALEN
                && requestedState != FreightState.TE_BEPALEN
                ? FreightState.AANGEVULD
                : requestedState;
        return orders.save(copyWithTerms(current, state, manualFreightEur, current.lines()));
    }

    /**
     * The order number as it should be after an update.
     *
     * Being able to set a number by hand matters: numbering carries on after
     * migrating from another system, and sometimes a quote belongs to an
     * existing file. But two orders with the same number make every reference
     * ambiguous - in a mail, on an invoice, in the books - so that is
     * refused. Leaving it empty means: keep what was there.
     */
    private String numberFor(SalesOrder current, SalesOrder changes) {
        String wanted = changes.number() == null ? null : changes.number().trim();
        if (wanted == null || wanted.isBlank() || wanted.equals(current.number())) {
            return current.number();
        }
        boolean taken = orders.findAll().stream()
                .anyMatch(other -> !other.id().equals(current.id())
                        && wanted.equalsIgnoreCase(other.number()));
        if (taken) {
            throw new BusinessRuleException("Er bestaat al een verkooporder met nummer " + wanted);
        }
        return wanted;
    }

    @Transactional
    public void delete(long id) {
        SalesOrder order = get(id);
        boolean hasRevisions = !revisions.findByOrder(id).isEmpty();
        SalesLifecycle.requireDeletable(order, hasRevisions);
        events.deleteByOrder(id);
        orders.deleteById(id);
    }

    @Transactional
    public SalesOrder duplicate(long id) {
        SalesOrder source = get(id);
        LocalDate today = LocalDate.now();
        SalesOrder duplicate = new SalesOrder(
                null, nextNumber(), source.customerId(), source.countryCode(),
                today, today.plusDays(30), QuoteStatus.CONCEPT, source.incoterm(),
                source.paymentTerms(), source.notes(),
                source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(),
                null, null, null, 0, null, null, null, source.internalNotes(),
                /* A copy starts clean: that quote has not left yet. */
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, source.manualFreightEur(),
                source.lines().stream()
                        .map(line -> new SalesOrderLine(null, line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()))
                        .toList(),
                source.pallets().stream()
                        .map(pallet -> new OrderPallet(null, pallet.label(), pallet.type(),
                                pallet.heightCm(), pallet.items()))
                        .toList());
        validateForSave(duplicate);
        return orders.save(duplicate);
    }

    /** Rechecks an existing draft/open quotation before a document leaves. */
    public void validateForSend(SalesOrder order) {
        validateForSave(order);
    }

    private void validateForSave(SalesOrder order) {
        if (order == null) {
            throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        }
        if (order.customerId() == null || order.customerId() <= 0) {
            throw new BusinessRuleException("Koppel een geldige klant aan de offerte");
        }
        try {
            customers.get(order.customerId());
        } catch (NotFoundException exception) {
            throw new BusinessRuleException("De gekozen klant bestaat niet meer");
        }
        if (order.countryCode() == null || order.countryCode().isBlank()
                || countries.find(order.countryCode()) == null) {
            throw new BusinessRuleException("Kies een geldig bestemmingsland");
        }
        if (order.orderDate() == null || order.validUntil() == null) {
            throw new BusinessRuleException("Orderdatum en geldigheidsdatum zijn verplicht");
        }
        if (order.validUntil().isBefore(order.orderDate())) {
            throw new BusinessRuleException("De geldigheidsdatum kan niet vóór de orderdatum liggen");
        }
        if (order.incoterm() == null || order.incoterm().isBlank()) {
            throw new BusinessRuleException("Incoterm is verplicht");
        }
        if (order.markupMode() == null) {
            throw new BusinessRuleException("Kies hoe de opslag wordt berekend");
        }
        requireNonNegative(order.orderMarkupPct(), "Opslagpercentage");
        requirePercentage(order.extraDiscountPct(), "Extra korting");
        requireNonNegative(order.manualFreightEur(), "Handmatige vracht");

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (SalesOrderLine line : order.lines()) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke offerteregel moet bij een product horen");
            }
            if (!seen.add(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId() + " staat dubbel op de offerte");
            }
            if (!byId.containsKey(line.productId())) {
                throw new BusinessRuleException("Product " + line.productId() + " bestaat niet meer");
            }
            if (line.quantity() < 0) {
                throw new BusinessRuleException("Een productaantal kan niet negatief zijn");
            }
            requireNonNegative(line.unitPriceEur(), "Handmatige stukprijs");
            requirePercentage(line.manualDiscountPct(), "Regelkorting");
        }
        validatePallets(order, byId);
    }

    private void validatePallets(SalesOrder order, Map<Long, Product> byId) {
        Set<Long> productsOnOrder = order.lines().stream()
                .map(SalesOrderLine::productId)
                .collect(Collectors.toSet());
        Map<Long, Integer> assigned = new HashMap<>();

        for (OrderPallet pallet : order.pallets()) {
            if (pallet == null) {
                throw new BusinessRuleException("Een pallet mag niet leeg zijn");
            }
            if (pallet.heightCm() != null && pallet.heightCm() < 0) {
                throw new BusinessRuleException("Pallethoogte kan niet negatief zijn");
            }
            for (OrderPallet.Item item : pallet.items()) {
                if (item == null || item.productId() <= 0 || item.cartons() <= 0) {
                    throw new BusinessRuleException("Elke palletregel moet een product en positief aantal dozen hebben");
                }
                if (!productsOnOrder.contains(item.productId()) || !byId.containsKey(item.productId())) {
                    throw new BusinessRuleException(
                            "Product " + item.productId() + " staat niet op deze offerte");
                }
                assigned.merge(item.productId(), item.cartons(), Integer::sum);
            }
        }

        for (SalesOrderLine line : order.lines()) {
            Product product = byId.get(line.productId());
            int orderedCartons = product.carton() == null
                    ? Math.max(0, line.quantity())
                    : product.carton().cartonsFor(line.quantity());
            if (assigned.getOrDefault(line.productId(), 0) > orderedCartons) {
                throw new BusinessRuleException(
                        "Er staan meer dozen van " + product.describe() + " op pallets dan op de offerte");
            }
        }
    }

    private static SalesOrder copyWithTerms(SalesOrder order, FreightState freight,
                                            BigDecimal manualFreightEur,
                                            List<SalesOrderLine> lines) {
        return new SalesOrder(order.id(), order.number(), order.customerId(), order.countryCode(),
                order.orderDate(), order.validUntil(), order.status(), order.incoterm(),
                order.paymentTerms(), order.notes(), order.markupMode(), order.orderMarkupPct(),
                order.extraDiscountPct(), order.extraDiscountLabel(), order.portalToken(),
                order.sentAt(), order.viewedAt(), order.viewCount(), order.decidedAt(),
                order.signedByName(), order.customerMessage(), order.internalNotes(),
                order.deliveryTerms(), freight, manualFreightEur, lines, order.pallets());
    }

    private static void requirePercentage(BigDecimal value, String label) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessRuleException(label + " moet tussen 0 en 100% liggen");
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nextNumber() {
        int year = LocalDate.now().getYear();
        String prefix = "ENR-" + year + "-";
        int highest = orders.findAll().stream()
                .map(SalesOrder::number)
                .filter(number -> number != null && number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(suffix -> suffix.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%04d", highest + 1);
    }
}
