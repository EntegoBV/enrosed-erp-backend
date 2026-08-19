package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import be.enrosed.catalog.domain.Carton;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Managing and pricing purchase orders.
 *
 * The outcome is not written onto the products automatically: that only
 * happens when someone applies the calculation deliberately. That way the
 * sales price never shifts just because a draft is computing somewhere.
 */
@ApplicationScoped
public class PurchaseOrderService {

    private static final Logger LOG = Logger.getLogger(PurchaseOrderService.class);

    private final SourcingRepositories.PurchaseOrders orders;
    private final SourcingRepositories.Suppliers suppliers;
    private final ProductService products;
    private final LandedCostCalculator calculator;

    public PurchaseOrderService(SourcingRepositories.PurchaseOrders orders,
                                SourcingRepositories.Suppliers suppliers,
                                ProductService products,
                                LandedCostCalculator calculator) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.products = products;
        this.calculator = calculator;
    }

    public List<PurchaseOrder> list() {
        return orders.findAll();
    }

    public PurchaseOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Inkooporder", id));
    }

    /** Serialises lifecycle changes so receipt can book stock only once. */
    private PurchaseOrder getForUpdate(long id) {
        return orders.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Inkooporder", id));
    }

    public LandedCost calculate(PurchaseOrder order) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        return calculator.calculate(order, byId);
    }

    @Transactional
    public PurchaseOrder create(long supplierId, BigDecimal cnyToUsd, BigDecimal usdToEur,
                                BigDecimal defaultDutyRatePct) {
        requireSupplier(supplierId);
        requirePositive(cnyToUsd, "CNY/USD-koers");
        requirePositive(usdToEur, "USD/EUR-koers");
        requirePercentage(defaultDutyRatePct, "Standaard invoerrecht");

        PurchaseOrder draft = new PurchaseOrder(
                null, nextNumber(), null, supplierId, LocalDate.now(),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                cnyToUsd, usdToEur, usdToEur,
                BigDecimal.ZERO, BigDecimal.ZERO, be.enrosed.shared.Currency.USD, BigDecimal.ZERO,
                defaultDutyRatePct, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Rotterdam", "", List.of());
        return orders.save(draft);
    }

    /**
     * Makes a copy of a purchase calculation.
     *
     * Meant to price a variant quickly: another container size, another
     * exchange rate, a supplier adjusting their price. Everything comes along
     * except the status and the number - a copy is a new draft, not a second
     * instance of an order already placed.
     */
    @Transactional
    public PurchaseOrder duplicate(long id) {
        PurchaseOrder source = get(id);
        String alias = source.alias() == null || source.alias().isBlank()
                ? null : source.alias() + " (kopie)";
        return orders.save(new PurchaseOrder(
                null, nextNumber(), alias, source.supplierId(), LocalDate.now(),
                /* Always a draft: otherwise a copy of a received order would
                   book the stock a second time. */
                PurchaseOrderStatus.CONCEPT, source.containerType(),
                source.cnyToUsd(), source.usdToEurGoods(), source.usdToEurTransport(),
                source.freightUsd(), source.originCosts(), source.originCurrency(),
                source.destinationCostsEur(), source.defaultDutyRatePct(), source.extraRevenueEur(),
                source.allocFreight(), source.allocOrigin(), source.allocDestination(),
                source.allocExtra(), source.destinationPort(), source.notes(),
                source.lines().stream()
                        .map(line -> new PurchaseOrderLine(null, line.productId(), line.quantity(),
                                line.exwPrice(), line.exwCurrency(), line.extraUnitCost(), null))
                        .toList()));
    }

    /**
     * A quantity that is not a whole number of cartons.
     *
     * Reported, never corrected: a supplier can perfectly well ship a
     * three-piece sample, and silently inflating an order to a supplier costs
     * real money. {@code adjusted} carries the nearest full carton as a
     * suggestion for the screen.
     */
    public record CartonAdjustment(long productId, String productName, int requested,
                                   int adjusted, int piecesPerCarton) {}

    public record UpdateResult(PurchaseOrder order, List<CartonAdjustment> adjustments) {}

    /**
     * Updates the purchase order.
     *
     * Quantities are entered in pieces, because that is how you talk to a
     * supplier. Quantities that do not fill whole cartons are flagged in the
     * response but saved exactly as entered — unlike sales, purchasing never
     * rounds. Only the user knows whether "3 pieces" is a typo or a sample.
     */
    @Transactional
    public UpdateResult update(long id, PurchaseOrder changes) {
        PurchaseOrder current = getForUpdate(id);
        if (changes == null) {
            throw new BusinessRuleException("Geen inkoopordergegevens meegestuurd");
        }
        validateHeader(changes);
        requireForwardTransition(current.status(), changes.status());
        if (current.status() == PurchaseOrderStatus.ONTVANGEN) {
            requireReceivedLinesUnchanged(current, changes);
        }

        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<CartonAdjustment> warnings = new ArrayList<>();
        List<PurchaseOrderLine> lines = new ArrayList<>();
        Set<Long> seenProducts = new HashSet<>();

        for (PurchaseOrderLine line : changes.lines()) {
            if (line == null || line.productId() == null) {
                throw new BusinessRuleException("Elke inkoopregel moet bij een product horen");
            }
            if (!seenProducts.add(line.productId())) {
                throw new BusinessRuleException(
                        "Product " + line.productId() + " staat dubbel op de inkooporder");
            }
            Product product = byId.get(line.productId());
            if (product == null) {
                throw new BusinessRuleException("Product " + line.productId() + " bestaat niet meer");
            }
            if (line.quantity() < 0) {
                throw new BusinessRuleException("Een besteld of ontvangen aantal kan niet negatief zijn");
            }
            requireNonNegative(line.exwPrice(), "EXW-prijs");
            requireNonNegative(line.extraUnitCost(), "Extra kost per stuk");

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            int perCarton = Math.max(1, carton.piecesPerCarton());
            int requested = line.quantity();
            int fullCartons = carton.cartonsFor(requested) * perCarton;

            if (fullCartons != requested) {
                warnings.add(new CartonAdjustment(
                        product.id(), product.describe(), requested, fullCartons, perCarton));
            }
            /* Saved as entered; the warning is the whole intervention. */
            lines.add(new PurchaseOrderLine(line.id(), line.productId(), requested,
                    line.exwPrice(), line.exwCurrency(), line.extraUnitCost(),
                    orderedQuantityFor(current, changes, line, requested)));
        }

        if (changes.status() != PurchaseOrderStatus.CONCEPT
                && lines.stream().noneMatch(line -> line.quantity() > 0)) {
            throw new BusinessRuleException("Een geplaatste inkooporder moet minstens één product bevatten");
        }

        PurchaseOrder saved = orders.save(new PurchaseOrder(
                current.id(), numberFor(current, changes), changes.alias(),
                changes.supplierId(), changes.orderDate(),
                changes.status(), changes.containerType(),
                changes.cnyToUsd(), changes.usdToEurGoods(), changes.usdToEurTransport(),
                changes.freightUsd(), changes.originCosts(), changes.originCurrency(),
                changes.destinationCostsEur(), changes.defaultDutyRatePct(), changes.extraRevenueEur(),
                changes.allocFreight(), changes.allocOrigin(), changes.allocDestination(),
                changes.allocExtra(), changes.destinationPort(), changes.notes(), lines));

        bookStockOnReceipt(current, saved);
        return new UpdateResult(saved, warnings);
    }

    /**
     * What the line's ordered-quantity snapshot should be after this update.
     *
     * The moment the order leaves concept it has been placed with the
     * supplier; from then on the quantity as ordered is a fact worth keeping.
     * Containers regularly arrive short, and "ordered 96, received 90" is the
     * difference between an explainable order and a mystery. Lines added
     * after ordering never get a snapshot: nothing was agreed for them.
     */
    private Integer orderedQuantityFor(PurchaseOrder current, PurchaseOrder changes,
                                       PurchaseOrderLine line, int requested) {
        boolean placingNow = current.status() == PurchaseOrderStatus.CONCEPT
                && changes.status() != PurchaseOrderStatus.CONCEPT;
        if (placingNow) {
            /* This save confirms the order: these are the agreed quantities. */
            return requested;
        }
        if (current.status() == PurchaseOrderStatus.CONCEPT) {
            /* Not ordered yet: nothing has been agreed, so nothing to keep. */
            return null;
        }
        /* Past ordering: preserve the value from storage, never the value the
           client echoed back. Lines added after ordering stay without one -
           nothing was agreed for them. */
        if (line.id() == null) return null;
        return current.lines().stream()
                .filter(stored -> line.id().equals(stored.id()))
                .map(PurchaseOrderLine::orderedQuantity)
                .findFirst()
                .orElse(null);
    }

    /**
     * The order number as it should be after an update.
     *
     * Setting a number by hand matters when migrating from another system or
     * matching the supplier's numbering. Two orders with the same number are
     * refused: every reference to it would be ambiguous. Empty means: keep
     * what was there.
     */
    private String numberFor(PurchaseOrder current, PurchaseOrder changes) {
        String wanted = changes.number() == null ? null : changes.number().trim();
        if (wanted == null || wanted.isBlank() || wanted.equals(current.number())) {
            return current.number();
        }
        boolean taken = orders.findAll().stream()
                .anyMatch(other -> !other.id().equals(current.id())
                        && wanted.equalsIgnoreCase(other.number()));
        if (taken) {
            throw new BusinessRuleException("Er bestaat al een inkooporder met nummer " + wanted);
        }
        return wanted;
    }

    /**
     * Books the stock the moment a container is in.
     *
     * Only on the transition to ONTVANGEN, and only once: every later save of
     * an already received order does nothing more. Otherwise the stock grows
     * every time someone saves the screen.
     */
    private void bookStockOnReceipt(PurchaseOrder before, PurchaseOrder after) {
        boolean justReceived = before.status() != PurchaseOrderStatus.ONTVANGEN
                && after.status() == PurchaseOrderStatus.ONTVANGEN;
        if (!justReceived) return;

        for (PurchaseOrderLine line : after.lines()) {
            products.adjustStock(line.productId(), line.quantity());
        }
        LOG.infof("Voorraad bijgeboekt uit %s: %d regel(s)", after.number(), after.lines().size());
    }

    @Transactional
    public void delete(long id) {
        PurchaseOrder order = getForUpdate(id);
        if (order.status() == PurchaseOrderStatus.ONTVANGEN) {
            throw new BusinessRuleException(
                    "Een ontvangen inkooporder kan niet verwijderd worden omdat de voorraad al geboekt is");
        }
        orders.deleteById(id);
    }

    /** Forward-only lifecycle; same-state saves remain possible for details. */
    static void requireForwardTransition(PurchaseOrderStatus current,
                                         PurchaseOrderStatus requested) {
        if (current == null || requested == null) {
            throw new BusinessRuleException("Kies een geldige status voor de inkooporder");
        }
        boolean allowed = current == requested
                || current == PurchaseOrderStatus.CONCEPT
                    && (requested == PurchaseOrderStatus.BESTELD
                        || requested == PurchaseOrderStatus.ONDERWEG)
                || current == PurchaseOrderStatus.BESTELD
                    && (requested == PurchaseOrderStatus.ONDERWEG
                        || requested == PurchaseOrderStatus.ONTVANGEN)
                || current == PurchaseOrderStatus.ONDERWEG
                    && requested == PurchaseOrderStatus.ONTVANGEN;
        if (!allowed) {
            throw new BusinessRuleException(
                    "Inkooporder kan niet van " + current.name().toLowerCase()
                            + " naar " + requested.name().toLowerCase() + " gaan");
        }
    }

    private void validateHeader(PurchaseOrder order) {
        if (order.supplierId() == null || order.supplierId() <= 0) {
            throw new BusinessRuleException("Koppel een geldige leverancier aan de inkooporder");
        }
        requireSupplier(order.supplierId());
        if (order.orderDate() == null) {
            throw new BusinessRuleException("Orderdatum is verplicht");
        }
        if (order.containerType() == null) {
            throw new BusinessRuleException("Kies een containertype");
        }
        requirePositive(order.cnyToUsd(), "CNY/USD-koers");
        requirePositive(order.usdToEurGoods(), "USD/EUR-goederenkoers");
        requirePositive(order.usdToEurTransport(), "USD/EUR-transportkoers");
        requireNonNegative(order.freightUsd(), "Zeevracht");
        requireNonNegative(order.originCosts(), "Kosten aan de vertrekzijde");
        requireNonNegative(order.destinationCostsEur(), "Kosten aan de aankomstzijde");
        requireNonNegative(order.extraRevenueEur(), "Extra opbrengst");
        requirePercentage(order.defaultDutyRatePct(), "Standaard invoerrecht");
        if (order.originCurrency() == null) {
            throw new BusinessRuleException("Kies de munt van de kosten aan de vertrekzijde");
        }
        if (order.allocFreight() == null || order.allocOrigin() == null
                || order.allocDestination() == null || order.allocExtra() == null) {
            throw new BusinessRuleException("Kies voor elke gedeelde kost een verdeelsleutel");
        }
    }

    private void requireSupplier(long supplierId) {
        if (supplierId <= 0 || suppliers.findById(supplierId).isEmpty()) {
            throw new BusinessRuleException("De gekozen leverancier bestaat niet meer");
        }
    }

    private static void requireReceivedLinesUnchanged(PurchaseOrder current,
                                                      PurchaseOrder changes) {
        if (current.lines().size() != changes.lines().size()) {
            throw new BusinessRuleException(
                    "Producten en aantallen van een ontvangen inkooporder kunnen niet meer wijzigen");
        }
        Set<Long> seen = new HashSet<>();
        for (PurchaseOrderLine incoming : changes.lines()) {
            if (incoming == null || incoming.id() == null || !seen.add(incoming.id())) {
                throw new BusinessRuleException(
                        "Producten en aantallen van een ontvangen inkooporder kunnen niet meer wijzigen");
            }
            PurchaseOrderLine stored = current.lines().stream()
                    .filter(line -> incoming.id().equals(line.id()))
                    .findFirst()
                    .orElse(null);
            if (stored == null
                    || !Objects.equals(stored.productId(), incoming.productId())
                    || stored.quantity() != incoming.quantity()) {
                throw new BusinessRuleException(
                        "Producten en aantallen van een ontvangen inkooporder kunnen niet meer wijzigen");
            }
        }
    }

    private static void requirePositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessRuleException(label + " moet groter zijn dan nul");
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    private static void requirePercentage(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException(label + " moet tussen 0 en 100% liggen");
        }
    }

    /**
     * Writes the calculated cost prices onto the products. From that moment
     * the sales side computes with them.
     */
    @Transactional
    public LandedCost applyToProducts(long id) {
        PurchaseOrder order = get(id);
        LandedCost result = calculate(order);
        for (LandedCost.Line line : result.lines()) {
            products.applyLandedCost(line.productId(), line.landedUnitEur(), order.number());
        }
        LOG.infof("Kostprijzen uit %s toegepast op %d product(en)", order.number(), result.lines().size());
        return result;
    }

    private String nextNumber() {
        int year = LocalDate.now().getYear();
        String prefix = "PO-" + year + "-";
        int highest = orders.findAll().stream()
                .map(PurchaseOrder::number)
                .filter(number -> number != null && number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(suffix -> suffix.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%03d", highest + 1);
    }
}
