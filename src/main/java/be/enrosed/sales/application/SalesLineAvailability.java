package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Exclusion belongs to this document, never to the catalogue or the stock ledger. */
@ApplicationScoped
public class SalesLineAvailability {
    @Inject SalesSplits splits;
    @Inject IncomingPaymentService incoming;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject SalesRepositories.Revisions revisions;

    public SalesOrder normalize(SalesOrder stored, SalesOrder proposed) {
        if (proposed == null) throw new BusinessRuleException("Geen offertegegevens meegestuurd");
        List<SalesOrderLine> result = new ArrayList<>();
        boolean changed = false;
        for (var line : proposed.lines()) {
            if (line == null) { result.add(null); continue; }
            var before = stored.lines().stream().filter(old -> line.id() != null
                    ? line.id().equals(old.id()) && Objects.equals(line.productId(), old.productId())
                    : Objects.equals(line.productId(), old.productId())).findFirst().orElse(null);
            boolean unavailable = line.unavailable() == null ? before != null && before.isUnavailable() : line.isUnavailable();
            if (unavailable && (stored.purpose() != SalesPurpose.STANDARD || stored.isPartnerDeal()))
                throw new BusinessRuleException("Producten van partnerdocumenten kunnen niet tijdelijk uitgesloten worden");
            Integer remembered = before == null ? null : before.requestedQuantity();
            if (unavailable && (before == null || !before.isUnavailable())) {
                if (line.requestedQuantity() != null && line.requestedQuantity() <= 0)
                    throw new BusinessRuleException("Het te bewaren aangevraagde aantal moet positief zijn");
                if (line.requestedQuantity() != null) remembered = line.requestedQuantity();
                else if (before != null && before.quantity() > 0) remembered = before.quantity();
                else if (line.quantity() > 0) remembered = line.quantity();
                else remembered = null;
            }
            Integer maximum = splits.allocatedQuantity(stored, line.productId());
            if (maximum != null && unavailable && maximum > 0) remembered = maximum;
            int quantity = unavailable ? 0 : line.quantity();
            if (before != null && before.isUnavailable() && !unavailable) {
                if (maximum != null) quantity = maximum;
                else if (remembered != null && remembered > 0) quantity = remembered;
                else if (quantity <= 0) throw new BusinessRuleException("Vul een positief aantal in om dit product opnieuw bestelbaar te maken");
            }
            if (maximum != null && !unavailable && maximum <= 0)
                throw new BusinessRuleException("Herstel dit product op de oorspronkelijke order voordat je het over leveringen verdeelt");
            changed |= unavailable != (before != null && before.isUnavailable());
            result.add(line.withAvailability(unavailable, quantity, remembered));
        }
        if (changed) requireEditable(stored);
        Set<Long> parked = new HashSet<>();
        result.stream().filter(Objects::nonNull).filter(SalesOrderLine::isUnavailable).forEach(line -> parked.add(line.productId()));
        var pallets = prunePallets(proposed.pallets(), parked);
        return proposed.withLinesAndPallets(result, pallets);
    }

    static List<OrderPallet> prunePallets(List<OrderPallet> pallets, Set<Long> parked) {
        var result = new ArrayList<OrderPallet>();
        boolean dropped = false;
        for (var pallet : pallets) {
            if (pallet == null) { result.add(null); continue; }
            var kept = pallet.items().stream().filter(item -> !parked.contains(item.productId())).toList();
            if (kept.size() == pallet.items().size()) result.add(pallet);
            else if (!kept.isEmpty()) result.add(new OrderPallet(pallet.id(), pallet.label(), pallet.type(), pallet.heightCm(), kept));
            else dropped = true;
        }
        return dropped ? renumbered(result) : result;
    }

    /**
     * The hand layout after its lines changed, so a save does not refuse it as stale: a product that
     * left the document or is parked leaves its pallets, and none stands on them with more cartons than
     * its line ships (counted as validatePallets counts them), the surplus coming off the last pallet
     * first. No carton is ever added: a raised line shows its new cartons as unassigned. A pallet
     * emptied here goes, and the pallets still called "Pallet n" then follow their new position; one
     * the seller left empty stays, as does a malformed item, for the validation to refuse.
     */
    static List<OrderPallet> reconcilePallets(List<OrderPallet> pallets, List<SalesOrderLine> lines,
                                              Function<Long, Product> products) {
        if (pallets == null || pallets.isEmpty()) return pallets;
        Map<Long, Integer> room = new HashMap<>();
        for (var line : lines) {
            if (line == null || line.productId() == null || line.isUnavailable()) continue;
            Product product = products.apply(line.productId());
            if (product == null) continue;
            room.merge(line.productId(), product.carton() == null ? Math.max(0, line.quantity())
                    : product.carton().cartonsFor(line.quantity()), Integer::sum);
        }
        var result = new ArrayList<OrderPallet>();
        boolean dropped = false;
        for (var pallet : pallets) {
            if (pallet == null || pallet.items().isEmpty()) { result.add(pallet); continue; }
            var kept = new ArrayList<OrderPallet.Item>();
            for (var item : pallet.items()) {
                if (item == null || item.productId() <= 0 || item.cartons() <= 0) { kept.add(item); continue; }
                int cartons = Math.min(item.cartons(), room.getOrDefault(item.productId(), 0));
                if (cartons <= 0) continue;
                room.merge(item.productId(), -cartons, Integer::sum);
                kept.add(cartons == item.cartons() ? item : new OrderPallet.Item(item.productId(), cartons));
            }
            if (kept.equals(pallet.items())) result.add(pallet);
            else if (!kept.isEmpty()) result.add(new OrderPallet(pallet.id(), pallet.label(), pallet.type(), pallet.heightCm(), kept));
            else dropped = true;
        }
        return dropped ? renumbered(result) : result;
    }

    private static final Pattern NUMBERED = Pattern.compile("Pallet \\d+");

    /**
     * After a pallet went, a pallet still called "Pallet n" takes its new position; a name of its own
     * stays (relabelPallets in the frontend's pallet-stack.ts). A layout nothing was dropped from is
     * never rewritten, so the packing slip keeps the labels the seller saw.
     */
    static List<OrderPallet> renumbered(List<OrderPallet> pallets) {
        var result = new ArrayList<OrderPallet>(pallets.size());
        for (int index = 0; index < pallets.size(); index++) {
            OrderPallet pallet = pallets.get(index);
            String label = "Pallet " + (index + 1);
            if (pallet != null && pallet.label() != null && NUMBERED.matcher(pallet.label().strip()).matches()
                    && !pallet.label().equals(label))
                pallet = new OrderPallet(pallet.id(), label, pallet.type(), pallet.heightCm(), pallet.items());
            result.add(pallet);
        }
        return result;
    }

    private void requireEditable(SalesOrder order) {
        if (order.purpose() != SalesPurpose.STANDARD || order.isPartnerDeal() || order.status() != QuoteStatus.CONCEPT
                || order.archivedAt() != null || order.sentAt() != null || order.viewedAt() != null || order.viewCount() != 0
                || order.decidedAt() != null || order.goodsShippedAt() != null || order.paidAt() != null
                || incoming.hasHistory(order.id()) || !revisions.findByOrder(order.id()).isEmpty()
                || !order.isClaimDocument() && orders.existsBySourceQuoteId(order.id())
                || events.findByOrder(order.id()).stream().anyMatch(event -> switch (event.type()) {
                    case VERSTUURD, UITGEREIKT, BEKEKEN, GETEKEND, BESTELLING_VERZONDEN, BETAALD -> true;
                    default -> false;
                }))
            throw new BusinessRuleException("Producten kunnen alleen op een ongebruikt standaardconcept tijdelijk niet bestelbaar worden gezet of hersteld");
    }
}
