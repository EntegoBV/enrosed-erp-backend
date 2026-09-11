package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

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
        for (var pallet : pallets) {
            if (pallet == null) { result.add(null); continue; }
            var kept = pallet.items().stream().filter(item -> !parked.contains(item.productId())).toList();
            if (kept.size() == pallet.items().size()) result.add(pallet);
            else if (!kept.isEmpty()) result.add(new OrderPallet(pallet.id(), pallet.label(), pallet.type(), pallet.heightCm(), kept));
        }
        return result;
    }

    private void requireEditable(SalesOrder order) {
        if (order.purpose() != SalesPurpose.STANDARD || order.isPartnerDeal() || order.status() != QuoteStatus.CONCEPT
                || order.archivedAt() != null || order.sentAt() != null || order.viewedAt() != null || order.viewCount() != 0
                || order.decidedAt() != null || order.goodsShippedAt() != null || order.paidAt() != null
                || incoming.hasHistory(order.id()) || !revisions.findByOrder(order.id()).isEmpty()
                || !order.isInvoice() && orders.existsBySourceQuoteId(order.id())
                || events.findByOrder(order.id()).stream().anyMatch(event -> switch (event.type()) {
                    case VERSTUURD, UITGEREIKT, BEKEKEN, GETEKEND, BESTELLING_VERZONDEN, BETAALD -> true;
                    default -> false;
                }))
            throw new BusinessRuleException("Producten kunnen alleen op een ongebruikt standaardconcept tijdelijk niet bestelbaar worden gezet of hersteld");
    }
}
