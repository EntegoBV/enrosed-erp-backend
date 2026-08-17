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
                null, nextNumber(), supplierId, LocalDate.now(),
                PurchaseOrderStatus.CONCEPT, ContainerType.FORTY_HQ,
                cnyToUsd, usdToEur, usdToEur,
                BigDecimal.ZERO, BigDecimal.ZERO, be.enrosed.shared.Currency.USD, BigDecimal.ZERO,
                defaultDutyRatePct, BigDecimal.ZERO,
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                "", List.of()));
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
        return orders.save(new PurchaseOrder(
                null, nextNumber(), source.supplierId(), LocalDate.now(),
                /* Altijd concept: anders zou een kopie van een ontvangen order de
                   voorraad een tweede keer bijboeken. */
                PurchaseOrderStatus.CONCEPT, source.containerType(),
                source.cnyToUsd(), source.usdToEurGoods(), source.usdToEurTransport(),
                source.freightUsd(), source.originCosts(), source.originCurrency(),
                source.destinationCostsEur(), source.defaultDutyRatePct(), source.extraRevenueEur(),
                source.allocFreight(), source.allocOrigin(), source.allocDestination(),
                source.allocExtra(), source.notes(),
                source.lines().stream()
                        .map(line -> new PurchaseOrderLine(null, line.productId(), line.quantity(),
                                line.exwPrice(), line.exwCurrency(), line.extraUnitCost()))
                        .toList()));
    }

    /** Wat er aan een ingetikt aantal is bijgesteld om op volle dozen uit te komen. */
    public record CartonAdjustment(long productId, String productName, int requested,
                                   int adjusted, int piecesPerCarton) {}

    public record UpdateResult(PurchaseOrder order, List<CartonAdjustment> adjustments) {}

    /**
     * Werkt de inkooporder bij.
     *
     * Aantallen worden ingetikt in stuks, want zo praat je met een leverancier.
     * Ze worden hier naar boven afgerond op een volle doos: vraag je 5 stuks van
     * iets dat per 6 verpakt zit, dan komen er 6. Half gevulde dozen bestaan niet
     * en een order die dat wel veronderstelt klopt verderop nergens meer - niet in
     * het volume, niet in de vracht, niet in de kostprijs.
     *
     * Wat er bijgesteld is komt terug in het antwoord, zodat de gebruiker het ziet
     * in plaats van dat het stilletjes gebeurt.
     */
    @Transactional
    public UpdateResult update(long id, PurchaseOrder changes) {
        PurchaseOrder current = get(id);
        Map<Long, Product> byId = products.list().stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<CartonAdjustment> adjustments = new ArrayList<>();
        List<PurchaseOrderLine> snapped = new ArrayList<>();

        for (PurchaseOrderLine line : changes.lines()) {
            Product product = byId.get(line.productId());
            if (product == null) { snapped.add(line); continue; }

            Carton carton = product.carton() == null ? Carton.empty() : product.carton();
            int perCarton = Math.max(1, carton.piecesPerCarton());
            int requested = Math.max(0, line.quantity());
            int rounded = carton.cartonsFor(requested) * perCarton;

            if (rounded != requested) {
                adjustments.add(new CartonAdjustment(
                        product.id(), product.describe(), requested, rounded, perCarton));
            }
            snapped.add(new PurchaseOrderLine(line.id(), line.productId(), rounded,
                    line.exwPrice(), line.exwCurrency(), line.extraUnitCost()));
        }

        PurchaseOrder saved = orders.save(new PurchaseOrder(
                current.id(), numberFor(current, changes), changes.supplierId(), changes.orderDate(),
                changes.status(), changes.containerType(),
                changes.cnyToUsd(), changes.usdToEurGoods(), changes.usdToEurTransport(),
                changes.freightUsd(), changes.originCosts(), changes.originCurrency(),
                changes.destinationCostsEur(), changes.defaultDutyRatePct(), changes.extraRevenueEur(),
                changes.allocFreight(), changes.allocOrigin(), changes.allocDestination(),
                changes.allocExtra(), changes.notes(), snapped));

        bookStockOnReceipt(current, saved);
        return new UpdateResult(saved, adjustments);
    }

    /**
     * Het ordernummer zoals het na een update moet worden.
     *
     * Zelf een nummer kunnen zetten is nodig bij een overstap uit een ander
     * systeem of om aan te sluiten bij de nummering van de leverancier. Twee
     * orders met hetzelfde nummer wordt geweigerd: elke verwijzing ernaar zou
     * dan dubbelzinnig zijn. Leeg laten betekent: laat staan wat er stond.
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
