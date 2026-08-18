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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inkooporders beheren en doorrekenen.
 *
 * De uitkomst wordt niet automatisch op de producten gezet: dat gebeurt pas
 * wanneer iemand de calculatie bewust toepast. Zo verandert de verkoopprijs
 * niet zomaar omdat er ergens een concept staat te rekenen.
 */
@ApplicationScoped
public class PurchaseOrderService {

    private static final Logger LOG = Logger.getLogger(PurchaseOrderService.class);

    private final SourcingRepositories.PurchaseOrders orders;
    private final ProductService products;
    private final LandedCostCalculator calculator;

    public PurchaseOrderService(SourcingRepositories.PurchaseOrders orders,
                                ProductService products,
                                LandedCostCalculator calculator) {
        this.orders = orders;
        this.products = products;
        this.calculator = calculator;
    }

    public List<PurchaseOrder> list() {
        return orders.findAll();
    }

    public PurchaseOrder get(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Inkooporder", id));
    }

    public LandedCost calculate(PurchaseOrder order) {
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        return calculator.calculate(order, byId);
    }

    @Transactional
    public PurchaseOrder create(long supplierId, BigDecimal cnyToUsd, BigDecimal usdToEur,
                                BigDecimal defaultDutyRatePct) {
        return orders.save(new PurchaseOrder(
                null, nextNumber(), null, supplierId, LocalDate.now(),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                cnyToUsd, usdToEur, usdToEur,
                BigDecimal.ZERO, BigDecimal.ZERO, be.enrosed.shared.Currency.USD, BigDecimal.ZERO,
                defaultDutyRatePct, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "Rotterdam", "", List.of()));
    }

    /**
     * Maakt een kopie van een inkoopcalculatie.
     *
     * Bedoeld om snel een variant door te rekenen: een andere containermaat, een
     * andere koers, een leverancier die zijn prijs aanpast. Alles komt mee
     * behalve de status en het nummer - een kopie is een nieuw concept, geen
     * tweede exemplaar van een order die al besteld is.
     */
    @Transactional
    public PurchaseOrder duplicate(long id) {
        PurchaseOrder source = get(id);
        String alias = source.alias() == null || source.alias().isBlank()
                ? null : source.alias() + " (kopie)";
        return orders.save(new PurchaseOrder(
                null, nextNumber(), alias, source.supplierId(), LocalDate.now(),
                /* Altijd concept: anders zou een kopie van een ontvangen order de
                   voorraad een tweede keer bijboeken. */
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
        PurchaseOrder current = get(id);
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<CartonAdjustment> warnings = new ArrayList<>();
        List<PurchaseOrderLine> lines = new ArrayList<>();

        for (PurchaseOrderLine line : changes.lines()) {
            Product product = byId.get(line.productId());
            if (product == null) { lines.add(line); continue; }

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            int perCarton = Math.max(1, carton.piecesPerCarton());
            int requested = Math.max(0, line.quantity());
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
        /* Past ordering: the snapshot is history and never changes. Lines
           added after ordering stay without one - nothing was agreed for
           them. */
        return line.orderedQuantity();
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
     * Boekt de voorraad bij zodra een container binnen is.
     *
     * Alleen bij de overgang naar ONTVANGEN, en maar een keer: bij elke volgende
     * bewaring van een reeds ontvangen order gebeurt er niets meer. Anders telt de
     * voorraad op bij elke keer dat iemand het scherm opslaat.
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
        get(id);
        orders.deleteById(id);
    }

    /**
     * Legt de berekende kostprijzen vast op de producten. Vanaf dat moment
     * rekent de verkoopkant ermee.
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
