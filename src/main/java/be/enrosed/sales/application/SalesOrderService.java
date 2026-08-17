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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verkooporders opstellen en doorrekenen.
 *
 * Het versturen, de klantweergave en de wijzigingsvoorstellen zitten in
 * {@link QuoteService}; hier blijft alleen het samenstellen van de order.
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
    private final CustomerService customers;
    private final VatCalculator vat;

    public SalesOrderService(SalesRepositories.Orders orders, ProductService products,
                             CountryService countries, DiscountTierService tiers,
                             SalesPricingCalculator pricing, SalesSettings settings,
                             CustomerService customers, VatCalculator vat,
                             SalesRepositories.Events events) {
        this.orders = orders;
        this.products = products;
        this.countries = countries;
        this.tiers = tiers;
        this.pricing = pricing;
        this.settings = settings;
        this.customers = customers;
        this.events = events;
        this.vat = vat;
    }

    public List<SalesOrder> list() {
        return orders.findAll();
    }

    public SalesOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Verkooporder", id));
    }

    /** Rekent een order door met de actuele prijzen, staffels en tarieven. */
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

    /** Prijs die deze order voor dit product hanteert; ook het portaal gebruikt die. */
    public BigDecimal unitPriceFor(be.enrosed.catalog.domain.Product product, SalesOrder order) {
        return pricing.unitPriceFor(product, order, null);
    }

    @Transactional
    public SalesOrder create(long customerId, String countryCode, String incoterm) {
        LocalDate today = LocalDate.now();
        SalesOrder created = orders.save(new SalesOrder(
                null, nextNumber(), customerId, countryCode, today, today.plusDays(30),
                QuoteStatus.CONCEPT, incoterm == null ? "DAP" : incoterm, "",
                MarkupMode.PRODUCT, settings.defaultMarkupPct(),
                null, null,
                null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                List.of()));

        events.add(new QuoteEvent(null, created.id(), QuoteEvent.Type.OPGEMAAKT,
                java.time.Instant.now(), null, false, "Offerte opgemaakt", null));
        return created;
    }

    @Transactional
    public SalesOrder update(long id, SalesOrder changes) {
        SalesOrder current = get(id);
        if (current.status().isFinal()) {
            throw new BusinessRuleException(
                    "Offerte " + current.number() + " is " + current.status().name().toLowerCase()
                            + " en kan niet meer gewijzigd worden");
        }
        return orders.save(new SalesOrder(
                current.id(), numberFor(current, changes),
                changes.customerId(), changes.countryCode(),
                changes.orderDate(), changes.validUntil(),
                /* De status wordt door de offerteworkflow gestuurd, niet door een gewone update. */
                current.status(),
                changes.incoterm(), changes.notes(),
                changes.markupMode() == null ? current.markupMode() : changes.markupMode(),
                changes.orderMarkupPct() == null ? current.orderMarkupPct() : changes.orderMarkupPct(),
                changes.extraDiscountPct(), changes.extraDiscountLabel(),
                current.portalToken(), current.sentAt(), current.viewedAt(), current.viewCount(),
                current.decidedAt(), current.signedByName(), current.customerMessage(),
                /* Interne notities komen wél van het formulier. */
                changes.internalNotes(),
                /* De stand van de levertermijnen stuurt de offerteworkflow: die
                   volgt uit wat er verstuurd is, niet uit wat er ingevuld staat. */
                current.deliveryTerms(),
                /* De vrachtstand zetten wij zelf op het scherm ("wordt later
                   bepaald"), dus die komt wél van het formulier. Wordt hij niet
                   meegestuurd, dan blijft staan wat er stond. */
                changes.freightOrNull() == null ? current.freight() : changes.freight(),
                changes.manualFreightEur(),
                changes.lines()));
    }

    /**
     * Het ordernummer zoals het na een update moet worden.
     *
     * Zelf een nummer kunnen zetten is nodig: bij een overstap uit een ander
     * systeem loopt de nummering door, en soms hoort een offerte bij een
     * bestaand dossier. Maar twee orders met hetzelfde nummer maakt elke
     * verwijzing dubbelzinnig - in een mail, op een factuur, in de boekhouding -
     * dus dat wordt geweigerd. Leeg laten betekent: laat staan wat er stond.
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
        get(id);
        orders.deleteById(id);
    }

    @Transactional
    public SalesOrder duplicate(long id) {
        SalesOrder source = get(id);
        LocalDate today = LocalDate.now();
        return orders.save(new SalesOrder(
                null, nextNumber(), source.customerId(), source.countryCode(),
                today, today.plusDays(30), QuoteStatus.CONCEPT, source.incoterm(), source.notes(),
                source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(),
                null, null, null, 0, null, null, null, source.internalNotes(),
                /* Een kopie begint schoon: die offerte is nog niet vertrokken. */
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, source.manualFreightEur(),
                source.lines().stream()
                        .map(line -> new SalesOrderLine(null, line.productId(), line.quantity(),
                                line.unitPriceEur(), line.manualDiscountPct(), line.deliveryWeek()))
                        .toList()));
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
